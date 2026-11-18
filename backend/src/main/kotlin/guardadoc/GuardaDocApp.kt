package guardadoc

import guardadoc.adapters.email.DevOutboxEmailSender
import guardadoc.adapters.email.SmtpEmailSender
import guardadoc.adapters.http.HttpApi
import guardadoc.adapters.http.HttpSettings
import guardadoc.adapters.persistence.Database
import guardadoc.adapters.persistence.PostgresAuditRepository
import guardadoc.adapters.persistence.PostgresDocumentRepository
import guardadoc.adapters.persistence.PostgresOtpRepository
import guardadoc.adapters.persistence.PostgresSessionRepository
import guardadoc.adapters.persistence.PostgresShareRepository
import guardadoc.adapters.persistence.PostgresUserRepository
import guardadoc.adapters.ratelimit.InMemoryRateLimiter
import guardadoc.adapters.storage.LocalFileSystemStorage
import guardadoc.auth.AuthRateLimits
import guardadoc.auth.AuthService
import guardadoc.auth.AuthSettings
import guardadoc.auth.EmailSender
import guardadoc.documents.DocumentService
import guardadoc.documents.DocumentStorage
import guardadoc.sharing.ShareService
import io.javalin.Javalin
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Composition root: liga portas aos adaptadores. */
class GuardaDocApp(
    val config: AppConfig,
    val database: Database,
    val clock: Clock = Clock.systemUTC(),
    emailOverride: EmailSender? = null,
    storageOverride: DocumentStorage? = null,
) {
    private val log = LoggerFactory.getLogger(GuardaDocApp::class.java)

    val email: EmailSender = emailOverride
        ?: config.smtp?.let(::SmtpEmailSender)
        ?: if (config.dev) DevOutboxEmailSender() else error("Configure SMTP_HOST para envio de e-mails")

    val storage: DocumentStorage = storageOverride ?: LocalFileSystemStorage(config.storageDir)
    private val audit = PostgresAuditRepository(database)
    private val documentRepository = PostgresDocumentRepository(database)

    val authService = AuthService(
        users = PostgresUserRepository(database),
        otps = PostgresOtpRepository(database),
        sessions = PostgresSessionRepository(database),
        email = email,
        limits = AuthRateLimits(
            otpRequestByEmail = InMemoryRateLimiter(5, Duration.ofMinutes(15), clock),
            otpRequestByIp = InMemoryRateLimiter(30, Duration.ofMinutes(15), clock),
            verifyByEmail = InMemoryRateLimiter(10, Duration.ofMinutes(15), clock),
            verifyByIp = InMemoryRateLimiter(60, Duration.ofMinutes(15), clock),
        ),
        audit = audit,
        settings = AuthSettings(otpHmacSecret = config.otpHmacKey),
        clock = clock,
    )
    val documentService = DocumentService(documentRepository, storage, audit, clock)
    val shareService = ShareService(PostgresShareRepository(database), documentRepository, storage, audit, clock)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "limpeza-arquivos").apply { isDaemon = true } }
    private var javalin: Javalin? = null

    fun start(port: Int = config.port): Javalin {
        Database.migrate(database.dataSource)
        val app = HttpApi(
            auth = authService,
            documents = documentService,
            shares = shareService,
            settings = HttpSettings(
                publicBaseUrl = config.publicBaseUrl,
                allowedOrigins = config.allowedOrigins,
                cookieSecure = config.cookieSecure,
                csrfSecret = config.csrfKey,
                trustProxy = config.trustProxy,
                staticDir = config.staticDir,
                devOutbox = (email as? DevOutboxEmailSender)?.takeIf { config.dev },
            ),
            clock = clock,
        ).create().start(port)
        // Reprocessa remoções de arquivos que falharam (documentos já inacessíveis).
        scheduler.scheduleWithFixedDelay({
            runCatching { documentService.purgePendingFiles() }
                .onSuccess { if (it > 0) log.info("Limpeza removeu {} arquivo(s) pendente(s)", it) }
                .onFailure { log.warn("Falha na limpeza de arquivos: {}", it.javaClass.simpleName) }
        }, 1, 5, TimeUnit.MINUTES)
        javalin = app
        return app
    }

    fun stop() {
        scheduler.shutdownNow()
        javalin?.stop()
    }
}
