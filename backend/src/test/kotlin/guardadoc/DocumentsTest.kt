package guardadoc

import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentsTest {
    private val t = TestApp()

    @AfterTest fun tearDown() = t.close()

    @Test
    fun `upload listagem e download do proprio documento`() {
        val c = t.login("ana@example.com")
        val created = c.upload("../../etc/RG frente.pdf", "application/pdf", Samples.pdf)
        assertEquals(201, created.statusCode(), String(created.body()))
        val doc = c.json(created)
        assertEquals("RG frente.pdf", doc["name"].asText())
        assertEquals("application/pdf", doc["mimeType"].asText())

        assertEquals(201, c.upload("foto.png", "image/png", Samples.png).statusCode())
        assertEquals(201, c.upload("foto.JPG", "image/jpeg", Samples.jpeg).statusCode())

        val list = c.json(c.get("/api/documents"))
        assertEquals(3, list.size())

        val download = c.get("/api/documents/${doc["id"].asText()}/download")
        assertEquals(200, download.statusCode())
        assertContentEquals(Samples.pdf, download.body())
        val headers = download.headers()
        assertTrue(headers.firstValue("Content-Disposition").get().startsWith("attachment;"))
        assertEquals("nosniff", headers.firstValue("X-Content-Type-Options").get())
        assertEquals("no-store", headers.firstValue("Cache-Control").get())
    }

    @Test
    fun `rejeita arquivo acima de 10 MiB`() {
        val c = t.login("bia@example.com")
        val big = Samples.pdf + ByteArray(10 * 1024 * 1024)
        assertEquals(413, c.upload("grande.pdf", "application/pdf", big).statusCode())
        assertEquals(0, t.storedFileCount())
    }

    @Test
    fun `aceita arquivo com exatamente 10 MiB`() {
        val c = t.login("bia2@example.com")
        val exact = Samples.pdf + ByteArray(10 * 1024 * 1024 - Samples.pdf.size)
        assertEquals(201, c.upload("limite.pdf", "application/pdf", exact).statusCode())
    }

    @Test
    fun `rejeita conteudo real nao permitido independente do MIME declarado`() {
        val c = t.login("caio@example.com")
        assertEquals(415, c.upload("falso.pdf", "application/pdf", "<script>alert(1)</script>".toByteArray()).statusCode())
        assertEquals(415, c.upload("troca.pdf", "application/pdf", Samples.png).statusCode())
        assertEquals(415, c.upload("foto.pdf", "image/png", Samples.png).statusCode())
        assertEquals(415, c.upload("doc.html", "application/pdf", Samples.pdf).statusCode())
        assertEquals(0, t.storedFileCount())
    }

    @Test
    fun `usuario nao acessa documentos de outro usuario`() {
        val owner = t.login("dono@example.com")
        val other = t.login("intruso@example.com")
        val id = owner.json(owner.upload("cpf.pdf", "application/pdf", Samples.pdf))["id"].asText()

        assertEquals(0, other.json(other.get("/api/documents")).size())
        assertEquals(404, other.get("/api/documents/$id/download").statusCode())
        assertEquals(404, other.delete("/api/documents/$id").statusCode())
        assertEquals(404, other.postJson("/api/documents/$id/shares", mapOf("expiresInMinutes" to 60)).statusCode())
        assertEquals(404, other.get("/api/documents/$id/shares").statusCode())
        assertEquals(1, owner.json(owner.get("/api/documents")).size())
    }

    @Test
    fun `exige autenticacao`() {
        val anon = t.client()
        assertEquals(401, anon.get("/api/documents").statusCode())
        assertEquals(401, anon.get("/api/documents/${UUID.randomUUID()}/download").statusCode())
        assertEquals(401, anon.upload("a.pdf", "application/pdf", Samples.pdf).statusCode())
    }

    @Test
    fun `exclusao remove o arquivo do armazenamento e revoga links`() {
        val c = t.login("eva@example.com")
        val id = c.json(c.upload("a.pdf", "application/pdf", Samples.pdf))["id"].asText()
        val url = c.json(c.postJson("/api/documents/$id/shares", mapOf("expiresInMinutes" to 60)))["url"].asText()
        assertEquals(1, t.storedFileCount())

        assertEquals(204, c.delete("/api/documents/$id").statusCode())
        assertEquals(0, t.storedFileCount())
        assertEquals(404, c.get("/api/documents/$id/download").statusCode())
        assertEquals(0, c.json(c.get("/api/documents")).size())
        assertEquals(404, t.client().get("/s/" + url.substringAfter("/s/") + "/download").statusCode())
    }

    @Test
    fun `falha ao remover arquivo mantem documento inacessivel e limpeza repete`() {
        val c = t.login("fabi@example.com")
        val id = c.json(c.upload("a.pdf", "application/pdf", Samples.pdf))["id"].asText()
        t.storage.failDeletes = true
        assertEquals(204, c.delete("/api/documents/$id").statusCode())
        assertEquals(1, t.storedFileCount())
        assertEquals(404, c.get("/api/documents/$id/download").statusCode())

        t.storage.failDeletes = false
        assertEquals(1, t.app.documentService.purgePendingFiles())
        assertEquals(0, t.storedFileCount())
        assertEquals(0, t.app.documentService.purgePendingFiles())
    }
}
