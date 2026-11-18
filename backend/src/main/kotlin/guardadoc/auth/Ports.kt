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

interface EmailSender {
    fun sendOtp(to: String, code: String)
}

interface RateLimiter {
    /** Retorna false quando a chave excedeu o limite da janela. */
    fun tryAcquire(key: String): Boolean
}
