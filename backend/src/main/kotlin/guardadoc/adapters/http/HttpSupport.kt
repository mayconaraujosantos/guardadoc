package guardadoc.adapters.http

import guardadoc.auth.SessionPrincipal
import guardadoc.documents.OpenedDocument
import guardadoc.shared.AppException
import io.javalin.http.Context
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

internal const val PRINCIPAL_ATTR = "guardadoc.principal"
internal const val SESSION_COOKIE = "gd_session"

internal fun Context.principalOrNull(): SessionPrincipal? = attribute(PRINCIPAL_ATTR)

internal fun Context.requirePrincipal(): SessionPrincipal = principalOrNull() ?: throw AppException.Unauthorized()

internal fun Context.uuidParam(name: String): UUID =
    runCatching { UUID.fromString(pathParam(name)) }.getOrElse { throw AppException.NotFound() }

internal inline fun <reified T : Any> Context.parseBody(): T =
    runCatching { bodyAsClass(T::class.java) }.getOrElse { throw AppException.Invalid("Corpo da requisição inválido") }

internal fun Context.noStore(): Context = header("Cache-Control", "no-store").header("Pragma", "no-cache")

/** Entrega o arquivo como anexo, nunca inline, sem cache e sem sniffing. */
internal fun Context.sendAttachment(opened: OpenedDocument) {
    val document = opened.document
    val asciiName = document.originalName.map { if (it.code in 0x20..0x7E && it != '"' && it != '\\') it else '_' }
        .joinToString("")
    val encodedName = URLEncoder.encode(document.originalName, StandardCharsets.UTF_8).replace("+", "%20")
    noStore()
    header("Content-Disposition", "attachment; filename=\"$asciiName\"; filename*=UTF-8''$encodedName")
    header("X-Content-Type-Options", "nosniff")
    header("Content-Length", document.sizeBytes.toString())
    contentType(document.mimeType)
    result(opened.content)
}

internal fun escapeHtml(value: String): String = buildString(value.length) {
    value.forEach { ch ->
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }
}

internal fun humanSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes bytes"
}
