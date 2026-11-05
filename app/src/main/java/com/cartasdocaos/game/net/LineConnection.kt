package com.cartasdocaos.game.net

import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * Conexão que troca mensagens de uma linha cada (JSON + '\n'). Funciona igual sobre
 * socket TCP (rede local) e socket RFCOMM (Bluetooth), pois ambos oferecem streams.
 */
class LineConnection(
    input: InputStream,
    private val output: OutputStream,
    private val closer: () -> Unit
) {
    companion object {
        private const val MAX_LINE = 64 * 1024
    }

    private val reader = InputStreamReader(input, Charsets.UTF_8).buffered()
    private val queue = LinkedBlockingQueue<String>()
    private val stopSignal = String() // sentinela: identidade própria, nunca igual a uma mensagem enviada
    @Volatile private var closing = false
    @Volatile private var closed = false

    init {
        // Toda escrita acontece em uma thread própria: o Android proíbe rede na thread principal.
        val w = Thread({
            try {
                while (true) {
                    val line = queue.take()
                    if (line === stopSignal) break
                    output.write((line + "\n").toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            } finally {
                closeNow()
            }
        }, "line-writer")
        w.isDaemon = true
        w.start()
    }

    /** Enfileira uma linha para envio (não bloqueia). */
    fun send(line: String) {
        if (closing) return
        queue.offer(line)
    }

    /** Inicia a thread de leitura. [onClosed] é chamado uma única vez quando a conexão termina. */
    fun startReading(onLine: (String) -> Unit, onClosed: () -> Unit) {
        val t = Thread({
            try {
                val sb = StringBuilder()
                while (!closed) {
                    val c = reader.read()
                    if (c < 0) break
                    if (c == '\n'.code) {
                        val line = sb.toString()
                        sb.setLength(0)
                        if (line.isNotBlank()) onLine(line)
                    } else {
                        sb.append(c.toChar())
                        if (sb.length > MAX_LINE) break // mensagem absurda: derruba a conexão
                    }
                }
            } catch (_: IOException) {
            } finally {
                closeNow()
                onClosed()
            }
        }, "line-reader")
        t.isDaemon = true
        t.start()
    }

    /** Encerra depois de enviar tudo o que já está na fila (ex.: "senha incorreta", "saí"). */
    fun close() {
        if (closing) return
        closing = true
        queue.offer(stopSignal)
    }

    /** Encerra imediatamente. */
    fun closeNow() {
        closing = true
        if (closed) return
        closed = true
        queue.offer(stopSignal)
        try { closer() } catch (_: Exception) { }
    }
}
