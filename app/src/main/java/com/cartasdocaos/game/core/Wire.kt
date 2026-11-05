package com.cartasdocaos.game.core

/** Mensagens enviadas pelo cliente ao anfitrião. */
sealed class ClientMsg {
    data class Hello(val nick: String, val password: String) : ClientMsg()
    data class Play(val card: String) : ClientMsg()
    data class Rate(val stars: Int) : ClientMsg()
    /** Foto de perfil: JPEG pequeno em Base64. */
    data class Avatar(val data: String) : ClientMsg()
    /** Só o anfitrião, só antes da partida: remove um jogador da sala. */
    data class Kick(val playerId: Int) : ClientMsg()
    object Leave : ClientMsg()
}

/** Mensagens enviadas pelo anfitrião aos clientes. */
sealed class ServerMsg {
    data class Welcome(val selfId: Int) : ServerMsg()
    data class Reject(val reason: String) : ServerMsg()
    data class Lobby(val players: List<PlayerInfo>, val settings: Settings) : ServerMsg()
    data class RoundStart(
        val round: Int, val total: Int, val black: String, val playSeconds: Int, val hand: List<String>
    ) : ServerMsg()
    data class PlayProgress(val submitted: Int, val total: Int) : ServerMsg()
    data class Reveal(val index: Int, val count: Int, val text: String, val seconds: Int, val mine: Boolean) : ServerMsg()
    data class RoundResult(
        val round: Int, val entries: List<ResultEntry>, val scores: List<ScoreEntry>, val seconds: Int
    ) : ServerMsg()
    data class Final(val ranking: List<ScoreEntry>) : ServerMsg()
    data class Closed(val reason: String) : ServerMsg()
    data class Avatar(val playerId: Int, val data: String) : ServerMsg()
}

/** Codificação das mensagens em uma linha de JSON. */
object Wire {
    fun encode(m: ClientMsg): String = Json.stringify(
        when (m) {
            is ClientMsg.Hello -> mapOf("t" to "hello", "nick" to m.nick, "pass" to m.password)
            is ClientMsg.Play -> mapOf("t" to "play", "card" to m.card)
            is ClientMsg.Rate -> mapOf("t" to "rate", "stars" to m.stars)
            is ClientMsg.Avatar -> mapOf("t" to "avatar", "d" to m.data)
            is ClientMsg.Kick -> mapOf("t" to "kick", "id" to m.playerId)
            ClientMsg.Leave -> mapOf("t" to "leave")
        }
    )

    fun decodeClient(line: String): ClientMsg {
        val o = obj(Json.parse(line))
        return when (str(o, "t")) {
            "hello" -> ClientMsg.Hello(str(o, "nick"), str(o, "pass"))
            "play" -> ClientMsg.Play(str(o, "card"))
            "rate" -> ClientMsg.Rate(int(o, "stars"))
            "avatar" -> ClientMsg.Avatar(str(o, "d"))
            "kick" -> ClientMsg.Kick(int(o, "id"))
            "leave" -> ClientMsg.Leave
            else -> throw IllegalArgumentException("Mensagem de cliente desconhecida")
        }
    }

    fun encode(m: ServerMsg): String = Json.stringify(
        when (m) {
            is ServerMsg.Welcome -> mapOf("t" to "welcome", "id" to m.selfId)
            is ServerMsg.Reject -> mapOf("t" to "reject", "reason" to m.reason)
            is ServerMsg.Lobby -> mapOf(
                "t" to "lobby",
                "players" to m.players.map {
                    mapOf("id" to it.id, "nick" to it.nick, "host" to it.isHost, "on" to it.connected)
                },
                "settings" to settingsToMap(m.settings)
            )
            is ServerMsg.RoundStart -> mapOf(
                "t" to "round", "round" to m.round, "total" to m.total, "black" to m.black,
                "secs" to m.playSeconds, "hand" to m.hand
            )
            is ServerMsg.PlayProgress -> mapOf("t" to "progress", "done" to m.submitted, "total" to m.total)
            is ServerMsg.Reveal -> mapOf(
                "t" to "reveal", "i" to m.index, "n" to m.count, "text" to m.text,
                "secs" to m.seconds, "mine" to m.mine
            )
            is ServerMsg.RoundResult -> mapOf(
                "t" to "result", "round" to m.round, "secs" to m.seconds,
                "entries" to m.entries.map {
                    mapOf("nick" to it.nick, "text" to it.text, "pts" to it.points, "votes" to it.votes)
                },
                "scores" to m.scores.map(::scoreToMap)
            )
            is ServerMsg.Final -> mapOf("t" to "final", "ranking" to m.ranking.map(::scoreToMap))
            is ServerMsg.Closed -> mapOf("t" to "closed", "reason" to m.reason)
            is ServerMsg.Avatar -> mapOf("t" to "avatar", "id" to m.playerId, "d" to m.data)
        }
    )

    fun decodeServer(line: String): ServerMsg {
        val o = obj(Json.parse(line))
        return when (str(o, "t")) {
            "welcome" -> ServerMsg.Welcome(int(o, "id"))
            "reject" -> ServerMsg.Reject(str(o, "reason"))
            "lobby" -> ServerMsg.Lobby(
                list(o, "players").map {
                    val p = obj(it)
                    PlayerInfo(int(p, "id"), str(p, "nick"), bool(p, "host"), bool(p, "on"))
                },
                settingsFromMap(obj(o["settings"]))
            )
            "round" -> ServerMsg.RoundStart(
                int(o, "round"), int(o, "total"), str(o, "black"), int(o, "secs"),
                list(o, "hand").map { it as String }
            )
            "progress" -> ServerMsg.PlayProgress(int(o, "done"), int(o, "total"))
            "reveal" -> ServerMsg.Reveal(int(o, "i"), int(o, "n"), str(o, "text"), int(o, "secs"), bool(o, "mine"))
            "result" -> ServerMsg.RoundResult(
                int(o, "round"),
                list(o, "entries").map {
                    val e = obj(it)
                    ResultEntry(str(e, "nick"), str(e, "text"), int(e, "pts"), int(e, "votes"))
                },
                list(o, "scores").map { scoreFromMap(obj(it)) },
                int(o, "secs")
            )
            "final" -> ServerMsg.Final(list(o, "ranking").map { scoreFromMap(obj(it)) })
            "closed" -> ServerMsg.Closed(str(o, "reason"))
            "avatar" -> ServerMsg.Avatar(int(o, "id"), str(o, "d"))
            else -> throw IllegalArgumentException("Mensagem de servidor desconhecida")
        }
    }

    private fun settingsToMap(s: Settings) = mapOf(
        "cats" to s.categories.map { it.key }, "rounds" to s.rounds,
        "play" to s.playSeconds, "rate" to s.ratingSeconds, "avatars" to s.avatars, "max" to s.maxPlayers
    )

    private fun settingsFromMap(o: Map<*, *>) = Settings(
        list(o, "cats").mapNotNull { Category.fromKey(it as String) }.toSet(),
        int(o, "rounds"), int(o, "play"), int(o, "rate"), o["avatars"] != false,
        (o["max"] as? Number)?.toInt()?.coerceIn(Settings.MIN_PLAYERS, Settings.MAX_PLAYERS) ?: Settings.DEFAULT_PLAYERS
    )

    private fun scoreToMap(s: ScoreEntry) = mapOf(
        "rank" to s.rank, "id" to s.playerId, "nick" to s.nick, "score" to s.score, "left" to s.left
    )

    private fun scoreFromMap(o: Map<*, *>) =
        ScoreEntry(int(o, "rank"), int(o, "id"), str(o, "nick"), int(o, "score"), bool(o, "left"))

    private fun obj(v: Any?): Map<*, *> = v as? Map<*, *> ?: throw IllegalArgumentException("Objeto esperado")
    private fun str(o: Map<*, *>, k: String): String = o[k] as? String ?: throw IllegalArgumentException("Campo '$k' ausente")
    private fun int(o: Map<*, *>, k: String): Int = (o[k] as? Number)?.toInt() ?: throw IllegalArgumentException("Campo '$k' ausente")
    private fun bool(o: Map<*, *>, k: String): Boolean = o[k] as? Boolean ?: throw IllegalArgumentException("Campo '$k' ausente")
    private fun list(o: Map<*, *>, k: String): List<*> = o[k] as? List<*> ?: throw IllegalArgumentException("Campo '$k' ausente")
}
