package guardadoc.documents

import guardadoc.shared.AppException
import guardadoc.shared.AuditEvent
import guardadoc.shared.AuditEventType
import guardadoc.shared.AuditRepository
import guardadoc.shared.Crypto
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.time.Clock
import java.util.UUID

class OpenedDocument(val document: Document, val content: InputStream)

/** Casos de uso: UploadDocument, ListDocuments, DownloadDocument, DeleteDocument. */
class DocumentService(
    private val documents: DocumentRepository,
    private val storage: DocumentStorage,
    private val audit: AuditRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(DocumentService::class.java)

    fun upload(
        ownerId: UUID,
        clientFileName: String,
        declaredMimeType: String?,
        declaredSize: Long,
        content: InputStream,
    ): Document {
        if (declaredSize > MAX_SIZE_BYTES) throw tooLarge()

        // Tamanho efetivamente recebido, independente do declarado.
        val bytes = content.use { it.readNBytes(MAX_SIZE_BYTES + 1) }
        if (bytes.size > MAX_SIZE_BYTES) throw tooLarge()
        if (bytes.isEmpty()) throw AppException.Invalid("Arquivo vazio")

        val detected = FileType.detect(bytes)
            ?: throw AppException.UnsupportedMedia("Somente PDF, JPEG ou PNG são aceitos")
        val declared = declaredMimeType?.substringBefore(';')?.trim()?.lowercase()
        if (declared != detected.mimeType) {
            throw AppException.UnsupportedMedia("O tipo declarado não corresponde ao conteúdo do arquivo")
        }
        val name = sanitizeFileName(clientFileName)
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in detected.extensions) {
            throw AppException.UnsupportedMedia("A extensão do arquivo não corresponde ao conteúdo")
        }

        val document = Document(
            id = UUID.randomUUID(),
            ownerId = ownerId,
            storageKey = Crypto.randomToken(24), // chave opaca, nunca derivada do nome do cliente
            originalName = name,
            mimeType = detected.mimeType,
            sizeBytes = bytes.size.toLong(),
            createdAt = clock.instant(),
        )
        storage.put(document.storageKey, bytes)
        try {
            documents.insert(document)
        } catch (e: Exception) {
            // Compensação: evita objeto órfão se os metadados não forem gravados.
            runCatching { storage.delete(document.storageKey) }
                .onFailure { log.warn("Objeto órfão após falha no upload: documento {}", document.id) }
            throw e
        }
        audit.record(AuditEvent(AuditEventType.DOCUMENTO_ENVIADO, userId = ownerId, documentId = document.id))
        return document
    }

    fun list(ownerId: UUID): List<Document> = documents.listActive(ownerId)

    fun download(ownerId: UUID, documentId: UUID): OpenedDocument {
        val document = documents.findActive(documentId, ownerId) ?: throw AppException.NotFound()
        val content = storage.open(document.storageKey)
        audit.record(AuditEvent(AuditEventType.DOCUMENTO_BAIXADO, userId = ownerId, documentId = documentId))
        return OpenedDocument(document, content)
    }

    fun delete(ownerId: UUID, documentId: UUID) {
        val now = clock.instant()
        val document = documents.markDeletedAndRevokeLinks(documentId, ownerId, now)
            ?: throw AppException.NotFound()
        audit.record(AuditEvent(AuditEventType.DOCUMENTO_EXCLUIDO, userId = ownerId, documentId = documentId))
        removeFile(document)
    }

    /** Reprocessa remoções de arquivos que falharam anteriormente. */
    fun purgePendingFiles(limit: Int = 100): Int =
        documents.pendingFileRemoval(limit).count { removeFile(it) }

    private fun removeFile(document: Document): Boolean = try {
        storage.delete(document.storageKey)
        documents.markFileRemoved(document.id, clock.instant())
        audit.record(AuditEvent(AuditEventType.ARQUIVO_REMOVIDO, documentId = document.id))
        true
    } catch (e: Exception) {
        // O documento já está inacessível; a limpeza será repetida.
        log.warn("Falha ao remover arquivo do documento {}; nova tentativa agendada", document.id)
        false
    }

    private fun tooLarge() = AppException.PayloadTooLarge("Arquivo excede o limite de 10 MiB")

    companion object {
        const val MAX_SIZE_BYTES = 10 * 1024 * 1024

        /** Mantém só o nome-base, sem caminhos nem caracteres de controle. */
        fun sanitizeFileName(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = base.filter { !it.isISOControl() && it !in "<>:\"|?*" }.trim().trim('.')
            if (cleaned.isEmpty()) throw AppException.Invalid("Nome de arquivo inválido")
            return if (cleaned.length <= 200) cleaned else cleaned.takeLast(200)
        }
    }
}
