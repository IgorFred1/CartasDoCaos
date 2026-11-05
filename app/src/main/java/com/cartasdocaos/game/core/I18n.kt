package com.cartasdocaos.game.core

/**
 * Tradução da interface. O texto original (em português) é a própria chave; o idioma do
 * sistema escolhe o arquivo assets/i18n/<idioma>.json. Chaves com {0}, {1}... valem como
 * modelos: servem tanto para montar frases (tr(modelo, args)) quanto para reconhecer uma frase
 * já montada (ex.: mensagens que chegam do anfitrião). Sem tradução, o texto fica em português.
 *
 * As cartas do baralho NÃO passam por aqui: elas vêm do anfitrião e ficam no idioma do baralho.
 */
object I18n {
    val SUPPORTED = listOf("pt", "en", "es", "fr", "de", "it")

    @Volatile var lang = "pt"
        private set
    @Volatile private var exact: Map<String, String> = emptyMap()
    @Volatile private var templates: Map<String, String> = emptyMap()
    @Volatile private var patterns: List<Pattern> = emptyList()

    private class Pattern(val regex: Regex, val order: List<Int>, val translation: String, val weight: Int)

    private val PLACEHOLDER = Regex("\\{(\\d+)\\}")

    /** pt/en/es/fr/de/it ficam como estão; qualquer outro idioma do sistema usa inglês. */
    fun pick(systemLanguage: String): String {
        val l = systemLanguage.lowercase().take(2)
        return if (l in SUPPORTED) l else "en"
    }

    /** [json] é o conteúdo de assets/i18n/<language>.json (ou null para português). */
    fun load(language: String, json: String?) {
        if (language == "pt" || json == null) {
            lang = "pt"; exact = emptyMap(); templates = emptyMap(); patterns = emptyList()
            return
        }
        val ex = HashMap<String, String>()
        val tp = HashMap<String, String>()
        val ps = ArrayList<Pattern>()
        for ((k, v) in Json.parse(json) as Map<*, *>) {
            val key = (k as String).trim()
            val value = v as? String ?: continue
            if (value.isBlank()) continue
            if (PLACEHOLDER.containsMatchIn(key)) {
                tp[key] = value
                ps.add(compile(key, value))
            } else ex[key] = value
        }
        exact = ex; templates = tp
        patterns = ps.sortedByDescending { it.weight } // modelos mais específicos primeiro
        lang = language
    }

    private fun compile(key: String, translation: String): Pattern {
        val sb = StringBuilder()
        val order = ArrayList<Int>()
        var last = 0
        var literal = 0
        for (m in PLACEHOLDER.findAll(key)) {
            val part = key.substring(last, m.range.first)
            sb.append(Regex.escape(part)); literal += part.length
            sb.append("(.*?)")
            order.add(m.groupValues[1].toInt())
            last = m.range.last + 1
        }
        val tail = key.substring(last)
        sb.append(Regex.escape(tail)); literal += tail.length
        return Pattern(Regex(sb.toString(), RegexOption.DOT_MATCHES_ALL), order, translation, literal)
    }

    /** Traduz um texto pronto (com ou sem números já dentro). */
    fun tr(s: String): String {
        if (lang == "pt" || s.isEmpty()) return s
        val core = s.trim()
        if (core.isEmpty()) return s
        val lead = s.substring(0, s.indexOf(core.first()))
        val trail = s.substring(s.lastIndexOf(core.last()) + 1)
        exact[core]?.let { return lead + it + trail }
        for (p in patterns) {
            val m = p.regex.matchEntire(core) ?: continue
            var out = p.translation
            val values = HashMap<Int, String>()
            p.order.forEachIndexed { pos, idx -> values[idx] = m.groupValues[pos + 1] }
            out = PLACEHOLDER.replace(out) { values[it.groupValues[1].toInt()] ?: it.value }
            return lead + out + trail
        }
        return s
    }

    /** Monta uma frase a partir de um modelo em português com {0}, {1}... */
    fun tr(template: String, vararg args: Any): String {
        val base = if (lang == "pt") template else (templates[template.trim()] ?: template)
        return PLACEHOLDER.replace(base) { args.getOrNull(it.groupValues[1].toInt())?.toString() ?: it.value }
    }
}
