package guardadoc.adapters.scan

import guardadoc.documents.MalwareScanner
import guardadoc.documents.ScanResult
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Cliente do clamd via protocolo INSTREAM (TCP). Falha com exceção se o
 * serviço estiver indisponível; o caso de uso trata isso como falha fechada.
 */
class ClamAvScanner(
    private val host: String,
    private val port: Int = 3310,
    private val timeoutMillis: Int = 30_000,
) : MalwareScanner {

    override fun scan(content: ByteArray): ScanResult {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            socket.soTimeout = timeoutMillis
            val out = DataOutputStream(socket.getOutputStream().buffered())
            out.write("zINSTREAM\u0000".toByteArray(Charsets.US_ASCII))
            var offset = 0
            while (offset < content.size) {
                val length = minOf(CHUNK, content.size - offset)
                out.writeInt(length)
                out.write(content, offset, length)
                offset += length
            }
            out.writeInt(0)
            out.flush()

            val reply = readReply(socket)
            return when {
                reply.endsWith("OK") -> ScanResult.Clean
                reply.endsWith("FOUND") ->
                    ScanResult.Infected(reply.removePrefix("stream:").removeSuffix("FOUND").trim())
                else -> error("Resposta inesperada do clamd")
            }
        }
    }

    private fun readReply(socket: Socket): String {
        val input = socket.getInputStream()
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b <= 0) break
            buffer.write(b)
            if (buffer.size() > 4096) break
        }
        return buffer.toString(Charsets.US_ASCII).trim()
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
