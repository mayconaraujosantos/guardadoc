package guardadoc.documents

enum class FileType(val mimeType: String, val extensions: Set<String>) {
    PDF("application/pdf", setOf("pdf")),
    JPEG("image/jpeg", setOf("jpg", "jpeg")),
    PNG("image/png", setOf("png"));

    companion object {
        private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

        /** Detecta o tipo pelo conteúdo real (assinatura), ignorando o que o cliente declarou. */
        fun detect(content: ByteArray): FileType? = when {
            content.startsWith(PDF_MAGIC) -> PDF
            content.startsWith(PNG_MAGIC) -> PNG
            content.startsWith(JPEG_MAGIC) -> JPEG
            else -> null
        }

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }
}
