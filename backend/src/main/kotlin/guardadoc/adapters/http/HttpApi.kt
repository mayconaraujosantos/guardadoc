package guardadoc.adapters.http

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import guardadoc.account.AccountService
import guardadoc.adapters.email.DevOutboxEmailSender
import guardadoc.auth.AuthService
import guardadoc.auth.SessionPrincipal
import guardadoc.documents.DocumentService
import guardadoc.shared.AppException
import guardadoc.shared.Crypto
import guardadoc.sharing.ShareService
import io.javalin.Javalin
import io.javalin.config.JavalinConfig
import io.javalin.config.SizeUnit
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import io.javalin.http.staticfiles.Location
import io.javalin.json.JavalinJackson
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

data class HttpSettings(
    val publicBaseUrl: String,
    val allowedOrigins: Set<String>,
    val cookieSecure: Boolean,
    val csrfSecret: ByteArray,
    val previousCsrfSecrets: List<ByteArray> = emptyList(),
    /** Recusa requisições que não chegaram por HTTPS (exceto /api/health). */
    val requireHttps: Boolean = false,
    val trustProxy: Boolean,
    val staticDir: Path?,
    /** Habilita /dev/outbox. Nunca usar em produção. */
    val devOutbox: DevOutboxEmailSender?,
)

/** Adaptador de entrada HTTP: traduz requisições para os casos de uso. */
class HttpApi(
    private val auth: AuthService,
    private val documents: DocumentService,
    private val shares: ShareService,
    private val accounts: AccountService,
    private val settings: HttpSettings,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(HttpApi::class.java)

    fun create(): Javalin = Javalin.create { config ->
        config.startup.showJavalinBanner = false
        config.jsonMapper(
            JavalinJackson(
                jacksonObjectMapper()
                    .registerModule(JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
            ),
        )
        config.http.maxRequestSize = MAX_REQUEST_BYTES
        config.jetty.multipartConfig.maxFileSize(DocumentService.MAX_SIZE_BYTES.toLong(), SizeUnit.BYTES)
        config.jetty.multipartConfig.maxTotalRequestSize(MAX_REQUEST_BYTES, SizeUnit.BYTES)
        config.jetty.multipartConfig.maxInMemoryFileSize(1, SizeUnit.MB)
        configureStaticFiles(config)
        registerFilters(config)
        registerRoutes(config)
        registerErrorHandlers(config)
    }

    // ------------------------------------------------------------------ filtros

    private fun registerFilters(config: JavalinConfig) {
        if (settings.requireHttps) config.routes.before(::enforceHttps)
        config.routes.before { ctx ->
            ctx.header("X-Content-Type-Options", "nosniff")
            ctx.header("Referrer-Policy", "no-referrer")
            ctx.header("X-Frame-Options", "DENY")
            ctx.header("Cross-Origin-Opener-Policy", "same-origin")
            ctx.header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
            ctx.header(
                "Content-Security-Policy",
                "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; " +
                    "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
            )
            if (settings.cookieSecure) {
                ctx.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
            }
        }
        // Respostas autenticadas, da API e de compartilhamento nunca entram em cache.
        config.routes.before("/api/*") { it.noStore() }
        config.routes.before("/s/*") { it.noStore() }
        config.routes.before("/dev/*") { it.noStore() }

        config.routes.before("/api/*") { ctx ->
            val principal = auth.authenticate(ctx.cookie(SESSION_COOKIE))
            if (principal != null) ctx.attribute(PRINCIPAL_ATTR, principal)

            if (ctx.method().name !in SAFE_METHODS) {
                // Defesa contra CSRF: origem precisa estar na allowlist, e
                // requisições autenticadas exigem token vinculado à sessão.
                val origin = ctx.header("Origin")
                if (origin != null && origin !in settings.allowedOrigins) {
                    throw AppException.Forbidden("Origem não permitida")
                }
                if (principal != null) {
                    val provided = ctx.header(CSRF_HEADER) ?: throw AppException.Forbidden("Token CSRF ausente")
                    val valid = (listOf(settings.csrfSecret) + settings.previousCsrfSecrets).any { secret ->
                        Crypto.constantTimeEquals(provided.toByteArray(), csrfToken(principal, secret).toByteArray())
                    }
                    if (!valid) {
                        throw AppException.Forbidden("Token CSRF inválido")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------- rotas

    private fun registerRoutes(config: JavalinConfig) {
        val r = config.routes
        r.get("/api/health") { it.json(mapOf("status" to "ok")) }

        // --- Autenticação
        r.post("/api/auth/otp") { ctx ->
            val body = ctx.parseBody<OtpRequestBody>()
            auth.requestOtp(body.email.orEmpty(), clientIp(ctx))
            ctx.status(HttpStatus.ACCEPTED)
                .json(MessageResponse("Se o e-mail for válido, enviaremos um código de acesso."))
        }
        r.post("/api/auth/otp/verify") { ctx ->
            val body = ctx.parseBody<OtpVerifyBody>()
            val issued = auth.verifyOtp(body.email.orEmpty(), body.code.orEmpty().trim(), clientIp(ctx))
            setSessionCookie(ctx, issued.token, Duration.between(clock.instant(), issued.expiresAt))
            val principal = auth.authenticate(issued.token) ?: throw AppException.Unauthorized()
            ctx.json(MeResponse(principal.email, csrfToken(principal), principal.expiresAt))
        }
        r.post("/api/auth/logout") { ctx ->
            auth.logout(ctx.cookie(SESSION_COOKIE), ctx.principalOrNull())
            setSessionCookie(ctx, "", Duration.ZERO)
            ctx.status(HttpStatus.NO_CONTENT)
        }
        r.get("/api/me") { ctx ->
            val principal = ctx.requirePrincipal()
            ctx.json(MeResponse(principal.email, csrfToken(principal), principal.expiresAt))
        }

        r.delete("/api/me") { ctx ->
            accounts.deleteAccount(ctx.requirePrincipal().userId)
            setSessionCookie(ctx, "", Duration.ZERO)
            ctx.status(HttpStatus.NO_CONTENT)
        }

        // --- Documentos
        r.get("/api/documents") { ctx ->
            ctx.json(documents.list(ctx.requirePrincipal().userId).map(DocumentResponse::of))
        }
        r.post("/api/documents") { ctx ->
            val principal = ctx.requirePrincipal()
            val declaredLength = ctx.header("Content-Length")?.toLongOrNull()
            if (declaredLength != null && declaredLength > MAX_REQUEST_BYTES) {
                throw AppException.PayloadTooLarge("Arquivo excede o limite de 10 MiB")
            }
            val file = try {
                ctx.uploadedFile("file")
            } catch (e: Exception) {
                throw AppException.PayloadTooLarge("Arquivo excede o limite de 10 MiB")
            } ?: throw AppException.Invalid("Envie o arquivo no campo 'file'")
            val document = documents.upload(
                ownerId = principal.userId,
                clientFileName = file.filename(),
                declaredMimeType = file.contentType(),
                declaredSize = file.size(),
                content = file.content(),
            )
            ctx.status(HttpStatus.CREATED).json(DocumentResponse.of(document))
        }
        r.get("/api/documents/{id}/download") { ctx ->
            ctx.sendAttachment(documents.download(ctx.requirePrincipal().userId, ctx.uuidParam("id")))
        }
        r.delete("/api/documents/{id}") { ctx ->
            documents.delete(ctx.requirePrincipal().userId, ctx.uuidParam("id"))
            ctx.status(HttpStatus.NO_CONTENT)
        }

        // --- Compartilhamento (proprietário)
        r.get("/api/documents/{id}/shares") { ctx ->
            val now = clock.instant()
            ctx.json(
                shares.list(ctx.requirePrincipal().userId, ctx.uuidParam("id")).map { ShareLinkResponse.of(it, now) },
            )
        }
        r.post("/api/documents/{id}/shares") { ctx ->
            val principal = ctx.requirePrincipal()
            val body = ctx.parseBody<CreateShareBody>()
            val created = shares.create(principal.userId, ctx.uuidParam("id"), body.expiresInMinutes, body.downloadLimit)
            ctx.status(HttpStatus.CREATED).json(
                CreatedShareResponse(
                    url = "${settings.publicBaseUrl.trimEnd('/')}/s/${created.token}",
                    link = ShareLinkResponse.of(created.link, clock.instant()),
                ),
            )
        }
        r.delete("/api/shares/{id}") { ctx ->
            shares.revoke(ctx.requirePrincipal().userId, ctx.uuidParam("id"))
            ctx.status(HttpStatus.NO_CONTENT)
        }

        // --- Acesso público por link (destinatário)
        r.get("/s/{token}") { ctx ->
            ctx.header("Content-Security-Policy", SharePages.CSP)
            ctx.header("X-Robots-Tag", "noindex, nofollow")
            val token = ctx.pathParam("token")
            val page = try {
                SharePages.valid(token, shares.inspect(token))
            } catch (_: AppException.NotFound) {
                ctx.status(HttpStatus.NOT_FOUND)
                SharePages.invalid()
            }
            ctx.html(page)
        }
        r.get("/s/{token}/download") { ctx ->
            ctx.header("X-Robots-Tag", "noindex, nofollow")
            try {
                ctx.sendAttachment(shares.download(ctx.pathParam("token")))
            } catch (_: AppException.NotFound) {
                ctx.header("Content-Security-Policy", SharePages.CSP)
                ctx.status(HttpStatus.NOT_FOUND).html(SharePages.invalid())
            }
        }

        // --- Somente desenvolvimento
        settings.devOutbox?.let { outbox ->
            log.warn("Outbox de e-mail de DESENVOLVIMENTO habilitado em /dev/outbox — não use em produção")
            r.get("/dev/outbox") { ctx -> ctx.json(outbox.snapshot()) }
        }
    }

    private fun registerErrorHandlers(config: JavalinConfig) {
        config.routes.exception(AppException::class.java) { e, ctx ->
            val status = when (e) {
                is AppException.Invalid -> HttpStatus.BAD_REQUEST
                is AppException.Unauthorized -> HttpStatus.UNAUTHORIZED
                is AppException.Forbidden -> HttpStatus.FORBIDDEN
                is AppException.NotFound -> HttpStatus.NOT_FOUND
                is AppException.TooManyRequests -> HttpStatus.TOO_MANY_REQUESTS
                is AppException.PayloadTooLarge -> HttpStatus.CONTENT_TOO_LARGE
                is AppException.UnsupportedMedia -> HttpStatus.UNSUPPORTED_MEDIA_TYPE
                is AppException.Rejected -> HttpStatus.UNPROCESSABLE_CONTENT
                is AppException.Unavailable -> HttpStatus.SERVICE_UNAVAILABLE
            }
            ctx.status(status).json(ErrorResponse(e.message ?: status.message))
        }
        config.routes.exception(Exception::class.java) { e, ctx ->
            // Não loga a URL (pode conter token de compartilhamento) nem o corpo.
            log.error("Erro inesperado em {} {}", ctx.method(), ctx.endpoint().path, e)
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(ErrorResponse("Erro interno"))
        }
    }

    // ----------------------------------------------------- arquivos estáticos

    private fun configureStaticFiles(config: JavalinConfig) {
        val dir = settings.staticDir?.toAbsolutePath()?.normalize() ?: return
        if (!Files.isRegularFile(dir.resolve("index.html"))) {
            log.info("Frontend não encontrado em {}; servindo somente a API", dir)
            return
        }
        config.staticFiles.add { files ->
            files.directory = dir.toString()
            files.location = Location.EXTERNAL
            // Revalida sempre: só o shell/estáticos versionados são cacheados pelo service worker.
            files.headers = mapOf("Cache-Control" to "no-cache")
            files.mimeTypes.add("application/manifest+json", "webmanifest")
        }
        config.spaRoot.addFile("/", dir.resolve("index.html").toString(), Location.EXTERNAL)
    }

    // ---------------------------------------------------------------- helpers

    private fun csrfToken(principal: SessionPrincipal, secret: ByteArray = settings.csrfSecret): String =
        Crypto.base64Url(Crypto.hmacSha256(secret, principal.sessionId.toString()))

    /**
     * HTTPS obrigatório. Atrás de proxy TLS, usa X-Forwarded-Proto (somente com
     * TRUST_PROXY=true). Navegações GET são redirecionadas para PUBLIC_BASE_URL
     * (nunca para o Host recebido, evitando open redirect); o resto é recusado.
     */
    private fun enforceHttps(ctx: Context) {
        if (ctx.path() == "/api/health") return
        val scheme = if (settings.trustProxy) {
            ctx.header("X-Forwarded-Proto")?.substringBefore(',')?.trim()?.lowercase() ?: ctx.scheme()
        } else {
            ctx.scheme()
        }
        if (scheme == "https") return
        val base = settings.publicBaseUrl.trimEnd('/')
        if (ctx.method().name in SAFE_METHODS && base.startsWith("https://")) {
            val query = ctx.queryString()?.let { "?$it" } ?: ""
            ctx.redirect(base + ctx.path() + query, HttpStatus.PERMANENT_REDIRECT)
            ctx.skipRemainingHandlers()
            return
        }
        throw AppException.Forbidden("HTTPS obrigatório")
    }

    private fun setSessionCookie(ctx: Context, token: String, maxAge: Duration) {
        val secure = if (settings.cookieSecure) "; Secure" else ""
        ctx.header(
            "Set-Cookie",
            "$SESSION_COOKIE=$token; Path=/; Max-Age=${maxAge.seconds.coerceAtLeast(0)}; HttpOnly; SameSite=Lax$secure",
        )
    }

    /** IP usado apenas em memória para limitar requisições; nunca persistido. */
    private fun clientIp(ctx: Context): String {
        if (settings.trustProxy) {
            ctx.header("X-Forwarded-For")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ctx.ip()
    }

    companion object {
        const val CSRF_HEADER = "X-CSRF-Token"
        private const val MAX_REQUEST_BYTES = 11L * 1024 * 1024
        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}
