package guardadoc.application.auth

import guardadoc.application.Query
import guardadoc.application.UseCase
import guardadoc.auth.IssuedSession
import guardadoc.auth.PasskeyService
import guardadoc.auth.PasskeyStart
import guardadoc.auth.SessionPrincipal

/*
 * A verificação WebAuthn permanece encapsulada em PasskeyService (domínio):
 * a biblioteca e o protocolo são detalhes daquele serviço. Estes casos de uso
 * dão à camada de entrada um ponto único por operação, sem repetir regras.
 */

/** Inicia um login com passkey para qualquer visitante. */
class StartPasskeyLogin(private val passkeys: PasskeyService) : Query<PasskeyStart> {
    override fun invoke() = passkeys.startLogin()
}

data class FinishPasskeyLoginCommand(val challengeId: String?, val credentialJson: String?)

/** Conclui o login com passkey e emite a sessão. */
class FinishPasskeyLogin(private val passkeys: PasskeyService) :
    UseCase<FinishPasskeyLoginCommand, IssuedSession> {
    override fun invoke(command: FinishPasskeyLoginCommand) =
        passkeys.finishLogin(command.challengeId, command.credentialJson)
}

data class StartPasskeyRegistrationCommand(val principal: SessionPrincipal)

/** Inicia o cadastro de uma passkey para a sessão autenticada. */
class StartPasskeyRegistration(private val passkeys: PasskeyService) :
    UseCase<StartPasskeyRegistrationCommand, PasskeyStart> {
    override fun invoke(command: StartPasskeyRegistrationCommand) =
        passkeys.startRegistration(command.principal)
}

data class FinishPasskeyRegistrationCommand(
    val principal: SessionPrincipal,
    val challengeId: String?,
    val credentialJson: String?,
)

/** Conclui o cadastro após a verificação da assinatura. */
class FinishPasskeyRegistration(private val passkeys: PasskeyService) :
    UseCase<FinishPasskeyRegistrationCommand, SessionPrincipal> {
    override fun invoke(command: FinishPasskeyRegistrationCommand) =
        passkeys.finishRegistration(command.principal, command.challengeId, command.credentialJson)
}
