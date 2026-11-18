package guardadoc.adapters.persistence

import guardadoc.maintenance.JobLock

/**
 * Lock de sessão do PostgreSQL (pg_try_advisory_lock): garante que uma rotina
 * agendada rode em uma única instância por vez. Se a conexão cair, o PostgreSQL
 * libera o lock automaticamente.
 */
class PostgresJobLock(private val db: Database) : JobLock {
    override fun <T> tryRun(name: String, block: () -> T): T? = db.withConnection { c ->
        val acquired = c.queryOne("SELECT pg_try_advisory_lock(hashtext(?))", "guardadoc-job:$name") {
            it.getBoolean(1)
        } ?: false
        if (!acquired) return@withConnection null
        try {
            block()
        } finally {
            c.queryOne("SELECT pg_advisory_unlock(hashtext(?))", "guardadoc-job:$name") { }
        }
    }
}
