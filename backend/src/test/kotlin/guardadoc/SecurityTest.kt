package guardadoc

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SecurityTest {
    private val t = TestApp()

    @AfterTest fun tearDown() = t.close()

    @Test
    fun `operacoes autenticadas exigem token CSRF`() {
        val c = t.login("ana@example.com")
        assertEquals(403, c.upload("a.pdf", "application/pdf", Samples.pdf, withCsrf = false).statusCode())
        val id = c.json(c.upload("a.pdf", "application/pdf", Samples.pdf))["id"].asText()
        assertEquals(403, c.delete("/api/documents/$id", withCsrf = false).statusCode())
        c.csrf = "invalido"
        assertEquals(403, c.delete("/api/documents/$id").statusCode())
    }

    @Test
    fun `origem fora da allowlist e rejeitada`() {
        val c = t.client()
        val res = c.postJson("/api/auth/otp", mapOf("email" to "a@example.com"), mapOf("Origin" to "https://evil.example"))
        assertEquals(403, res.statusCode())
    }

    @Test
    fun `API responde sem cache e com cabecalhos de seguranca`() {
        val res = t.client().get("/api/health")
        assertEquals("no-store", res.headers().firstValue("Cache-Control").get())
        assertEquals("nosniff", res.headers().firstValue("X-Content-Type-Options").get())
        assertEquals("DENY", res.headers().firstValue("X-Frame-Options").get())
    }
}
