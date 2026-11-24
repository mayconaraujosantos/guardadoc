package guardadoc.documents

import guardadoc.application.documents.DeleteDocument
import guardadoc.application.documents.DeleteDocumentCommand
import guardadoc.application.documents.DownloadDocument
import guardadoc.application.documents.DownloadDocumentCommand
import guardadoc.application.documents.FileNames
import guardadoc.application.documents.ListDocuments
import guardadoc.application.documents.ListDocumentsCommand
import guardadoc.application.documents.PurgePendingFiles
import guardadoc.application.documents.PurgePendingFilesCommand
import guardadoc.application.documents.RemoveDocumentFile
import guardadoc.application.documents.UploadDocument
import guardadoc.application.documents.UploadDocumentCommand
import guardadoc.application.documents.UploadLimits
import guardadoc.application.ports.DocumentFileRemoval
import guardadoc.application.ports.PendingFilePurger
import guardadoc.shared.AuditRepository
import java.io.InputStream
import java.time.Clock
import java.util.UUID

class OpenedDocument(val document: Document, val content: InputStream)

/**
 * API da aplicação de documentos: upload, listagem, download e exclusão.
 *
 * A lógica está nos casos de uso da camada `application`; esta fachada mantém
 * a assinatura pública e implementa as portas consumidas por outras áreas.
 */
class DocumentService(
    documents: DocumentRepository,
    storage: DocumentStorage,
    audit: AuditRepository,
    clock: Clock,
    scanner: MalwareScanner = NoMalwareScanner,
) : DocumentFileRemoval, PendingFilePurger {
    private val removeFileUseCase = RemoveDocumentFile(storage, documents, audit, clock)
    private val uploadUseCase = UploadDocument(documents, storage, audit, clock, scanner)
    private val listUseCase = ListDocuments(documents)
    private val downloadUseCase = DownloadDocument(documents, storage, audit, clock)
    private val deleteUseCase = DeleteDocument(documents, audit, clock, this)
    private val purgeUseCase = PurgePendingFiles(documents, this)

    fun upload(
        ownerId: UUID,
        clientFileName: String,
        declaredMimeType: String?,
        declaredSize: Long,
        content: InputStream,
        categoryId: UUID? = null,
    ): Document = uploadUseCase(
        UploadDocumentCommand(ownerId, clientFileName, declaredMimeType, declaredSize, content, categoryId),
    )

    fun list(ownerId: UUID): List<Document> = listUseCase(ListDocumentsCommand(ownerId))

    fun download(ownerId: UUID, documentId: UUID): OpenedDocument =
        downloadUseCase(DownloadDocumentCommand(ownerId, documentId))

    fun delete(ownerId: UUID, documentId: UUID) =
        deleteUseCase(DeleteDocumentCommand(ownerId, documentId))

    /** Reprocessa remoções de arquivos que falharam anteriormente. */
    override fun purgePendingFiles(limit: Int): Int = purgeUseCase(PurgePendingFilesCommand(limit))

    override fun removeFile(document: Document): Boolean = removeFileUseCase(document)

    companion object {
        const val MAX_SIZE_BYTES = UploadLimits.MAX_SIZE_BYTES

        fun sanitizeFileName(raw: String): String = FileNames.sanitize(raw)
    }
}
