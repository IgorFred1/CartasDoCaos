package com.cartasdocaos.game.core

/**
 * Tradução das cartas só para exibição. O jogo inteiro (anfitrião, rede, regras) continua
 * usando o texto original em português; cada aparelho mostra a carta no idioma do próprio
 * sistema, procurando o original em assets/cards/<idioma>.json. Carta sem tradução aparece
 * em português.
 */
object CardText {
    @Volatile private var map: Map<String, String> = emptyMap()

    fun load(json: String?) {
        if (json == null) { map = emptyMap(); return }
        val out = HashMap<String, String>()
        for ((k, v) in Json.parse(json) as Map<*, *>) {
            if (k is String && v is String && v.isNotBlank()) out[k] = v
        }
        map = out
    }

    fun show(pt: String): String = map[pt] ?: pt
}
