package guardadoc.adapters.http

import guardadoc.documents.Document
import guardadoc.sharing.ShareLink
import java.time.Instant
import java.util.UUID

data class OtpRequestBody(val email: String? = null)
data class OtpVerifyBody(val email: String? = null, val code: String? = null)
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
) {
    companion object {
        fun of(d: Document) = DocumentResponse(d.id, d.originalName, d.mimeType, d.sizeBytes, d.createdAt)
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
