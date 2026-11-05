package com.cartasdocaos.game.net

import com.cartasdocaos.game.core.Json
import com.cartasdocaos.game.core.Nick
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** Sala encontrada na rede local. [host] é o IP de quem a criou. */
data class LanRoom(val host: String, val port: Int, val name: String, val players: Int, val locked: Boolean = true)

/** Dados que o anfitrião anuncia. Nunca incluem a senha. */
data class BeaconInfo(val name: String, val gamePort: Int, val players: Int, val open: Boolean, val locked: Boolean = true)

private const val MAGIC = "cartasdocaos"

/**
 * Anfitrião: avisa a cada segundo, por broadcast UDP, que existe uma sala aberta.
 * Assim os outros aparelhos da mesma rede a encontram sem digitar IP.
 */
class LanBeacon(
    private val info: () -> BeaconInfo?,
    private val port: Int = Lan.DISCOVERY_PORT,
    private val targets: () -> List<InetAddress> = { Lan.broadcastAddresses() },
    private val intervalMs: Long = 1000
) : Acceptor {
    @Volatile private var stopped = false
    private var thread: Thread? = null

    fun start() {
        val t = Thread({
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                while (!stopped) {
                    val i = info()
                    if (i != null && i.open) {
                        val bytes = Json.stringify(
                            mapOf("g" to MAGIC, "v" to 1, "name" to i.name, "port" to i.gamePort, "n" to i.players, "lock" to i.locked)
                        ).toByteArray(Charsets.UTF_8)
                        for (target in targets()) {
                            try {
                                socket.send(DatagramPacket(bytes, bytes.size, target, port))
                            } catch (_: IOException) { /* esta interface não aceita; tenta as outras */ }
                        }
                    }
                    Thread.sleep(intervalMs)
                }
            } catch (_: InterruptedException) {
            } catch (_: IOException) {
            } finally {
                socket?.close()
            }
        }, "lan-beacon")
        t.isDaemon = true
        thread = t
        t.start()
    }

    override fun stop() {
        stopped = true
        thread?.interrupt()
    }
}

/** Convidado: escuta os anúncios e mantém a lista de salas abertas (some após [ttlMs] sem aviso). */
class LanScanner(
    private val onChange: (List<LanRoom>) -> Unit,
    private val port: Int = Lan.DISCOVERY_PORT,
    private val ttlMs: Long = 4000
) {
    @Volatile private var stopped = false
    private var socket: DatagramSocket? = null

    /** Lança IOException se a porta não puder ser aberta. */
    fun start() {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        s.soTimeout = 500
        socket = s
        val t = Thread({
            val seen = LinkedHashMap<String, Pair<LanRoom, Long>>()
            var last: List<LanRoom> = emptyList()
            val buf = ByteArray(1024)
            try {
                while (!stopped) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val room = parse(p)
                        if (room != null) seen["${room.host}:${room.port}"] = room to System.currentTimeMillis()
                    } catch (_: SocketTimeoutException) {
                    }
                    val now = System.currentTimeMillis()
                    seen.entries.removeAll { now - it.value.second > ttlMs }
                    val current = seen.values.map { it.first }
                    if (current != last) {
                        last = current
                        onChange(current)
                    }
                }
            } catch (_: IOException) {
            } finally {
                s.close()
            }
        }, "lan-scanner")
        t.isDaemon = true
        t.start()
    }

    fun stop() {
        stopped = true
        try { socket?.close() } catch (_: Exception) { }
    }

    private fun parse(p: DatagramPacket): LanRoom? {
        return try {
            val o = Json.parse(String(p.data, 0, p.length, Charsets.UTF_8)) as? Map<*, *> ?: return null
            if (o["g"] != MAGIC) return null
            val port = (o["port"] as? Number)?.toInt() ?: return null
            val players = (o["n"] as? Number)?.toInt() ?: return null
            if (port !in 1..65535) return null
            val name = Nick.clean((o["name"] as? String ?: return null).take(Nick.MAX))
            if (name.isEmpty()) return null
            LanRoom(p.address.hostAddress ?: return null, port, name, players.coerceIn(0, 99), o["lock"] != false)
        } catch (_: Exception) {
            null
        }
    }
}
