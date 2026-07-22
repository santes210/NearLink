
package com.nearlink.app.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class EncryptionManager {
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "NearLinkMasterKey"

    init {
        if (!keyStore.containsAlias(alias)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        return (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
    }

    fun deriveKeyFromPin(pin: String): SecretKey {
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(pin.toCharArray(), "NearLinkEnterpriseSalt2026".toByteArray(), 15000, 256)
            val tmp = factory.generateSecret(spec)
            SecretKeySpec(tmp.encoded, "AES")
        } catch (e: Exception) {
            getSecretKey()
        }
    }

    fun encrypt(plainText: String, secretKey: SecretKey? = null): Pair<String, String> {
        val key = secretKey ?: getSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Pair(Base64.encodeToString(encryptedBytes, Base64.NO_WRAP), Base64.encodeToString(iv, Base64.NO_WRAP))
    }

    fun decrypt(encryptedBase64: String, ivBase64: String, secretKey: SecretKey? = null): String {
        val key = secretKey ?: getSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
    }

    /** Cifra datos binarios (p. ej. la llave privada X25519) con la llave de AndroidKeyStore. */
    fun encryptBytes(plain: ByteArray): Pair<String, String> {
        val key = getSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return Pair(Base64.encodeToString(ct, Base64.NO_WRAP), Base64.encodeToString(iv, Base64.NO_WRAP))
    }

    fun decryptBytes(encryptedBase64: String, ivBase64: String): ByteArray {
        val key = getSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(encryptedBytes)
    }
}
