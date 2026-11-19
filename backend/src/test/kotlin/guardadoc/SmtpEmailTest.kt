package guardadoc

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import guardadoc.adapters.email.SmtpEmailSender
import guardadoc.adapters.email.SmtpSettings
import guardadoc.auth.EmailDeliveryException
import jakarta.mail.internet.InternetAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Envio real por SMTP contra um servidor em memória (GreenMail). */
class SmtpEmailTest {
    private val port = ServerSocket(0).use { it.localPort }
    private val greenMail = GreenMail(ServerSetup(port, "127.0.0.1", ServerSetup.PROTOCOL_SMTP))

    @BeforeTest
    fun start() {
        greenMail.start()
        greenMail.setUser("conta@example.com", "conta@example.com", "abcdefghijklmnop")
    }

    @AfterTest
    fun stop() = greenMail.stop()

    private fun settings(password: String = "abcd efgh ijkl mnop", smtpPort: Int = port) = SmtpSettings(
        host = "127.0.0.1",
        port = smtpPort,
        username = "conta@example.com",
        password = password,
        from = "GuardaDoc <conta@example.com>",
        startTls = false, // GreenMail sem TLS; Gmail usa STARTTLS
        timeoutMillis = 2_000,
    )

    @Test
    fun `envia o codigo autenticado e remove espacos da senha de app`() {
        SmtpEmailSender(settings()).sendOtp("destino@example.com", "123456")

        val message = greenMail.receivedMessages.single()
        assertEquals("Seu código de acesso ao GuardaDoc", message.subject)
        assertEquals(InternetAddress("destino@example.com"), message.allRecipients.single())
        assertEquals("GuardaDoc", (message.from.single() as InternetAddress).personal)
        assertTrue((message.content as String).contains("123456"))
    }

    @Test
    fun `senha recusada gera diagnostico sem expor segredos`() {
        val e = assertFailsWith<EmailDeliveryException> {
            SmtpEmailSender(settings(password = "senha-errada")).sendOtp("destino@example.com", "654321")
        }
        assertTrue(e.message!!.startsWith("Autenticação SMTP recusada"), e.message)
        assertFalse(e.message!!.contains("senha-errada"))
        assertFalse(e.message!!.contains("654321"))
        assertFalse(e.message!!.contains("destino@example.com"))
    }

    @Test
    fun `servidor fora do ar gera diagnostico de conexao`() {
        val closed = ServerSocket(0).use { it.localPort }
        val e = assertFailsWith<EmailDeliveryException> {
            SmtpEmailSender(settings(smtpPort = closed)).sendOtp("destino@example.com", "111111")
        }
        assertTrue(e.message!!.startsWith("Não foi possível conectar"), e.message)
    }

    @Test
    fun `falha de envio nao bloqueia o login e o log nao contem o codigo`() {
        TestApp(emailOverride = SmtpEmailSender(settings(password = "senha-errada"))).use { t ->
            LogCapture().use { logs ->
                val c = t.client()
                assertEquals(202, c.requestOtp("x@example.com").statusCode()) // resposta genérica
                assertTrue(logs.text().contains("Autenticação SMTP recusada"))
                assertFalse(logs.text().contains("senha-errada"))
            }
        }
    }

    @Test
    fun `configuracao do Gmail`() {
        fun cfg(vararg env: Pair<String, String>) = AppConfig.fromEnv(
            mapOf("APP_ENV" to "dev", "SMTP_HOST" to "smtp.gmail.com") + env,
        ).smtp!!

        val starttls = cfg("SMTP_USERNAME" to "eu@gmail.com", "SMTP_PASSWORD" to "x")
        assertEquals(587, starttls.port)
        assertTrue(starttls.startTls)
        assertFalse(starttls.ssl)
        assertEquals("eu@gmail.com", starttls.from) // remetente padrão = conta

        val ssl = cfg("SMTP_PORT" to "465", "SMTP_USERNAME" to "eu@gmail.com", "SMTP_PASSWORD" to "x")
        assertTrue(ssl.ssl)
        assertFalse(ssl.startTls)

        assertFailsWith<IllegalArgumentException> { cfg("SMTP_USERNAME" to "eu@gmail.com") }
        assertFalse(starttls.toString().contains("password"))
    }
}
