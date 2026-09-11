package com.nearlink.app.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Caja cifrada: texto cifrado + IV del GCM. Nunca se reutiliza un IV. */
data class SealedBox(val ciphertext: ByteArray, val iv: ByteArray) {
    fun encode(): String =
        Base64.getEncoder().encodeToString(ciphertext) + "." + Base64.getEncoder().encodeToString(iv)

    companion object {
        fun decode(encoded: String): SealedBox? {
            val parts = encoded.split('.')
            if (parts.size != 2) return null
            return runCatching {
                SealedBox(
                    Base64.getDecoder().decode(parts[0]),
                    Base64.getDecoder().decode(parts[1]),
                )
            }.getOrNull()
        }
    }
}

/**
 * Manager criptografico de NearLink.
 *
 * Decisiones de diseno (por portabilidad y porque se puede testear):
 *  - Identidad: par de claves P-256 (ECDH). Se genera en software y la clave
 *    privada se guarda CIFRADA con una llave AES-256-GCM del Android Keystore.
 *    (X25519 solo esta disponible en KeyPairGenerator a partir de API 31, y una
 *    llave del Keystore no siempre permite ECDH en dispositivos antiguos.)
 *  - Secreto compartido: ECDH + HKDF-SHA256 (RFC 5869) -> clave AES-256.
 *  - Mensajes y adjuntos: AES-256-GCM con IV aleatorio de 12 bytes.
 *  - PIN de emparejamiento: PBKDF2-HMAC-SHA256 con 600.000 iteraciones (OWASP)
 *    y salt aleatoria por sesion.
 */
class CryptoManager(private val filesDir: File) {

    private val keystore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    private val random = SecureRandom()

    @Volatile
    private var cachedKeyPair: KeyPair? = null

    /** Clave de reposo derivada de la identidad (sin par remoto). */
    fun localKey(): ByteArray =
        sha256(MASTER_KEY_ALIAS.toByteArray() + ensureIdentity().public.encoded)

    /** Borra la identidad y genera una nueva (rotacion de claves). */
    @Synchronized
    fun regenerate(): KeyPair {
        runCatching { identityFile.delete() }
        cachedKeyPair = null
        return ensureIdentity()
    }

    private val identityFile: File get() = File(filesDir, IDENTITY_FILE)

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "nearlink_master_key"
        private const val IDENTITY_FILE = "identity.bin"
        private const val CURVE = "secp256r1" // P-256 / prime256v1
        private const val AES_ALGORITHM = "AES"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val HKDF_OUTPUT_BYTES = 32 // AES-256
        private const val PBKDF2_ITERATIONS = 600_000
        private const val PBKDF2_KEY_BITS = 256
        private val HKDF_INFO = "NearLink/v1/message-key".toByteArray()
    }

    // ---------------------------------------------------------------- llaves

    private fun masterKey(): SecretKey {
        if (!keystore.containsAlias(MASTER_KEY_ALIAS)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            generator.init(spec)
            generator.generateKey()
        }
        return (keystore.getEntry(MASTER_KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    /**
     * Devuelve la identidad del nodo, creandola (y guardandola cifrada) si es
     * la primera vez.
     */
    @Synchronized
    fun ensureIdentity(): KeyPair {
        cachedKeyPair?.let { return it }
        val loaded = loadIdentity()
        if (loaded != null) {
            cachedKeyPair = loaded
            return loaded
        }
        val generated = generateIdentity()
        persistIdentity(generated)
        cachedKeyPair = generated
        return generated
    }

    /** Huella legible (8 grupos de 4 hex) del SHA-256 de la clave publica. */
    fun fingerprint(publicKey: ByteArray = publicKeyBytes()): String =
        sha256(publicKey)
            .joinToString("") { "%02X".format(it) }
            .chunked(4)
            .take(8)
            .joinToString(":")

    /** Clave publica (X.509) lista para compartir en el handshake. */
    fun publicKeyBytes(): ByteArray = ensureIdentity().public.encoded

    // ------------------------------------------------------------- ECDH/HKDF

    /** Secreto compartido derivado de ECDH + HKDF-SHA256. */
    fun sharedSecret(peerPublicKey: ByteArray): ByteArray {
        val peerKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublicKey))
        val agreement = KeyAgreement.getInstance("ECDH").apply {
            init(ensureIdentity().private)
            doPhase(peerKey, true)
        }
        return hkdf(
            ikm = agreement.generateSecret(),
            salt = sha256(ensureIdentity().public.encoded + peerPublicKey),
            info = HKDF_INFO,
        )
    }

    fun derivePinKey(pin: String, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(
            pin.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            PBKDF2_KEY_BITS,
        )
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val secret = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return hkdf(ikm = secret, salt = salt, info = "NearLink/v1/pin".toByteArray())
    }

    // ------------------------------------------------------------------- AES

    fun encrypt(plaintext: ByteArray, key: ByteArray): SealedBox {
        val iv = randomBytes(IV_BYTES)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, AES_ALGORITHM), GCMParameterSpec(GCM_TAG_BITS, iv))
        return SealedBox(ciphertext = cipher.doFinal(plaintext), iv = iv)
    }

    fun decrypt(box: SealedBox, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, AES_ALGORITHM),
            GCMParameterSpec(GCM_TAG_BITS, box.iv),
        )
        return cipher.doFinal(box.ciphertext)
    }

    fun encryptString(value: String, key: ByteArray): SealedBox = encrypt(value.toByteArray(Charsets.UTF_8), key)

    fun decryptString(box: SealedBox, key: ByteArray): String = String(decrypt(box, key), Charsets.UTF_8)

    /** Cifra con la clave de reposo del dispositivo (sin par remoto). */
    fun encryptLocal(plaintext: ByteArray): SealedBox = encrypt(plaintext, localKey())

    fun decryptLocal(box: SealedBox): ByteArray = decrypt(box, localKey())

    // ------------------------------------------------------------------ util

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun randomPin(digits: Int = 6): String {
        val bound = Math.pow(10.0, digits.toDouble()).toInt()
        return (random.nextInt(bound)).toString().padStart(digits, '0')
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    // --------------------------------------------------------------- privado

    private fun generateIdentity(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE), random)
        return generator.generateKeyPair()
    }

    private fun persistIdentity(keyPair: KeyPair) {
        val privateEncoded = keyPair.private.encoded // PKCS#8
        val sealed = encrypt(privateEncoded, localMaterialForIdentity())
        val publicEncoded = keyPair.public.encoded // X.509
        val bytes = ByteArrayOutputStream().use { out ->
            DataOutputStream(out).use { data ->
                data.writeInt(publicEncoded.size)
                data.write(publicEncoded)
                data.writeInt(sealed.iv.size)
                data.write(sealed.iv)
                data.writeInt(sealed.ciphertext.size)
                data.write(sealed.ciphertext)
                data.flush()
            }
            out.toByteArray()
        }
        identityFile.writeBytes(bytes)
    }

    private fun loadIdentity(): KeyPair? {
        if (!identityFile.exists()) return null
        return runCatching {
            DataInputStream(identityFile.inputStream().buffered()).use { data ->
                val publicEncoded = ByteArray(data.readInt()).also { data.readFully(it) }
                val iv = ByteArray(data.readInt()).also { data.readFully(it) }
                val ciphertext = ByteArray(data.readInt()).also { data.readFully(it) }

                val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicEncoded))
                val privateBytes = decrypt(SealedBox(ciphertext, iv), localMaterialForIdentity())
                val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateBytes))
                KeyPair(publicKey, privateKey)
            }
        }.getOrElse {
            // Identidad corrupta o llave maestra perdida: se regenera.
            runCatching { identityFile.delete() }
            null
        }
    }

    /**
     * Material para cifrar la identidad en reposo. Se deriva de la propia
     * llave del Keystore cifrando un bloque fijo: asi la clave privada nunca
     * toca el disco en claro y no dependemos de que el Keystore permita ECDH.
     */
    private fun localMaterialForIdentity(): ByteArray {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val wrapped = cipher.doFinal("nearlink-identity-v1".toByteArray())
        return sha256(wrapped + cipher.iv)
    }

    /** HKDF-SHA256 (extract + expand) segun RFC 5869. */
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = HKDF_OUTPUT_BYTES): ByteArray {
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
