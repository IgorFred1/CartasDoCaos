package com.cartasdocaos.game.core

import java.util.ArrayDeque
import kotlin.random.Random

/**
 * Baralho de uma partida. As cartas brancas saem de um monte único e embaralhado,
 * então nenhuma carta é entregue duas vezes na mesma partida (nem para jogadores
 * diferentes). Textos repetidos entre expansões também são descartados.
 */
class Deck(data: CardData, categories: Set<Category>, private val rng: Random = Random.Default) {
    private val blackAll: List<Card> = data.black.filter { it.category in categories }.distinctBy { it.text }
    private val white = ArrayDeque(
        data.white.filter { it.category in categories }.distinctBy { it.text }.shuffled(rng).map { it.text }
    )
    private val black = ArrayDeque<Card>()

    init {
        require(blackAll.isNotEmpty()) { "Sem cartas pretas para as expansões escolhidas" }
        refillBlack()
    }

    private fun refillBlack() {
        black.addAll(blackAll.shuffled(rng))
    }

    /** Carta preta aleatória; só se repete depois de todas as outras terem saído. */
    fun nextBlack(): Card {
        if (black.isEmpty()) refillBlack()
        return black.poll()
    }

    /** Carta branca nunca usada na partida, ou null se o monte acabou. */
    fun drawWhite(): String? = white.poll()

    fun whiteLeft(): Int = white.size
}
