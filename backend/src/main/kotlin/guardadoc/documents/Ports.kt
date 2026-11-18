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

    /** Das chaves informadas, quais têm metadados (ativos ou excluídos) no banco. */
    fun knownStorageKeys(keys: Collection<String>): Set<String>

    /** Exclusão de conta: marca todos os documentos ativos do usuário e revoga seus links. */
    fun markAllDeletedAndRevokeLinks(ownerId: UUID, now: Instant): List<Document>
}

data class StoredObject(val key: String, val lastModified: Instant)

/** Armazenamento privado de objetos; somente o backend acessa. */
interface DocumentStorage {
    fun put(key: String, content: ByteArray)
    fun open(key: String): InputStream

    /** Idempotente: remover uma chave inexistente não é erro. */
    fun delete(key: String)
    fun exists(key: String): Boolean

    /** Percorre todos os objetos (usado na varredura de órfãos). */
    fun listObjects(): Sequence<StoredObject>
}

sealed interface ScanResult {
    data object Clean : ScanResult
    data class Infected(val signature: String) : ScanResult
}

/** Verificação de malware antes de aceitar o arquivo (ex.: ClamAV). */
interface MalwareScanner {
    fun scan(content: ByteArray): ScanResult
}

/** Usado quando nenhum antivírus está configurado. */
object NoMalwareScanner : MalwareScanner {
    override fun scan(content: ByteArray): ScanResult = ScanResult.Clean
}
