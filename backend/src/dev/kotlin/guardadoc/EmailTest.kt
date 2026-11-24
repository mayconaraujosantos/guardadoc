package guardadoc

import guardadoc.adapters.email.SmtpEmailSender
import guardadoc.auth.EmailDeliveryException
import kotlin.system.exitProcess

/**
 * Envia um e-mail de teste com a configuração SMTP_* do ambiente.
 * Uso: make email-test TO=voce@exemplo.com   (ou ./gradlew emailTest -Pto=...)
 */
fun main(args: Array<String>) {
    val to = args.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: fail("Informe o destinatário: make email-test TO=voce@exemplo.com")
    val env = HashMap(System.getenv()).apply { putIfAbsent("APP_ENV", "dev") }
    val smtp = AppConfig.fromEnv(env).smtp
        ?: fail("SMTP_HOST não definido. Copie .env.example para .env e preencha SMTP_*")

    println("Enviando teste via ${smtp.host}:${smtp.port} (${if (smtp.ssl) "SSL" else if (smtp.startTls) "STARTTLS" else "sem TLS"}), remetente ${smtp.from}…")
    try {
        SmtpEmailSender(smtp).sendTest(to)
        println("OK: e-mail de teste enviado. Confira a caixa de entrada (e o spam).")
    } catch (e: EmailDeliveryException) {
        fail("FALHOU: ${e.message}")
    }
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}
