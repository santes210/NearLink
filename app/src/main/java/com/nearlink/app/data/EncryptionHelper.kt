
package com.nearlink.app.data

import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object EncryptionHelper {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val SALT = "NearLinkSecureSalt2026"

    fun generateKeyPair(): KeyPair {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        return keyGen.generateKeyPair()
    }

    fun generateKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance(ALGORITHM)
        keyGen.init(256)
        return keyGen.generateKey()
    }

    fun deriveKeyFromPin(pin: String): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.toCharArray(), SALT.toByteArray(), 10000, 256)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, ALGORITHM)
    }

    fun encrypt(plainText: String, secretKey: SecretKey): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        
        val encodedIv = Base64.encodeToString(iv, Base64.NO_WRAP)
        val encodedBytes = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        return Pair(encodedBytes, encodedIv)
    }

    fun decrypt(encryptedBase64: String, ivBase64: String, secretKey: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        
        val spec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }
}
