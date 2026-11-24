package guardadoc.application

import guardadoc.Samples
import guardadoc.application.account.DeleteAccount
import guardadoc.application.account.DeleteAccountCommand
import guardadoc.application.documents.PurgePendingFiles
import guardadoc.application.documents.PurgePendingFilesCommand
import guardadoc.application.documents.UploadDocument
import guardadoc.application.documents.UploadDocumentCommand
import guardadoc.application.ports.DocumentFileRemoval
import guardadoc.auth.User
import guardadoc.auth.UserRepository
import guardadoc.documents.Document
import guardadoc.documents.DocumentRepository
import guardadoc.documents.DocumentStorage
import guardadoc.documents.NoMalwareScanner
import guardadoc.documents.StoredObject
import guardadoc.shared.AppException
import guardadoc.shared.AuditEvent
import guardadoc.shared.AuditEventType
import guardadoc.shared.AuditRepository
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

private class FakeAudit : AuditRepository {
    val events = mutableListOf<AuditEvent>()
    override fun record(event: AuditEvent) {
        events += event
    }
}

private class FakeUsers(initial: List<User>) : UserRepository {
    val users = initial.associateBy { it.id }.toMutableMap()
    var deletedId: UUID? = null
    override fun findOrCreate(email: String): User =
        users.values.firstOrNull { it.email == email } ?: User(UUID.randomUUID(), email).also { users[it.id] = it }

    override fun findById(userId: UUID): User? = users[userId]
    override fun delete(userId: UUID) {
        deletedId = userId
        users -= userId
    }
}

private class FakeDocuments(
    private val active: MutableList<Document> = mutableListOf(),
    private val pending: List<Document> = emptyList(),
) : DocumentRepository {
    val inserted = mutableListOf<Document>()
    val markedRemoved = mutableListOf<UUID>()
    override fun insert(document: Document) {
        inserted += document
        active += document
    }

    override fun listActive(ownerId: UUID): List<Document> = active.filter { it.ownerId == ownerId }
    override fun findActive(id: UUID, ownerId: UUID): Document? =
        active.firstOrNull { it.id == id && it.ownerId == ownerId }

    override fun setCategory(id: UUID, ownerId: UUID, categoryId: UUID?): Document? = TODO()
    override fun markDeletedAndRevokeLinks(id: UUID, ownerId: UUID, now: Instant): Document? = TODO()
    override fun markFileRemoved(id: UUID, now: Instant) {
        markedRemoved += id
    }

    override fun pendingFileRemoval(limit: Int): List<Document> = pending.take(limit)
    override fun knownStorageKeys(keys: Collection<String>): Set<String> = emptySet()
    override fun markAllDeletedAndRevokeLinks(ownerId: UUID, now: Instant): List<Document> {
        val owned = active.filter { it.ownerId == ownerId }
        active.removeAll(owned)
        return owned
    }
}

private class FakeStorage : DocumentStorage {
    val objects = mutableMapOf<String, ByteArray>()
    var failDeletes = false
    override fun put(key: String, content: ByteArray) {
        objects[key] = content
    }

    override fun open(key: String): InputStream = ByteArrayInputStream(objects.getValue(key))
    override fun delete(key: String) {
        if (failDeletes) error("falha simulada")
        objects -= key
    }

    override fun exists(key: String): Boolean = key in objects
    override fun listObjects(): Sequence<StoredObject> =
        objects.keys.asSequence().map { StoredObject(it, FIXED_CLOCK.instant()) }
}

private class FakeFileRemoval(private val failFor: Set<UUID> = emptySet()) : DocumentFileRemoval {
    val removed = mutableListOf<Document>()
    override fun removeFile(document: Document): Boolean {
        if (document.id in failFor) return false
        removed += document
        return true
    }
}

private fun document(ownerId: UUID) = Document(
    id = UUID.randomUUID(),
    ownerId = ownerId,
    storageKey = "chave-${UUID.randomUUID()}",
    originalName = "a.pdf",
    mimeType = "application/pdf",
    sizeBytes = Samples.pdf.size.toLong(),
    createdAt = FIXED_CLOCK.instant(),
)

class UseCasesTest {
    private val owner = UUID.randomUUID()

    @Test
    fun `upload valida conteudo e grava somente via portas`() {
        val documents = FakeDocuments()
        val storage = FakeStorage()
        val audit = FakeAudit()
        val upload = UploadDocument(documents, storage, audit, FIXED_CLOCK, NoMalwareScanner)

        assertFailsWith<AppException.Invalid> {
            upload(
                UploadDocumentCommand(
                    owner, "vazio.pdf", "application/pdf", 0, ByteArrayInputStream(byteArrayOf()),
                ),
            )
        }
        assertEquals(0, storage.objects.size)

        val saved = upload(
            UploadDocumentCommand(
                owner, "rg.pdf", "application/pdf", Samples.pdf.size.toLong(), ByteArrayInputStream(Samples.pdf),
            ),
        )
        assertEquals("rg.pdf", saved.originalName)
        assertEquals(1, documents.inserted.size)
        assertTrue(saved.storageKey in storage.objects)
        assertTrue(audit.events.any { it.type == AuditEventType.DOCUMENTO_ENVIADO })
    }

    @Test
    fun `excluir conta usa a porta de remocao em vez do servico concreto`() {
        val doc = document(owner)
        val storage = FakeStorage().apply { put(doc.storageKey, Samples.pdf) }
        val users = FakeUsers(listOf(User(owner, "ana@example.com")))
        val documents = FakeDocuments(mutableListOf(doc))
        val audit = FakeAudit()
        val removal = FakeFileRemoval()

        DeleteAccount(users, documents, removal, audit, FIXED_CLOCK)(DeleteAccountCommand(owner))

        assertEquals(listOf(doc.id), removal.removed.map { it.id })
        assertNull(users.findById(owner))
        assertTrue(audit.events.any { it.type == AuditEventType.CONTA_EXCLUIDA })
    }

    @Test
    fun `purga conta somente remocoes bem sucedidas`() {
        val ok = document(owner)
        val failed = document(owner)
        val documents = FakeDocuments(pending = listOf(ok, failed))
        val purge = PurgePendingFiles(documents, FakeFileRemoval(failFor = setOf(failed.id)))

        assertEquals(1, purge(PurgePendingFilesCommand(limit = 10)))
    }
}
