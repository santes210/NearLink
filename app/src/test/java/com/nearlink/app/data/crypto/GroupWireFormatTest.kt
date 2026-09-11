package com.nearlink.app.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupWireFormatTest {

    private val channelId = ByteArray(16) { it.toByte() }
    private val senderNodeId = byteArrayOf(0x0A, 0x1B, 0x2C, 0x3D, 0x4E, 0x5F)
    private val salt = ByteArray(16) { (it + 1).toByte() }
    private val box = SealedBox(ciphertext = ByteArray(64) { (it * 3).toByte() }, iv = ByteArray(12) { (it + 9).toByte() })

    @Test
    fun `encode y decode conservan todos los campos`() {
        val encoded = GroupWireFormat.encode(channelId, senderNodeId, "Nodo Prueba", salt, box)

        val decoded = GroupWireFormat.decode(encoded)!!

        assertArrayEquals(channelId, decoded.channelId)
        assertArrayEquals(senderNodeId, decoded.senderNodeId)
        assertEquals("Nodo Prueba", decoded.senderName)
        assertArrayEquals(salt, decoded.salt)
        assertArrayEquals(box.iv, decoded.box.iv)
        assertArrayEquals(box.ciphertext, decoded.box.ciphertext)
    }

    @Test
    fun `decode rechaza tramas demasiado cortas para la cabecera`() {
        assertNull(GroupWireFormat.decode(ByteArray(0)))
        assertNull(GroupWireFormat.decode(ByteArray(5)))
        val encoded = GroupWireFormat.encode(channelId, senderNodeId, "X", salt, box)
        val minHeader = GroupWireFormat.CHANNEL_ID_BYTES + GroupWireFormat.NODE_ID_BYTES + 1 +
            GroupWireFormat.SALT_BYTES + GroupWireFormat.IV_BYTES
        assertNull(GroupWireFormat.decode(encoded.copyOf(minHeader - 1)))
    }

    @Test
    fun `el ciphertext truncado sigue siendo parseable (su longitud la valida el GCM)`() {
        val encoded = GroupWireFormat.encode(channelId, senderNodeId, "X", salt, box)
        val truncated = encoded.copyOf(encoded.size - 3)
        val decoded = GroupWireFormat.decode(truncated)!!
        assertArrayEquals(channelId, decoded.channelId)
        assertEquals(box.ciphertext.size - 3, decoded.box.ciphertext.size)
    }

    @Test
    fun `el nombre se trunca al maximo de bytes del formato`() {
        val longName = "a".repeat(200)
        val encoded = GroupWireFormat.encode(channelId, senderNodeId, longName, salt, box)
        val decoded = GroupWireFormat.decode(encoded)!!
        assert(decoded.senderName.length <= 127)
        assert(decoded.senderName.toByteArray(Charsets.UTF_8).size <= GroupWireFormat.NAME_MAX_BYTES)
    }

    @Test
    fun `nombres unicode sobreviven al viaje de ida y vuelta`() {
        val encoded = GroupWireFormat.encode(channelId, senderNodeId, "Nodo Túxpam 🚀", salt, box)
        val decoded = GroupWireFormat.decode(encoded)!!
        assertEquals("Nodo Túxpam 🚀", decoded.senderName)
    }
}
