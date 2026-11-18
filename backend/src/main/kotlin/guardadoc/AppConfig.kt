package guardadoc

import guardadoc.adapters.email.SmtpSettings
import guardadoc.shared.Crypto
import java.nio.file.Path

data class AppConfig(
    val dev: Boolean,
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String?,
    val databasePassword: String?,
    /** Segredo mestre (≥ 32 bytes). Chaves de OTP e CSRF são derivadas dele. */
    val appSecret: String,
    val publicBaseUrl: String,
    val allowedOrigins: Set<String>,
    val cookieSecure: Boolean,
    val trustProxy: Boolean,
    val storageDir: Path,
    val staticDir: Path?,
    val smtp: SmtpSettings?,
) {
    val otpHmacKey: ByteArray get() = Crypto.hmacSha256(appSecret.toByteArray(), "guardadoc/otp")
    val csrfKey: ByteArray get() = Crypto.hmacSha256(appSecret.toByteArray(), "guardadoc/csrf")

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun get(name: String) = env[name]?.takeIf { it.isNotBlank() }
            val dev = get("APP_ENV") == "dev"
            val port = get("PORT")?.toInt() ?: 7070
            val publicBaseUrl = get("PUBLIC_BASE_URL") ?: "http://localhost:$port"
            val secret = get("APP_SECRET") ?: if (dev) Crypto.randomToken() else error("APP_SECRET é obrigatório")
            require(secret.length >= 32) { "APP_SECRET deve ter pelo menos 32 caracteres" }
            return AppConfig(
                dev = dev,
                port = port,
                databaseUrl = get("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/guardadoc",
                databaseUser = get("DATABASE_USER") ?: "guardadoc",
                databasePassword = get("DATABASE_PASSWORD") ?: "guardadoc",
                appSecret = secret,
                publicBaseUrl = publicBaseUrl,
                allowedOrigins = (get("ALLOWED_ORIGINS")?.split(',')?.map { it.trim().trimEnd('/') }
                    ?: listOf(publicBaseUrl.trimEnd('/'), "http://localhost:5173")).toSet(),
                cookieSecure = get("COOKIE_SECURE")?.toBooleanStrict() ?: true,
                trustProxy = get("TRUST_PROXY")?.toBooleanStrict() ?: false,
                storageDir = Path.of(get("STORAGE_DIR") ?: "./data/storage"),
                staticDir = Path.of(get("STATIC_DIR") ?: "../frontend/dist"),
                smtp = get("SMTP_HOST")?.let { host ->
                    SmtpSettings(
                        host = host,
                        port = get("SMTP_PORT")?.toInt() ?: 587,
                        username = get("SMTP_USERNAME"),
                        password = get("SMTP_PASSWORD"),
                        from = get("SMTP_FROM") ?: error("SMTP_FROM é obrigatório quando SMTP_HOST é definido"),
                        startTls = get("SMTP_STARTTLS")?.toBooleanStrict() ?: true,
                    )
                },
            )
        }
    }
}
