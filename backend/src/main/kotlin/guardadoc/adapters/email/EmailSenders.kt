package guardadoc.adapters.email

import guardadoc.auth.EmailDeliveryException
import guardadoc.auth.EmailSender
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import org.eclipse.angus.mail.util.MailConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
    /** Remetente; aceita "Nome <email@dominio>". No Gmail, deve ser a conta ou um alias verificado. */
    val from: String,
    /** STARTTLS (porta 587). */
    val startTls: Boolean = true,
    /** TLS implícito (porta 465). */
    val ssl: Boolean = false,
    val timeoutMillis: Int = 10_000,
) {
    override fun toString() =
        "SmtpSettings(host=$host, port=$port, username=${username != null}, from=$from, startTls=$startTls, ssl=$ssl)"
}

/** Envio real por SMTP (Gmail, SES, Mailgun, servidor próprio...). */
class SmtpEmailSender(private val settings: SmtpSettings) : EmailSender {
    private val fromAddress = InternetAddress(settings.from, true).apply { if (personal == null) setPersonal("GuardaDoc", "UTF-8") }

    // Senhas de app do Gmail são exibidas em grupos ("abcd efgh ijkl mnop"); os espaços não fazem parte delas.
    private val password = settings.password?.replace(" ", "")

    private val session: Session = Session.getInstance(
        Properties().apply {
            val protocol = if (settings.ssl) "smtps" else "smtp"
            put("mail.transport.protocol", protocol)
            put("mail.$protocol.host", settings.host)
            put("mail.$protocol.port", settings.port.toString())
            put("mail.$protocol.auth", (settings.username != null).toString())
            put("mail.$protocol.connectiontimeout", settings.timeoutMillis.toString())
            put("mail.$protocol.timeout", settings.timeoutMillis.toString())
            put("mail.$protocol.writetimeout", settings.timeoutMillis.toString())
            if (settings.ssl || settings.startTls) {
                put("mail.$protocol.ssl.protocols", "TLSv1.3 TLSv1.2")
                put("mail.$protocol.ssl.checkserveridentity", "true")
            }
            if (settings.ssl) put("mail.smtps.ssl.enable", "true")
            if (settings.startTls && !settings.ssl) {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }
        },
    )

    override fun sendOtp(to: String, code: String) {
        val message = MimeMessage(session).apply {
            setFrom(this@SmtpEmailSender.fromAddress)
            setRecipient(Message.RecipientType.TO, InternetAddress(to, true))
            setSubject("Seu código de acesso ao GuardaDoc", "UTF-8")
            setText(otpBody(code), "UTF-8")
        }
        send(message)
    }

    /** Envia uma mensagem de teste (usado por `make email-test`). */
    fun sendTest(to: String) {
        val message = MimeMessage(session).apply {
            setFrom(this@SmtpEmailSender.fromAddress)
            setRecipient(Message.RecipientType.TO, InternetAddress(to, true))
            setSubject("GuardaDoc: teste de envio", "UTF-8")
            setText("Se você recebeu esta mensagem, o envio de e-mails do GuardaDoc está configurado.", "UTF-8")
        }
        send(message)
    }

    private fun send(message: MimeMessage) {
        try {
            message.saveChanges()
            session.transport.use { transport ->
                transport.connect(settings.host, settings.port, settings.username, password)
                transport.sendMessage(message, message.allRecipients)
            }
        } catch (e: MessagingException) {
            throw EmailDeliveryException(diagnose(e), e)
        }
    }

    private fun diagnose(e: MessagingException): String {
        val where = "${settings.host}:${settings.port}"
        val root = generateSequence<Throwable>(e) { it.cause }.last()
        return when {
            e is AuthenticationFailedException ->
                "Autenticação SMTP recusada em $where. No Gmail, use uma senha de app (16 caracteres, " +
                    "exige verificação em duas etapas) e o e-mail completo em SMTP_USERNAME"
            e is MailConnectException || root is UnknownHostException || root is SocketTimeoutException ->
                "Não foi possível conectar a $where (${root.javaClass.simpleName}). Verifique host, porta e firewall"
            e is SMTPSendFailedException && e.returnCode in 550..553 ->
                "Servidor recusou o envio (${e.returnCode}). No Gmail, SMTP_FROM deve ser a própria conta ou um alias verificado"
            e is SMTPSendFailedException ->
                "Servidor recusou o envio (${e.returnCode})"
            (e.message ?: "").contains("STARTTLS", ignoreCase = true) ->
                "Falha no STARTTLS em $where. Gmail: porta 587 com SMTP_STARTTLS=true, ou 465 com SMTP_SSL=true"
            root is javax.net.ssl.SSLException ->
                "Falha de TLS em $where (${root.javaClass.simpleName}). A porta 465 exige SMTP_SSL=true; a 587, STARTTLS"
            else -> "Falha no envio SMTP para $where (${e.javaClass.simpleName})"
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
