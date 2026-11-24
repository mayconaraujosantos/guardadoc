package guardadoc

import guardadoc.adapters.scan.ClamAvScanner
import guardadoc.documents.MalwareScanner
import guardadoc.documents.ScanResult
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ContentSecurityTest {

    @Test
    fun `rejeita arquivos com assinatura valida mas estrutura invalida`() {
        TestApp().use { t ->
            val c = t.login("ana@example.com")
            val pdfSemEof = "%PDF-1.4\n<html><script>alert(1)</script></html>".toByteArray()
            val pngTruncado = Samples.png.copyOf(Samples.png.size - 20)
            val jpegFalso = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + "<html>".toByteArray()
            assertEquals(415, c.upload("a.pdf", "application/pdf", pdfSemEof).statusCode())
            assertEquals(415, c.upload("a.png", "image/png", pngTruncado).statusCode())
            assertEquals(415, c.upload("a.jpg", "image/jpeg", jpegFalso).statusCode())
            assertEquals(415, c.upload("bomba.png", "image/png", pngWithDimensions(30_000, 30_000)).statusCode())
            assertEquals(0, t.storedFileCount())
        }
    }

    @Test
    fun `malware detectado e recusado e auditado`() {
        val scanner = object : MalwareScanner {
            override fun scan(content: ByteArray) = ScanResult.Infected("Eicar-Test-Signature")
        }
        TestApp(scanner = scanner).use { t ->
            val c = t.login("bia@example.com")
            assertEquals(422, c.upload("a.pdf", "application/pdf", Samples.pdf).statusCode())
            assertEquals(0, t.storedFileCount())
        }
    }

    @Test
    fun `antivirus indisponivel falha fechado`() {
        val scanner = object : MalwareScanner {
            override fun scan(content: ByteArray): ScanResult = error("conexão recusada")
        }
        TestApp(scanner = scanner).use { t ->
            val c = t.login("caio@example.com")
            assertEquals(503, c.upload("a.pdf", "application/pdf", Samples.pdf).statusCode())
            assertEquals(0, t.storedFileCount())
        }
    }

    @Test
    fun `cliente ClamAV fala o protocolo INSTREAM`() {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                repeat(2) {
                    server.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val command = ByteArray(10).also { input.readFully(it) }
                        check(String(command) == "zINSTREAM\u0000")
                        val received = ByteArrayOutputStream()
                        while (true) {
                            val length = input.readInt()
                            if (length == 0) break
                            received.write(ByteArray(length).also { input.readFully(it) })
                        }
                        val reply = if ("EICAR" in received.toString()) "stream: Eicar-Signature FOUND" else "stream: OK"
                        socket.getOutputStream().write((reply + "\u0000").toByteArray())
                    }
                }
            }
            val scanner = ClamAvScanner("127.0.0.1", server.localPort)
            assertEquals(ScanResult.Clean, scanner.scan(Samples.pdf))
            val infected = scanner.scan("X5O!P%@AP[4\\PZX54(P^)7CC)7}\$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!".toByteArray())
            assertIs<ScanResult.Infected>(infected)
            assertEquals("Eicar-Signature", infected.signature)
        }
    }

    /** PNG mínimo bem formado (CRC correto) declarando dimensões arbitrárias. */
    private fun pngWithDimensions(width: Int, height: Int): ByteArray {
        fun chunk(type: String, data: ByteArray): ByteArray {
            val crc = CRC32().apply { update(type.toByteArray()); update(data) }.value.toInt()
            return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(type.toByteArray()).put(data).putInt(crc).array()
        }
        val ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array()
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return signature + chunk("IHDR", ihdr) + chunk("IDAT", ByteArray(16)) + chunk("IEND", ByteArray(0))
    }
}
