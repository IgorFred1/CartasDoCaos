package com.cartasdocaos.game.core

enum class Category(val key: String, val label: String, val description: String) {
    FAMILIA("familia", "Família", "Piadas leves, para jogar com todo mundo"),
    PICANTE("picante", "Picante", "Climão, paquera e situações constrangedoras"),
    HUMOR_NEGRO("humor_negro", "Humor negro", "Sem filtro: crimes, tragédias e absurdos chocantes (18+)"),
    PESADELO("pesadelo", "Pesadelo", "Halloween, contos e filmes de terror, cenas macabras");

    companion object {
        fun fromKey(key: String): Category? = values().firstOrNull { it.key == key }
    }
}

data class Card(val category: Category, val text: String)

data class Settings(
    val categories: Set<Category>,
    val rounds: Int,
    val playSeconds: Int,
    val ratingSeconds: Int,
    /** O anfitrião decide se os jogadores podem usar foto de perfil. */
    val avatars: Boolean = true,
    /** Limite de jogadores na sala (decidido pelo anfitrião). */
    val maxPlayers: Int = DEFAULT_PLAYERS
) {
    fun isValid(): Boolean =
        categories.isNotEmpty() &&
            rounds in MIN_ROUNDS..MAX_ROUNDS &&
            playSeconds in MIN_PLAY..MAX_PLAY &&
            ratingSeconds in MIN_RATING..MAX_RATING &&
            maxPlayers in MIN_PLAYERS..MAX_PLAYERS

    companion object {
        const val MIN_PLAYERS = 2
        const val MAX_PLAYERS = 20
        const val DEFAULT_PLAYERS = 10
        const val MIN_ROUNDS = 3
        const val MAX_ROUNDS = 15
        const val MIN_PLAY = 10
        const val MAX_PLAY = 180
        const val MIN_RATING = 10
        const val MAX_RATING = 90
        val PLAY_OPTIONS = listOf(15, 30, 45, 60, 90)
    }
}

data class PlayerInfo(val id: Int, val nick: String, val isHost: Boolean, val connected: Boolean)

data class ScoreEntry(val rank: Int, val playerId: Int, val nick: String, val score: Int, val left: Boolean)

data class ResultEntry(val nick: String, val text: String, val points: Int, val votes: Int)

object Nick {
    const val MIN = 3
    const val MAX = 15

    /** Remove espaços nas pontas e comprime espaços repetidos. */
    fun clean(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    /** Retorna a mensagem de erro, ou null se o apelido é válido. */
    fun validate(raw: String): String? {
        val n = clean(raw)
        if (n.length < MIN) return "O apelido precisa ter pelo menos $MIN caracteres."
        if (n.length > MAX) return "O apelido pode ter no máximo $MAX caracteres."
        if (!n.all { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' || it == '.' }) {
            return "Use apenas letras, números, espaço, _ - ou ."
        }
        return null
    }
}

/** Dados brutos do baralho (cartas pretas e brancas de todas as expansões). */
class CardData(val black: List<Card>, val white: List<Card>) {
    companion object {
        fun parse(json: String): CardData {
            val root = Json.parse(json) as Map<*, *>
            fun read(key: String): List<Card> = (root[key] as List<*>).mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                val cat = Category.fromKey(m["c"] as? String ?: return@mapNotNull null) ?: return@mapNotNull null
                val text = m["t"] as? String ?: return@mapNotNull null
                Card(cat, text)
            }
            return CardData(read("pretas"), read("brancas"))
        }
    }
}
