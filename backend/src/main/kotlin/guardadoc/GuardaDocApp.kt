package guardadoc

import guardadoc.account.AccountService
import guardadoc.adapters.email.DevOutboxEmailSender
import guardadoc.adapters.email.SmtpEmailSender
import guardadoc.adapters.http.HttpApi
import guardadoc.adapters.http.HttpSettings
import guardadoc.adapters.persistence.Database
import guardadoc.adapters.persistence.PostgresAuditRepository
import guardadoc.adapters.persistence.PostgresDocumentRepository
import guardadoc.adapters.persistence.PostgresOtpRepository
import guardadoc.adapters.persistence.PostgresRetentionRepository
import guardadoc.adapters.persistence.PostgresSessionRepository
import guardadoc.adapters.persistence.PostgresShareRepository
import guardadoc.adapters.persistence.PostgresUserRepository
import guardadoc.adapters.ratelimit.InMemoryRateLimiter
import guardadoc.adapters.ratelimit.PostgresRateLimiter
import guardadoc.adapters.scan.ClamAvScanner
import guardadoc.adapters.storage.LocalFileSystemStorage
import guardadoc.adapters.storage.S3DocumentStorage
import guardadoc.auth.AuthRateLimits
import guardadoc.auth.AuthService
import guardadoc.auth.AuthSettings
import guardadoc.auth.EmailSender
import guardadoc.auth.RateLimiter
import guardadoc.documents.DocumentService
import guardadoc.documents.DocumentStorage
import guardadoc.documents.MalwareScanner
import guardadoc.documents.NoMalwareScanner
import guardadoc.adapters.persistence.PostgresJobLock
import guardadoc.maintenance.JobLock
import guardadoc.maintenance.MaintenanceService
import guardadoc.sharing.ShareService
import io.javalin.Javalin
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Composition root: liga portas aos adaptadores conforme a configuração. */
class GuardaDocApp(
    val config: AppConfig,
    val database: Database,
    val clock: Clock = Clock.systemUTC(),
    emailOverride: EmailSender? = null,
    storageOverride: DocumentStorage? = null,
    scannerOverride: MalwareScanner? = null,
) {
    private val log = LoggerFactory.getLogger(GuardaDocApp::class.java)

    val email: EmailSender = emailOverride
        ?: config.smtp?.let {
            log.info("E-mails de acesso via SMTP {}:{} (remetente {})", it.host, it.port, it.from)
            SmtpEmailSender(it)
        }
        ?: if (config.dev) {
            log.info("SMTP_HOST não definido: códigos de acesso ficam em /dev/outbox (nenhum e-mail é enviado)")
            DevOutboxEmailSender()
        } else {
            error("Configure SMTP_HOST para envio de e-mails")
        }

    val storage: DocumentStorage = storageOverride ?: when (config.storage) {
        AppConfig.StorageBackend.LOCAL -> LocalFileSystemStorage(config.storageDir)
        AppConfig.StorageBackend.S3 -> S3DocumentStorage(config.s3!!)
    }

    private val scanner: MalwareScanner = scannerOverride
        ?: config.clamAvHost?.let { ClamAvScanner(it, config.clamAvPort) }
        ?: NoMalwareScanner.also {
            if (!config.dev) log.warn("CLAMAV_HOST não configurado: uploads NÃO passam por antivírus")
        }

    val jobLock: JobLock = PostgresJobLock(database)

    private val audit = PostgresAuditRepository(database)
    private val users = PostgresUserRepository(database)
    private val documentRepository = PostgresDocumentRepository(database)

    private fun limiter(name: String, max: Int, window: Duration): RateLimiter = when (config.rateLimitStore) {
        "memory" -> InMemoryRateLimiter(max, window, clock)
        "postgres" -> PostgresRateLimiter(database, name, max, window, config.rateLimitKey, clock)
        else -> error("RATE_LIMIT_STORE deve ser 'postgres' ou 'memory'")
    }

    val authService = AuthService(
        users = users,
        otps = PostgresOtpRepository(database),
        sessions = PostgresSessionRepository(database),
        email = email,
        limits = AuthRateLimits(
            otpRequestByEmail = limiter("otp-email", 5, Duration.ofMinutes(15)),
            otpRequestByIp = limiter("otp-ip", 30, Duration.ofMinutes(15)),
            verifyByEmail = limiter("verify-email", 10, Duration.ofMinutes(15)),
            verifyByIp = limiter("verify-ip", 60, Duration.ofMinutes(15)),
        ),
        audit = audit,
        settings = AuthSettings(otpHmacSecret = config.otpHmacKey, previousOtpHmacSecrets = config.previousOtpHmacKeys),
        clock = clock,
    )
    val documentService = DocumentService(documentRepository, storage, audit, clock, scanner)
    val shareService = ShareService(PostgresShareRepository(database), documentRepository, storage, audit, clock)
    val accountService = AccountService(users, documentRepository, documentService, audit, clock)
    val maintenanceService = MaintenanceService(
        retention = PostgresRetentionRepository(database),
        documents = documentRepository,
        documentService = documentService,
        storage = storage,
        audit = audit,
        policy = config.retention,
        clock = clock,
    )

    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "manutencao").apply { isDaemon = true } }
    private var javalin: Javalin? = null

    fun start(port: Int = config.port, scheduleJobs: Boolean = true): Javalin {
        Database.migrate(database.dataSource)
        val app = HttpApi(
            auth = authService,
            documents = documentService,
            shares = shareService,
            accounts = accountService,
            settings = HttpSettings(
                publicBaseUrl = config.publicBaseUrl,
                allowedOrigins = config.allowedOrigins,
                cookieSecure = config.cookieSecure,
                csrfSecret = config.csrfKey,
                previousCsrfSecrets = config.previousCsrfKeys,
                requireHttps = config.requireHttps,
                trustProxy = config.trustProxy,
                staticDir = config.staticDir,
                devOutbox = (email as? DevOutboxEmailSender)?.takeIf { config.dev },
            ),
            clock = clock,
        ).create().start(port)
        if (scheduleJobs) scheduleMaintenance()
        javalin = app
        return app
    }

    private fun scheduleMaintenance() {
        // Com várias instâncias, cada rotina roda em uma só por vez (advisory lock).
        // Reprocessa remoções de arquivos que falharam (documentos já inacessíveis).
        scheduler.scheduleWithFixedDelay({
            runCatching { jobLock.tryRun("arquivos-pendentes") { documentService.purgePendingFiles() } }
                .onFailure { log.warn("Falha na limpeza de arquivos: {}", it.javaClass.simpleName) }
        }, 1, 5, TimeUnit.MINUTES)
        // Retenção de dados e varredura de objetos órfãos.
        scheduler.scheduleWithFixedDelay({
            runCatching { jobLock.tryRun("manutencao") { maintenanceService.runAll() } }
                .onFailure { log.warn("Falha na manutenção: {}", it.javaClass.simpleName) }
        }, 10, 60, TimeUnit.MINUTES)
    }

    fun stop() {
        scheduler.shutdownNow()
        javalin?.stop()
    }
}
