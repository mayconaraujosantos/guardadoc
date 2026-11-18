package guardadoc

import guardadoc.adapters.persistence.Database
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import java.nio.file.Path

/**
 * Modo desenvolvimento: PostgreSQL embarcado (dados em ./data/pg), cookie sem
 * Secure (HTTP local) e outbox de e-mail em memória em /dev/outbox.
 * Se DATABASE_URL estiver definido, usa esse banco em vez do embarcado.
 */
fun main() {
    val env = HashMap(System.getenv())
    env.putIfAbsent("APP_ENV", "dev")
    env.putIfAbsent("COOKIE_SECURE", "false")

    val database = if (env["DATABASE_URL"].isNullOrBlank()) {
        val dataDir = Path.of("./data/pg").toAbsolutePath()
        Files.createDirectories(dataDir)
        val postgres = EmbeddedPostgres.builder()
            .setDataDirectory(dataDir)
            .setCleanDataDirectory(false)
            .setPort(env["DEV_PG_PORT"]?.toInt() ?: 54329)
            .start()
        Runtime.getRuntime().addShutdownHook(Thread { postgres.close() })
        println("PostgreSQL embarcado em jdbc:postgresql://localhost:${postgres.port}/postgres (dados em $dataDir)")
        Database(postgres.postgresDatabase)
    } else {
        val config = AppConfig.fromEnv(env)
        Database.connect(config.databaseUrl, config.databaseUser, config.databasePassword)
    }

    val config = AppConfig.fromEnv(env)
    val app = GuardaDocApp(config, database)
    Runtime.getRuntime().addShutdownHook(Thread { app.stop() })
    app.start()
    println("GuardaDoc (dev) em http://localhost:${config.port}  •  códigos OTP em http://localhost:${config.port}/dev/outbox")
}
