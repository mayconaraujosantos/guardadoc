package guardadoc.adapters.http

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import guardadoc.account.AccountService
import guardadoc.adapters.email.DevOutboxEmailSender
import guardadoc.adapters.http.routes.AccountRoutes
import guardadoc.adapters.http.routes.AuthRoutes
import guardadoc.adapters.http.routes.CategoryRoutes
import guardadoc.adapters.http.routes.DocumentRoutes
import guardadoc.adapters.http.routes.ShareRoutes
import guardadoc.adapters.http.routes.SystemRoutes
import guardadoc.auth.AuthService
import guardadoc.auth.PasskeyService
import guardadoc.documents.CategoryService
import guardadoc.documents.DocumentService
import guardadoc.shared.AppException
import guardadoc.shared.Crypto
import guardadoc.sharing.ShareService
import io.javalin.Javalin
import io.javalin.config.JavalinConfig
import io.javalin.config.SizeUnit
import io.javalin.http.HttpStatus
import io.javalin.http.staticfiles.Location
import io.javalin.json.JavalinJackson
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

data class HttpSettings(
    val publicBaseUrl: String,
    val allowedOrigins: Set<String>,
    val dev: Boolean,
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

/**
 * Adaptador de entrada HTTP.
 *
 * Configura o servidor, os filtros comuns e delega cada grupo de rotas a um
 * registrador especializado. Regras de negócio ficam na camada de aplicação.
 */
class HttpApi(
    private val auth: AuthService,
    private val passkeys: PasskeyService,
    private val documents: DocumentService,
    private val categories: CategoryService,
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
        if (settings.requireHttps) config.routes.before { it.enforceHttps(settings) }
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
                val sameOriginInDev = settings.dev && origin != null && run {
                    val host = ctx.header("Host")?.substringBefore(',')?.trim()?.lowercase()
                    val normalizedOrigin = origin.trimEnd('/')
                    host != null && (normalizedOrigin == "http://$host" || normalizedOrigin == "https://$host")
                }
                if (origin != null && origin !in settings.allowedOrigins && !sameOriginInDev) {
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
        SystemRoutes(settings).register(config)
        AuthRoutes(auth, passkeys, settings, clock).register(config)
        AccountRoutes(accounts, settings).register(config)
        DocumentRoutes(documents, categories).register(config)
        CategoryRoutes(categories).register(config)
        ShareRoutes(shares, settings, clock).register(config)
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

    companion object {
        const val CSRF_HEADER = "X-CSRF-Token"
    }
}
