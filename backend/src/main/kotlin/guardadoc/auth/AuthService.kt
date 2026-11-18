package guardadoc.auth

import guardadoc.shared.AppException
import guardadoc.shared.AuditEvent
import guardadoc.shared.AuditEventType
import guardadoc.shared.AuditRepository
import guardadoc.shared.Crypto
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class AuthSettings(
    val otpHmacSecret: ByteArray,
    /** Segredos anteriores aceitos na verificação durante uma rotação. */
    val previousOtpHmacSecrets: List<ByteArray> = emptyList(),
    val otpTtl: Duration = Duration.ofMinutes(5),
    val otpMaxAttempts: Int = 5,
    val sessionTtl: Duration = Duration.ofHours(12),
)

/** Limitadores independentes por e-mail normalizado e por IP de origem. */
data class AuthRateLimits(
    val otpRequestByEmail: RateLimiter,
    val otpRequestByIp: RateLimiter,
    val verifyByEmail: RateLimiter,
    val verifyByIp: RateLimiter,
)

data class IssuedSession(val token: String, val expiresAt: Instant, val user: User)

/** Casos de uso: RequestOtp, VerifyOtp/CreateSession, autenticação e logout. */
class AuthService(
    private val users: UserRepository,
    private val otps: OtpRepository,
    private val sessions: SessionRepository,
    private val email: EmailSender,
    private val limits: AuthRateLimits,
    private val audit: AuditRepository,
    private val settings: AuthSettings,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)

    /** Sempre termina com a mesma resposta genérica, exista ou não o usuário. */
    fun requestOtp(rawEmail: String, clientIp: String) {
        val normalized = normalizeEmail(rawEmail)
        if (!limits.otpRequestByIp.tryAcquire(clientIp) || !limits.otpRequestByEmail.tryAcquire(normalized)) {
            throw AppException.TooManyRequests()
        }
        val now = clock.instant()
        val code = Crypto.sixDigitCode()
        otps.replaceActive(
            NewOtpChallenge(
                email = normalized,
                digest = digest(normalized, code),
                expiresAt = now.plus(settings.otpTtl),
                maxAttempts = settings.otpMaxAttempts,
            ),
            now,
        )
        try {
            email.sendOtp(normalized, code)
        } catch (e: Exception) {
            // Não propaga detalhes (nem o código) para o cliente ou para os logs.
            log.error("Falha ao enviar e-mail de OTP: {}", e.javaClass.simpleName)
        }
        audit.record(AuditEvent(AuditEventType.OTP_SOLICITADO))
    }

    fun verifyOtp(rawEmail: String, code: String, clientIp: String): IssuedSession {
        val normalized = normalizeEmail(rawEmail)
        if (!limits.verifyByIp.tryAcquire(clientIp) || !limits.verifyByEmail.tryAcquire(normalized)) {
            throw AppException.TooManyRequests()
        }
        if (!OTP_FORMAT.matches(code)) throw invalidCode()

        val now = clock.instant()
        val challenge = otps.registerAttempt(normalized, now) ?: throw invalidCode()
        val matches = (listOf(settings.otpHmacSecret) + settings.previousOtpHmacSecrets).any { secret ->
            Crypto.constantTimeEquals(challenge.digest, digest(normalized, code, secret))
        }
        if (!matches || !otps.consume(challenge.id, now)) {
            audit.record(AuditEvent(AuditEventType.LOGIN_FALHA))
            throw invalidCode()
        }

        val user = users.findOrCreate(normalized)
        val token = Crypto.randomToken()
        val expiresAt = now.plus(settings.sessionTtl)
        sessions.create(user.id, Crypto.sha256(token), expiresAt)
        audit.record(AuditEvent(AuditEventType.LOGIN_SUCESSO, userId = user.id))
        return IssuedSession(token, expiresAt, user)
    }

    fun authenticate(sessionToken: String?): SessionPrincipal? {
        if (sessionToken.isNullOrBlank() || sessionToken.length > 128) return null
        return sessions.findActive(Crypto.sha256(sessionToken), clock.instant())
    }

    fun logout(sessionToken: String?, principal: SessionPrincipal?) {
        if (sessionToken.isNullOrBlank()) return
        sessions.revoke(Crypto.sha256(sessionToken), clock.instant())
        principal?.let { audit.record(AuditEvent(AuditEventType.LOGOUT, userId = it.userId)) }
    }

    private fun digest(email: String, code: String, secret: ByteArray = settings.otpHmacSecret) =
        Crypto.hmacSha256(secret, "$email:$code")

    private fun invalidCode() = AppException.Unauthorized("Código inválido ou expirado")

    companion object {
        private val OTP_FORMAT = Regex("^[0-9]{6}$")
        private val EMAIL_FORMAT = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        fun normalizeEmail(raw: String): String {
            val email = raw.trim().lowercase()
            if (email.length > 254 || !EMAIL_FORMAT.matches(email)) {
                throw AppException.Invalid("E-mail inválido")
            }
            return email
        }
    }
}
