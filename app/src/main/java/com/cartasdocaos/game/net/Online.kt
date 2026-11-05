package com.cartasdocaos.game.net

import com.cartasdocaos.game.core.Json
import com.cartasdocaos.game.core.Nick
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Sala pública listada no servidor. [locked] = exige senha. */
data class OnlineRoom(val id: String, val name: String, val players: Int, val locked: Boolean)

object Online {
    /** Aceita "https://x", "wss://x", "x.com" ou "http://localhost:8080"; devolve (httpBase, wsBase) sem barra final. */
    fun normalize(input: String): Pair<String, String>? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty() || s.contains(' ')) return null
        if (!s.contains("://")) s = "https://$s"
        val scheme = s.substringBefore("://").lowercase()
        val rest = s.substringAfter("://")
        if (rest.isEmpty() || rest.contains('?') || rest.contains('#')) return null
        return when (scheme) {
            "https", "wss" -> "https://$rest" to "wss://$rest"
            "http", "ws" -> "http://$rest" to "ws://$rest"
            else -> null
        }
    }

    /** Lista as salas abertas (bloqueante). */
    fun listRooms(server: String): List<OnlineRoom> {
        val (http, _) = normalize(server) ?: throw IOException("Endereço do servidor inválido")
        val conn = URL("$http/rooms").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            if (conn.responseCode != 200) throw IOException("Servidor respondeu ${conn.responseCode}")
            val body = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            val arr = try { Json.parse(body) as? List<*> } catch (e: IllegalArgumentException) { null }
                ?: throw IOException("Resposta inválida do servidor")
            return arr.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                val id = m["id"] as? String ?: return@mapNotNull null
                val name = Nick.clean((m["name"] as? String ?: return@mapNotNull null).take(Nick.MAX))
                if (!Regex("^[A-Z0-9]{4}$").matches(id) || name.isEmpty()) return@mapNotNull null
                OnlineRoom(id, name, ((m["players"] as? Number)?.toInt() ?: 0).coerceIn(0, 99), m["locked"] == true)
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Entra em uma sala pelo servidor (bloqueante). */
    fun join(server: String, roomId: String): LineConnection {
        val (_, ws) = normalize(server) ?: throw IOException("Endereço do servidor inválido")
        return WsClient.connect("$ws/join/$roomId").asLineConnection()
    }
}

/**
 * Anfitrião online: mantém uma conexão com o servidor, registra a sala na lista pública e
 * transforma cada convidado em um [LineConnection] igual aos de Wi-Fi/Bluetooth.
 */
class RelayHost(
    private val server: String,
    private val nick: String,
    private val locked: Boolean,
    private val info: () -> BeaconInfo?,
    private val onConnection: (LineConnection) -> Unit,
    private val onLost: () -> Unit
) : Acceptor {
    private var ws: WsClient? = null
    @Volatile private var stopped = false
    private val channels = ConcurrentHashMap<Int, PipeInputStream>()

    /** Registra a sala e devolve o código (ex.: "KX7P"). Lança IOException se falhar. */
    fun start(): String {
        val (_, wsBase) = Online.normalize(server) ?: throw IOException("Endereço do servidor inválido")
        val client = WsClient.connect("$wsBase/host")
        ws = client
        val opened = CountDownLatch(1)
        var roomId: String? = null
        client.startReading(
            onText = { text -> handle(text) { roomId = it; opened.countDown() } },
            onClosed = {
                for (p in channels.values) p.endOfStream()
                channels.clear()
                opened.countDown()
                if (!stopped) onLost()
            }
        )
        client.sendText(Json.stringify(mapOf("t" to "open", "name" to nick, "locked" to locked)))
        if (!opened.await(8, TimeUnit.SECONDS) || roomId == null) {
            client.closeNow()
            throw IOException("O servidor não criou a sala")
        }
        startInfoLoop(client)
        return roomId!!
    }

    private fun handle(text: String, onOpened: (String) -> Unit) {
        val m = try { Json.parse(text) as? Map<*, *> } catch (_: IllegalArgumentException) { null } ?: return
        val client = ws ?: return
        when (m["t"]) {
            "opened" -> (m["room"] as? String)?.let(onOpened)
            "join" -> {
                val cid = (m["cid"] as? Number)?.toInt() ?: return
                val pin = PipeInputStream()
                channels[cid] = pin
                val conn = LineConnection(
                    pin,
                    LineOutputStream { line -> client.sendText(Json.stringify(mapOf("t" to "data", "cid" to cid, "d" to line))) }
                ) {
                    channels.remove(cid)
                    client.sendText(Json.stringify(mapOf("t" to "kick", "cid" to cid)))
                }
                try { onConnection(conn) } catch (_: Exception) { conn.closeNow() }
            }
            "data" -> {
                val cid = (m["cid"] as? Number)?.toInt() ?: return
                val d = m["d"] as? String ?: return
                channels[cid]?.push((d + "\n").toByteArray(Charsets.UTF_8))
            }
            "left" -> {
                val cid = (m["cid"] as? Number)?.toInt() ?: return
                channels.remove(cid)?.endOfStream()
            }
        }
    }

    private fun startInfoLoop(client: WsClient) {
        val t = Thread({
            try {
                while (!stopped) {
                    val i = info()
                    if (i != null) {
                        client.sendText(Json.stringify(mapOf("t" to "info", "players" to i.players, "open" to i.open)))
                    }
                    Thread.sleep(2000)
                }
            } catch (_: InterruptedException) {
            }
        }, "relay-info")
        t.isDaemon = true
        t.start()
    }

    override fun stop() {
        stopped = true
        ws?.close()
    }
}
