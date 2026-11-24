package guardadoc.adapters.http

import guardadoc.auth.SessionPrincipal
import guardadoc.documents.OpenedDocument
import guardadoc.shared.AppException
import guardadoc.shared.Crypto
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

internal const val PRINCIPAL_ATTR = "guardadoc.principal"
internal const val SESSION_COOKIE = "gd_session"
internal const val MAX_REQUEST_BYTES = 11L * 1024 * 1024
internal val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")

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

internal fun csrfToken(principal: SessionPrincipal, secret: ByteArray): String =
    Crypto.base64Url(Crypto.hmacSha256(secret, principal.sessionId.toString()))

internal fun Context.setSessionCookie(token: String, maxAge: Duration, secure: Boolean) {
    val suffix = if (secure) "; Secure" else ""
    header(
        "Set-Cookie",
        "$SESSION_COOKIE=$token; Path=/; Max-Age=${maxAge.seconds.coerceAtLeast(0)}; HttpOnly; SameSite=Lax$suffix",
    )
}

/** IP usado apenas em memória para limitar requisições; nunca persistido. */
internal fun Context.clientIp(trustProxy: Boolean): String {
    if (trustProxy) {
        header("X-Forwarded-For")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return ip()
}

/**
 * HTTPS obrigatório. Atrás de proxy TLS, usa X-Forwarded-Proto (somente com
 * TRUST_PROXY=true). Navegações GET são redirecionadas para PUBLIC_BASE_URL
 * (nunca para o Host recebido, evitando open redirect); o resto é recusado.
 */
internal fun Context.enforceHttps(settings: HttpSettings) {
    if (path() == "/api/health") return
    val scheme = if (settings.trustProxy) {
        header("X-Forwarded-Proto")?.substringBefore(',')?.trim()?.lowercase() ?: scheme()
    } else {
        scheme()
    }
    if (scheme == "https") return
    val base = settings.publicBaseUrl.trimEnd('/')
    if (method().name in SAFE_METHODS && base.startsWith("https://")) {
        val query = queryString()?.let { "?$it" } ?: ""
        redirect(base + path() + query, HttpStatus.PERMANENT_REDIRECT)
        skipRemainingHandlers()
        return
    }
    throw AppException.Forbidden("HTTPS obrigatório")
}
