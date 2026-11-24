package guardadoc.auth

import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.FinishAssertionOptions
import com.yubico.webauthn.FinishRegistrationOptions
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.StartAssertionOptions
import com.yubico.webauthn.StartRegistrationOptions
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria
import com.yubico.webauthn.data.ByteArray
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import com.yubico.webauthn.data.ResidentKeyRequirement
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.UserIdentity
import com.yubico.webauthn.data.UserVerificationRequirement
import guardadoc.shared.AppException
import guardadoc.shared.AuditEvent
import guardadoc.shared.AuditEventType
import guardadoc.shared.AuditRepository
import guardadoc.shared.Crypto
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class PasskeyStart(val challengeId: UUID, val browserOptions: String)

class PasskeyService(
    private val users: UserRepository,
    private val passkeys: PasskeyRepository,
    private val sessions: SessionRepository,
    private val audit: AuditRepository,
    private val settings: AuthSettings,
    private val publicBaseUrl: String,
    private val allowedOrigins: Set<String>,
    private val clock: Clock,
) {
    private val credentialRepository = object : CredentialRepository {
        override fun getCredentialIdsForUsername(username: String): Set<PublicKeyCredentialDescriptor> =
            users.findOrCreate(username).let { user ->
                passkeys.findCredentialsByUser(user.id).mapTo(mutableSetOf()) {
                    PublicKeyCredentialDescriptor.builder().id(ByteArray(it.credentialId)).build()
                }
            }

        override fun getUserHandleForUsername(username: String): java.util.Optional<ByteArray> =
            users.findOrCreate(username).let { user ->
                passkeys.findCredentialsByUser(user.id).firstOrNull()?.let {
                    java.util.Optional.of(ByteArray(it.userHandle))
                } ?: java.util.Optional.empty()
            }

        override fun getUsernameForUserHandle(userHandle: ByteArray): java.util.Optional<String> =
            passkeys.findUserIdByHandle(userHandle.bytes)?.let { userId ->
                users.findById(userId)?.let { java.util.Optional.of(it.email) }
            } ?: java.util.Optional.empty()

        override fun lookup(credentialId: ByteArray, userHandle: ByteArray): java.util.Optional<RegisteredCredential> =
            passkeys.findCredential(credentialId.bytes, userHandle.bytes)?.let { credential ->
                java.util.Optional.of(registeredCredential(credential))
            } ?: java.util.Optional.empty()

        override fun lookupAll(userHandle: ByteArray): Set<RegisteredCredential> =
            passkeys.findCredentialsByUserHandle(userHandle.bytes).mapTo(mutableSetOf()) { registeredCredential(it) }
    }

    private val rpId = URI(publicBaseUrl).host ?: "localhost"
    private val rp = RelyingParty.builder()
        .identity(RelyingPartyIdentity.builder().id(rpId).name("GuardaDoc").build())
        .credentialRepository(credentialRepository)
        .origins(allowedOrigins)
        .allowUntrustedAttestation(true)
        .validateSignatureCounter(true)
        .clock(clock)
        .build()

    fun startRegistration(principal: SessionPrincipal): PasskeyStart {
        val user = users.findById(principal.userId) ?: throw AppException.Unauthorized()
        val handle = userHandle(user.id)
        val options = rp.startRegistration(
            StartRegistrationOptions.builder()
                .user(
                    UserIdentity.builder()
                        .name(user.email)
                        .displayName(user.email)
                        .id(handle)
                        .build(),
                )
                .authenticatorSelection(
                    AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .build(),
                )
                .timeout(Duration.ofMinutes(5).toMillis())
                .build(),
        )
        val id = UUID.randomUUID()
        passkeys.createChallenge(id, user.id, "registro", options.toJson(), clock.instant().plus(Duration.ofMinutes(5)))
        return PasskeyStart(id, options.toCredentialsCreateJson())
    }

    fun finishRegistration(principal: SessionPrincipal, challengeId: String?, credentialJson: String?): SessionPrincipal {
        val challenge = consumeChallenge(challengeId, "registro") ?: throw AppException.Unauthorized("Passkey inválida ou expirada")
        if (challenge.userId != principal.userId) throw AppException.Unauthorized("Passkey inválida")
        val response = try {
            PublicKeyCredential.parseRegistrationResponseJson(credentialJson ?: "")
        } catch (e: Exception) {
            throw AppException.Unauthorized("Passkey inválida")
        }
        val request = try {
            PublicKeyCredentialCreationOptions.fromJson(challenge.requestJson)
        } catch (e: Exception) {
            throw AppException.Unauthorized("Passkey inválida")
        }
        val result = try {
            rp.finishRegistration(
                FinishRegistrationOptions.builder()
                    .request(request)
                    .response(response)
                    .build(),
            )
        } catch (e: Exception) {
            throw AppException.Unauthorized("Passkey inválida")
        }
        passkeys.saveCredential(
            principal.userId,
            result.keyId.id.bytes,
            userHandle(principal.userId).bytes,
            result.publicKeyCose.bytes,
            result.signatureCount,
        )
        audit.record(AuditEvent(AuditEventType.LOGIN_SUCESSO, userId = principal.userId))
        return principal
    }

    fun startLogin(): PasskeyStart {
        val request = rp.startAssertion(
            StartAssertionOptions.builder()
                .userVerification(UserVerificationRequirement.REQUIRED)
                .timeout(Duration.ofMinutes(5).toMillis())
                .build(),
        )
        val id = UUID.randomUUID()
        passkeys.createChallenge(id, null, "login", request.toJson(), clock.instant().plus(Duration.ofMinutes(5)))
        return PasskeyStart(id, request.publicKeyCredentialRequestOptions.toCredentialsGetJson())
    }

    fun finishLogin(challengeId: String?, credentialJson: String?): IssuedSession {
        val challenge = consumeChallenge(challengeId, "login") ?: throw AppException.Unauthorized("Passkey inválida ou expirada")
        val response = try {
            PublicKeyCredential.parseAssertionResponseJson(credentialJson ?: "")
        } catch (e: Exception) {
            throw AppException.Unauthorized("Passkey inválida")
        }
        val request = try {
            AssertionRequest.fromJson(challenge.requestJson)
        } catch (e: Exception) {
            throw AppException.Unauthorized("Passkey inválida")
        }
        val result = try {
            rp.finishAssertion(
                FinishAssertionOptions.builder()
                    .request(request)
                    .response(response)
                    .build(),
            )
        } catch (e: Exception) {
            throw AppException.Unauthorized("Passkey inválida")
        }
        val userHandle = result.userHandle
        val userId = passkeys.findUserIdByHandle(userHandle.bytes)
            ?: throw AppException.Unauthorized("Passkey inválida")
        val user = users.findById(userId) ?: throw AppException.Unauthorized("Passkey inválida")
        passkeys.updateSignatureCount(result.credentialId.bytes, result.signatureCount)
        val token = Crypto.randomToken()
        val now = clock.instant()
        val expiresAt = now.plus(settings.sessionTtl)
        sessions.create(user.id, Crypto.sha256(token), expiresAt)
        audit.record(AuditEvent(AuditEventType.LOGIN_SUCESSO, userId = user.id))
        return IssuedSession(token, expiresAt, user)
    }

    private fun consumeChallenge(challengeId: String?, purpose: String): PasskeyChallenge? {
        val id = challengeId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        return passkeys.consumeChallenge(id, purpose, clock.instant())
    }

    private fun registeredCredential(credential: PasskeyCredential) =
        RegisteredCredential.builder()
            .credentialId(ByteArray(credential.credentialId))
            .userHandle(ByteArray(credential.userHandle))
            .publicKeyCose(ByteArray(credential.publicKeyCose))
            .signatureCount(credential.signatureCount)
            .build()

    private fun userHandle(userId: UUID): ByteArray =
        ByteArray(Crypto.sha256(userId.toString()))
}
