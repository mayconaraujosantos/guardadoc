package guardadoc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Variáveis de ambiente no formato fornecido pelo Railway. */
class RailwayConfigTest {
    private fun cfg(vararg env: Pair<String, String>) = AppConfig.fromEnv(
        mapOf(
            "APP_ENV" to "dev",
            "APP_SECRET" to "segredo-de-teste-com-pelo-menos-32-caracteres",
        ) + env,
    )

    @Test
    fun `database_url do railway vira jdbc com usuario e senha`() {
        val config = cfg("DATABASE_URL" to "postgresql://guardadoc:s3cr%3At@db.railway.internal:5432/railway")

        assertEquals("jdbc:postgresql://db.railway.internal:5432/railway", config.databaseUrl)
        assertEquals("guardadoc", config.databaseUser)
        assertEquals("s3cr:t", config.databasePassword)
    }

    @Test
    fun `mantem opcoes de ssl e normaliza o scheme postgres`() {
        val config = cfg("DATABASE_URL" to "postgres://user:pass@host:6543/guardadoc?sslmode=require")

        assertEquals("jdbc:postgresql://host:6543/guardadoc?sslmode=require", config.databaseUrl)
        assertEquals("user", config.databaseUser)
        assertEquals("pass", config.databasePassword)
    }

    @Test
    fun `url jdbc e variaveis explicitas continuam valendo`() {
        val config = cfg(
            "DATABASE_URL" to "jdbc:postgresql://localhost:5432/local",
            "DATABASE_USER" to "outro",
            "DATABASE_PASSWORD" to "senha",
        )

        assertEquals("jdbc:postgresql://localhost:5432/local", config.databaseUrl)
        assertEquals("outro", config.databaseUser)
        assertEquals("senha", config.databasePassword)
    }

    @Test
    fun `configuracao de producao usa porta e proxy do railway`() {
        val config = cfg(
            "APP_ENV" to "production",
            "PORT" to "8080",
            "PUBLIC_BASE_URL" to "https://guardadoc.up.railway.app",
            "TRUST_PROXY" to "true",
            "REQUIRE_HTTPS" to "true",
            "COOKIE_SECURE" to "true",
            "DATABASE_URL" to "postgresql://u:p@db.railway.internal:5432/guardadoc",
            "STORAGE_BACKEND" to "s3",
            "S3_BUCKET" to "guardadoc",
            "SMTP_HOST" to "smtp.exemplo.com",
            "SMTP_USERNAME" to "nao-responda@exemplo.com",
            "SMTP_PASSWORD" to "senha-de-app",
        )

        assertEquals(8080, config.port)
        assertTrue(config.requireHttps)
        assertTrue(config.trustProxy)
        assertTrue(config.cookieSecure)
        assertEquals(AppConfig.StorageBackend.S3, config.storage)
        assertTrue(config.allowedOrigins.contains("https://guardadoc.up.railway.app"))
    }
}
