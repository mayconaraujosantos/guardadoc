package guardadoc

import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthFlowTest {
    private val t = TestApp()

    @AfterTest fun tearDown() = t.close()

    @Test
    fun `login por OTP cria sessao em cookie seguro e HttpOnly`() {
        val c = t.client()
        val generic = c.requestOtp("Ana@Example.com ")
        assertEquals(202, generic.statusCode())
        val code = t.outbox.lastCodeFor("ana@example.com")
        assertNotNull(code)
        assertTrue(code.matches(Regex("\\d{6}")))

        val verified = c.verify("ana@example.com", code)
        assertEquals(200, verified.statusCode())
        val setCookie = verified.headers().firstValue("Set-Cookie").get()
        assertTrue("HttpOnly" in setCookie && "SameSite=Lax" in setCookie)
        assertEquals("no-store", verified.headers().firstValue("Cache-Control").get())

        val me = c.get("/api/me")
        assertEquals(200, me.statusCode())
        assertEquals("ana@example.com", c.json(me)["email"].asText())
    }

    @Test
    fun `resposta de solicitacao e generica`() {
        val c = t.client()
        val first = c.requestOtp("novo@example.com")
        c.login("existente@example.com")
        val second = t.client().requestOtp("existente@example.com")
        assertEquals(first.statusCode(), second.statusCode())
        assertEquals(String(first.body()), String(second.body()))
    }

    @Test
    fun `OTP so pode ser usado uma vez`() {
        val c = t.client()
        c.requestOtp("bia@example.com")
        val code = t.outbox.lastCodeFor("bia@example.com")!!
        assertEquals(200, c.verify("bia@example.com", code).statusCode())
        assertEquals(401, t.client().verify("bia@example.com", code).statusCode())
    }

    @Test
    fun `OTP expira em cinco minutos`() {
        val c = t.client()
        c.requestOtp("caio@example.com")
        val code = t.outbox.lastCodeFor("caio@example.com")!!
        t.clock.advance(Duration.ofMinutes(5).plusSeconds(1))
        assertEquals(401, c.verify("caio@example.com", code).statusCode())
    }

    @Test
    fun `OTP e rejeitado apos cinco tentativas invalidas`() {
        val c = t.client()
        c.requestOtp("davi@example.com")
        val code = t.outbox.lastCodeFor("davi@example.com")!!
        val wrong = if (code == "000000") "111111" else "000000"
        repeat(5) { assertEquals(401, c.verify("davi@example.com", wrong).statusCode()) }
        assertEquals(401, c.verify("davi@example.com", code).statusCode())
    }

    @Test
    fun `novo OTP invalida o anterior`() {
        val c = t.client()
        c.requestOtp("eva@example.com")
        val first = t.outbox.lastCodeFor("eva@example.com")!!
        c.requestOtp("eva@example.com")
        val second = t.outbox.lastCodeFor("eva@example.com")!!
        if (first != second) assertEquals(401, c.verify("eva@example.com", first).statusCode())
        assertEquals(200, c.verify("eva@example.com", second).statusCode())
    }

    @Test
    fun `verificacoes concorrentes aceitam o OTP apenas uma vez`() {
        t.client().requestOtp("fabi@example.com")
        val code = t.outbox.lastCodeFor("fabi@example.com")!!
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val results = (1..threads).map {
            pool.submit(Callable { start.await(); t.client().verify("fabi@example.com", code).statusCode() })
        }
        start.countDown()
        val statuses = results.map { it.get() }
        pool.shutdown()
        assertEquals(1, statuses.count { it == 200 }, statuses.toString())
    }

    @Test
    fun `solicitacoes de OTP sao limitadas por e-mail`() {
        val c = t.client()
        repeat(5) { assertEquals(202, c.requestOtp("gui@example.com").statusCode()) }
        assertEquals(429, c.requestOtp("gui@example.com").statusCode())
        t.clock.advance(Duration.ofMinutes(16))
        assertEquals(202, c.requestOtp("gui@example.com").statusCode())
    }

    @Test
    fun `logout revoga a sessao`() {
        val c = t.login("hugo@example.com")
        val oldCookie = c.cookie
        assertEquals(204, c.request("POST", "/api/auth/logout").statusCode())
        c.cookie = oldCookie
        assertEquals(401, c.get("/api/me").statusCode())
    }

    @Test
    fun `codigos e tokens nunca aparecem nos logs`() {
        LogCapture().use { logs ->
            val c = t.login("iris@example.com")
            val code = t.outbox.lastCodeFor("iris@example.com")!!
            val doc = c.json(c.upload("a.pdf", "application/pdf", Samples.pdf))["id"].asText()
            val share = c.json(c.postJson("/api/documents/$doc/shares", mapOf("expiresInMinutes" to 60)))
            val token = share["url"].asText().substringAfterLast('/')
            t.client().get("/s/$token/download")
            t.client().get("/s/${token}x") // token inválido
            val text = logs.text()
            assertFalse(code in text)
            assertFalse(token in text)
            assertFalse(c.cookie!!.substringAfter('=') in text)
            assertNotEquals("", c.csrf)
        }
    }
}
