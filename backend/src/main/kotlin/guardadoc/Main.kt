package guardadoc

import guardadoc.adapters.persistence.Database

fun main() {
    val config = AppConfig.fromEnv()
    val database = Database.connect(config.databaseUrl, config.databaseUser, config.databasePassword)
    val app = GuardaDocApp(config, database)
    Runtime.getRuntime().addShutdownHook(Thread { app.stop() })
    app.start()
}
