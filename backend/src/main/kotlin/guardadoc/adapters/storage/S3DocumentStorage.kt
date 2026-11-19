package guardadoc.adapters.storage

import guardadoc.documents.DocumentStorage
import guardadoc.documents.StoredObject
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.ServerSideEncryption
import java.io.InputStream
import java.net.URI

data class S3Settings(
    val bucket: String,
    val region: String,
    /** Endpoint customizado para provedores compatíveis (R2, MinIO, B2...). */
    val endpoint: String? = null,
    val accessKeyId: String? = null,
    val secretAccessKey: String? = null,
    val pathStyle: Boolean = false,
    /** Criptografia em repouso gerenciada pelo provedor (SSE-S3). */
    val serverSideEncryption: Boolean = true,
    val prefix: String = "documentos/",
)

/**
 * Bucket privado acessado somente pelo backend. Não gera URLs públicas nem
 * pré-assinadas: todo download passa pela autorização da aplicação.
 */
class S3DocumentStorage(private val settings: S3Settings) : DocumentStorage {
    private val client: S3Client = S3Client.builder()
        .region(Region.of(settings.region))
        .httpClient(UrlConnectionHttpClient.create())
        .credentialsProvider(
            if (settings.accessKeyId != null && settings.secretAccessKey != null) {
                StaticCredentialsProvider.create(AwsBasicCredentials.create(settings.accessKeyId, settings.secretAccessKey))
            } else {
                DefaultCredentialsProvider.builder().build()
            },
        )
        .forcePathStyle(settings.pathStyle)
        // Compatibilidade com provedores S3 que não suportam checksums "trailing".
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .apply { settings.endpoint?.let { endpointOverride(URI.create(it)) } }
        .build()

    override fun put(key: String, content: ByteArray) {
        client.putObject({ req ->
            req.bucket(settings.bucket).key(objectKey(key)).contentLength(content.size.toLong())
                .contentType("application/octet-stream")
            if (settings.serverSideEncryption) req.serverSideEncryption(ServerSideEncryption.AES256)
        }, RequestBody.fromBytes(content))
    }

    override fun open(key: String): InputStream =
        client.getObject { it.bucket(settings.bucket).key(objectKey(key)) }

    override fun delete(key: String) {
        client.deleteObject { it.bucket(settings.bucket).key(objectKey(key)) }
    }

    override fun exists(key: String): Boolean = try {
        client.headObject { it.bucket(settings.bucket).key(objectKey(key)) }
        true
    } catch (_: NoSuchKeyException) {
        false
    } catch (e: S3Exception) {
        if (e.statusCode() == 404) false else throw e
    }

    override fun listObjects(): Sequence<StoredObject> =
        client.listObjectsV2Paginator { it.bucket(settings.bucket).prefix(settings.prefix) }
            .contents()
            .asSequence()
            .map { StoredObject(it.key().removePrefix(settings.prefix), it.lastModified()) }
            .filter { KEY_FORMAT.matches(it.key) }

    private fun objectKey(key: String): String {
        require(KEY_FORMAT.matches(key)) { "Chave de armazenamento inválida" }
        return settings.prefix + key
    }

    private companion object {
        val KEY_FORMAT = Regex("^[A-Za-z0-9_-]{16,128}$")
    }
}
