package com.cartasdocaos.game.core

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Canal de comunicação com um jogador (o próprio anfitrião ou uma conexão remota). */
interface Peer {
    fun send(msg: ServerMsg)
    fun close()
}

/**
 * Regras do jogo, executadas apenas no aparelho do anfitrião.
 *
 * Todas as chamadas públicas são enfileiradas em [exec] (uma única thread), então o
 * estado nunca é acessado em paralelo. Os tempos são multiplicados por [msPerSecond]
 * (1000 no jogo real; menor nos testes automáticos).
 *
 * Fluxo de cada rodada: carta preta -> jogadores escolhem a carta branca (tempo do
 * anfitrião) -> cada resposta fica na tela pelo tempo de notas (10 a 30 s), anônima,
 * enquanto os outros jogadores dão nota de 1 a 5 -> resultado da rodada.
 * A pontuação do jogador é a soma das notas que recebeu.
 */
class GameEngine(
    val settings: Settings,
    private val password: String,
    private val deck: Deck,
    private val exec: ScheduledExecutorService,
    private val msPerSecond: Long = 1000L,
    private val rng: Random = Random.Default,
    private val onClosed: () -> Unit = {}
) {
    companion object {
        const val MAX_PLAYERS = Settings.MAX_PLAYERS
        const val MIN_PLAYERS_TO_START = 2
        const val KICKED_REASON = "Você foi removido da sala pelo anfitrião."
        const val HAND_SIZE = 7
        const val RESULT_SECONDS = 7
        const val MAX_AVATAR_CHARS = 14000
        private const val EARLY_ADVANCE_MS = 1200L
    }

    private class Player(val id: Int, val nick: String, val peer: Peer, val isHost: Boolean) {
        var score = 0
        var connected = true
        var submitted = false
        val hand = ArrayList<String>()
        var avatar: String? = null
    }

    private class Answer(val author: Player, val text: String) {
        val ratings = HashMap<Int, Int>() // id do avaliador -> nota
    }

    private enum class Phase { LOBBY, PLAYING, RATING, RESULT, FINISHED, CLOSED }

    private val players = ArrayList<Player>()
    private var nextId = 1
    private var phase = Phase.LOBBY
    private var round = 0
    private var token = 0L
    private var answers = ArrayList<Answer>()
    private var revealIndex = 0
    private var earlyScheduled = false

    // ---------- API pública (thread-safe) ----------

    fun addHost(peer: Peer, nick: String) = post { admit(peer, nick, isHost = true) }

    fun join(peer: Peer, nick: String, pass: String) = post {
        when {
            phase == Phase.CLOSED -> reject(peer, "A sala foi encerrada.")
            phase != Phase.LOBBY -> reject(peer, "A partida já começou.")
            pass != password -> reject(peer, "Senha incorreta.")
            else -> admit(peer, nick, isHost = false)
        }
    }

    fun start(peer: Peer) = post {
        val p = find(peer) ?: return@post
        if (!p.isHost || phase != Phase.LOBBY) return@post
        if (players.count { it.connected } < MIN_PLAYERS_TO_START) return@post
        startRound()
    }

    /** Foto de perfil: só uma vez por jogador, só no lobby e só se o anfitrião permitiu. */
    fun setAvatar(peer: Peer, data: String) = post {
        val p = find(peer) ?: return@post
        if (!settings.avatars || phase != Phase.LOBBY || !p.connected || p.avatar != null) return@post
        if (!validAvatar(data)) return@post
        p.avatar = data
        broadcast(ServerMsg.Avatar(p.id, data))
    }

    fun play(peer: Peer, card: String) = post {
        val p = find(peer) ?: return@post
        if (phase != Phase.PLAYING || p.submitted || !p.connected) return@post
        if (!p.hand.remove(card)) return@post // só vale carta que está na mão do jogador
        p.submitted = true
        answers.add(Answer(p, card))
        sendProgress()
        if (allSubmitted()) endPlaying()
    }

    fun rate(peer: Peer, stars: Int) = post {
        val p = find(peer) ?: return@post
        if (phase != Phase.RATING || !p.connected || stars !in 1..5) return@post
        val a = answers[revealIndex]
        if (a.author === p) return@post // ninguém avalia a própria resposta
        a.ratings[p.id] = stars
        checkAllRated()
    }

    /** Anfitrião remove um jogador da sala; só vale antes da partida começar. */
    fun kick(peer: Peer, playerId: Int) = post {
        val h = find(peer) ?: return@post
        if (!h.isHost || phase != Phase.LOBBY) return@post
        val p = players.firstOrNull { it.id == playerId && it.connected } ?: return@post
        if (p.isHost) return@post
        p.connected = false
        send(p, ServerMsg.Closed(KICKED_REASON))
        p.peer.close()
        players.remove(p)
        broadcastLobby()
    }

    fun leave(peer: Peer) = post {
        val p = find(peer) ?: return@post
        if (!p.connected) return@post
        p.connected = false
        p.peer.close()
        if (p.isHost) {
            closeAll("O anfitrião encerrou a sala.")
            return@post
        }
        when (phase) {
            Phase.LOBBY -> { players.remove(p); broadcastLobby() }
            Phase.CLOSED -> Unit
            Phase.FINISHED -> Unit
            else -> {
                if (players.count { it.connected } < MIN_PLAYERS_TO_START) {
                    finish()
                } else if (phase == Phase.PLAYING) {
                    if (allSubmitted()) endPlaying()
                } else if (phase == Phase.RATING) {
                    checkAllRated()
                }
            }
        }
    }

    // ---------- lobby ----------

    private fun admit(peer: Peer, rawNick: String, isHost: Boolean) {
        val err = Nick.validate(rawNick)
        if (err != null) return reject(peer, err)
        val nick = Nick.clean(rawNick)
        if (players.any { it.connected && it.nick.equals(nick, ignoreCase = true) }) {
            return reject(peer, "Já existe um jogador com o apelido \"$nick\" na sala.")
        }
        if (players.count { it.connected } >= minOf(settings.maxPlayers, MAX_PLAYERS)) return reject(peer, "A sala está cheia.")
        val p = Player(nextId++, nick, peer, isHost)
        players.add(p)
        send(p, ServerMsg.Welcome(p.id))
        broadcastLobby()
        for (o in players) {
            val a = o.avatar
            if (a != null && o.connected) send(p, ServerMsg.Avatar(o.id, a))
        }
    }

    /** Aceita só um JPEG pequeno em Base64 (evita lixo e mensagens enormes). */
    private fun validAvatar(data: String): Boolean {
        if (data.length !in 100..MAX_AVATAR_CHARS) return false
        if (!data.all { it.isLetterOrDigit() && it.code < 128 || it == '+' || it == '/' || it == '=' }) return false
        return try {
            val b = java.util.Base64.getDecoder().decode(data)
            b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun reject(peer: Peer, reason: String) {
        try { peer.send(ServerMsg.Reject(reason)) } catch (_: Exception) { }
        peer.close()
    }

    private fun broadcastLobby() {
        val info = players.filter { it.connected }.map { PlayerInfo(it.id, it.nick, it.isHost, true) }
        broadcast(ServerMsg.Lobby(info, settings))
    }

    // ---------- rodada ----------

    private fun startRound() {
        round++
        val black = deck.nextBlack().text
        answers = ArrayList()
        revealIndex = 0
        for (p in players) {
            p.submitted = false
            if (!p.connected) continue
            while (p.hand.size < HAND_SIZE) p.hand.add(deck.drawWhite() ?: break)
        }
        phase = Phase.PLAYING
        val t = ++token
        for (p in players) {
            if (p.connected) {
                send(p, ServerMsg.RoundStart(round, settings.rounds, black, settings.playSeconds, ArrayList(p.hand)))
            }
        }
        sendProgress()
        schedule(settings.playSeconds * msPerSecond) { if (token == t && phase == Phase.PLAYING) endPlaying() }
    }

    private fun sendProgress() {
        val connected = players.filter { it.connected }
        broadcast(ServerMsg.PlayProgress(connected.count { it.submitted }, connected.size))
    }

    private fun allSubmitted() = players.filter { it.connected }.all { it.submitted }

    private fun endPlaying() {
        token++
        if (answers.isEmpty()) {
            showResult()
        } else {
            answers.shuffle(rng) // a ordem não revela quem respondeu primeiro
            revealIndex = 0
            startReveal()
        }
    }

    private fun startReveal() {
        phase = Phase.RATING
        earlyScheduled = false
        val t = ++token
        val a = answers[revealIndex]
        for (p in players) {
            if (p.connected) {
                send(p, ServerMsg.Reveal(revealIndex + 1, answers.size, a.text, settings.ratingSeconds, p === a.author))
            }
        }
        schedule(settings.ratingSeconds * msPerSecond) { if (token == t && phase == Phase.RATING) endReveal() }
        checkAllRated()
    }

    /** Se todos que podem avaliar já avaliaram, passa para a próxima resposta em instantes. */
    private fun checkAllRated() {
        if (phase != Phase.RATING || earlyScheduled) return
        val a = answers[revealIndex]
        val voters = players.filter { it.connected && it !== a.author }
        if (voters.all { a.ratings.containsKey(it.id) }) {
            earlyScheduled = true
            val t = token
            schedule(EARLY_ADVANCE_MS * msPerSecond / 1000) { if (token == t && phase == Phase.RATING) endReveal() }
        }
    }

    private fun endReveal() {
        token++
        revealIndex++
        if (revealIndex < answers.size) startReveal() else showResult()
    }

    private fun showResult() {
        phase = Phase.RESULT
        val entries = answers.map { a ->
            val pts = a.ratings.values.sum()
            a.author.score += pts
            ResultEntry(a.author.nick, a.text, pts, a.ratings.size)
        }
        val scores = scoreboard()
        broadcast(ServerMsg.RoundResult(round, entries, scores, RESULT_SECONDS))
        val t = ++token
        schedule(RESULT_SECONDS * msPerSecond) {
            if (token == t && phase == Phase.RESULT) {
                if (round < settings.rounds) startRound() else finish()
            }
        }
    }

    private fun finish() {
        token++
        phase = Phase.FINISHED
        broadcast(ServerMsg.Final(scoreboard()))
    }

    /** Ranking com empates dividindo a posição (1, 1, 3...). */
    private fun scoreboard(): List<ScoreEntry> {
        val sorted = players.sortedByDescending { it.score } // estável: empate mantém ordem de entrada
        var rank = 0
        var lastScore = Int.MIN_VALUE
        return sorted.mapIndexed { i, p ->
            if (p.score != lastScore) { rank = i + 1; lastScore = p.score }
            ScoreEntry(rank, p.id, p.nick, p.score, !p.connected)
        }
    }

    private fun closeAll(reason: String) {
        token++
        phase = Phase.CLOSED
        for (p in players) {
            if (p.connected && !p.isHost) {
                try { p.peer.send(ServerMsg.Closed(reason)) } catch (_: Exception) { }
            }
            p.connected = false
            p.peer.close()
        }
        onClosed()
    }

    // ---------- utilidades ----------

    private fun find(peer: Peer): Player? = players.firstOrNull { it.peer === peer }

    private fun send(p: Player, m: ServerMsg) {
        try { p.peer.send(m) } catch (_: Exception) { }
    }

    private fun broadcast(m: ServerMsg) {
        for (p in players) if (p.connected) send(p, m)
    }

    private fun schedule(delayMs: Long, block: () -> Unit) {
        try {
            exec.schedule({ guard(block) }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) { }
    }

    private fun post(block: () -> Unit) {
        try {
            exec.execute { guard(block) }
        } catch (_: RejectedExecutionException) { }
    }

    private fun guard(block: () -> Unit) {
        try { block() } catch (t: Throwable) { t.printStackTrace() }
    }
}
