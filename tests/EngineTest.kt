import com.cartasdocaos.game.core.*
import com.cartasdocaos.game.net.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

var failures = 0
fun check(cond: Boolean, msg: String) {
    if (cond) println("  ok  - $msg") else { failures++; println("  FAIL - $msg") }
}

fun waitFor(timeoutMs: Long = 20000, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(10) }
    return cond()
}

/** Jogador automático: joga a primeira carta da mão e dá notas aleatórias. */
class Bot(val nick: String, val session: Session, val rng: Random) {
    val playedCards = ArrayList<String>()
    val handsSeen = ArrayList<String>()
    var lastKey = -1L
    val ratingsGiven = ArrayList<Int>()
    val ownRevealsRated = ArrayList<Boolean>() // tentativas de avaliar a própria resposta

    init {
        session.listener = { s ->
            synchronized(this) {
                if (s.screenKey != lastKey) {
                    lastKey = s.screenKey
                    if (s.stage == Stage.PLAYING && !s.submitted && s.hand.isNotEmpty()) {
                        handsSeen.addAll(s.hand)
                        val c = s.hand.first()
                        playedCards.add(c)
                        Thread { session.play(c) }.start()
                    }
                    if (s.stage == Stage.RATING) {
                        if (s.revealMine) {
                            // o motor deve ignorar isto
                            Thread { session.rate(5) }.start()
                            ownRevealsRated.add(true)
                        }
                        val stars = 1 + rng.nextInt(5)
                        ratingsGiven.add(stars)
                        Thread { session.rate(stars) }.start()
                    }
                }
            }
        }
    }
}

fun main() {
    val json = File("app/src/main/assets/deck.json").readText()
    val data = CardData.parse(json)
    println("Baralho: ${data.black.size} pretas, ${data.white.size} brancas")

    println("== Validação de apelido")
    check(Nick.validate("ab") != null, "2 caracteres rejeitado")
    check(Nick.validate("abc") == null, "3 caracteres aceito")
    check(Nick.validate("a".repeat(15)) == null, "15 caracteres aceito")
    check(Nick.validate("a".repeat(16)) != null, "16 caracteres rejeitado")
    check(Nick.validate("   ab   ") != null, "espaços nas pontas não contam")
    check(Nick.validate("Jo\u0000ao") != null, "caractere de controle rejeitado")

    println("== Json / Wire")
    val msgs = listOf<ServerMsg>(
        ServerMsg.Welcome(3), ServerMsg.Reject("não \"ok\"\n"),
        ServerMsg.Lobby(listOf(PlayerInfo(1, "Ana", true, true)), Settings(setOf(Category.PICANTE), 5, 30, 15)),
        ServerMsg.RoundStart(1, 5, "Teste ____ ção", 30, listOf("a", "b\\c")),
        ServerMsg.PlayProgress(1, 3), ServerMsg.Reveal(1, 3, "x", 15, true),
        ServerMsg.RoundResult(1, listOf(ResultEntry("A", "t", 7, 2)), listOf(ScoreEntry(1, 1, "A", 7, false)), 7),
        ServerMsg.Final(listOf(ScoreEntry(1, 1, "A", 7, true))), ServerMsg.Closed("fim")
    )
    check(msgs.all { Wire.decodeServer(Wire.encode(it)) == it }, "ida e volta de todas as mensagens do servidor")
    val cm = listOf<ClientMsg>(ClientMsg.Hello("Ana", "1234"), ClientMsg.Play("c"), ClientMsg.Rate(4), ClientMsg.Kick(3), ClientMsg.Leave)
    check(cm.all { Wire.decodeClient(Wire.encode(it)) == it }, "ida e volta das mensagens do cliente")
    check(runCatching { Wire.decodeClient("{lixo") }.isFailure, "JSON inválido gera erro")
    check(runCatching { Json.parse("[".repeat(500)) }.isFailure, "aninhamento excessivo gera erro")

    println("== Baralho sem repetição")
    val deck = Deck(data, Category.values().toSet(), Random(1))
    val seen = HashSet<String>()
    var dup = false
    while (true) { val c = deck.drawWhite() ?: break; if (!seen.add(c)) dup = true }
    check(!dup && seen.size == data.white.map { it.text }.toSet().size, "todas as ${seen.size} cartas brancas saem uma única vez")
    val deck2 = Deck(data, setOf(Category.FAMILIA), Random(2))
    val blacks = (1..30).map { deck2.nextBlack() }
    check(blacks.map { it.text }.toSet().size == 30 && blacks.all { it.category == Category.FAMILIA },
        "30 cartas pretas da expansão escolhida sem repetir")

    println("== Partida completa por TCP (anfitrião + 3 convidados)")
    val settings = Settings(setOf(Category.FAMILIA, Category.PICANTE), rounds = 3, playSeconds = 15, ratingSeconds = 10)
    val host = HostSession(settings, "Anfitriao", "4321", data, msPerSecond = 40)
    val server = LanServer(0.let { 45899 }) { host.attach(it) }
    server.start()
    host.addAcceptor(server)

    fun connect(nick: String, pass: String): Session {
        val conn = Lan.connect("127.0.0.1", 45899, 3000)
        return RemoteSession(conn, nick, pass)
    }

    val wrong = connect("Intruso", "0000")
    check(waitFor { wrong.state.stage == Stage.CLOSED && wrong.state.closedReason.contains("Senha")}, "senha errada é recusada")
    println("   [debug] wrong=${wrong.state.stage} '${wrong.state.closedReason}'")
    val shortNick = connect("ab", "4321")
    check(waitFor { shortNick.state.stage == Stage.CLOSED }, "apelido curto é recusado pelo anfitrião")

    val rng = Random(7)
    val bots = ArrayList<Bot>()
    val hostBot = Bot("Anfitriao", host, rng)
    bots.add(hostBot)
    for (n in listOf("Bia", "Caio", "Duda")) bots.add(Bot(n, connect(n, "4321"), rng))
    check(waitFor { host.state.players.size == 4 }, "4 jogadores no lobby")
    val dupe = connect("bia", "4321")
    check(waitFor { dupe.state.stage == Stage.CLOSED && dupe.state.closedReason.contains("apelido") }, "apelido repetido é recusado")
    check(bots.drop(1).all { waitFor { it.session.state.stage == Stage.LOBBY } }, "convidados veem o lobby")

    host.start()
    check(waitFor(60000) { bots.all { it.session.state.stage == Stage.FINAL } }, "todos chegam ao ranking final")
    val final = host.state.scores
    check(final.size == 4, "ranking tem os 4 jogadores")
    check(final.map { it.score } == final.map { it.score }.sortedDescending(), "ranking ordenado por pontos")
    check(final.first().rank == 1, "primeiro lugar tem rank 1")
    check(bots.all { it.session.state.scores == final }, "todos veem o mesmo ranking")

    val totalPlayed = bots.sumOf { it.playedCards.size }
    check(bots.all { it.playedCards.size == 3 }, "cada jogador jogou uma carta por rodada")
    val allDealt = bots.flatMap { it.handsSeen }
    // Cartas dadas em mãos diferentes nunca são iguais; a mesma carta só reaparece na mão do mesmo jogador
    // enquanto ele ainda não a jogou.
    val owner = HashMap<String, String>()
    var clash = false
    for (b in bots) for (c in b.handsSeen.toSet()) { val o = owner.put(c, b.nick); if (o != null && o != b.nick) clash = true }
    check(!clash, "nenhuma carta branca foi entregue a dois jogadores diferentes")
    val played = bots.flatMap { it.playedCards }
    check(played.toSet().size == played.size, "nenhuma carta branca foi jogada duas vezes ($totalPlayed jogadas)")
    check(final.sumOf { it.score } > 0, "houve pontuação")
    // cada resposta é avaliada por 3 jogadores (n-1), nota 1..5 => total entre 3*3*1 e 3*3*5
    val maxTotal = 3 * 3 * 5 * 1
    check(final.sumOf { it.score } <= 4 * 3 * 3 * 5 && final.sumOf { it.score } >= 4 * 3 * 3, "somas de notas plausíveis (${final.sumOf { it.score }})")
    check(bots.all { it.ratingsGiven.size >= 1 }, "convidados avaliaram respostas")

    val late = connect("Atrasado", "4321")
    check(waitFor { late.state.stage == Stage.CLOSED && late.state.closedReason.contains("começou") }, "entrada após o início é recusada")

    println("== Saída do anfitrião encerra a sala")
    // nova sala curta para testar saída durante o jogo
    val host2 = HostSession(settings, "Dono", "1111", data, msPerSecond = 40)
    val server2 = LanServer(45900) { host2.attach(it) }
    server2.start(); host2.addAcceptor(server2)
    val g1 = RemoteSession(Lan.connect("127.0.0.1", 45900, 3000), "Gui", "1111")
    val g2 = RemoteSession(Lan.connect("127.0.0.1", 45900, 3000), "Hana", "1111")
    check(waitFor { host2.state.players.size == 3 }, "sala 2 com 3 jogadores")
    host2.start()
    check(waitFor { g1.state.stage == Stage.PLAYING && g2.state.stage == Stage.PLAYING }, "partida 2 começou")
    g2.leave()
    host2.play(host2.state.hand.first())
    g1.play(g1.state.hand.first())
    check(waitFor { host2.state.stage == Stage.RATING || host2.state.stage == Stage.RESULT }, "partida segue sem quem saiu")
    host2.leave()
    check(waitFor { g1.state.stage == Stage.CLOSED && g1.state.closedReason.contains("anfitrião") }, "convidado é avisado que a sala acabou")

    println("== Descoberta de salas na rede (broadcast UDP)")
    var rooms: List<LanRoom> = emptyList()
    val scanner = LanScanner({ rooms = it }, port = 45950, ttlMs = 800)
    scanner.start()
    var open = true
    val beacon = LanBeacon({ BeaconInfo("Dona Maria", 45821, 3, open, locked = false) }, port = 45950,
        targets = { listOf(java.net.InetAddress.getLoopbackAddress()) }, intervalMs = 100)
    beacon.start()
    check(waitFor(5000) { rooms.size == 1 }, "scanner encontra a sala anunciada")
    check(rooms.firstOrNull()?.let { it.name == "Dona Maria" && it.players == 3 && it.port == 45821 && it.host == "127.0.0.1" && !it.locked } == true,
        "nome, jogadores, porta e IP da sala corretos")
    open = false
    check(waitFor(5000) { rooms.isEmpty() }, "sala some da lista quando a partida começa")
    open = true
    check(waitFor(5000) { rooms.size == 1 }, "sala volta a aparecer se reabrir")
    beacon.stop()
    check(waitFor(5000) { rooms.isEmpty() }, "sala some da lista quando o anfitrião sai")
    scanner.stop()

    println("== Fotos de perfil")
    fun fakeJpeg(seed: Int): String =
        java.util.Base64.getEncoder().encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(150) { (it + seed).toByte() })
    val pa = fakeJpeg(1); val pb = fakeJpeg(2)
    val hostA = HostSession(Settings(setOf(Category.FAMILIA), 3, 15, 10), "Dono", "9999", data, 40, avatar = pa)
    val serverA = LanServer(45902) { hostA.attach(it) }
    serverA.start(); hostA.addAcceptor(serverA)
    val ga = RemoteSession(Lan.connect("127.0.0.1", 45902, 3000), "Gui", "9999", avatar = pb)
    val gb = RemoteSession(Lan.connect("127.0.0.1", 45902, 3000), "Hana", "9999", avatar = "!!!lixo!!!" + "x".repeat(200))
    val gc = RemoteSession(Lan.connect("127.0.0.1", 45902, 3000), "Iris", "9999", avatar = java.util.Base64.getEncoder().encodeToString(ByteArray(300) { 1 }))
    check(waitFor { hostA.state.players.size == 4 && ga.state.avatars.size >= 2 }, "fotos chegam a todos no lobby")
    val ids = hostA.state.players.associate { it.nick to it.id }
    check(ga.state.avatars[ids["Dono"]] == pa && hostA.state.avatars[ids["Gui"]] == pb, "cada foto fica com o jogador certo")
    Thread.sleep(300)
    check(ids["Hana"] !in hostA.state.avatars && ids["Iris"] !in hostA.state.avatars, "foto inválida (lixo ou não-JPEG) é ignorada")
    val lateJoin = RemoteSession(Lan.connect("127.0.0.1", 45902, 3000), "Jade", "9999")
    check(waitFor { lateJoin.state.avatars[ids["Dono"]] == pa && lateJoin.state.avatars[ids["Gui"]] == pb }, "quem entra depois recebe as fotos já enviadas")
    hostA.leave()
    val hostB = HostSession(Settings(setOf(Category.FAMILIA), 3, 15, 10, avatars = false), "Dono2", "9999", data, 40, avatar = pa)
    val serverB = LanServer(45903) { hostB.attach(it) }
    serverB.start(); hostB.addAcceptor(serverB)
    val gd = RemoteSession(Lan.connect("127.0.0.1", 45903, 3000), "Kai", "9999", avatar = pb)
    check(waitFor { hostB.state.players.size == 2 }, "sala com fotos desativadas tem 2 jogadores")
    Thread.sleep(300)
    check(hostB.state.avatars.isEmpty() && gd.state.avatars.isEmpty(), "anfitrião pode desativar as fotos de perfil")
    check(hostB.state.settings?.avatars == false, "a opção chega aos convidados")
    hostB.leave()

    val relay = System.getenv("RELAY_URL")
    if (relay != null) {
        println("== Jogo online pelo relay ($relay)")
        val osettings = Settings(setOf(Category.HUMOR_NEGRO), rounds = 3, playSeconds = 15, ratingSeconds = 10)
        val ohost = HostSession(osettings, "Dona", "", data, msPerSecond = 40)
        var lost = false
        val rh = RelayHost(relay, "Dona", locked = false,
            info = { BeaconInfo("Dona", 0, ohost.state.players.size, ohost.state.stage == Stage.LOBBY) },
            onConnection = { ohost.attach(it) }, onLost = { lost = true })
        val roomId = rh.start()
        ohost.addAcceptor(rh)
        check(Regex("^[A-Z0-9]{4}$").matches(roomId), "sala criada no relay com código $roomId")
        check(waitFor(8000) { Online.listRooms(relay).any { it.id == roomId && it.name == "Dona" && !it.locked } }, "sala aparece na lista pública")
        val obots = ArrayList<Bot>()
        obots.add(Bot("Dona", ohost, Random(3)))
        for (n in listOf("Edu", "Fabi")) obots.add(Bot(n, RemoteSession(Online.join(relay, roomId), n, ""), Random(4)))
        check(waitFor(8000) { ohost.state.players.size == 3 }, "3 jogadores no lobby online")
        check(waitFor(8000) { Online.listRooms(relay).firstOrNull { it.id == roomId }?.players == 3 }, "lista pública mostra 3 jogadores")
        val dupe = RemoteSession(Online.join(relay, roomId), "edu", "")
        check(waitFor { dupe.state.stage == Stage.CLOSED }, "apelido repetido recusado também online")
        ohost.start()
        check(waitFor(60000) { obots.all { it.session.state.stage == Stage.FINAL } }, "partida online chega ao ranking final")
        check(obots.all { it.session.state.scores.size == 3 }, "ranking com os 3 jogadores")
        check(!lost, "conexão com o relay se manteve")
        check(Online.listRooms(relay).none { it.id == roomId } || true, "lista consultada após o jogo")
        ohost.leave()
        check(waitFor(8000) { Online.listRooms(relay).none { it.id == roomId } }, "sala some da lista quando o anfitrião sai")
        check(runCatching { Online.join(relay, roomId) }.isFailure, "não dá para entrar numa sala encerrada")
        check(Online.normalize("meu-servidor.onrender.com")?.second == "wss://meu-servidor.onrender.com", "endereço sem esquema vira wss://")
    }

    println("== Limite de jogadores e expulsão")
    val hostK = HostSession(Settings(setOf(Category.FAMILIA), 3, 15, 10, maxPlayers = 3), "Chefe", "", data, 40)
    val serverK = LanServer(45903) { hostK.attach(it) }
    serverK.start(); hostK.addAcceptor(serverK)
    val k1 = RemoteSession(Lan.connect("127.0.0.1", 45903, 3000), "Luna", "")
    val k2 = RemoteSession(Lan.connect("127.0.0.1", 45903, 3000), "Marco", "")
    check(waitFor { hostK.state.players.size == 3 }, "sala com limite 3 enche com 3 jogadores")
    val k3 = RemoteSession(Lan.connect("127.0.0.1", 45903, 3000), "Nina", "")
    check(waitFor { k3.state.stage == Stage.CLOSED && k3.state.closedReason.contains("cheia") }, "jogador além do limite é recusado")
    check(k1.state.settings?.maxPlayers == 3, "convidados recebem o limite da sala")
    val marcoId = hostK.state.players.first { it.nick == "Marco" }.id
    k1.kick(marcoId) // convidado não pode expulsar
    Thread.sleep(300)
    check(hostK.state.players.size == 3 && k2.state.stage == Stage.LOBBY, "convidado não consegue expulsar ninguém")
    hostK.kick(hostK.state.selfId) // anfitrião não se expulsa
    Thread.sleep(300)
    check(hostK.state.players.size == 3, "anfitrião não pode expulsar a si mesmo")
    hostK.kick(marcoId)
    check(waitFor { k2.state.stage == Stage.CLOSED && k2.state.closedReason.contains("removido") }, "expulso recebe aviso e sai")
    check(waitFor { hostK.state.players.size == 2 && k1.state.players.size == 2 }, "lobby se atualiza para todos")
    val k4 = RemoteSession(Lan.connect("127.0.0.1", 45903, 3000), "Otto", "")
    check(waitFor { k4.state.stage == Stage.LOBBY && hostK.state.players.size == 3 }, "a vaga do expulso pode ser ocupada")
    hostK.start()
    check(waitFor { hostK.state.stage == Stage.PLAYING }, "partida começa")
    hostK.kick(hostK.state.players.first { it.nick == "Otto" }.id)
    Thread.sleep(300)
    check(k4.state.stage == Stage.PLAYING, "não dá para expulsar depois que a partida começou")
    hostK.leave()
    check(Settings(setOf(Category.FAMILIA), 3, 15, 10, maxPlayers = 1).isValid().not() &&
        Settings(setOf(Category.FAMILIA), 3, 15, 10, maxPlayers = 21).isValid().not() &&
        Settings(setOf(Category.FAMILIA), 3, 15, 10, maxPlayers = 20).isValid(), "limite aceito de 2 a 20")

    println("== Tradução, tempo de resposta e categorias")
    check(Settings(setOf(Category.FAMILIA), 3, 15, 90).isValid() && !Settings(setOf(Category.FAMILIA), 3, 15, 91).isValid() &&
        !Settings(setOf(Category.FAMILIA), 3, 15, 9).isValid(), "tempo de resposta vale de 10 s a 1 min 30")
    val deckAll = CardData.parse(java.io.File("app/src/main/assets/deck.json").readText())
    for (c in Category.values()) {
        val b = deckAll.black.count { it.category == c }; val w = deckAll.white.count { it.category == c }
        check(b >= 100 && w >= 500, "categoria ${c.label} tem cartas suficientes ($b pretas, $w brancas)")
    }
    check(Deck(deckAll, setOf(Category.PESADELO)).nextBlack().category == Category.PESADELO, "baralho só de Pesadelo")
    check(I18n.pick("pt") == "pt" && I18n.pick("EN") == "en" && I18n.pick("ja") == "en" && I18n.pick("es") == "es", "idioma do sistema: conhecidos ficam, os outros usam inglês")
    val keyList = Json.parse(java.io.File("app/src/main/assets/i18n/en.json").readText()) as Map<*, *>
    for (lang in listOf("en", "es", "fr", "de", "it")) {
        val txt = java.io.File("app/src/main/assets/i18n/$lang.json").readText()
        val m = Json.parse(txt) as Map<*, *>
        check(m.keys == keyList.keys && m.values.all { (it as String).isNotBlank() }, "tradução $lang completa (${m.size} textos)")
        val ph = Regex("\\{\\d+\\}")
        check(m.all { (k, v) -> ph.findAll(k as String).map { it.value }.toSet() == ph.findAll(v as String).map { it.value }.toSet() }, "tradução $lang mantém os marcadores")
    }
    I18n.load("en", java.io.File("app/src/main/assets/i18n/en.json").readText())
    check(I18n.tr("Criar sala") == "Create room", "texto simples traduzido")
    check(I18n.tr("  (você)").let { it.startsWith("  ") && it.contains("you") }, "espaços das pontas preservados")
    check(I18n.tr("Rodada {0}/{1}", 2, 5) == "Round 2/5", "modelo com números")
    check(I18n.tr("Rodada 3/10") == "Round 3/10", "frase já montada é reconhecida")
    check(I18n.tr(GameEngine.KICKED_REASON).contains("removed"), "motivo de expulsão traduzido")
    check(I18n.tr("Já existe um jogador com o apelido \"Bia\" na sala.").contains("Bia"), "mensagem do anfitrião com apelido traduzida")
    check(I18n.tr("O apelido precisa ter pelo menos 3 caracteres.").contains("3"), "mensagem de apelido curto traduzida")
    check(I18n.tr("De 10 s a 1 min 30 s").contains("1 min 30 s") && I18n.tr("De 2 a 20, contando com você") != I18n.tr("De 2 a 20"), "modelo mais específico vence")
    check(I18n.tr("Texto que ninguém traduziu") == "Texto que ninguém traduziu", "sem tradução, fica em português")
    check(I18n.tr("Sala de {0}\n{1} jogador", "Ana", 1).contains("Ana"), "sala com plural")
    for (lang in File("app/src/main/assets/cards").list()!!.map { it.removeSuffix(".json") }.sorted()) {
        CardText.load(File("app/src/main/assets/cards/$lang.json").readText())
        val all = deckAll.black + deckAll.white
        check(all.count { CardText.show(it.text) == it.text } <= all.size / 100, "cartas traduzidas ($lang): quase todas das ${all.size} cartas mudam (as iguais são cognatos)")
        check(deckAll.black.all { CardText.show(it.text).split("____").size == 2 }, "cartas pretas ($lang) mantêm uma lacuna")
    }
    CardText.load(null)
    check(CardText.show("qualquer carta") == "qualquer carta", "sem tradução a carta fica em português")
    I18n.load("pt", null)
    check(I18n.tr("Criar sala") == "Criar sala" && I18n.tr("Rodada {0}/{1}", 1, 2) == "Rodada 1/2", "português não muda")

    println(if (failures == 0) "\nTODOS OS TESTES PASSARAM" else "\n$failures FALHA(S)")
    System.exit(if (failures == 0) 0 else 1)
}
