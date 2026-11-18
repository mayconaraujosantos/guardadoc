package guardadoc.adapters.persistence

import guardadoc.auth.NewOtpChallenge
import guardadoc.auth.OtpChallenge
import guardadoc.auth.OtpRepository
import guardadoc.auth.SessionPrincipal
import guardadoc.auth.SessionRepository
import guardadoc.auth.User
import guardadoc.auth.UserRepository
import java.time.Instant
import java.util.UUID

class PostgresUserRepository(private val db: Database) : UserRepository {
    override fun findOrCreate(email: String): User = db.withConnection { c ->
        c.update("INSERT INTO usuarios (email) VALUES (?) ON CONFLICT ((lower(email))) DO NOTHING", email)
        c.queryOne("SELECT id, email FROM usuarios WHERE lower(email) = lower(?)", email) {
            User(it.uuid("id"), it.getString("email"))
        }!!
    }
}

class PostgresOtpRepository(private val db: Database) : OtpRepository {
    override fun replaceActive(challenge: NewOtpChallenge, now: Instant) {
        db.transaction { c ->
            // Serializa emissões concorrentes para o mesmo e-mail.
            c.queryOne("SELECT pg_advisory_xact_lock(hashtext(?))", challenge.email) { }
            c.update(
                "UPDATE desafios_otp SET consumido_em = ? WHERE lower(email) = ? AND consumido_em IS NULL",
                now, challenge.email,
            )
            c.update(
                """INSERT INTO desafios_otp (email, digest_hmac, expira_em, max_tentativas, criado_em)
                   VALUES (?, ?, ?, ?, ?)""",
                challenge.email, challenge.digest, challenge.expiresAt, challenge.maxAttempts.toShort(), now,
            )
        }
    }

    override fun registerAttempt(email: String, now: Instant): OtpChallenge? = db.withConnection { c ->
        c.queryOne(
            """UPDATE desafios_otp SET tentativas = tentativas + 1
               WHERE id = (
                   SELECT id FROM desafios_otp
                   WHERE lower(email) = ? AND consumido_em IS NULL AND expira_em > ?
                   ORDER BY criado_em DESC LIMIT 1
               )
               AND consumido_em IS NULL AND expira_em > ? AND tentativas < max_tentativas
               RETURNING id, digest_hmac, tentativas, max_tentativas""",
            email, now, now,
        ) {
            OtpChallenge(it.uuid("id"), it.getBytes("digest_hmac"), it.getInt("tentativas"), it.getInt("max_tentativas"))
        }
    }

    override fun consume(id: UUID, now: Instant): Boolean = db.withConnection { c ->
        c.update(
            "UPDATE desafios_otp SET consumido_em = ? WHERE id = ? AND consumido_em IS NULL AND expira_em > ?",
            now, id, now,
        ) == 1
    }
}

class PostgresSessionRepository(private val db: Database) : SessionRepository {
    override fun create(userId: UUID, tokenHash: ByteArray, expiresAt: Instant): UUID = db.withConnection { c ->
        c.queryOne(
            "INSERT INTO sessoes (usuario_id, token_hash, expira_em) VALUES (?, ?, ?) RETURNING id",
            userId, tokenHash, expiresAt,
        ) { it.uuid("id") }!!
    }

    override fun findActive(tokenHash: ByteArray, now: Instant): SessionPrincipal? = db.withConnection { c ->
        c.queryOne(
            """SELECT s.id, s.usuario_id, s.expira_em, u.email FROM sessoes s
               JOIN usuarios u ON u.id = s.usuario_id
               WHERE s.token_hash = ? AND s.revogada_em IS NULL AND s.expira_em > ?""",
            tokenHash, now,
        ) { SessionPrincipal(it.uuid("id"), it.uuid("usuario_id"), it.getString("email"), it.instant("expira_em")) }
    }

    override fun revoke(tokenHash: ByteArray, now: Instant) {
        db.withConnection { c ->
            c.update("UPDATE sessoes SET revogada_em = ? WHERE token_hash = ? AND revogada_em IS NULL", now, tokenHash)
        }
    }
}
