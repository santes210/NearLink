package com.nearlink.app.wifidirect

import com.nearlink.app.data.security.Crypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Transferencia de archivos por socket TCP sobre el grupo Wi-Fi Direct.
 *
 * Formato del flujo:
 *   [int nombreLen][nombre UTF-8]
 *   tramas hasta EOF: [int ctLen][12 iv][ctLen bytes ciphertext]
 * Si se proporciona una llave (derivada de la sesión), cada chunk va cifrado con
 * AES-256-GCM; si no, viaja en claro (solo para el modo sin sesión).
 */
object FileTransferService {
    const val PORT = 8888
    private const val CHUNK = 64 * 1024

    data class ReceivedFile(val name: String, val data: ByteArray)

    /** Envía bytes al host del group owner. */
    suspend fun send(host: String, key: ByteArray?, fileName: String, data: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(java.net.InetSocketAddress(host, PORT), 15000)
                val out = DataOutputStream(socket.getOutputStream())
                val nameBytes = fileName.toByteArray(Charsets.UTF_8)
                out.writeInt(nameBytes.size)
                out.write(nameBytes)
                var offset = 0
                while (offset < data.size) {
                    val piece = data.copyOfRange(offset, minOf(offset + CHUNK, data.size))
                    val (ct, iv) = if (key != null) Crypto.encrypt(key, piece) else Crypto.Encrypted(piece, ByteArray(0))
                    out.writeInt(ct.size)
                    if (key != null) {
                        out.write(iv)
                    } else {
                        out.write(ByteArray(0))
                    }
                    out.write(ct)
                    offset += piece.size
                }
                out.flush()
                true
            } catch (e: IOException) {
                false
            } finally {
                runCatching { socket?.close() }
            }
        }

    /** El group owner acepta UNA conexión y devuelve el archivo recibido. */
    suspend fun receive(serverSocket: ServerSocket, key: ByteArray?): ReceivedFile? =
        withContext(Dispatchers.IO) {
            var client: Socket? = null
            try {
                serverSocket.soTimeout = 0
                client = serverSocket.accept()
                val input = DataInputStream(client.getInputStream())
                val nameLen = input.readInt()
                val nameBytes = ByteArray(nameLen)
                input.readFully(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                val buffer = java.io.ByteArrayOutputStream()
                while (true) {
                    val ctLen = try { input.readInt() } catch (e: IOException) { break }
                    if (ctLen <= 0) break
                    val iv = ByteArray(12)
                    if (key != null) input.readFully(iv)
                    val ct = ByteArray(ctLen)
                    input.readFully(ct)
                    val piece = if (key != null) Crypto.decrypt(key, ct, iv) else ct
                    buffer.write(piece)
                }
                ReceivedFile(name, buffer.toByteArray())
            } catch (e: IOException) {
                null
            } finally {
                runCatching { client?.close() }
            }
        }
}
