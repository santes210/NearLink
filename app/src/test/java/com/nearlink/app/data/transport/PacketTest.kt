package com.nearlink.app.data.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class PacketTest {

    private val messageId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
    private val originId = "AA:BB:CC:DD:EE:FF"

    @Test
    fun `encode y decode conservan tipo, ttl, flags, id y payload`() {
        val payload = "hola malla".toByteArray()
        val bytes = Packet.encode(
            type = Packet.TYPE_MESSAGE,
            messageId = messageId,
            originId = originId,
            payload = payload,
            ttl = 5,
            flags = Packet.FLAG_REQUIRE_ACK,
        )

        val decoded = Packet.decode(bytes)!!

        assertEquals(Packet.TYPE_MESSAGE, decoded.type)
        assertEquals(5, decoded.ttl)
        assertEquals(Packet.FLAG_REQUIRE_ACK, decoded.flags)
        assertEquals(messageId, decoded.messageId)
        assertEquals(originId, decoded.originId)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `la cabecera ocupa exactamente 30 bytes`() {
        val bytes = Packet.encode(Packet.TYPE_ACK, messageId, originId, ByteArray(0))
        assertEquals(Packet.HEADER_SIZE, bytes.size)
    }

    @Test
    fun `decode rechaza magia invalida`() {
        val bytes = Packet.encode(Packet.TYPE_MESSAGE, messageId, originId, ByteArray(1))
        bytes[0] = 0x00
        assertNull(Packet.decode(bytes))
    }

    @Test
    fun `decode rechaza tramas truncadas`() {
        val bytes = Packet.encode(Packet.TYPE_MESSAGE, messageId, originId, ByteArray(16))
        val truncated = bytes.copyOf(bytes.size - 4)
        assertNull(Packet.decode(truncated))
    }

    @Test
    fun `ack conserva el id del mensaje confirmado`() {
        val ack = Packet.ack(messageId, originId)
        val decoded = Packet.decode(ack)!!
        assertEquals(Packet.TYPE_ACK, decoded.type)
        assertEquals(messageId, decoded.messageId)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `mac a bytes y vuelta`() {
        val bytes = Packet.macToBytes(originId)
        assertEquals(6, bytes.size)
        assertEquals(originId, Packet.bytesToMac(bytes))
    }

    @Test
    fun `chunker fragmenta y el reensamblador reconstruye`() {
        val payload = ByteArray(500) { it.toByte() }
        val chunks = Chunker(120).chunk(payload)

        assertEquals(5, chunks.size)
        val assembler = ChunkAssembler()
        var result: ByteArray? = null
        chunks.forEach { chunk -> result = assembler.add("AA:BB:CC:DD:EE:FF", chunk) }

        assertArrayEquals(payload, result)
    }

    @Test
    fun `el reensamblador solo entrega cuando estan todos los fragmentos`() {
        val chunks = Chunker(10).chunk(ByteArray(25) { 1 })
        val assembler = ChunkAssembler()

        assertNull(assembler.add("nodo", chunks[0]))
        assertNull(assembler.add("nodo", chunks[1]))
        assertEquals(25, assembler.add("nodo", chunks[2])!!.size)
    }

    @Test
    fun `el reensamblador afronta fragmentos desordenados`() {
        val payload = ByteArray(30) { it.toByte() }
        val chunks = Chunker(8).chunk(payload)
        val assembler = ChunkAssembler()

        var result: ByteArray? = null
        listOf(2, 0, 3, 1).forEach { index -> result = assembler.add("nodo", chunks[index]) }

        assertArrayEquals(payload, result)
    }

    @Test
    fun `cada fragmento declara su secuencia y el total`() {
        val chunks = Chunker(4).chunk(ByteArray(10))
        assertEquals(3, chunks.size)
        chunks.forEachIndexed { index, chunk ->
            val (seq, total) = Chunker.sequenceOf(chunk)
            assertEquals(index, seq)
            assertEquals(3, total)
        }
    }
}
