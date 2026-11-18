package guardadoc.adapters.email

import guardadoc.auth.EmailSender
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

private fun otpBody(code: String) = """
    Seu código de acesso ao GuardaDoc é: $code

    Ele expira em 5 minutos e só pode ser usado uma vez.
    Se você não solicitou este código, ignore esta mensagem.
""".trimIndent()

data class SmtpSettings(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val from: String,
    val startTls: Boolean = true,
)

/** Envio real por SMTP (qualquer provedor). */
class SmtpEmailSender(private val settings: SmtpSettings) : EmailSender {
    private val session: Session = Session.getInstance(
        Properties().apply {
            put("mail.smtp.host", settings.host)
            put("mail.smtp.port", settings.port.toString())
            put("mail.smtp.auth", (settings.username != null).toString())
            put("mail.smtp.starttls.enable", settings.startTls.toString())
            put("mail.smtp.starttls.required", settings.startTls.toString())
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
        },
    )

    override fun sendOtp(to: String, code: String) {
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(settings.from))
            setRecipient(Message.RecipientType.TO, InternetAddress(to))
            subject = "Seu código de acesso ao GuardaDoc"
            setText(otpBody(code), "UTF-8")
        }
        if (settings.username != null) {
            Transport.send(message, settings.username, settings.password)
        } else {
            Transport.send(message)
        }
    }
}

/**
 * SOMENTE DESENVOLVIMENTO: guarda o último código por e-mail em memória para
 * ser consultado em /dev/outbox. Nada é escrito em log ou disco.
 */
class DevOutboxEmailSender : EmailSender {
    private val lastCodes = ConcurrentHashMap<String, String>()

    override fun sendOtp(to: String, code: String) {
        lastCodes[to] = code
    }

    fun lastCodeFor(email: String): String? = lastCodes[email]

    fun snapshot(): Map<String, String> = HashMap(lastCodes)
}
