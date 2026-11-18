package guardadoc.sharing

import guardadoc.documents.DocumentRepository
import guardadoc.documents.DocumentStorage
import guardadoc.documents.OpenedDocument
import guardadoc.shared.AppException
import guardadoc.shared.AuditEvent
import guardadoc.shared.AuditEventType
import guardadoc.shared.AuditRepository
import guardadoc.shared.Crypto
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Token devolvido uma única vez; apenas seu hash é persistido. */
data class CreatedShare(val token: String, val link: ShareLink)

/** Casos de uso: CreateShareLink, AccessSharedDocument, RevokeShareLink. */
class ShareService(
    private val shares: ShareRepository,
    private val documents: DocumentRepository,
    private val storage: DocumentStorage,
    private val audit: AuditRepository,
    private val clock: Clock,
) {
    fun create(ownerId: UUID, documentId: UUID, expiresInMinutes: Long?, downloadLimit: Int?): CreatedShare {
        val minutes = expiresInMinutes ?: throw AppException.Invalid("A expiração é obrigatória")
        if (minutes !in MIN_EXPIRATION.toMinutes()..MAX_EXPIRATION.toMinutes()) {
            throw AppException.Invalid("Expiração deve estar entre 5 minutos e 30 dias")
        }
        if (downloadLimit != null && downloadLimit !in 1..1000) {
            throw AppException.Invalid("Limite de downloads deve estar entre 1 e 1000")
        }
        documents.findActive(documentId, ownerId) ?: throw AppException.NotFound()

        val token = Crypto.randomToken()
        val link = shares.create(
            documentId = documentId,
            createdBy = ownerId,
            tokenHash = Crypto.sha256(token),
            expiresAt = clock.instant().plus(Duration.ofMinutes(minutes)),
            downloadLimit = downloadLimit,
        )
        audit.record(AuditEvent(AuditEventType.LINK_CRIADO, userId = ownerId, documentId = documentId, linkId = link.id))
        return CreatedShare(token, link)
    }

    fun list(ownerId: UUID, documentId: UUID): List<ShareLink> {
        documents.findActive(documentId, ownerId) ?: throw AppException.NotFound()
        return shares.listForDocument(documentId, ownerId)
    }

    fun revoke(ownerId: UUID, linkId: UUID) {
        if (!shares.revoke(linkId, ownerId, clock.instant())) throw AppException.NotFound()
        audit.record(AuditEvent(AuditEventType.LINK_REVOGADO, userId = ownerId, linkId = linkId))
    }

    /** Consulta metadados para a página do link; não lê o armazenamento. */
    fun inspect(token: String): SharedDocument {
        if (!TOKEN_FORMAT.matches(token)) throw AppException.NotFound()
        return shares.findValid(Crypto.sha256(token), clock.instant()) ?: throw AppException.NotFound()
    }

    /** Valida o link (e consome um download) antes de qualquer leitura no armazenamento. */
    fun download(token: String): OpenedDocument {
        if (!TOKEN_FORMAT.matches(token)) throw AppException.NotFound()
        val shared = shares.consumeDownload(Crypto.sha256(token), clock.instant()) ?: throw AppException.NotFound()
        val content = storage.open(shared.document.storageKey)
        audit.record(AuditEvent(AuditEventType.LINK_ACESSADO, documentId = shared.document.id, linkId = shared.link.id))
        return OpenedDocument(shared.document, content)
    }

    companion object {
        val MIN_EXPIRATION: Duration = Duration.ofMinutes(5)
        val MAX_EXPIRATION: Duration = Duration.ofDays(30)
        private val TOKEN_FORMAT = Regex("^[A-Za-z0-9_-]{43}$")
    }
}
