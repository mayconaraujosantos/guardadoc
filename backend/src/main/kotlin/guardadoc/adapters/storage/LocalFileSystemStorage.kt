package guardadoc.adapters.storage

import guardadoc.documents.DocumentStorage
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Armazenamento privado em disco local (diretório fora da pasta pública).
 * Substituível por um adaptador de bucket privado (S3, R2, GCS...) sem
 * alterar o núcleo da aplicação.
 */
class LocalFileSystemStorage(baseDir: Path) : DocumentStorage {
    private val root: Path = baseDir.toAbsolutePath().normalize()

    init {
        Files.createDirectories(root)
        restrictPermissions(root, "rwx------")
    }

    override fun put(key: String, content: ByteArray) {
        val target = resolve(key)
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, ".upload-", ".tmp")
        try {
            restrictPermissions(temp, "rw-------")
            Files.write(temp, content)
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun open(key: String): InputStream = Files.newInputStream(resolve(key))

    override fun delete(key: String) {
        Files.deleteIfExists(resolve(key))
    }

    override fun exists(key: String): Boolean = Files.exists(resolve(key))

    /** Chaves são geradas pelo servidor; ainda assim, nunca aceitar caminhos. */
    private fun resolve(key: String): Path {
        require(KEY_FORMAT.matches(key)) { "Chave de armazenamento inválida" }
        val path = root.resolve(key.take(2)).resolve(key).normalize()
        require(path.startsWith(root)) { "Chave de armazenamento inválida" }
        return path
    }

    private fun restrictPermissions(path: Path, permissions: String) {
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions)) }
    }

    companion object {
        private val KEY_FORMAT = Regex("^[A-Za-z0-9_-]{16,128}$")
    }
}
