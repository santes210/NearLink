package com.nearlink.app.data.security

import android.util.Base64
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Primitivas criptográficas de NearLink:
 *  - X25519 (ECDH) vía BouncyCastle (proveedor pasado explícitamente para no chocar
 *    con el "BC" recortado que incluye Android).
 *  - HKDF-SHA256 para derivar la llave de sesión.
 *  - AES-256-GCM (JCA nativo del sistema) para el cifrado autenticado de mensajes.
 *  - HMAC-SHA256 / SHA-256 / huellas, utilidades.
 */
object Crypto {

    private val bc: Provider = BouncyCastleProvider()
    private val random = SecureRandom()

    const val X25519 = "X25519"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    const val IV_LEN = 12
    const val KEY_LEN = 32 // AES-256

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun b64decode(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    // ---------- X25519 (ECDH) ----------
    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(X25519, bc).generateKeyPair()

    fun publicKeyEncoded(key: PublicKey): ByteArray = key.encoded

    fun decodePublicKey(bytes: ByteArray): PublicKey =
        KeyFactory.getInstance(X25519, bc).generatePublic(X509EncodedKeySpec(bytes))

    fun decodePrivateKey(bytes: ByteArray): PrivateKey =
        KeyFactory.getInstance(X25519, bc).generatePrivate(PKCS8EncodedKeySpec(bytes))

    fun sharedSecret(myPriv: PrivateKey, peerPub: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance(X25519, bc)
        ka.init(myPriv)
        ka.doPhase(peerPub, true)
        return ka.generateSecret()
    }

    // ---------- Derivación de llave (HKDF-SHA256) ----------
    fun deriveSessionKey(secret: ByteArray, salt: ByteArray, pairingPin: String): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(secret, salt, ("NearLink-v1:" + pairingPin).toByteArray(Charsets.UTF_8)))
        val out = ByteArray(KEY_LEN)
        gen.generateBytes(out, 0, KEY_LEN)
        return out
    }

    fun deriveFileKey(sessionKey: ByteArray): ByteArray =
        deriveSessionKey(sessionKey, "nearlink-files".toByteArray(), "files")

    // ---------- MAC / Hash ----------
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun sha256(data: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)

    /** Concatena dos arreglos en orden canónico (lexicográfico) para que ambos extremos coincidan. */
    fun concatSorted(a: ByteArray, b: ByteArray): ByteArray =
        if (compare(a, b) <= 0) a + b else b + a

    private fun compare(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val cmp = a[i].toInt().and(0xff) - b[i].toInt().and(0xff)
            if (cmp != 0) return cmp
        }
        return a.size - b.size
    }

    /** Huella legible: "X25519:XXXX:XXXX:..." a partir del SHA-256 de la llave pública. */
    fun fingerprint(publicKeyBytes: ByteArray): String {
        val hex = sha256(publicKeyBytes).joinToString("") { "%02X".format(it) }
        val groups = hex.chunked(4).take(5).joinToString(":")
        return "$X25519:$groups"
    }

    // ---------- AES-256-GCM ----------
    data class Encrypted(val ciphertext: ByteArray, val iv: ByteArray)

    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): Encrypted {
        val iv = randomBytes(IV_LEN)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return Encrypted(cipher.doFinal(plaintext), iv)
    }

    fun decrypt(key: ByteArray, ciphertext: ByteArray, iv: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }
}
