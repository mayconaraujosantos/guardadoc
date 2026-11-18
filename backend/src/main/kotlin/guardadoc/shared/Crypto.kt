package guardadoc.shared

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Primitivas criptográficas usadas pelos casos de uso. */
object Crypto {
    private val random = SecureRandom()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    /** Token opaco de alta entropia (256 bits), seguro para URL e cookie. */
    fun randomToken(bytes: Int = 32): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return urlEncoder.encodeToString(buffer)
    }

    /** Código numérico de seis dígitos com distribuição uniforme. */
    fun sixDigitCode(): String = random.nextInt(1_000_000).toString().padStart(6, '0')

    fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    fun hmacSha256(secret: ByteArray, value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8))
    }

    /** Comparação em tempo constante. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun base64Url(bytes: ByteArray): String = urlEncoder.encodeToString(bytes)
}
