package guardadoc.adapters.ratelimit

import guardadoc.auth.RateLimiter
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Janela fixa em memória. Suficiente para uma instância única da POC; para
 * múltiplas instâncias, trocar por um adaptador compartilhado (Redis/PostgreSQL).
 * As chaves ficam só em memória e expiram com a janela.
 */
class InMemoryRateLimiter(
    private val maxRequests: Int,
    private val window: Duration,
    private val clock: Clock,
) : RateLimiter {
    private data class Bucket(val windowStart: Long, val count: Int)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    override fun tryAcquire(key: String): Boolean {
        val now = clock.millis()
        val windowMillis = window.toMillis()
        val bucket = buckets.compute(key) { _, current ->
            if (current == null || now - current.windowStart >= windowMillis) Bucket(now, 1)
            else current.copy(count = current.count + 1)
        }!!
        if (buckets.size > 10_000) buckets.entries.removeIf { now - it.value.windowStart >= windowMillis }
        return bucket.count <= maxRequests
    }
}
