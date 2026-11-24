package guardadoc.application.sharing

import guardadoc.application.CommandUseCase
import guardadoc.application.UseCase
import guardadoc.application.ports.DocumentContentOpener
import guardadoc.documents.DocumentRepository
import guardadoc.documents.OpenedDocument
import guardadoc.shared.AppException
import guardadoc.shared.AuditEvent
import guardadoc.shared.AuditEventType
import guardadoc.shared.AuditRepository
import guardadoc.shared.Crypto
import guardadoc.sharing.CreatedShare
import guardadoc.sharing.ShareLink
import guardadoc.sharing.ShareRepository
import guardadoc.sharing.SharedDocument
import java.time.Clock
import java.time.Duration
import java.util.UUID

object ShareTokens {
    val MIN_EXPIRATION: Duration = Duration.ofMinutes(5)
    val MAX_EXPIRATION: Duration = Duration.ofDays(30)
    private val TOKEN_FORMAT = Regex("^[A-Za-z0-9_-]{43}$")

    fun requireWellFormed(token: String) {
        if (!TOKEN_FORMAT.matches(token)) throw AppException.NotFound()
    }

    fun hash(token: String): ByteArray = Crypto.sha256(token)
}

data class CreateShareCommand(
    val ownerId: UUID,
    val documentId: UUID,
    val expiresInMinutes: Long?,
    val downloadLimit: Int?,
)

/** Cria um link com token de alta entropia; só o hash é persistido. */
class CreateShare(
    private val shares: ShareRepository,
    private val documents: DocumentRepository,
    private val audit: AuditRepository,
    private val clock: Clock,
) : UseCase<CreateShareCommand, CreatedShare> {
    override fun invoke(command: CreateShareCommand): CreatedShare {
        val minutes = command.expiresInMinutes ?: throw AppException.Invalid("A expiração é obrigatória")
        if (minutes !in ShareTokens.MIN_EXPIRATION.toMinutes()..ShareTokens.MAX_EXPIRATION.toMinutes()) {
            throw AppException.Invalid("Expiração deve estar entre 5 minutos e 30 dias")
        }
        if (command.downloadLimit != null && command.downloadLimit !in 1..1000) {
            throw AppException.Invalid("Limite de downloads deve estar entre 1 e 1000")
        }
        documents.findActive(command.documentId, command.ownerId) ?: throw AppException.NotFound()

        val token = Crypto.randomToken()
        val link = shares.create(
            documentId = command.documentId,
            createdBy = command.ownerId,
            tokenHash = ShareTokens.hash(token),
            expiresAt = clock.instant().plus(Duration.ofMinutes(minutes)),
            downloadLimit = command.downloadLimit,
        )
        audit.record(
            AuditEvent(
                AuditEventType.LINK_CRIADO,
                userId = command.ownerId,
                documentId = command.documentId,
                linkId = link.id,
            ),
        )
        return CreatedShare(token, link)
    }
}

data class ListSharesCommand(val ownerId: UUID, val documentId: UUID)

/** Lista os links de um documento do titular. */
class ListShares(
    private val shares: ShareRepository,
    private val documents: DocumentRepository,
) : UseCase<ListSharesCommand, List<ShareLink>> {
    override fun invoke(command: ListSharesCommand): List<ShareLink> {
        documents.findActive(command.documentId, command.ownerId) ?: throw AppException.NotFound()
        return shares.listForDocument(command.documentId, command.ownerId)
    }
}

data class RevokeShareCommand(val ownerId: UUID, val linkId: UUID)

/** Revoga um link do titular. */
class RevokeShare(
    private val shares: ShareRepository,
    private val audit: AuditRepository,
    private val clock: Clock,
) : CommandUseCase<RevokeShareCommand> {
    override fun invoke(command: RevokeShareCommand) {
        if (!shares.revoke(command.linkId, command.ownerId, clock.instant())) throw AppException.NotFound()
        audit.record(AuditEvent(AuditEventType.LINK_REVOGADO, userId = command.ownerId, linkId = command.linkId))
    }
}

data class InspectShareCommand(val token: String)

/** Consulta metadados para a página do link; não lê o armazenamento. */
class InspectShare(private val shares: ShareRepository, private val clock: Clock) :
    UseCase<InspectShareCommand, SharedDocument> {
    override fun invoke(command: InspectShareCommand): SharedDocument {
        ShareTokens.requireWellFormed(command.token)
        return shares.findValid(ShareTokens.hash(command.token), clock.instant())
            ?: throw AppException.NotFound()
    }
}

data class DownloadSharedDocumentCommand(val token: String)

/** Valida o link (e consome um download) antes de qualquer leitura no armazenamento. */
class DownloadSharedDocument(
    private val shares: ShareRepository,
    private val openFile: DocumentContentOpener,
    private val audit: AuditRepository,
    private val clock: Clock,
) : UseCase<DownloadSharedDocumentCommand, OpenedDocument> {
    override fun invoke(command: DownloadSharedDocumentCommand): OpenedDocument {
        ShareTokens.requireWellFormed(command.token)
        val shared = shares.consumeDownload(ShareTokens.hash(command.token), clock.instant())
            ?: throw AppException.NotFound()
        val content = try {
            openFile.open(shared.document.storageKey)
        } catch (e: Exception) {
            shares.releaseDownload(shared.link.id) // não gasta o download se a leitura falhar
            throw e
        }
        audit.record(
            AuditEvent(
                AuditEventType.LINK_ACESSADO,
                documentId = shared.document.id,
                linkId = shared.link.id,
            ),
        )
        return OpenedDocument(shared.document, content)
    }
}
