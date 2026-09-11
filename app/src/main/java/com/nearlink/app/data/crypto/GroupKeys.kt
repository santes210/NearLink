package com.nearlink.app.data.crypto

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Derivación de claves para canales/grupos. Kotlin puro (sin Android) para que
 * la lógica criptográfica pueda testearse en la JVM.
 *
 * Modelo de confianza: todos los miembros comparten el MISMO código de grupo.
 * Ese código es el único secreto compartido, y de él se derivan:
 *
 *  - [channelId] = SHA-256(código) truncado a 16 bytes: identidad pública del
 *    canal (se usa para enrutar y deduplicar; no revela el código).
 *  - [groupKey]  = HKDF(PBKDF2-HMAC-SHA256(código, 1.000.000 iter)): clave
 *    maestra AES-256 del canal.
 *  - [messageKey] = HKDF(groupKey, salt aleatoria por mensaje): clave única por
 *    mensaje, de modo que comprometer una clave no compromete el resto.
 *
 * El código NUNCA se almacena en disco: solo se guardan el channelId y la
 * groupKey cifrada en reposo (ver ChannelRepositoryImpl).
 *
 * Nota de seguridad: la fortaleza de un grupo depende de la entropía del
 * código. Con PBKDF2 a 1.000.000 de iteraciones un atacante que capture tráfico
 * tiene que pagar ese coste por cada código que pruebe, así que conviene usar
 * códigos largos (12+ caracteres, a ser posible una frase).
 */
object GroupKeys {

    const val CHANNEL_ID_BYTES = 16
    const val SALT_BYTES = 16
    const val KEY_BYTES = 32

    private const val PBKDF2_ITERATIONS = 1_000_000
    private const val PBKDF2_KEY_BITS = 256

    private val CODE_SALT = "NearLink/v2/group-code".toByteArray(Charsets.UTF_8)
    private val GROUP_KEY_INFO = "NearLink/v2/group-key".toByteArray(Charsets.UTF_8)
    private val MESSAGE_KEY_INFO = "NearLink/v2/group-msg".toByteArray(Charsets.UTF_8)

    /** Identidad pública del canal: 16 bytes derivados del código. */
    fun channelId(code: String): ByteArray =
        sha256(code.toByteArray(Charsets.UTF_8)).copyOf(CHANNEL_ID_BYTES)

    /** Identidad pública del canal en hexadecimal (32 caracteres). */
    fun channelIdHex(code: String): String = hexOf(channelId(code))

    /** Clave maestra AES-256 del canal. */
    fun groupKey(code: String): ByteArray {
        val ikm = pbkdf2(code, CODE_SALT, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        return hkdf(ikm, CODE_SALT, GROUP_KEY_INFO, KEY_BYTES)
    }

    /** Clave única por mensaje: HKDF de la groupKey con una salt aleatoria. */
    fun messageKey(groupKey: ByteArray, salt: ByteArray): ByteArray =
        hkdf(groupKey, salt, MESSAGE_KEY_INFO, KEY_BYTES)

    fun hexOf(bytes: ByteArray): String =
        bytes.joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Longitud hexadecimal impar: ${hex.length}" }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    /** PBKDF2-HMAC-SHA256 según OWASP (iteraciones altas para códigos cortos). */
    fun pbkdf2(password: String, salt: ByteArray, iterations: Int, keyBits: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyBits)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val secret = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return secret
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** HKDF-SHA256 (extract + expand) según RFC 5869. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val okm = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (okm.size() < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            okm.write(t)
            counter++
        }
        return okm.toByteArray().copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
