package guardadoc.sharing

import guardadoc.application.ports.DocumentContentOpener
import guardadoc.application.sharing.CreateShare
import guardadoc.application.sharing.CreateShareCommand
import guardadoc.application.sharing.DownloadSharedDocument
import guardadoc.application.sharing.DownloadSharedDocumentCommand
import guardadoc.application.sharing.InspectShare
import guardadoc.application.sharing.InspectShareCommand
import guardadoc.application.sharing.ListShares
import guardadoc.application.sharing.ListSharesCommand
import guardadoc.application.sharing.RevokeShare
import guardadoc.application.sharing.RevokeShareCommand
import guardadoc.application.sharing.ShareTokens
import guardadoc.documents.DocumentRepository
import guardadoc.documents.DocumentStorage
import guardadoc.documents.OpenedDocument
import guardadoc.shared.AuditRepository
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Token devolvido uma única vez; apenas seu hash é persistido. */
data class CreatedShare(val token: String, val link: ShareLink)

/**
 * API da aplicação de compartilhamento.
 *
 * O armazenamento entra como [DocumentContentOpener]: o caso de uso de download
 * não conhece `DocumentStorage`, apenas a operação de abrir um objeto.
 */
class ShareService(
    shares: ShareRepository,
    documents: DocumentRepository,
    storage: DocumentStorage,
    audit: AuditRepository,
    clock: Clock,
) {
    private val openFile = DocumentContentOpener { storage.open(it) }
    private val createUseCase = CreateShare(shares, documents, audit, clock)
    private val listUseCase = ListShares(shares, documents)
    private val revokeUseCase = RevokeShare(shares, audit, clock)
    private val inspectUseCase = InspectShare(shares, clock)
    private val downloadUseCase = DownloadSharedDocument(shares, openFile, audit, clock)

    fun create(
        ownerId: UUID,
        documentId: UUID,
        expiresInMinutes: Long?,
        downloadLimit: Int?,
    ): CreatedShare = createUseCase(CreateShareCommand(ownerId, documentId, expiresInMinutes, downloadLimit))

    fun list(ownerId: UUID, documentId: UUID): List<ShareLink> =
        listUseCase(ListSharesCommand(ownerId, documentId))

    fun revoke(ownerId: UUID, linkId: UUID) = revokeUseCase(RevokeShareCommand(ownerId, linkId))

    /** Consulta metadados para a página do link; não lê o armazenamento. */
    fun inspect(token: String): SharedDocument = inspectUseCase(InspectShareCommand(token))

    /** Valida o link (e consome um download) antes de qualquer leitura no armazenamento. */
    fun download(token: String): OpenedDocument = downloadUseCase(DownloadSharedDocumentCommand(token))

    companion object {
        val MIN_EXPIRATION: Duration = ShareTokens.MIN_EXPIRATION
        val MAX_EXPIRATION: Duration = ShareTokens.MAX_EXPIRATION
    }
}
