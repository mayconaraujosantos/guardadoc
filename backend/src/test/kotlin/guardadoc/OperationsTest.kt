package guardadoc

import guardadoc.adapters.persistence.queryOne
import guardadoc.adapters.ratelimit.PostgresRateLimiter
import guardadoc.shared.Crypto
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationsTest {

    private fun TestApp.count(table: String): Int =
        database.withConnection { c -> c.queryOne("SELECT count(*) AS n FROM $table") { it.getInt("n") }!! }

    // ------------------------------------------------------------- HTTPS

    @Test
    fun `HTTPS obrigatorio atras de proxy confiavel`() {
        val env = mapOf(
            "REQUIRE_HTTPS" to "true",
            "TRUST_PROXY" to "true",
            "PUBLIC_BASE_URL" to "https://guardadoc.example",
        )
        TestApp(env).use { t ->
            val c = t.client()
            val redirect = c.request("GET", "/api/me?x=1")
            assertEquals(308, redirect.statusCode())
            assertEquals("https://guardadoc.example/api/me?x=1", redirect.headers().firstValue("Location").get())
            assertEquals(403, c.requestOtp("a@example.com").statusCode())
            assertEquals(200, c.get("/api/health").statusCode())

            val https = mapOf("X-Forwarded-Proto" to "https")
            assertEquals(202, c.postJson("/api/auth/otp", mapOf("email" to "a@example.com"), https).statusCode())
            assertEquals(401, c.request("GET", "/api/me", headers = https).statusCode())
        }
    }

    @Test
    fun `X-Forwarded-Proto e ignorado sem TRUST_PROXY`() {
        TestApp(mapOf("REQUIRE_HTTPS" to "true", "PUBLIC_BASE_URL" to "https://guardadoc.example")).use { t ->
            val res = t.client().postJson("/api/auth/otp", mapOf("email" to "a@example.com"), mapOf("X-Forwarded-Proto" to "https"))
            assertEquals(403, res.statusCode())
        }
    }

    // ------------------------------------------------- rotação de segredos

    @Test
    fun `rotacao de APP_SECRET preserva OTPs e sessoes em andamento`() {
        val db = EmbeddedDb.fresh()
        val oldSecret = "segredo-antigo-com-pelo-menos-32-caracteres!"
        val newSecret = "segredo-novo-com-pelo-menos-32-caracteres!!"

        val (cookie, oldCsrf, pendingCode) = TestApp(mapOf("APP_SECRET" to oldSecret), database = db).use { t ->
            val c = t.login("rot@example.com")
            t.client().requestOtp("pendente@example.com")
            Triple(c.cookie, c.csrf, t.outbox.lastCodeFor("pendente@example.com")!!)
        }

        TestApp(mapOf("APP_SECRET" to newSecret, "APP_SECRET_PREVIOUS" to oldSecret), database = db).use { t ->
            assertEquals(200, t.client().verify("pendente@example.com", pendingCode).statusCode())
            val c = t.client().apply { this.cookie = cookie; csrf = oldCsrf }
            assertEquals(201, c.upload("a.pdf", "application/pdf", Samples.pdf).statusCode())
            val freshCsrf = c.json(c.get("/api/me"))["csrfToken"].asText()
            assertTrue(freshCsrf != oldCsrf)
        }

        TestApp(mapOf("APP_SECRET" to newSecret), database = db).use { t ->
            val c = t.client().apply { this.cookie = cookie; csrf = oldCsrf }
            assertEquals(403, c.upload("a.pdf", "application/pdf", Samples.pdf).statusCode())
        }
    }

    // ------------------------------------------------------- rate limit

    @Test
    fun `verificacao de OTP e limitada por e-mail`() {
        TestApp().use { t ->
            val c = t.client()
            c.requestOtp("lim@example.com")
            repeat(10) { assertEquals(401, c.verify("lim@example.com", "999999").statusCode()) }
            assertEquals(429, c.verify("lim@example.com", "999999").statusCode())
        }
    }

    @Test
    fun `limite em PostgreSQL e compartilhado entre instancias e nao guarda dados em claro`() {
        TestApp().use { t ->
            val key = Crypto.randomToken()
            val a = PostgresRateLimiter(t.database, "teste", 3, Duration.ofMinutes(1), key.toByteArray(), t.clock)
            val b = PostgresRateLimiter(t.database, "teste", 3, Duration.ofMinutes(1), key.toByteArray(), t.clock)
            assertTrue(a.tryAcquire("1.2.3.4"))
            assertTrue(b.tryAcquire("1.2.3.4"))
            assertTrue(a.tryAcquire("1.2.3.4"))
            assertFalse(b.tryAcquire("1.2.3.4"))
            assertTrue(a.tryAcquire("5.6.7.8"))
            t.clock.advance(Duration.ofMinutes(2))
            assertTrue(b.tryAcquire("1.2.3.4"))
            val stored = t.database.withConnection { c ->
                c.queryOne("SELECT string_agg(encode(chave, 'escape'), '') AS s FROM limites_requisicao") { it.getString("s") }
            }.orEmpty()
            assertFalse("1.2.3.4" in stored)
        }
    }

    // ---------------------------------------------------- retenção e órfãos

    @Test
    fun `rotina de retencao remove dados vencidos`() {
        TestApp().use { t ->
            val c = t.login("ret@example.com")
            val id = c.json(c.upload("a.pdf", "application/pdf", Samples.pdf))["id"].asText()
            c.postJson("/api/documents/$id/shares", mapOf("expiresInMinutes" to 60))
            c.delete("/api/documents/$id")
            val keep = c.json(c.upload("b.pdf", "application/pdf", Samples.pdf))["id"].asText()

            // Nada vence imediatamente.
            val early = t.app.maintenanceService.runAll().retention
            assertEquals(0, early.documents + early.shareLinks + early.auditEvents)

            t.clock.advance(Duration.ofDays(200))
            val report = t.app.maintenanceService.runAll().retention
            assertEquals(1, report.otpChallenges)
            assertEquals(1, report.sessions)
            assertEquals(1, report.shareLinks)
            assertEquals(1, report.documents)
            assertTrue(report.auditEvents > 0)
            assertEquals(0, t.count("desafios_otp"))
            assertEquals(0, t.count("sessoes"))
            assertEquals(0, t.count("links_compartilhamento"))
            assertEquals(0, t.count("limites_requisicao"))
            assertEquals(1, t.count("documentos")) // o documento ativo é preservado
            assertEquals(1, t.storedFileCount())
            assertTrue(keep.isNotEmpty())
        }
    }

    @Test
    fun `varredura remove somente objetos orfaos fora do periodo de carencia`() {
        TestApp().use { t ->
            val c = t.login("orf@example.com")
            c.upload("a.pdf", "application/pdf", Samples.pdf)
            t.storage.put(Crypto.randomToken(24), Samples.pdf) // órfão (sem metadados)
            assertEquals(2, t.storedFileCount())

            assertEquals(0, t.app.maintenanceService.sweepOrphans()) // ainda na carência
            t.clock.advance(Duration.ofHours(2))
            assertEquals(1, t.app.maintenanceService.sweepOrphans())
            assertEquals(1, t.storedFileCount())
            assertEquals(1, c.json(c.get("/api/documents")).size())
        }
    }

    // ------------------------------------------------------ exclusão de conta

    @Test
    fun `titular exclui a propria conta e todos os dados`() {
        TestApp().use { t ->
            val owner = t.login("sair@example.com")
            val other = t.login("fica@example.com")
            other.upload("outro.pdf", "application/pdf", Samples.pdf)
            val id = owner.json(owner.upload("a.pdf", "application/pdf", Samples.pdf))["id"].asText()
            owner.upload("b.png", "image/png", Samples.png)
            val url = owner.json(owner.postJson("/api/documents/$id/shares", mapOf("expiresInMinutes" to 60)))["url"].asText()
            val sharePath = "/s/" + url.substringAfter("/s/") + "/download"
            val oldCookie = owner.cookie

            assertEquals(403, owner.delete("/api/me", withCsrf = false).statusCode())
            assertEquals(204, owner.delete("/api/me").statusCode())
            assertEquals(null, owner.cookie)

            assertEquals(1, t.storedFileCount())
            assertEquals(404, t.client().get(sharePath).statusCode())
            owner.cookie = oldCookie
            assertEquals(401, owner.get("/api/me").statusCode())
            assertEquals(1, t.count("usuarios"))
            assertEquals(1, other.json(other.get("/api/documents")).size())

            val again = t.login("sair@example.com")
            assertEquals(0, again.json(again.get("/api/documents")).size())
        }
    }

    // ---------------------------------------------- compensação de download

    @Test
    fun `falha ao ler o arquivo nao consome download do link`() {
        TestApp().use { t ->
            val c = t.login("comp@example.com")
            val id = c.json(c.upload("a.pdf", "application/pdf", Samples.pdf))["id"].asText()
            val body = mapOf("expiresInMinutes" to 60, "downloadLimit" to 1)
            val url = c.json(c.postJson("/api/documents/$id/shares", body))["url"].asText()
            val path = "/s/" + url.substringAfter("/s/") + "/download"

            t.storage.failOpens = true
            assertEquals(500, t.client().get(path).statusCode())
            t.storage.failOpens = false
            assertEquals(200, t.client().get(path).statusCode())
            assertEquals(404, t.client().get(path).statusCode())
        }
    }
}
