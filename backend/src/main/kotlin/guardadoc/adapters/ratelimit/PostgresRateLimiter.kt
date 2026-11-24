package guardadoc.adapters.ratelimit

import guardadoc.adapters.persistence.Database
import guardadoc.auth.RateLimiter
import guardadoc.shared.Crypto
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.Clock
import java.time.Duration

/**
 * Janela fixa persistida no PostgreSQL, válida para várias instâncias.
 * O incremento é um único UPSERT atômico.
 */
class PostgresRateLimiter(
    private val db: Database,
    private val name: String,
    private val maxRequests: Int,
    private val window: Duration,
    private val keySecret: ByteArray,
    private val clock: Clock,
) : RateLimiter {

    override fun tryAcquire(key: String): Boolean {
        val now = clock.instant()
        val windowStartCutoff = OffsetDateTime.ofInstant(now.minus(window), ZoneOffset.UTC)
        val hashedKey = Crypto.hmacSha256(keySecret, "$name:$key")
        val count = db.withConnection { c ->
            c.prepareStatement(
                """INSERT INTO limites_requisicao (chave, inicio_janela, contagem) VALUES (?, ?, 1)
                   ON CONFLICT (chave) DO UPDATE SET
                     contagem = CASE WHEN limites_requisicao.inicio_janela <= ? THEN 1
                                     ELSE limites_requisicao.contagem + 1 END,
                     inicio_janela = CASE WHEN limites_requisicao.inicio_janela <= ? THEN EXCLUDED.inicio_janela
                                          ELSE limites_requisicao.inicio_janela END
                   RETURNING contagem""",
            ).use { st ->
                st.setBytes(1, hashedKey)
                st.setObject(2, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                st.setObject(3, windowStartCutoff)
                st.setObject(4, windowStartCutoff)
                st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        return count <= maxRequests
    }
}
