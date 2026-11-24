package guardadoc.application.documents

import guardadoc.application.CommandUseCase
import guardadoc.application.Query
import guardadoc.application.UseCase
import guardadoc.application.ports.DocumentFileRemoval
import guardadoc.application.ports.PendingFilePurger
import guardadoc.documents.ContentValidator
import guardadoc.documents.Document
import guardadoc.documents.DocumentRepository
import guardadoc.documents.DocumentStorage
import guardadoc.documents.FileType
import guardadoc.documents.MalwareScanner
import guardadoc.documents.OpenedDocument
import guardadoc.documents.ScanResult
import guardadoc.shared.AppException
import guardadoc.shared.AuditEvent
import guardadoc.shared.AuditEventType
import guardadoc.shared.AuditRepository
import guardadoc.shared.Crypto
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.time.Clock
import java.util.UUID

object UploadLimits {
    const val MAX_SIZE_BYTES = 10 * 1024 * 1024
}

/** Nome de arquivo do cliente é dado não confiável: mantém só o nome-base. */
object FileNames {
    fun sanitize(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.filter { !it.isISOControl() && it !in "<>:\"|?*" }.trim().trim('.')
        if (cleaned.isEmpty()) throw AppException.Invalid("Nome de arquivo inválido")
        return if (cleaned.length <= 200) cleaned else cleaned.takeLast(200)
    }
}

data class UploadDocumentCommand(
    val ownerId: UUID,
    val clientFileName: String,
    val declaredMimeType: String?,
    val declaredSize: Long,
    val content: InputStream,
    val categoryId: UUID? = null,
)

/** Valida, verifica malware e guarda o arquivo com metadados. */
class UploadDocument(
    private val documents: DocumentRepository,
    private val storage: DocumentStorage,
    private val audit: AuditRepository,
    private val clock: Clock,
    private val scanner: MalwareScanner,
) : UseCase<UploadDocumentCommand, Document> {
    private val log = LoggerFactory.getLogger(UploadDocument::class.java)

    override fun invoke(command: UploadDocumentCommand): Document {
        if (command.declaredSize > UploadLimits.MAX_SIZE_BYTES) throw tooLarge()

        // Tamanho efetivamente recebido, independente do declarado.
        val bytes = command.content.use { it.readNBytes(UploadLimits.MAX_SIZE_BYTES + 1) }
        if (bytes.size > UploadLimits.MAX_SIZE_BYTES) throw tooLarge()
        if (bytes.isEmpty()) throw AppException.Invalid("Arquivo vazio")

        val detected = FileType.detect(bytes)
            ?: throw AppException.UnsupportedMedia("Somente PDF, JPEG ou PNG são aceitos")
        val declared = command.declaredMimeType?.substringBefore(';')?.trim()?.lowercase()
        if (declared != detected.mimeType) {
            throw AppException.UnsupportedMedia("O tipo declarado não corresponde ao conteúdo do arquivo")
        }
        val name = FileNames.sanitize(command.clientFileName)
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in detected.extensions) {
            throw AppException.UnsupportedMedia("A extensão do arquivo não corresponde ao conteúdo")
        }

        ContentValidator.validate(detected, bytes)
        val verdict = try {
            scanner.scan(bytes)
        } catch (e: Exception) {
            log.error("Antivírus indisponível: {}", e.javaClass.simpleName)
            throw AppException.Unavailable("Verificação de segurança indisponível; tente novamente")
        }
        if (verdict is ScanResult.Infected) {
            audit.record(AuditEvent(AuditEventType.UPLOAD_REJEITADO_MALWARE, userId = command.ownerId))
            throw AppException.Rejected("Arquivo rejeitado pela verificação de segurança")
        }

        val document = Document(
            id = UUID.randomUUID(),
            ownerId = command.ownerId,
            storageKey = Crypto.randomToken(24), // chave opaca, nunca derivada do nome do cliente
            originalName = name,
            mimeType = detected.mimeType,
            sizeBytes = bytes.size.toLong(),
            createdAt = clock.instant(),
            categoryId = command.categoryId,
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
        audit.record(AuditEvent(AuditEventType.DOCUMENTO_ENVIADO, userId = command.ownerId, documentId = document.id))
        return document
    }

    private fun tooLarge() = AppException.PayloadTooLarge("Arquivo excede o limite de 10 MiB")
}

data class ListDocumentsCommand(val ownerId: UUID)

/** Lista somente os documentos ativos do titular. */
class ListDocuments(private val documents: DocumentRepository) :
    UseCase<ListDocumentsCommand, List<Document>> {
    override fun invoke(command: ListDocumentsCommand) = documents.listActive(command.ownerId)
}

data class DownloadDocumentCommand(val ownerId: UUID, val documentId: UUID)

/** Autoriza a propriedade antes de abrir o conteúdo. */
class DownloadDocument(
    private val documents: DocumentRepository,
    private val storage: DocumentStorage,
    private val audit: AuditRepository,
    private val clock: Clock,
) : UseCase<DownloadDocumentCommand, OpenedDocument> {
    override fun invoke(command: DownloadDocumentCommand): OpenedDocument {
        val document = documents.findActive(command.documentId, command.ownerId)
            ?: throw AppException.NotFound()
        val content = storage.open(document.storageKey)
        audit.record(
            AuditEvent(
                AuditEventType.DOCUMENTO_BAIXADO,
                userId = command.ownerId,
                documentId = command.documentId,
            ),
        )
        return OpenedDocument(document, content)
    }
}

data class DeleteDocumentCommand(val ownerId: UUID, val documentId: UUID)

/** Marca como excluído, revoga links e remove o arquivo. */
class DeleteDocument(
    private val documents: DocumentRepository,
    private val audit: AuditRepository,
    private val clock: Clock,
    private val fileRemoval: DocumentFileRemoval,
) : CommandUseCase<DeleteDocumentCommand> {
    override fun invoke(command: DeleteDocumentCommand) {
        val now = clock.instant()
        val document = documents.markDeletedAndRevokeLinks(command.documentId, command.ownerId, now)
            ?: throw AppException.NotFound()
        audit.record(
            AuditEvent(
                AuditEventType.DOCUMENTO_EXCLUIDO,
                userId = command.ownerId,
                documentId = command.documentId,
            ),
        )
        fileRemoval.removeFile(document)
    }
}

data class PurgePendingFilesCommand(val limit: Int = 100)

/** Reprocessa remoções de arquivos que falharam anteriormente. */
class PurgePendingFiles(
    private val documents: DocumentRepository,
    private val fileRemoval: DocumentFileRemoval,
) : UseCase<PurgePendingFilesCommand, Int> {
    override fun invoke(command: PurgePendingFilesCommand): Int =
        documents.pendingFileRemoval(command.limit).count { fileRemoval.removeFile(it) }
}

/** Remove o objeto e registra o sucesso; falhas ficam pendentes para a varredura. */
class RemoveDocumentFile(
    private val storage: DocumentStorage,
    private val documents: DocumentRepository,
    private val audit: AuditRepository,
    private val clock: Clock,
) : UseCase<Document, Boolean>, DocumentFileRemoval {
    private val log = LoggerFactory.getLogger(RemoveDocumentFile::class.java)

    override fun invoke(command: Document): Boolean = try {
        storage.delete(command.storageKey)
        documents.markFileRemoved(command.id, clock.instant())
        audit.record(AuditEvent(AuditEventType.ARQUIVO_REMOVIDO, documentId = command.id))
        true
    } catch (e: Exception) {
        // O documento já está inacessível; a limpeza será repetida.
        log.warn("Falha ao remover arquivo do documento {}; nova tentativa agendada", command.id)
        false
    }

    override fun removeFile(document: Document) = invoke(document)
}
