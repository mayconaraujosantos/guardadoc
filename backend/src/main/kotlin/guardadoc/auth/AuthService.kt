package guardadoc.auth

import guardadoc.application.auth.Authenticate
import guardadoc.application.auth.AuthenticateCommand
import guardadoc.application.auth.EmailNormalizer
import guardadoc.application.auth.Logout
import guardadoc.application.auth.LogoutCommand
import guardadoc.application.auth.RequestOtp
import guardadoc.application.auth.RequestOtpCommand
import guardadoc.application.auth.VerifyOtp
import guardadoc.application.auth.VerifyOtpCommand
import guardadoc.shared.AuditRepository
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

/**
 * API da aplicação de autenticação.
 *
 * Cada método delega a um caso de uso dedicado; a classe existe para manter
 * estável a fachada pública usada pelo adaptador HTTP e pelos testes.
 */
class AuthService(
    users: UserRepository,
    otps: OtpRepository,
    sessions: SessionRepository,
    email: EmailSender,
    limits: AuthRateLimits,
    audit: AuditRepository,
    settings: AuthSettings,
    clock: Clock,
) {
    private val requestOtpUseCase = RequestOtp(otps, email, limits, audit, settings, clock)
    private val verifyOtpUseCase = VerifyOtp(users, otps, sessions, limits, audit, settings, clock)
    private val authenticateUseCase = Authenticate(sessions, clock)
    private val logoutUseCase = Logout(sessions, audit, clock)

    /** Sempre termina com a mesma resposta genérica, exista ou não o usuário. */
    fun requestOtp(rawEmail: String, clientIp: String) =
        requestOtpUseCase(RequestOtpCommand(rawEmail, clientIp))

    fun verifyOtp(rawEmail: String, code: String, clientIp: String): IssuedSession =
        verifyOtpUseCase(VerifyOtpCommand(rawEmail, code, clientIp))

    fun authenticate(sessionToken: String?): SessionPrincipal? =
        authenticateUseCase(AuthenticateCommand(sessionToken))

    fun logout(sessionToken: String?, principal: SessionPrincipal?) =
        logoutUseCase(LogoutCommand(sessionToken, principal))

    companion object {
        fun normalizeEmail(raw: String): String = EmailNormalizer.normalize(raw)
    }
}
