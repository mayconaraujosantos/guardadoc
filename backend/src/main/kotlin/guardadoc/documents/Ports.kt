package guardadoc.documents

import java.io.InputStream
import java.time.Instant
import java.util.UUID

data class Document(
    val id: UUID,
    val ownerId: UUID,
    val storageKey: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Instant,
)

// ---- Portas de saída do módulo de documentos ----

interface DocumentRepository {
    fun insert(document: Document)
    fun listActive(ownerId: UUID): List<Document>

    /** Retorna o documento somente se estiver ativo e pertencer ao usuário. */
    fun findActive(id: UUID, ownerId: UUID): Document?

    /**
     * Em uma única transação: marca o documento como excluído e revoga todos os
     * seus links. Retorna o documento se estava ativo e pertencia ao usuário.
     */
    fun markDeletedAndRevokeLinks(id: UUID, ownerId: UUID, now: Instant): Document?

    fun markFileRemoved(id: UUID, now: Instant)

    /** Documentos excluídos cujo objeto ainda não foi removido do armazenamento. */
    fun pendingFileRemoval(limit: Int): List<Document>
}

/** Armazenamento privado de objetos; somente o backend acessa. */
interface DocumentStorage {
    fun put(key: String, content: ByteArray)
    fun open(key: String): InputStream

    /** Idempotente: remover uma chave inexistente não é erro. */
    fun delete(key: String)
    fun exists(key: String): Boolean
}
