package guardadoc

import guardadoc.adapters.storage.S3DocumentStorage
import guardadoc.adapters.storage.S3Settings
import guardadoc.shared.Crypto
import org.gaul.s3proxy.S3Proxy
import org.gaul.s3proxy.auth.AuthenticationType
import org.gaul.s3proxy.nio2blob.TransientNio2BlobStore
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class S3StorageTest {
    private val blobStore = TransientNio2BlobStore().also { it.createContainer("guardadoc-privado") }

    private val proxy = S3Proxy.builder()
        .blobStore(blobStore)
        .awsAuthentication(AuthenticationType.AWS_V2_OR_V4, "chave", "segredo")
        .endpoint(URI.create("http://127.0.0.1:0"))
        .build()
        .also { it.start(); while (it.state != "STARTED") Thread.sleep(10) }

    private val storage = S3DocumentStorage(
        S3Settings(
            bucket = "guardadoc-privado",
            region = "us-east-1",
            endpoint = "http://127.0.0.1:${proxy.port}",
            accessKeyId = "chave",
            secretAccessKey = "segredo",
            pathStyle = true,
            serverSideEncryption = true,
        ),
    )

    @AfterTest fun tearDown() = proxy.stop()

    @Test
    fun `operacoes basicas no bucket privado`() {
        val key = Crypto.randomToken(24)
        assertFalse(storage.exists(key))
        storage.put(key, Samples.pdf)
        assertTrue(storage.exists(key))
        assertContentEquals(Samples.pdf, storage.open(key).use { it.readBytes() })
        assertEquals(listOf(key), storage.listObjects().map { it.key }.toList())
        storage.delete(key)
        storage.delete(key) // idempotente
        assertFalse(storage.exists(key))
    }

    @Test
    fun `aplicacao completa usando bucket S3`() {
        TestApp(baseStorage = storage).use { t ->
            val c = t.login("s3@example.com")
            val id = c.json(c.upload("a.pdf", "application/pdf", Samples.pdf))["id"].asText()
            assertEquals(1, storage.listObjects().count())
            assertContentEquals(Samples.pdf, c.get("/api/documents/$id/download").body())
            assertEquals(204, c.delete("/api/documents/$id").statusCode())
            assertEquals(0, storage.listObjects().count())
        }
    }
}
