package com.cartasdocaos.game.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Cliente WebSocket mínimo (RFC 6455), só texto. Sem bibliotecas externas:
 * suporta ws:// e wss:// (com verificação do nome do servidor no certificado).
 */
class WsClient private constructor(
    private val socket: Socket,
    input: InputStream,
    private val output: OutputStream
) {
    private val din = DataInputStream(input)
    private val writeLock = Any()
    @Volatile private var closed = false

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_MESSAGE = 1 shl 20
        private val random = SecureRandom()

        /** Abre a conexão (bloqueante). Lança IOException se o servidor recusar ou não responder. */
        fun connect(url: String, timeoutMs: Int = 8000): WsClient {
            val uri = URI(url)
            val secure = when (uri.scheme?.lowercase()) {
                "wss", "https" -> true
                "ws", "http" -> false
                else -> throw IOException("Endereço inválido: $url")
            }
            val host = uri.host ?: throw IOException("Endereço inválido: $url")
            val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
            val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")

            val plain = Socket()
            var sock: Socket = plain
            try {
                plain.connect(InetSocketAddress(host, port), timeoutMs)
                plain.soTimeout = timeoutMs
                if (secure) {
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
                    val params = ssl.sslParameters
                    params.endpointIdentificationAlgorithm = "HTTPS" // confere se o certificado é do servidor certo
                    ssl.sslParameters = params
                    ssl.startHandshake()
                    sock = ssl
                }
                sock.tcpNoDelay = true
                val input = BufferedInputStream(sock.getInputStream())
                val output = sock.getOutputStream()

                val keyBytes = ByteArray(16).also { random.nextBytes(it) }
                val key = Base64.getEncoder().encodeToString(keyBytes)
                val hostHeader = if ((secure && port == 443) || (!secure && port == 80)) host else "$host:$port"
                val req = "GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
                output.write(req.toByteArray(Charsets.US_ASCII))
                output.flush()

                val headers = readHeaders(input)
                val lines = headers.split("\r\n")
                if (!lines[0].contains(" 101")) throw IOException("O servidor recusou a conexão (${lines[0].trim()})")
                val expected = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII))
                )
                val accept = lines.drop(1).firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()
                if (accept != expected) throw IOException("Resposta inválida do servidor")
                sock.soTimeout = 0
                return WsClient(sock, input, output)
            } catch (e: Exception) {
                try { sock.close() } catch (_: IOException) { }
                try { plain.close() } catch (_: IOException) { }
                if (e is IOException) throw e
                throw IOException(e.message ?: "Falha ao conectar", e)
            }
        }

        private fun readHeaders(input: InputStream): String {
            val sb = StringBuilder()
            while (sb.length < 8192) {
                val c = input.read()
                if (c < 0) throw EOFException("Conexão encerrada pelo servidor")
                sb.append(c.toChar())
                if (sb.endsWith("\r\n\r\n")) return sb.toString()
            }
            throw IOException("Resposta grande demais")
        }
    }

    fun sendText(text: String) = sendFrame(0x1, text.toByteArray(Charsets.UTF_8))

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        if (closed) return
        try {
            synchronized(writeLock) {
                val out = ByteArrayOutputStream(payload.size + 14)
                out.write(0x80 or opcode)
                when {
                    payload.size < 126 -> out.write(0x80 or payload.size)
                    payload.size < 65536 -> { out.write(0x80 or 126); out.write(payload.size shr 8); out.write(payload.size and 0xFF) }
                    else -> {
                        out.write(0x80 or 127)
                        for (shift in 56 downTo 0 step 8) out.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
                    }
                }
                val mask = ByteArray(4).also { random.nextBytes(it) }
                out.write(mask)
                val masked = ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i and 3].toInt()).toByte() }
                out.write(masked)
                output.write(out.toByteArray())
                output.flush()
            }
        } catch (_: IOException) {
            closeNow()
        }
    }

    /** Lê mensagens em uma thread própria. [onClosed] é chamado uma única vez ao terminar. */
    fun startReading(onText: (String) -> Unit, onClosed: () -> Unit) {
        val t = Thread({
            try {
                var partial: ByteArrayOutputStream? = null
                while (!closed) {
                    val b0 = din.readUnsignedByte()
                    val b1 = din.readUnsignedByte()
                    val fin = b0 and 0x80 != 0
                    val op = b0 and 0x0F
                    val masked = b1 and 0x80 != 0
                    var len = (b1 and 0x7F).toLong()
                    if (len == 126L) len = din.readUnsignedShort().toLong()
                    else if (len == 127L) len = din.readLong()
                    if (len < 0 || len > MAX_MESSAGE) throw IOException("Mensagem grande demais")
                    val mask = if (masked) ByteArray(4).also { din.readFully(it) } else null
                    val payload = ByteArray(len.toInt())
                    din.readFully(payload)
                    if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                    when (op) {
                        0x8 -> { sendFrame(0x8, ByteArray(0)); break }
                        0x9 -> sendFrame(0xA, payload)
                        0xA -> {}
                        0x1, 0x0 -> {
                            if (op == 0x1) partial = ByteArrayOutputStream()
                            val acc = partial ?: throw IOException("Fragmento inesperado")
                            acc.write(payload)
                            if (acc.size() > MAX_MESSAGE) throw IOException("Mensagem grande demais")
                            if (fin) {
                                partial = null
                                onText(String(acc.toByteArray(), Charsets.UTF_8))
                            }
                        }
                        else -> throw IOException("Tipo de mensagem não suportado")
                    }
                }
            } catch (_: IOException) {
            } finally {
                closeNow()
                onClosed()
            }
        }, "ws-reader")
        t.isDaemon = true
        t.start()
    }

    /** Fecha avisando o servidor. */
    fun close() {
        if (closed) return
        sendFrame(0x8, byteArrayOf(0x03, 0xE8.toByte()))
        closeNow()
    }

    fun closeNow() {
        if (closed) return
        closed = true
        try { socket.close() } catch (_: IOException) { }
    }
}

/** InputStream alimentado por outra thread (usado para ligar mensagens WebSocket ao LineConnection). */
class PipeInputStream : InputStream() {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val eof = ByteArray(0)
    private var current: ByteArray? = null
    private var pos = 0
    private var ended = false

    fun push(bytes: ByteArray) { queue.offer(bytes) }
    fun endOfStream() { queue.offer(eof) }

    private fun fill(): Boolean {
        if (ended) return false
        while (current == null || pos >= current!!.size) {
            val next = try { queue.take() } catch (_: InterruptedException) { throw InterruptedIOException() }
            if (next === eof) { ended = true; return false }
            current = next
            pos = 0
        }
        return true
    }

    override fun read(): Int {
        if (!fill()) return -1
        return current!![pos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, current!!.size - pos)
        System.arraycopy(current!!, pos, b, off, n)
        pos += n
        return n
    }
}

/** OutputStream que entrega cada linha completa (terminada em '\n') a [onLine]. */
class LineOutputStream(private val onLine: (String) -> Unit) : OutputStream() {
    private val buf = ByteArrayOutputStream()

    @Synchronized
    override fun write(b: Int) {
        if (b == '\n'.code) {
            val line = String(buf.toByteArray(), Charsets.UTF_8)
            buf.reset()
            onLine(line)
        } else buf.write(b)
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) write(b[i].toInt())
    }
}

/** Liga um WsClient a um LineConnection (cada mensagem de texto = uma linha do protocolo do jogo). */
fun WsClient.asLineConnection(): LineConnection {
    val pin = PipeInputStream()
    startReading(
        onText = { pin.push((it + "\n").toByteArray(Charsets.UTF_8)) },
        onClosed = { pin.endOfStream() }
    )
    return LineConnection(pin, LineOutputStream { sendText(it) }) { close() }
}
