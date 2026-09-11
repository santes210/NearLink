package com.nearlink.app.data.repository

import com.nearlink.app.data.crypto.SealedBox
import com.nearlink.app.data.transport.IdentityPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WireFormatTest {

    @Test
    fun `seal y unseal conservan iv y ciphertext`() {
        val box = SealedBox(ciphertext = ByteArray(32) { 7 }, iv = ByteArray(12) { 3 })
        val sealed = WireFormat.seal(box)

        val unsealed = WireFormat.unseal(sealed)!!

        assertArrayEquals(box.iv, unsealed.iv)
        assertArrayEquals(box.ciphertext, unsealed.ciphertext)
    }

    @Test
    fun `unseal rechaza basura`() {
        assertNull(WireFormat.unseal(ByteArray(0)))
        assertNull(WireFormat.unseal(byteArrayOf(0x20)))
    }

    @Test
    fun `fileFrame conserva nombre, mime y tamano`() {
        val box = SealedBox(ciphertext = ByteArray(64) { 1 }, iv = ByteArray(12) { 2 })
        val frame = WireFormat.fileFrame("nota.m4a", "audio/mp4", 4096, box)

        val parsed = WireFormat.parseFileFrame(frame)!!

        assertEquals("nota.m4a", parsed.name)
        assertEquals("audio/mp4", parsed.mimeType)
        assertEquals(4096L, parsed.size)
        assertArrayEquals(box.ciphertext, parsed.box.ciphertext)
        assertArrayEquals(box.iv, parsed.box.iv)
    }

    @Test
    fun `identityPayload conserva nombre y clave publica`() {
        val publicKey = ByteArray(91) { it.toByte() }
        val encoded = IdentityPayload.encode("Nodo Túxpam", publicKey)

        val decoded = IdentityPayload.decode(encoded)!!

        assertEquals("Nodo Túxpam", decoded.name)
        assertArrayEquals(publicKey, decoded.publicKey)
    }

    @Test
    fun `SealedBox codifica y decodifica en una sola cadena`() {
        val box = SealedBox(ciphertext = ByteArray(16) { 9 }, iv = ByteArray(12) { 4 })
        val decoded = SealedBox.decode(box.encode())!!
        assertArrayEquals(box.ciphertext, decoded.ciphertext)
        assertArrayEquals(box.iv, decoded.iv)
    }
}
