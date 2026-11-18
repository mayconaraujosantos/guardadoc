package guardadoc.adapters.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** Acesso JDBC mínimo, com transações explícitas. */
class Database(val dataSource: DataSource) {

    fun <T> withConnection(block: (Connection) -> T): T = dataSource.connection.use(block)

    fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            block(connection).also { connection.commit() }
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    companion object {
        fun connect(url: String, user: String?, password: String?, maxPool: Int = 10): Database {
            val config = HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = maxPool
                poolName = "guardadoc"
            }
            return Database(HikariDataSource(config))
        }

        fun migrate(dataSource: DataSource) {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
        }
    }
}

internal fun Connection.update(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { it.bind(params); it.executeUpdate() }

internal fun <T> Connection.query(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(params)
        statement.executeQuery().use { rs ->
            buildList { while (rs.next()) add(mapper(rs)) }
        }
    }

internal fun <T> Connection.queryOne(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): T? =
    query(sql, *params, mapper = mapper).firstOrNull()

private fun PreparedStatement.bind(params: Array<out Any?>) {
    params.forEachIndexed { index, value ->
        val position = index + 1
        when (value) {
            null -> setObject(position, null)
            is Instant -> setObject(position, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
            is ByteArray -> setBytes(position, value)
            is UUID -> setObject(position, value)
            else -> setObject(position, value)
        }
    }
}

internal fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
internal fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)
internal fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()
internal fun ResultSet.instantOrNull(column: String): Instant? =
    getObject(column, OffsetDateTime::class.java)?.toInstant()
internal fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }
