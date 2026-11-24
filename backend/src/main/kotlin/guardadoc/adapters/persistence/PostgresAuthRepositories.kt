package guardadoc.adapters.persistence

import guardadoc.auth.NewOtpChallenge
import guardadoc.auth.OtpChallenge
import guardadoc.auth.OtpRepository
import guardadoc.auth.PasskeyChallenge
import guardadoc.auth.PasskeyCredential
import guardadoc.auth.PasskeyRepository
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

    override fun findById(userId: UUID): User? = db.withConnection { c ->
        c.queryOne("SELECT id, email FROM usuarios WHERE id = ?", userId) {
            User(it.uuid("id"), it.getString("email"))
        }
    }

    override fun delete(userId: UUID) {
        db.transaction { c ->
            c.update(
                "DELETE FROM desafios_otp WHERE lower(email) = (SELECT lower(email) FROM usuarios WHERE id = ?)",
                userId,
            )
            c.update("DELETE FROM usuarios WHERE id = ?", userId)
        }
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

class PostgresPasskeyRepository(private val db: Database) : PasskeyRepository {
        override fun createChallenge(id: UUID, userId: UUID?, purpose: String, requestJson: String, expiresAt: Instant) {
            db.withConnection { c ->
                c.update(
                    """INSERT INTO desafios_passkey (id, usuario_id, finalidade, request_json, expira_em)
                       VALUES (?, ?, ?, ?, ?)""",
                    id, userId, purpose, requestJson, expiresAt,
                )
            }
        }

        override fun consumeChallenge(id: UUID, purpose: String, now: Instant): PasskeyChallenge? =
            db.withConnection { c ->
                c.queryOne(
                    """UPDATE desafios_passkey
                       SET consumido_em = ?
                       WHERE id = ? AND finalidade = ? AND consumido_em IS NULL AND expira_em > ?
                       RETURNING id, usuario_id, finalidade, request_json""",
                    now, id, purpose, now,
                ) {
                    PasskeyChallenge(
                        it.uuid("id"),
                        it.uuidOrNull("usuario_id"),
                        it.getString("finalidade"),
                        it.getString("request_json"),
                    )
                }
            }

        override fun saveCredential(
            userId: UUID,
            credentialId: ByteArray,
            userHandle: ByteArray,
            publicKeyCose: ByteArray,
            signatureCount: Long,
        ) {
            db.withConnection { c ->
                c.update(
                    """INSERT INTO credenciais_passkey
                       (usuario_id, credential_id, user_handle, public_key_cose, contador_assinatura)
                       VALUES (?, ?, ?, ?, ?)
                       ON CONFLICT (credential_id) DO UPDATE SET
                       public_key_cose = EXCLUDED.public_key_cose,
                       contador_assinatura = EXCLUDED.contador_assinatura,
                       atualizado_em = CURRENT_TIMESTAMP""",
                    userId, credentialId, userHandle, publicKeyCose, signatureCount,
                )
            }
        }

        override fun updateSignatureCount(credentialId: ByteArray, signatureCount: Long) {
            db.withConnection { c ->
                c.update(
                    "UPDATE credenciais_passkey SET contador_assinatura = ?, atualizado_em = CURRENT_TIMESTAMP WHERE credential_id = ?",
                    signatureCount, credentialId,
                )
            }
        }

        override fun findCredentialsByUser(userId: UUID): List<PasskeyCredential> =
            db.withConnection { c ->
                c.query(
                    """SELECT id, usuario_id, credential_id, user_handle, public_key_cose, contador_assinatura
                       FROM credenciais_passkey WHERE usuario_id = ?""",
                    userId,
                ) { credential(it) }
            }

        override fun findCredentialsByUserHandle(userHandle: ByteArray): List<PasskeyCredential> =
            db.withConnection { c ->
                c.query(
                    """SELECT id, usuario_id, credential_id, user_handle, public_key_cose, contador_assinatura
                       FROM credenciais_passkey WHERE user_handle = ?""",
                    userHandle,
                ) { credential(it) }
            }

        override fun findCredential(credentialId: ByteArray, userHandle: ByteArray): PasskeyCredential? =
            db.withConnection { c ->
                c.queryOne(
                    """SELECT id, usuario_id, credential_id, user_handle, public_key_cose, contador_assinatura
                       FROM credenciais_passkey WHERE credential_id = ? AND user_handle = ?""",
                    credentialId, userHandle,
                ) { credential(it) }
            }

        override fun findCredentialById(credentialId: ByteArray): PasskeyCredential? =
            db.withConnection { c ->
                c.queryOne(
                    """SELECT id, usuario_id, credential_id, user_handle, public_key_cose, contador_assinatura
                       FROM credenciais_passkey WHERE credential_id = ?""",
                    credentialId,
                ) { credential(it) }
            }

        override fun findUserIdByHandle(userHandle: ByteArray): UUID? =
            db.withConnection { c ->
                c.queryOne(
                    "SELECT usuario_id FROM credenciais_passkey WHERE user_handle = ? LIMIT 1",
                    userHandle,
                ) { it.uuid("usuario_id") }
            }

        private fun credential(rs: java.sql.ResultSet): PasskeyCredential = PasskeyCredential(
            rs.uuid("id"),
            rs.uuid("usuario_id"),
            rs.getBytes("credential_id"),
            rs.getBytes("user_handle"),
            rs.getBytes("public_key_cose"),
            rs.getLong("contador_assinatura"),
        )
}
