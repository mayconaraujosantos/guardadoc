package guardadoc.sharing

import guardadoc.documents.Document
import java.time.Instant
import java.util.UUID

data class ShareLink(
    val id: UUID,
    val documentId: UUID,
    val createdBy: UUID,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val downloadLimit: Int?,
    val downloads: Int,
    val createdAt: Instant,
)

data class SharedDocument(val link: ShareLink, val document: Document)

// ---- Porta de saída do módulo de compartilhamento ----

interface ShareRepository {
    fun create(documentId: UUID, createdBy: UUID, tokenHash: ByteArray, expiresAt: Instant, downloadLimit: Int?): ShareLink
    fun listForDocument(documentId: UUID, ownerId: UUID): List<ShareLink>

    /** Revoga somente se o link pertencer a um documento do usuário. */
    fun revoke(linkId: UUID, ownerId: UUID, now: Instant): Boolean

    /** Link válido (não expirado, não revogado, dentro do limite) de documento ativo. */
    fun findValid(tokenHash: ByteArray, now: Instant): SharedDocument?

    /**
     * Incrementa atomicamente o contador de downloads, somente se o link
     * continuar válido. Retorna null se a operação não for permitida.
     */
    fun consumeDownload(tokenHash: ByteArray, now: Instant): SharedDocument?

    /** Compensa um download consumido cujo arquivo não pôde ser lido. */
    fun releaseDownload(linkId: UUID)
}
