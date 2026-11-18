package guardadoc

import guardadoc.adapters.email.SmtpSettings
import guardadoc.adapters.storage.S3Settings
import guardadoc.maintenance.RetentionPolicy
import guardadoc.shared.Crypto
import java.nio.file.Path
import java.time.Duration

data class AppConfig(
    val dev: Boolean,
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String?,
    val databasePassword: String?,
    /** Segredo mestre (≥ 32 bytes). Chaves de OTP e CSRF são derivadas dele. */
    val appSecret: String,
    /** Segredo anterior, aceito durante a rotação (ver docs/politicas/segredos-e-chaves.md). */
    val appSecretPrevious: String?,
    val publicBaseUrl: String,
    val allowedOrigins: Set<String>,
    val cookieSecure: Boolean,
    val trustProxy: Boolean,
    val storageDir: Path,
    val staticDir: Path?,
    val smtp: SmtpSettings?,
    val requireHttps: Boolean,
    val storage: StorageBackend,
    val s3: S3Settings?,
    val rateLimitStore: String,
    val clamAvHost: String?,
    val clamAvPort: Int,
    val retention: RetentionPolicy,
) {
    enum class StorageBackend { LOCAL, S3 }

    private fun derive(secret: String, purpose: String) = Crypto.hmacSha256(secret.toByteArray(), "guardadoc/$purpose")

    val otpHmacKey: ByteArray get() = derive(appSecret, "otp")
    val csrfKey: ByteArray get() = derive(appSecret, "csrf")
    val rateLimitKey: ByteArray get() = derive(appSecret, "ratelimit")
    val previousOtpHmacKeys: List<ByteArray> get() = listOfNotNull(appSecretPrevious?.let { derive(it, "otp") })
    val previousCsrfKeys: List<ByteArray> get() = listOfNotNull(appSecretPrevious?.let { derive(it, "csrf") })

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun get(name: String) = env[name]?.takeIf { it.isNotBlank() }
            val dev = get("APP_ENV") == "dev"
            val port = get("PORT")?.toInt() ?: 7070
            val publicBaseUrl = get("PUBLIC_BASE_URL") ?: "http://localhost:$port"
            val secret = get("APP_SECRET") ?: if (dev) Crypto.randomToken() else error("APP_SECRET é obrigatório")
            require(secret.length >= 32) { "APP_SECRET deve ter pelo menos 32 caracteres" }
            val previous = get("APP_SECRET_PREVIOUS")
            require(previous == null || previous.length >= 32) { "APP_SECRET_PREVIOUS deve ter pelo menos 32 caracteres" }
            val storage = AppConfig.StorageBackend.valueOf((get("STORAGE_BACKEND") ?: "local").uppercase())
            fun days(name: String, default: Long) = Duration.ofDays(get(name)?.toLong() ?: default)
            return AppConfig(
                dev = dev,
                port = port,
                databaseUrl = get("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/guardadoc",
                databaseUser = get("DATABASE_USER") ?: "guardadoc",
                databasePassword = get("DATABASE_PASSWORD") ?: "guardadoc",
                appSecret = secret,
                appSecretPrevious = previous,
                publicBaseUrl = publicBaseUrl,
                allowedOrigins = (get("ALLOWED_ORIGINS")?.split(',')?.map { it.trim().trimEnd('/') }
                    ?: listOf(publicBaseUrl.trimEnd('/'), "http://localhost:5173")).toSet(),
                cookieSecure = get("COOKIE_SECURE")?.toBooleanStrict() ?: true,
                trustProxy = get("TRUST_PROXY")?.toBooleanStrict() ?: false,
                storageDir = Path.of(get("STORAGE_DIR") ?: "./data/storage"),
                staticDir = Path.of(get("STATIC_DIR") ?: "../frontend/dist"),
                smtp = get("SMTP_HOST")?.let { host ->
                    val ssl = get("SMTP_SSL")?.toBooleanStrict() ?: (get("SMTP_PORT") == "465")
                    val username = get("SMTP_USERNAME")
                    val password = get("SMTP_PASSWORD")
                    require((username == null) == (password == null)) {
                        "SMTP_USERNAME e SMTP_PASSWORD devem ser definidos juntos"
                    }
                    SmtpSettings(
                        host = host,
                        port = get("SMTP_PORT")?.toInt() ?: if (ssl) 465 else 587,
                        username = username,
                        password = password,
                        // No Gmail, o remetente padrão é a própria conta.
                        from = get("SMTP_FROM") ?: username
                            ?: error("SMTP_FROM é obrigatório quando SMTP_HOST é definido"),
                        startTls = !ssl && (get("SMTP_STARTTLS")?.toBooleanStrict() ?: true),
                        ssl = ssl,
                    )
                },
                // Fora de dev, recusa HTTP puro (use proxy TLS com TRUST_PROXY=true).
                requireHttps = get("REQUIRE_HTTPS")?.toBooleanStrict() ?: !dev,
                storage = storage,
                s3 = if (storage == AppConfig.StorageBackend.S3) {
                    S3Settings(
                        bucket = get("S3_BUCKET") ?: error("S3_BUCKET é obrigatório com STORAGE_BACKEND=s3"),
                        region = get("S3_REGION") ?: "us-east-1",
                        endpoint = get("S3_ENDPOINT"),
                        accessKeyId = get("S3_ACCESS_KEY_ID"),
                        secretAccessKey = get("S3_SECRET_ACCESS_KEY"),
                        pathStyle = get("S3_PATH_STYLE")?.toBooleanStrict() ?: false,
                        serverSideEncryption = get("S3_SSE")?.toBooleanStrict() ?: true,
                    )
                } else null,
                rateLimitStore = get("RATE_LIMIT_STORE") ?: "postgres",
                clamAvHost = get("CLAMAV_HOST"),
                clamAvPort = get("CLAMAV_PORT")?.toInt() ?: 3310,
                retention = RetentionPolicy(
                    inactiveShareLinks = days("RETENTION_SHARE_LINKS_DAYS", 90),
                    deletedDocumentMetadata = days("RETENTION_DELETED_DOCUMENTS_DAYS", 30),
                    auditEvents = days("RETENTION_AUDIT_DAYS", 180),
                ),
            )
        }
    }
}
