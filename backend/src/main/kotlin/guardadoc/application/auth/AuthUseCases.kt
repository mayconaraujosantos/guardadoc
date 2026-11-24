package guardadoc.application.auth

import guardadoc.application.CommandUseCase
import guardadoc.application.UseCase
import guardadoc.auth.AuthRateLimits
import guardadoc.auth.AuthSettings
import guardadoc.auth.EmailDeliveryException
import guardadoc.auth.EmailSender
import guardadoc.auth.IssuedSession
import guardadoc.auth.NewOtpChallenge
import guardadoc.auth.OtpRepository
import guardadoc.auth.SessionPrincipal
import guardadoc.auth.SessionRepository
import guardadoc.auth.UserRepository
import guardadoc.shared.AppException
import guardadoc.shared.AuditEvent
import guardadoc.shared.AuditEventType
import guardadoc.shared.AuditRepository
import guardadoc.shared.Crypto
import org.slf4j.LoggerFactory
import java.time.Clock

private val OTP_FORMAT = Regex("^[0-9]{6}$")
private val EMAIL_FORMAT = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/** Regra de normalização de e-mail, compartilhada por todos os casos de uso de autenticação. */
object EmailNormalizer {
    fun normalize(raw: String): String {
        val email = raw.trim().lowercase()
        if (email.length > 254 || !EMAIL_FORMAT.matches(email)) {
            throw AppException.Invalid("E-mail inválido")
        }
        return email
    }
}

data class RequestOtpCommand(val rawEmail: String, val clientIp: String)

/** Emite um desafio OTP; a resposta HTTP nunca revela se o e-mail existe. */
class RequestOtp(
    private val otps: OtpRepository,
    private val email: EmailSender,
    private val limits: AuthRateLimits,
    private val audit: AuditRepository,
    private val settings: AuthSettings,
    private val clock: Clock,
) : CommandUseCase<RequestOtpCommand> {
    private val log = LoggerFactory.getLogger(RequestOtp::class.java)

    override fun invoke(command: RequestOtpCommand) {
        val normalized = EmailNormalizer.normalize(command.rawEmail)
        if (!limits.otpRequestByIp.tryAcquire(command.clientIp) ||
            !limits.otpRequestByEmail.tryAcquire(normalized)
        ) {
            throw AppException.TooManyRequests()
        }
        val now = clock.instant()
        val code = Crypto.sixDigitCode()
        otps.replaceActive(
            NewOtpChallenge(
                email = normalized,
                digest = digest(normalized, code, settings.otpHmacSecret),
                expiresAt = now.plus(settings.otpTtl),
                maxAttempts = settings.otpMaxAttempts,
            ),
            now,
        )
        try {
            email.sendOtp(normalized, code)
        } catch (e: Exception) {
            // Não propaga detalhes ao cliente. No log, só o diagnóstico do adaptador.
            val reason = if (e is EmailDeliveryException) e.message else e.javaClass.simpleName
            log.error("Falha ao enviar e-mail de OTP: {}", reason)
        }
        audit.record(AuditEvent(AuditEventType.OTP_SOLICITADO))
    }
}

data class VerifyOtpCommand(val rawEmail: String, val code: String, val clientIp: String)

/** Valida o OTP (uma única vez) e cria a sessão. */
class VerifyOtp(
    private val users: UserRepository,
    private val otps: OtpRepository,
    private val sessions: SessionRepository,
    private val limits: AuthRateLimits,
    private val audit: AuditRepository,
    private val settings: AuthSettings,
    private val clock: Clock,
) : UseCase<VerifyOtpCommand, IssuedSession> {
    override fun invoke(command: VerifyOtpCommand): IssuedSession {
        val normalized = EmailNormalizer.normalize(command.rawEmail)
        if (!limits.verifyByIp.tryAcquire(command.clientIp) ||
            !limits.verifyByEmail.tryAcquire(normalized)
        ) {
            throw AppException.TooManyRequests()
        }
        if (!OTP_FORMAT.matches(command.code)) throw invalidCode()

        val now = clock.instant()
        val challenge = otps.registerAttempt(normalized, now) ?: throw invalidCode()
        val matches = (listOf(settings.otpHmacSecret) + settings.previousOtpHmacSecrets).any { secret ->
            Crypto.constantTimeEquals(challenge.digest, digest(normalized, command.code, secret))
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
}

data class AuthenticateCommand(val sessionToken: String?)

/** Resolve o principal a partir do token opaco da sessão. */
class Authenticate(
    private val sessions: SessionRepository,
    private val clock: Clock,
) : UseCase<AuthenticateCommand, SessionPrincipal?> {
    override fun invoke(command: AuthenticateCommand): SessionPrincipal? {
        val token = command.sessionToken
        if (token.isNullOrBlank() || token.length > 128) return null
        return sessions.findActive(Crypto.sha256(token), clock.instant())
    }
}

data class LogoutCommand(val sessionToken: String?, val principal: SessionPrincipal?)

/** Revoga a sessão atual. */
class Logout(
    private val sessions: SessionRepository,
    private val audit: AuditRepository,
    private val clock: Clock,
) : CommandUseCase<LogoutCommand> {
    override fun invoke(command: LogoutCommand) {
        val token = command.sessionToken
        if (token.isNullOrBlank()) return
        sessions.revoke(Crypto.sha256(token), clock.instant())
        command.principal?.let { audit.record(AuditEvent(AuditEventType.LOGOUT, userId = it.userId)) }
    }
}

private fun digest(email: String, code: String, secret: ByteArray) =
    Crypto.hmacSha256(secret, "$email:$code")

private fun invalidCode() = AppException.Unauthorized("Código inválido ou expirado")
