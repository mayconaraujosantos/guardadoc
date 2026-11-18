package guardadoc

import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharingTest {
    private val t = TestApp()

    @AfterTest fun tearDown() = t.close()

    private fun setup(limit: Int? = null, minutes: Long = 60): Triple<ApiClient, String, String> {
        val c = t.login("dono${System.nanoTime()}@example.com")
        val id = c.json(c.upload("contrato.pdf", "application/pdf", Samples.pdf))["id"].asText()
        val body = buildMap<String, Any> { put("expiresInMinutes", minutes); limit?.let { put("downloadLimit", it) } }
        val res = c.postJson("/api/documents/$id/shares", body)
        assertEquals(201, res.statusCode(), String(res.body()))
        val path = "/s/" + c.json(res)["url"].asText().substringAfter("/s/")
        return Triple(c, id, path)
    }

    @Test
    fun `link valido mostra pagina e entrega o arquivo`() {
        val (_, _, path) = setup()
        val page = t.client().get(path)
        assertEquals(200, page.statusCode())
        val headers = page.headers()
        assertEquals("no-referrer", headers.firstValue("Referrer-Policy").get())
        assertEquals("no-store", headers.firstValue("Cache-Control").get())
        assertTrue(String(page.body()).contains("contrato.pdf"))

        val file = t.client().get("$path/download")
        assertEquals(200, file.statusCode())
        assertContentEquals(Samples.pdf, file.body())
        assertTrue(file.headers().firstValue("Content-Disposition").get().startsWith("attachment"))
    }

    @Test
    fun `expiracao e obrigatoria`() {
        val c = t.login("x@example.com")
        val id = c.json(c.upload("a.pdf", "application/pdf", Samples.pdf))["id"].asText()
        assertEquals(400, c.postJson("/api/documents/$id/shares", emptyMap<String, Any>()).statusCode())
        assertEquals(400, c.postJson("/api/documents/$id/shares", mapOf("expiresInMinutes" to 0)).statusCode())
    }

    @Test
    fun `link expirado nao le o armazenamento`() {
        val (_, _, path) = setup(minutes = 10)
        t.clock.advance(Duration.ofMinutes(11))
        val before = t.storage.opens.get()
        assertEquals(404, t.client().get(path).statusCode())
        assertEquals(404, t.client().get("$path/download").statusCode())
        assertEquals(before, t.storage.opens.get())
    }

    @Test
    fun `link revogado pelo proprietario deixa de funcionar`() {
        val (c, id, path) = setup()
        val linkId = c.json(c.get("/api/documents/$id/shares"))[0]["id"].asText()
        val intruder = t.login("intruso@example.com")
        assertEquals(404, intruder.delete("/api/shares/$linkId").statusCode())
        assertEquals(200, t.client().get(path).statusCode())

        assertEquals(204, c.delete("/api/shares/$linkId").statusCode())
        val before = t.storage.opens.get()
        assertEquals(404, t.client().get("$path/download").statusCode())
        assertEquals(before, t.storage.opens.get())
        assertEquals("REVOGADO", c.json(c.get("/api/documents/$id/shares"))[0]["status"].asText())
    }

    @Test
    fun `link de documento excluido nao le o armazenamento`() {
        val (c, id, path) = setup()
        t.storage.failDeletes = true // mantém o objeto no disco para provar que não é lido
        c.delete("/api/documents/$id")
        val before = t.storage.opens.get()
        assertEquals(404, t.client().get("$path/download").statusCode())
        assertEquals(before, t.storage.opens.get())
    }

    @Test
    fun `token invalido nao le o armazenamento`() {
        setup()
        val before = t.storage.opens.get()
        assertEquals(404, t.client().get("/s/naoexiste/download").statusCode())
        assertEquals(404, t.client().get("/s/${"a".repeat(43)}/download").statusCode())
        assertEquals(before, t.storage.opens.get())
    }

    @Test
    fun `limite de downloads nao e ultrapassado com requisicoes concorrentes`() {
        val (c, id, path) = setup(limit = 3)
        val threads = 20
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val results = (1..threads).map {
            pool.submit(Callable { start.await(); t.client().get("$path/download").statusCode() })
        }
        start.countDown()
        val statuses = results.map { it.get() }
        pool.shutdown()
        assertEquals(3, statuses.count { it == 200 }, statuses.toString())
        assertEquals(3, t.storage.opens.get())
        val link = c.json(c.get("/api/documents/$id/shares"))[0]
        assertEquals(3, link["downloads"].asInt())
        assertEquals("ESGOTADO", link["status"].asText())
    }
}
