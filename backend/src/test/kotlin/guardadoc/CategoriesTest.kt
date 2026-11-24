package guardadoc

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CategoriesTest {
    private val t = TestApp()

    @AfterTest fun tearDown() = t.close()

    private fun ApiClient.createCategory(name: String): String {
        val response = postJson("/api/categories", mapOf("name" to name))
        assertEquals(201, response.statusCode(), String(response.body()))
        return json(response)["id"].asText()
    }

    @Test
    fun `cria lista renomeia e exclui categorias`() {
        val c = t.login("ana@example.com")
        val escolar = c.createCategory("  Escolar  ")
        c.createCategory("Casa")

        val list = c.json(c.get("/api/categories"))
        assertEquals(listOf("Casa", "Escolar"), list.map { it["name"].asText() })
        assertEquals(0, list[0]["documentCount"].asInt())

        val renamed = c.patchJson("/api/categories/$escolar", mapOf("name" to "Faculdade"))
        assertEquals(200, renamed.statusCode())
        assertEquals("Faculdade", c.json(renamed)["name"].asText())

        assertEquals(204, c.delete("/api/categories/$escolar").statusCode())
        assertEquals(listOf("Casa"), c.json(c.get("/api/categories")).map { it["name"].asText() })
        assertEquals(404, c.delete("/api/categories/$escolar").statusCode())
    }

    @Test
    fun `valida nome e recusa duplicado sem diferenciar maiusculas`() {
        val c = t.login("bia@example.com")
        c.createCategory("Trabalho / RH")
        assertEquals(400, c.postJson("/api/categories", mapOf("name" to "trabalho   /  rh")).statusCode())
        assertEquals(400, c.postJson("/api/categories", mapOf("name" to "   ")).statusCode())
        assertEquals(400, c.postJson("/api/categories", mapOf("name" to "x".repeat(41))).statusCode())
        val casa = c.createCategory("Casa")
        assertEquals(400, c.patchJson("/api/categories/$casa", mapOf("name" to "TRABALHO / RH")).statusCode())

        // Outro usuário pode usar o mesmo nome.
        t.login("caio@example.com").createCategory("Casa")
    }

    @Test
    fun `documento entra sai e muda de categoria e a contagem acompanha`() {
        val c = t.login("dani@example.com")
        val saude = c.createCategory("Saúde")
        val casa = c.createCategory("Casa")

        // Envio direto para uma categoria.
        val doc = c.json(c.upload("exame.pdf", "application/pdf", Samples.pdf, categoryId = saude))
        assertEquals(saude, doc["categoryId"].asText())
        val id = doc["id"].asText()

        val moved = c.patchJson("/api/documents/$id", mapOf("categoryId" to casa))
        assertEquals(200, moved.statusCode())
        assertEquals(casa, c.json(moved)["categoryId"].asText())
        val counts = c.json(c.get("/api/categories")).associate { it["name"].asText() to it["documentCount"].asInt() }
        assertEquals(mapOf("Casa" to 1, "Saúde" to 0), counts)

        // Excluir a categoria não exclui o documento: ele fica sem categoria.
        assertEquals(204, c.delete("/api/categories/$casa").statusCode())
        val listed = c.json(c.get("/api/documents")).single()
        assertEquals(id, listed["id"].asText())
        assertTrue(listed["categoryId"].isNull)

        val cleared = c.patchJson("/api/documents/$id", mapOf("categoryId" to saude))
        assertEquals(saude, c.json(cleared)["categoryId"].asText())
        assertTrue(c.json(c.patchJson("/api/documents/$id", mapOf("categoryId" to null)))["categoryId"].isNull)
    }

    @Test
    fun `usuario nao usa nem altera categorias de outro`() {
        val alice = t.login("alice@example.com")
        val bob = t.login("bob@example.com")
        val daAlice = alice.createCategory("Particular")
        val docBob = bob.json(bob.upload("bob.pdf", "application/pdf", Samples.pdf))["id"].asText()
        val docAlice = alice.json(alice.upload("alice.pdf", "application/pdf", Samples.pdf))["id"].asText()

        assertEquals(0, bob.json(bob.get("/api/categories")).size())
        assertEquals(400, bob.patchJson("/api/documents/$docBob", mapOf("categoryId" to daAlice)).statusCode())
        assertEquals(400, bob.upload("x.pdf", "application/pdf", Samples.pdf, categoryId = daAlice).statusCode())
        assertEquals(404, bob.patchJson("/api/categories/$daAlice", mapOf("name" to "Hack")).statusCode())
        assertEquals(404, bob.delete("/api/categories/$daAlice").statusCode())

        // Nem mover o documento da Alice para uma categoria do Bob.
        val doBob = bob.createCategory("Do Bob")
        assertEquals(404, bob.patchJson("/api/documents/$docAlice", mapOf("categoryId" to doBob)).statusCode())
        assertEquals(400, alice.patchJson("/api/documents/$docAlice", mapOf("categoryId" to doBob)).statusCode())
        assertEquals("Particular", alice.json(alice.get("/api/categories")).single()["name"].asText())
    }

    @Test
    fun `alteracoes de categoria exigem token CSRF`() {
        val c = t.login("eva@example.com")
        val response = c.request(
            "POST", "/api/categories",
            java.net.http.HttpRequest.BodyPublishers.ofString("""{"name":"Casa"}"""),
            "application/json", withCsrf = false,
        )
        assertEquals(403, response.statusCode())
    }

    @Test
    fun `limite de categorias por usuario`() {
        val c = t.login("fabi@example.com")
        repeat(50) { c.createCategory("Categoria $it") }
        assertEquals(400, c.postJson("/api/categories", mapOf("name" to "Mais uma")).statusCode())
    }
}
