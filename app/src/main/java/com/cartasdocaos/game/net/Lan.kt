package com.cartasdocaos.game.net

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/** Algo que aceita conexões de jogadores (rede local ou Bluetooth). */
interface Acceptor {
    fun stop()
}

object Lan {
    const val PORT = 45821
    const val DISCOVERY_PORT = 45822

    fun connect(host: String, port: Int = PORT, timeoutMs: Int = 6000): LineConnection {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) { }
            throw e
        }
        return wrap(s)
    }

    internal fun wrap(s: Socket): LineConnection {
        s.tcpNoDelay = true
        s.keepAlive = true
        return LineConnection(s.getInputStream(), s.getOutputStream()) { s.close() }
    }

    /** Endereços de broadcast das redes deste aparelho (Wi-Fi, hotspot...), mais o broadcast geral. */
    fun broadcastAddresses(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            if (ifs != null) {
                for (nif in ifs) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (ia in nif.interfaceAddresses) {
                        val b = ia.broadcast
                        if (b != null) out.add(b)
                    }
                }
            }
        } catch (_: Exception) { }
        try { out.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) { }
        return out.toList()
    }

    /** Endereços IPv4 privados deste aparelho (Wi-Fi ou ponto de acesso), mais prováveis primeiro. */
    fun localAddresses(): List<String> {
        val found = ArrayList<Pair<Int, String>>()
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in ifs) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                val priority = when {
                    name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") -> 0
                    name.startsWith("eth") -> 1
                    else -> 2
                }
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) found.add(priority to addr.hostAddress!!)
                }
            }
        } catch (_: Exception) { }
        return found.sortedBy { it.first }.map { it.second }
    }
}

class LanServer(
    private val port: Int,
    private val onConnection: (LineConnection) -> Unit
) : Acceptor {
    private var server: ServerSocket? = null
    @Volatile private var stopped = false

    /** Abre a porta; lança IOException se não conseguir (porta em uso, sem rede...). */
    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        val t = Thread({
            while (!stopped) {
                val sock = try { ss.accept() } catch (_: IOException) { break }
                try {
                    onConnection(Lan.wrap(sock))
                } catch (_: Exception) {
                    try { sock.close() } catch (_: IOException) { }
                }
            }
        }, "lan-accept")
        t.isDaemon = true
        t.start()
    }

    override fun stop() {
        stopped = true
        try { server?.close() } catch (_: IOException) { }
    }
}
