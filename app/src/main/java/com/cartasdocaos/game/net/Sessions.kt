package com.cartasdocaos.game.net

import com.cartasdocaos.game.core.CardData
import com.cartasdocaos.game.core.ClientModel
import com.cartasdocaos.game.core.ClientMsg
import com.cartasdocaos.game.core.Deck
import com.cartasdocaos.game.core.GameEngine
import com.cartasdocaos.game.core.Peer
import com.cartasdocaos.game.core.ServerMsg
import com.cartasdocaos.game.core.Settings
import com.cartasdocaos.game.core.UiState
import com.cartasdocaos.game.core.Wire
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Ponto de contato entre a interface e o jogo, seja como anfitrião ou como convidado. */
interface Session {
    val isHost: Boolean
    val state: UiState

    /** Chamado em thread de fundo a cada mudança de estado. */
    var listener: ((UiState) -> Unit)?

    fun start()
    fun play(card: String)
    fun rate(stars: Int)
    fun leave()

    /** Anfitrião: remove um jogador antes da partida (convidados ignoram). */
    fun kick(playerId: Int)
}

/** Sessão do anfitrião: roda o motor do jogo e aceita conexões por Wi-Fi e/ou Bluetooth. */
class HostSession(
    settings: Settings,
    nick: String,
    password: String,
    data: CardData,
    msPerSecond: Long = 1000L,
    avatar: String? = null
) : Session {
    private val model = ClientModel()
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "game-engine").also { it.isDaemon = true }
    }
    private val acceptors = ArrayList<Acceptor>()
    @Volatile private var left = false

    override val isHost = true
    override var listener: ((UiState) -> Unit)? = null
    override val state: UiState get() = model.current

    private val localPeer = object : Peer {
        override fun send(msg: ServerMsg) = publish(model.apply(msg))
        override fun close() {}
    }

    private val engine = GameEngine(
        settings, password, Deck(data, settings.categories), exec, msPerSecond,
        onClosed = { shutdown() }
    )

    init {
        engine.addHost(localPeer, nick)
        if (avatar != null) engine.setAvatar(localPeer, avatar)
    }

    private fun publish(s: UiState) {
        listener?.invoke(s)
    }

    @Synchronized
    fun addAcceptor(a: Acceptor) {
        if (left) a.stop() else acceptors.add(a)
    }

    /** Recebe uma conexão nova (de LanServer ou do servidor Bluetooth). */
    fun attach(conn: LineConnection) {
        val peer = object : Peer {
            override fun send(msg: ServerMsg) = conn.send(Wire.encode(msg))
            override fun close() = conn.close()
        }
        val joined = AtomicBoolean(false)
        conn.startReading(
            onLine = { line ->
                try {
                    when (val m = Wire.decodeClient(line)) {
                        is ClientMsg.Hello -> if (joined.compareAndSet(false, true)) engine.join(peer, m.nick, m.password)
                        is ClientMsg.Play -> engine.play(peer, m.card)
                        is ClientMsg.Rate -> engine.rate(peer, m.stars)
                        is ClientMsg.Avatar -> engine.setAvatar(peer, m.data)
                        is ClientMsg.Kick -> engine.kick(peer, m.playerId)
                        ClientMsg.Leave -> engine.leave(peer)
                    }
                } catch (_: IllegalArgumentException) {
                    conn.close() // mensagem malformada
                }
            },
            onClosed = { engine.leave(peer) }
        )
        // Quem conecta e não se identifica em 10 s é desconectado.
        try {
            exec.schedule({ if (!joined.get()) conn.close() }, 10, TimeUnit.SECONDS)
        } catch (_: Exception) { conn.close() }
    }

    override fun start() = engine.start(localPeer)

    override fun play(card: String) {
        publish(model.markPlayed(card))
        engine.play(localPeer, card)
    }

    override fun rate(stars: Int) {
        publish(model.markRated(stars))
        engine.rate(localPeer, stars)
    }

    override fun kick(playerId: Int) = engine.kick(localPeer, playerId)

    override fun leave() {
        engine.leave(localPeer) // anfitrião saindo encerra a sala para todos
        shutdown()
    }

    @Synchronized
    private fun shutdown() {
        if (left) return
        left = true
        acceptors.forEach { it.stop() }
        acceptors.clear()
        // Dá tempo de a mensagem "sala encerrada" sair antes de parar o motor.
        try {
            exec.schedule({ exec.shutdown() }, 500, TimeUnit.MILLISECONDS)
        } catch (_: Exception) { }
    }
}

/** Sessão de convidado: fala com o anfitrião por uma conexão já aberta. */
class RemoteSession(
    private val conn: LineConnection,
    nick: String,
    password: String,
    avatar: String? = null
) : Session {
    private val model = ClientModel()

    override val isHost = false
    override var listener: ((UiState) -> Unit)? = null
    override val state: UiState get() = model.current

    private fun publish(s: UiState) {
        listener?.invoke(s)
    }

    init {
        conn.startReading(
            onLine = { line ->
                try {
                    publish(model.apply(Wire.decodeServer(line)))
                } catch (_: IllegalArgumentException) {
                    // linha inválida do anfitrião: ignora
                }
            },
            onClosed = { publish(model.connectionLost("A conexão com o anfitrião foi perdida.")) }
        )
        conn.send(Wire.encode(ClientMsg.Hello(nick, password)))
        if (avatar != null) conn.send(Wire.encode(ClientMsg.Avatar(avatar)))
    }

    override fun start() {}

    override fun play(card: String) {
        publish(model.markPlayed(card))
        conn.send(Wire.encode(ClientMsg.Play(card)))
    }

    override fun rate(stars: Int) {
        publish(model.markRated(stars))
        conn.send(Wire.encode(ClientMsg.Rate(stars)))
    }

    override fun kick(playerId: Int) {}

    override fun leave() {
        conn.send(Wire.encode(ClientMsg.Leave))
        conn.close()
    }
}
