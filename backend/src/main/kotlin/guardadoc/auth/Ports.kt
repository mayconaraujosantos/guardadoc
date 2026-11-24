package guardadoc.auth

import java.time.Instant
import java.util.UUID

data class User(val id: UUID, val email: String)

data class NewOtpChallenge(
    val email: String,
    val digest: ByteArray,
    val expiresAt: Instant,
    val maxAttempts: Int,
)

data class OtpChallenge(
    val id: UUID,
    val digest: ByteArray,
    val attempts: Int,
    val maxAttempts: Int,
)

data class SessionPrincipal(val sessionId: UUID, val userId: UUID, val email: String, val expiresAt: Instant)

// ---- Portas de saída do módulo de autenticação ----

interface UserRepository {
    fun findOrCreate(email: String): User
    fun findById(userId: UUID): User?

    /** Remove o usuário e, em cascata, sessões, documentos, links e desafios OTP. */
    fun delete(userId: UUID)
}

interface OtpRepository {
    /** Invalida desafios ativos do e-mail e grava o novo, numa única transação. */
    fun replaceActive(challenge: NewOtpChallenge, now: Instant)

    /**
     * Incrementa atomicamente as tentativas do desafio ativo mais recente e o
     * devolve somente se ainda estava elegível (não consumido, não expirado e
     * abaixo do limite de tentativas).
     */
    fun registerAttempt(email: String, now: Instant): OtpChallenge?

    /** Consome o desafio; retorna false se outra requisição já o consumiu. */
    fun consume(id: UUID, now: Instant): Boolean
}

interface SessionRepository {
    fun create(userId: UUID, tokenHash: ByteArray, expiresAt: Instant): UUID
    fun findActive(tokenHash: ByteArray, now: Instant): SessionPrincipal?
    fun revoke(tokenHash: ByteArray, now: Instant)
}

data class PasskeyChallenge(
    val id: UUID,
    val userId: UUID?,
    val purpose: String,
    val requestJson: String,
)

data class PasskeyCredential(
    val id: UUID,
    val userId: UUID,
    val credentialId: ByteArray,
    val userHandle: ByteArray,
    val publicKeyCose: ByteArray,
    val signatureCount: Long,
)

interface PasskeyRepository {
    fun createChallenge(id: UUID, userId: UUID?, purpose: String, requestJson: String, expiresAt: Instant)
    fun consumeChallenge(id: UUID, purpose: String, now: Instant): PasskeyChallenge?
    fun saveCredential(userId: UUID, credentialId: ByteArray, userHandle: ByteArray, publicKeyCose: ByteArray, signatureCount: Long)
    fun updateSignatureCount(credentialId: ByteArray, signatureCount: Long)
    fun findCredentialsByUser(userId: UUID): List<PasskeyCredential>
    fun findCredentialsByUserHandle(userHandle: ByteArray): List<PasskeyCredential>
    fun findCredential(credentialId: ByteArray, userHandle: ByteArray): PasskeyCredential?
    fun findCredentialById(credentialId: ByteArray): PasskeyCredential?
    fun findUserIdByHandle(userHandle: ByteArray): UUID?
}

interface EmailSender {
    fun sendOtp(to: String, code: String)
}

/**
 * Falha de envio com mensagem segura para log: os adaptadores nunca incluem
 * senha, código OTP nem o endereço do destinatário.
 */
class EmailDeliveryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

interface RateLimiter {
    /** Retorna false quando a chave excedeu o limite da janela. */
    fun tryAcquire(key: String): Boolean
}
