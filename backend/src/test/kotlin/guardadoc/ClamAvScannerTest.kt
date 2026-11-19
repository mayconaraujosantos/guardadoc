package guardadoc

import guardadoc.adapters.scan.ClamAvScanner
import guardadoc.documents.ScanResult
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Testa o protocolo INSTREAM contra um clamd falso (sem Docker). */
class ClamAvScannerTest {

    /** Sobe um clamd falso que responde [reply] e devolve o comando e o conteúdo recebidos. */
    private fun withFakeClamd(reply: String, block: (port: Int) -> Unit): Pair<String, ByteArray> {
        ServerSocket(0).use { server ->
            var command = ""
            val received = ByteArrayOutputStream()
            val worker = thread {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    command = buildString {
                        while (true) {
                            val b = input.read()
                            if (b <= 0) break
                            append(b.toChar())
                        }
                    }
                    while (true) {
                        val length = input.readInt()
                        if (length == 0) break
                        received.write(input.readNBytes(length))
                    }
                    socket.getOutputStream().apply { write("$reply\u0000".toByteArray()); flush() }
                }
            }
            block(server.localPort)
            worker.join(5_000)
            return command to received.toByteArray()
        }
    }

    @Test
    fun `arquivo limpo e enviado em blocos pelo INSTREAM`() {
        val content = ByteArray(150 * 1024) { (it % 251).toByte() } // > 2 blocos de 64 KiB
        var result: ScanResult? = null
        val (command, received) = withFakeClamd("stream: OK") { port ->
            result = ClamAvScanner("localhost", port).scan(content)
        }
        assertEquals("zINSTREAM", command)
        assertContentEquals(content, received)
        assertEquals(ScanResult.Clean, result)
    }

    @Test
    fun `arquivo infectado devolve a assinatura`() {
        var result: ScanResult? = null
        withFakeClamd("stream: Eicar-Signature FOUND") { port ->
            result = ClamAvScanner("localhost", port).scan(Samples.pdf)
        }
        assertEquals("Eicar-Signature", assertIs<ScanResult.Infected>(result).signature)
    }

    @Test
    fun `resposta inesperada e tratada como falha`() {
        withFakeClamd("INSTREAM size limit exceeded. ERROR") { port ->
            assertFailsWith<IllegalStateException> { ClamAvScanner("localhost", port).scan(Samples.pdf) }
        }
    }

    @Test
    fun `clamd indisponivel faz o upload falhar com 503`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        TestApp(scanner = ClamAvScanner("localhost", closedPort, timeoutMillis = 1_000)).use { t ->
            val c = t.login("av@example.com")
            assertEquals(503, c.upload("a.pdf", "application/pdf", Samples.pdf).statusCode())
            assertEquals(0, t.storedFileCount())
        }
    }
}
