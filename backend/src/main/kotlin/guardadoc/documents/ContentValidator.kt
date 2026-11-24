package guardadoc.documents

import guardadoc.shared.AppException
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Validação estrutural além da assinatura inicial: evita aceitar arquivos que
 * só começam com bytes "mágicos" mas não são PDF/JPEG/PNG bem formados.
 * Imagens têm apenas o cabeçalho lido (sem decodificar pixels), o que também
 * protege contra bombas de descompressão via limite de dimensões.
 */
object ContentValidator {
    const val MAX_IMAGE_SIDE = 20_000
    const val MAX_IMAGE_PIXELS = 100_000_000L

    fun validate(type: FileType, content: ByteArray) {
        val ok = when (type) {
            FileType.PDF -> isPdf(content)
            FileType.PNG -> isPng(content) && hasSaneDimensions(content, "png")
            FileType.JPEG -> isJpeg(content) && hasSaneDimensions(content, "jpeg")
        }
        if (!ok) throw AppException.UnsupportedMedia("Arquivo corrompido ou com estrutura inválida")
    }

    private val PDF_VERSION = Regex("^%PDF-[12]\\.[0-9]")

    private fun isPdf(content: ByteArray): Boolean {
        val head = String(content, 0, minOf(content.size, 16), Charsets.ISO_8859_1)
        if (!PDF_VERSION.containsMatchIn(head)) return false
        val tailStart = maxOf(0, content.size - 2048)
        val tail = String(content, tailStart, content.size - tailStart, Charsets.ISO_8859_1)
        return tail.contains("%%EOF")
    }

    private fun isPng(content: ByteArray): Boolean {
        if (content.size < 8 + 25 + 12) return false
        val ihdr = String(content, 12, 4, Charsets.US_ASCII)
        val tailStart = content.size - 12
        val iend = String(content, tailStart + 4, 4, Charsets.US_ASCII)
        return ihdr == "IHDR" && iend == "IEND"
    }

    private fun isJpeg(content: ByteArray): Boolean {
        if (content.size < 4) return false
        // Marcador EOI (FF D9) no final; tolera poucos bytes de preenchimento.
        val from = maxOf(2, content.size - 64)
        return (content.size - 2 downTo from).any {
            content[it] == 0xFF.toByte() && content[it + 1] == 0xD9.toByte()
        }
    }

    private fun hasSaneDimensions(content: ByteArray, format: String): Boolean = runCatching {
        ImageIO.createImageInputStream(ByteArrayInputStream(content)).use { input ->
            val reader = ImageIO.getImageReadersByFormatName(format).asSequence().firstOrNull() ?: return false
            try {
                reader.setInput(input, true, true)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                width in 1..MAX_IMAGE_SIDE && height in 1..MAX_IMAGE_SIDE &&
                    width.toLong() * height <= MAX_IMAGE_PIXELS
            } finally {
                reader.dispose()
            }
        }
    }.getOrDefault(false)
}
