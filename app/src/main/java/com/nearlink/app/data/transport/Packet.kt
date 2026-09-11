package com.nearlink.app.data.transport

import java.nio.ByteBuffer
import java.util.UUID

/**
 * Formato de trama de NearLink (protocolo de malla).
 *
 * ```
 *  0..1   magia "NL"
 *  2      version
 *  3      tipo
 *  4      ttl (saltos restantes)
 *  5      flags
 *  6..21  id del mensaje (UUID, 16 bytes)
 *  22..27 origen (MAC del emisor original, 6 bytes)
 *  28..29 longitud del payload (2 bytes, big endian)
 *  30..   payload
 * ```
 *
 * Es Kotlin puro (sin dependencias de Android) para poder testearlo en JVM.
 */
object Packet {

    const val MAGIC_0: Byte = 0x4E // 'N'
    const val MAGIC_1: Byte = 0x4C // 'L'
    const val VERSION: Byte = 1
    const val HEADER_SIZE = 30
    const val MAX_PAYLOAD = 65_535

    // Tipos de trama
    const val TYPE_HELLO = 1
    const val TYPE_HELLO_ACK = 2
    const val TYPE_MESSAGE = 3
    const val TYPE_ACK = 4
    const val TYPE_FILE = 5
    const val TYPE_SOS = 6
    const val TYPE_GROUP = 7

    // Flags
    const val FLAG_REQUIRE_ACK = 0x01
    const val FLAG_RELAYED = 0x02
    const val FLAG_SOS = 0x04

    /**
     * La trama es un fragmento de un mensaje mayor: los 4 primeros bytes del
     * payload son `[indice(2)][total(2)]`. Permite enviar archivos de varios MB
     * troceandolos en tramas que quepan en el MTU.
     */
    const val FLAG_MULTIPART = 0x08
    const val MULTIPART_HEADER_SIZE = 4

    /** Saltos maximos que puede dar un mensaje por la malla. */
    const val MAX_TTL = 7

    fun encode(
        type: Int,
        messageId: UUID,
        originId: String,
        payload: ByteArray,
        ttl: Int = MAX_TTL,
        flags: Int = 0,
    ): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "Payload demasiado grande: ${payload.size}" }
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size)
        buffer.put(MAGIC_0)
        buffer.put(MAGIC_1)
        buffer.put(VERSION)
        buffer.put(type.toByte())
        buffer.put(ttl.coerceIn(0, MAX_TTL).toByte())
        buffer.put(flags.toByte())
        buffer.putLong(messageId.mostSignificantBits)
        buffer.putLong(messageId.leastSignificantBits)
        buffer.put(macToBytes(originId))
        buffer.putShort(payload.size.toShort())
        buffer.put(payload)
        return buffer.array()
    }

    /** Decodifica una trama completa. Devuelve null si esta corrupta. */
    fun decode(bytes: ByteArray): DecodedPacket? {
        if (bytes.size < HEADER_SIZE) return null
        if (bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1) return null
        val buffer = ByteBuffer.wrap(bytes)
        buffer.position(2)
        val version = buffer.get()
        if (version != VERSION) return null
        val type = buffer.get().toInt() and 0xFF
        val ttl = buffer.get().toInt() and 0xFF
        val flags = buffer.get().toInt() and 0xFF
        val mostSig = buffer.long
        val leastSig = buffer.long
        val origin = ByteArray(6)
        buffer.get(origin)
        val payloadLength = buffer.short.toInt() and 0xFFFF
        if (bytes.size < HEADER_SIZE + payloadLength) return null
        val payload = ByteArray(payloadLength)
        buffer.get(payload)
        return DecodedPacket(
            type = type,
            ttl = ttl,
            flags = flags,
            messageId = UUID(mostSig, leastSig),
            originId = bytesToMac(origin),
            payload = payload,
        )
    }

    /** Trama de confirmacion para un mensaje concreto. */
    fun ack(messageId: UUID, originId: String, relayed: Boolean = false): ByteArray =
        encode(
            type = TYPE_ACK,
            messageId = messageId,
            originId = originId,
            payload = ByteArray(0),
            ttl = 1,
            flags = if (relayed) FLAG_RELAYED else 0,
        )

    fun macToBytes(mac: String): ByteArray {
        val parts = mac.split(':')
        val result = ByteArray(6)
        parts.take(6).forEachIndexed { index, part ->
            result[index] = (part.toIntOrNull(16) ?: 0).toByte()
        }
        return result
    }

    fun bytesToMac(bytes: ByteArray): String =
        bytes.take(6).joinToString(":") { "%02X".format(it) }
}

data class DecodedPacket(
    val type: Int,
    val ttl: Int,
    val flags: Int,
    val messageId: UUID,
    val originId: String,
    val payload: ByteArray,
)

/**
 * Divide un payload en chunks que caben en una escritura GATT y los reensambla
 * en destino. Formato del chunk: `[seq(2)][total(2)][datos]`.
 */
class Chunker(private val maxPayload: Int) {

    fun chunk(payload: ByteArray, offset: Int = 0): List<ByteArray> {
        if (payload.isEmpty()) return emptyList()
        val usable = payload.size - offset
        val total = (usable + maxPayload - 1) / maxPayload
        val chunks = ArrayList<ByteArray>(total)
        for (index in 0 until total) {
            val start = offset + index * maxPayload
            val length = maxPayload.coerceAtMost(payload.size - start)
            val chunk = ByteBuffer.allocate(length + 4)
            chunk.putShort(index.toShort())
            chunk.putShort(total.toShort())
            chunk.put(payload, start, length)
            chunks.add(chunk.array())
        }
        return chunks
    }

    companion object {
        fun payloadOf(chunk: ByteArray): ByteArray = chunk.copyOfRange(4, chunk.size)

        fun sequenceOf(chunk: ByteArray): Pair<Int, Int> {
            if (chunk.size < 4) return 0 to 0
            val buffer = ByteBuffer.wrap(chunk)
            return (buffer.short.toInt() and 0xFFFF) to (buffer.short.toInt() and 0xFFFF)
        }
    }
}

/**
 * Reensamblador por dispositivo: acumula chunks hasta completar el mensaje.
 * No se aceptan mensajes entrelazados del mismo dispositivo (las escrituras
 * GATT se serializan, asi que llegan en orden).
 */
class ChunkAssembler {

    private val pending = mutableMapOf<String, Assembly>()

    @Synchronized
    fun add(deviceAddress: String, chunk: ByteArray): ByteArray? {
        val (seq, total) = Chunker.sequenceOf(chunk)
        if (total <= 0) return null
        val assembly = pending.getOrPut(deviceAddress) { Assembly(total) }
        if (assembly.total != total) {
            // Cambio de mensaje: se descarta lo anterior.
            pending[deviceAddress] = Assembly(total).also { it.parts[seq] = Chunker.payloadOf(chunk) }
            return if (total == 1) Chunker.payloadOf(chunk) else null
        }
        assembly.parts[seq] = Chunker.payloadOf(chunk)
        if (assembly.parts.size != total) return null
        pending.remove(deviceAddress)
        val size = assembly.parts.values.sumOf { it.size }
        val result = ByteArray(size)
        var position = 0
        for (index in 0 until total) {
            val part = assembly.parts[index] ?: return null
            System.arraycopy(part, 0, result, position, part.size)
            position += part.size
        }
        return result
    }

    @Synchronized
    fun reset(deviceAddress: String) {
        pending.remove(deviceAddress)
    }

    private class Assembly(val total: Int) {
        val parts = mutableMapOf<Int, ByteArray>()
    }
}
