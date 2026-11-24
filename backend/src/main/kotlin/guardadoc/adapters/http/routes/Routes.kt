package guardadoc.adapters.http.routes

import guardadoc.account.AccountService
import guardadoc.adapters.http.*
import guardadoc.application.auth.FinishPasskeyLogin
import guardadoc.application.auth.FinishPasskeyLoginCommand
import guardadoc.application.auth.FinishPasskeyRegistration
import guardadoc.application.auth.FinishPasskeyRegistrationCommand
import guardadoc.application.auth.StartPasskeyLogin
import guardadoc.application.auth.StartPasskeyRegistration
import guardadoc.application.auth.StartPasskeyRegistrationCommand
import guardadoc.auth.AuthService
import guardadoc.auth.PasskeyService
import guardadoc.documents.CategoryService
import guardadoc.documents.DocumentService
import guardadoc.sharing.ShareService
import guardadoc.shared.AppException
import io.javalin.config.JavalinConfig
import io.javalin.http.HttpStatus
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Registradores de rotas: um por área da aplicação.
 *
 * Cada classe traduz requisições para chamadas de casos de uso/fachadas e
 * converte o resultado em resposta HTTP. `HttpApi` apenas compõe o servidor.
 */

class SystemRoutes(private val settings: HttpSettings) {
    private val log = LoggerFactory.getLogger(SystemRoutes::class.java)

    fun register(config: JavalinConfig) {
        config.routes.get("/api/health") { it.json(mapOf("status" to "ok")) }

        settings.devOutbox?.let { outbox ->
            log.warn("Outbox de e-mail de DESENVOLVIMENTO habilitado em /dev/outbox — não use em produção")
            config.routes.get("/dev/outbox") { ctx -> ctx.json(outbox.snapshot()) }
        }
    }
}

class AuthRoutes(
    private val auth: AuthService,
    private val passkeys: PasskeyService,
    private val settings: HttpSettings,
    private val clock: Clock,
) {
    private val startLogin = StartPasskeyLogin(passkeys)
    private val finishLogin = FinishPasskeyLogin(passkeys)
    private val startRegistration = StartPasskeyRegistration(passkeys)
    private val finishRegistration = FinishPasskeyRegistration(passkeys)

    fun register(config: JavalinConfig) {
        val r = config.routes

        r.post("/api/auth/otp") { ctx ->
            val body = ctx.parseBody<OtpRequestBody>()
            auth.requestOtp(body.email.orEmpty(), ctx.clientIp(settings.trustProxy))
            ctx.status(HttpStatus.ACCEPTED)
                .json(MessageResponse("Se o e-mail for válido, enviaremos um código de acesso."))
        }
        r.post("/api/auth/otp/verify") { ctx ->
            val body = ctx.parseBody<OtpVerifyBody>()
            val issued = auth.verifyOtp(body.email.orEmpty(), body.code.orEmpty().trim(), ctx.clientIp(settings.trustProxy))
            ctx.setSessionCookie(issued.token, Duration.between(clock.instant(), issued.expiresAt), settings.cookieSecure)
            val principal = auth.authenticate(issued.token) ?: throw AppException.Unauthorized()
            ctx.json(MeResponse(principal.email, csrfToken(principal, settings.csrfSecret), principal.expiresAt))
        }
        r.post("/api/auth/passkey/options") { ctx ->
            val started = startLogin()
            ctx.json(PasskeyStartResponse(started.challengeId, started.browserOptions))
        }
        r.post("/api/auth/passkey/verify") { ctx ->
            val body = ctx.parseBody<PasskeyCredentialBody>()
            val issued = finishLogin(FinishPasskeyLoginCommand(body.challengeId, body.credential))
            ctx.setSessionCookie(issued.token, Duration.between(clock.instant(), issued.expiresAt), settings.cookieSecure)
            val principal = auth.authenticate(issued.token) ?: throw AppException.Unauthorized()
            ctx.json(MeResponse(principal.email, csrfToken(principal, settings.csrfSecret), principal.expiresAt))
        }
        r.post("/api/passkeys/options") { ctx ->
            val principal = ctx.requirePrincipal()
            val started = startRegistration(StartPasskeyRegistrationCommand(principal))
            ctx.json(PasskeyStartResponse(started.challengeId, started.browserOptions))
        }
        r.post("/api/passkeys/register") { ctx ->
            val principal = ctx.requirePrincipal()
            val body = ctx.parseBody<PasskeyCredentialBody>()
            finishRegistration(FinishPasskeyRegistrationCommand(principal, body.challengeId, body.credential))
            ctx.status(HttpStatus.NO_CONTENT)
        }
        r.post("/api/auth/logout") { ctx ->
            auth.logout(ctx.cookie(SESSION_COOKIE), ctx.principalOrNull())
            ctx.setSessionCookie("", Duration.ZERO, settings.cookieSecure)
            ctx.status(HttpStatus.NO_CONTENT)
        }
        r.get("/api/me") { ctx ->
            val principal = ctx.requirePrincipal()
            ctx.json(MeResponse(principal.email, csrfToken(principal, settings.csrfSecret), principal.expiresAt))
        }
    }
}

class AccountRoutes(private val accounts: AccountService, private val settings: HttpSettings) {
    fun register(config: JavalinConfig) {
        config.routes.delete("/api/me") { ctx ->
            accounts.deleteAccount(ctx.requirePrincipal().userId)
            ctx.setSessionCookie("", Duration.ZERO, settings.cookieSecure)
            ctx.status(HttpStatus.NO_CONTENT)
        }
    }
}
