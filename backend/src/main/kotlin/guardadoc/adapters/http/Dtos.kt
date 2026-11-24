package guardadoc.adapters.http

import guardadoc.documents.Category
import guardadoc.documents.CategorySummary
import guardadoc.documents.Document
import guardadoc.sharing.ShareLink
import java.time.Instant
import java.util.UUID

data class OtpRequestBody(val email: String? = null)
data class OtpVerifyBody(val email: String? = null, val code: String? = null)
data class PasskeyStartResponse(val challengeId: UUID, val options: String)
data class PasskeyCredentialBody(val challengeId: String? = null, val credential: String? = null)
data class CategoryBody(val name: String? = null)
data class UpdateDocumentBody(val categoryId: UUID? = null)
data class CreateShareBody(val expiresInMinutes: Long? = null, val downloadLimit: Int? = null)

data class MessageResponse(val message: String)
data class ErrorResponse(val error: String)
data class MeResponse(val email: String, val csrfToken: String, val sessionExpiresAt: Instant)

data class DocumentResponse(
    val id: UUID,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Instant,
    val categoryId: UUID?,
) {
    companion object {
        fun of(d: Document) = DocumentResponse(d.id, d.originalName, d.mimeType, d.sizeBytes, d.createdAt, d.categoryId)
    }
}

data class CategoryResponse(val id: UUID, val name: String, val documentCount: Int, val createdAt: Instant) {
    companion object {
        fun of(s: CategorySummary) = CategoryResponse(s.category.id, s.category.name, s.documentCount, s.category.createdAt)
        fun of(c: Category, documentCount: Int = 0) = CategoryResponse(c.id, c.name, documentCount, c.createdAt)
    }
}

data class ShareLinkResponse(
    val id: UUID,
    val documentId: UUID,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val downloadLimit: Int?,
    val downloads: Int,
    val createdAt: Instant,
    val status: String,
) {
    companion object {
        fun of(l: ShareLink, now: Instant) = ShareLinkResponse(
            l.id, l.documentId, l.expiresAt, l.revokedAt, l.downloadLimit, l.downloads, l.createdAt,
            status = when {
                l.revokedAt != null -> "REVOGADO"
                !l.expiresAt.isAfter(now) -> "EXPIRADO"
                l.downloadLimit != null && l.downloads >= l.downloadLimit -> "ESGOTADO"
                else -> "ATIVO"
            },
        )
    }
}

/** Resposta de criação: a URL com o token só é exibida uma vez. */
data class CreatedShareResponse(val url: String, val link: ShareLinkResponse)
