package guardadoc

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import guardadoc.adapters.email.DevOutboxEmailSender
import guardadoc.adapters.persistence.Database
import guardadoc.adapters.storage.LocalFileSystemStorage
import guardadoc.auth.EmailSender
import guardadoc.documents.DocumentStorage
import guardadoc.documents.MalwareScanner
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class MutableClock(@Volatile var now: Instant = Instant.now()) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advance(duration: Duration) { now = now.plus(duration) }
}

/** Storage instrumentado: conta leituras e pode simular falha na remoção. */
class SpyStorage(private val delegate: DocumentStorage) : DocumentStorage by delegate {
    val opens = AtomicInteger()
    @Volatile var failDeletes = false
    @Volatile var failOpens = false
    override fun open(key: String): InputStream {
        opens.incrementAndGet()
        if (failOpens) error("falha simulada na leitura")
        return delegate.open(key)
    }
    override fun delete(key: String) {
        if (failDeletes) error("falha simulada no armazenamento")
        delegate.delete(key)
    }
}

object EmbeddedDb {
    private val server: EmbeddedPostgres by lazy { EmbeddedPostgres.builder().start() }

    /** Banco vazio e exclusivo para cada teste. */
    fun fresh(): Database {
        val name = "t_" + UUID.randomUUID().toString().replace("-", "")
        server.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        return Database(server.getDatabase("postgres", name))
    }
}

class TestApp(
    env: Map<String, String> = emptyMap(),
    val database: Database = EmbeddedDb.fresh(),
    val clock: MutableClock = MutableClock(),
    val storageDir: Path = Files.createTempDirectory("guardadoc-test"),
    baseStorage: DocumentStorage? = null,
    scanner: MalwareScanner? = null,
    emailOverride: EmailSender? = null,
) : AutoCloseable {
    val outbox = DevOutboxEmailSender()
    val storage = SpyStorage(baseStorage ?: LocalFileSystemStorage(storageDir))
    val app = GuardaDocApp(
        config = AppConfig.fromEnv(
            mapOf(
                "APP_ENV" to "dev",
                "APP_SECRET" to "segredo-de-teste-com-pelo-menos-32-caracteres",
                "COOKIE_SECURE" to "false",
                "STATIC_DIR" to "/nonexistent",
            ) + env,
        ),
        database = database,
        clock = clock,
        emailOverride = emailOverride ?: outbox,
        storageOverride = storage,
        scannerOverride = scanner,
    )
    val port = app.start(0, scheduleJobs = false).port()
    val base = "http://localhost:$port"
    val http: HttpClient = HttpClient.newHttpClient()

    fun storedFileCount(): Long = Files.walk(storageDir).use { s -> s.filter { Files.isRegularFile(it) }.count() }

    override fun close() = app.stop()

    fun client() = ApiClient(this)

    /** Faz login completo por OTP e devolve um cliente autenticado. */
    fun login(email: String): ApiClient = client().also { it.login(email) }
}

class ApiClient(private val t: TestApp) {
    var cookie: String? = null
    var csrf: String? = null
    private val json = jacksonObjectMapper()

    fun request(
        method: String,
        path: String,
        body: HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.noBody(),
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
        withCsrf: Boolean = true,
    ): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI.create(t.base + path)).method(method, body)
        contentType?.let { builder.header("Content-Type", it) }
        cookie?.let { builder.header("Cookie", it) }
        if (withCsrf) csrf?.let { builder.header("X-CSRF-Token", it) }
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = t.http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        response.headers().allValues("Set-Cookie").firstOrNull()?.let { set ->
            val pair = set.substringBefore(';')
            cookie = if (pair.endsWith("=")) null else pair
        }
        return response
    }

    fun postJson(path: String, payload: Any, headers: Map<String, String> = emptyMap(), withCsrf: Boolean = true) =
        request(
            "POST", path, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)),
            "application/json", headers, withCsrf,
        )

    fun get(path: String) = request("GET", path)
    fun delete(path: String, withCsrf: Boolean = true) = request("DELETE", path, withCsrf = withCsrf)

    fun requestOtp(email: String) = postJson("/api/auth/otp", mapOf("email" to email))
    fun verify(email: String, code: String) = postJson("/api/auth/otp/verify", mapOf("email" to email, "code" to code))

    fun login(email: String) {
        assertEquals(202, requestOtp(email).statusCode())
        val response = verify(email, t.outbox.lastCodeFor(email)!!)
        assertEquals(200, response.statusCode(), String(response.body()))
        csrf = json(response)["csrfToken"].asText()
    }

    fun upload(fileName: String, mime: String, content: ByteArray, withCsrf: Boolean = true): HttpResponse<ByteArray> {
        val boundary = "----guardadoc" + UUID.randomUUID()
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
            "Content-Type: $mime\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        val payload = head.toByteArray() + content + tail.toByteArray()
        return request(
            "POST", "/api/documents", HttpRequest.BodyPublishers.ofByteArray(payload),
            "multipart/form-data; boundary=$boundary", withCsrf = withCsrf,
        )
    }

    fun json(response: HttpResponse<ByteArray>): JsonNode = json.readTree(response.body())
}

object Samples {
    val pdf = "%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << >>\n%%EOF\n".toByteArray()
    val png = image("png")
    val jpeg = image("jpeg")

    /** PDF válido com exatamente [size] bytes. */
    fun pdfOfSize(size: Int): ByteArray {
        val head = "%PDF-1.4\n".toByteArray()
        val tail = "\n%%EOF\n".toByteArray()
        return head + ByteArray(size - head.size - tail.size) { ' '.code.toByte() } + tail
    }

    private fun image(format: String): ByteArray {
        val img = java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val out = java.io.ByteArrayOutputStream()
        check(javax.imageio.ImageIO.write(img, format, out))
        return out.toByteArray()
    }
}

/** Captura tudo que é logado durante o teste. */
class LogCapture : AutoCloseable {
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }
    init { root.addAppender(appender) }
    fun text(): String = appender.list.joinToString("\n") { e ->
        e.formattedMessage + (e.throwableProxy?.let { " " + it.message } ?: "")
    }
    override fun close() { root.detachAppender(appender) }
}
