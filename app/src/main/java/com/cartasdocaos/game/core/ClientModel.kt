package com.cartasdocaos.game.core

enum class Stage { CONNECTING, LOBBY, PLAYING, RATING, RESULT, FINAL, CLOSED }

/** Tudo o que a interface precisa para desenhar a tela do jogador. */
data class UiState(
    val stage: Stage = Stage.CONNECTING,
    val selfId: Int = -1,
    val players: List<PlayerInfo> = emptyList(),
    val settings: Settings? = null,
    val round: Int = 0,
    val totalRounds: Int = 0,
    val black: String = "",
    val hand: List<String> = emptyList(),
    val submitted: Boolean = false,
    val submittedCount: Int = 0,
    val playerCount: Int = 0,
    /** Momento (System.currentTimeMillis) em que a fase atual chegou a este aparelho. */
    val phaseStartMs: Long = 0,
    val phaseSeconds: Int = 0,
    val revealIndex: Int = 0,
    val revealCount: Int = 0,
    val revealText: String = "",
    val revealMine: Boolean = false,
    val myRating: Int = 0,
    val resultEntries: List<ResultEntry> = emptyList(),
    val scores: List<ScoreEntry> = emptyList(),
    val closedReason: String = "",
    /** Fotos de perfil (JPEG em Base64) por id de jogador. */
    val avatars: Map<Int, String> = emptyMap(),
    /** Aumenta a cada mudança de tela; a interface só redesenha tudo quando muda. */
    val screenKey: Long = 0
) {
    val isHost: Boolean get() = players.firstOrNull { it.id == selfId }?.isHost == true
}

/** Aplica as mensagens do anfitrião ao estado local. Thread-safe. */
class ClientModel(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private var state = UiState()
    private var keyCounter = 0L

    val current: UiState @Synchronized get() = state

    @Synchronized
    fun apply(m: ServerMsg): UiState {
        val s = state
        // Depois do ranking final ou de um encerramento, nada mais muda a tela.
        if (s.stage == Stage.CLOSED) return s
        state = when (m) {
            is ServerMsg.Welcome -> s.copy(selfId = m.selfId)
            is ServerMsg.Avatar -> s.copy(avatars = s.avatars + (m.playerId to m.data))
            is ServerMsg.Reject -> s.copy(stage = Stage.CLOSED, closedReason = m.reason, screenKey = nextKey())
            is ServerMsg.Lobby -> {
                if (s.stage == Stage.CONNECTING || s.stage == Stage.LOBBY) {
                    val key = if (s.stage == Stage.CONNECTING) nextKey() else s.screenKey
                    s.copy(stage = Stage.LOBBY, players = m.players, settings = m.settings, screenKey = key)
                } else s
            }
            is ServerMsg.RoundStart -> s.copy(
                stage = Stage.PLAYING, round = m.round, totalRounds = m.total, black = m.black, hand = m.hand,
                submitted = false, submittedCount = 0, phaseStartMs = clock(), phaseSeconds = m.playSeconds,
                myRating = 0, screenKey = nextKey()
            )
            is ServerMsg.PlayProgress ->
                if (s.stage == Stage.PLAYING) s.copy(submittedCount = m.submitted, playerCount = m.total) else s
            is ServerMsg.Reveal -> s.copy(
                stage = Stage.RATING, revealIndex = m.index, revealCount = m.count, revealText = m.text,
                revealMine = m.mine, myRating = 0, phaseStartMs = clock(), phaseSeconds = m.seconds,
                screenKey = nextKey()
            )
            is ServerMsg.RoundResult -> s.copy(
                stage = Stage.RESULT, round = m.round, resultEntries = m.entries, scores = m.scores,
                phaseStartMs = clock(), phaseSeconds = m.seconds, screenKey = nextKey()
            )
            is ServerMsg.Final -> s.copy(stage = Stage.FINAL, scores = m.ranking, screenKey = nextKey())
            is ServerMsg.Closed ->
                if (s.stage == Stage.FINAL) s // o ranking final continua visível
                else s.copy(stage = Stage.CLOSED, closedReason = m.reason, screenKey = nextKey())
        }
        return state
    }

    /** Marca localmente que a carta foi jogada (a interface responde na hora). */
    @Synchronized
    fun markPlayed(card: String): UiState {
        state = state.copy(submitted = true, hand = state.hand - card)
        return state
    }

    @Synchronized
    fun markRated(stars: Int): UiState {
        state = state.copy(myRating = stars)
        return state
    }

    /** A conexão caiu sem aviso do anfitrião. */
    @Synchronized
    fun connectionLost(reason: String): UiState {
        if (state.stage != Stage.FINAL && state.stage != Stage.CLOSED) {
            state = state.copy(stage = Stage.CLOSED, closedReason = reason, screenKey = nextKey())
        }
        return state
    }

    private fun nextKey() = ++keyCounter
}
