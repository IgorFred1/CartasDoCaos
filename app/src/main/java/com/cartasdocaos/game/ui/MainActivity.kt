package com.cartasdocaos.game.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.view.Gravity
import java.io.ByteArrayOutputStream
import java.io.File
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.content.res.ColorStateList
import com.cartasdocaos.game.core.CardData
import com.cartasdocaos.game.core.CardText
import com.cartasdocaos.game.core.Category
import com.cartasdocaos.game.core.GameEngine
import com.cartasdocaos.game.core.I18n
import com.cartasdocaos.game.core.Nick
import com.cartasdocaos.game.core.Settings
import com.cartasdocaos.game.core.Stage
import com.cartasdocaos.game.core.UiState
import com.cartasdocaos.game.net.Bt
import com.cartasdocaos.game.net.BtClient
import com.cartasdocaos.game.net.BtServer
import com.cartasdocaos.game.net.HostSession
import com.cartasdocaos.game.net.BeaconInfo
import com.cartasdocaos.game.net.Lan
import com.cartasdocaos.game.net.LanBeacon
import com.cartasdocaos.game.net.LanRoom
import com.cartasdocaos.game.net.LanScanner
import com.cartasdocaos.game.net.LanServer
import com.cartasdocaos.game.net.LineConnection
import com.cartasdocaos.game.net.Online
import com.cartasdocaos.game.net.OnlineRoom
import com.cartasdocaos.game.net.RelayHost
import com.cartasdocaos.game.net.RemoteSession
import com.cartasdocaos.game.net.Session
import kotlin.random.Random

class MainActivity : Activity() {

    private enum class Screen { MENU, CREATE, JOIN, HOWTO, GAME }
    private enum class Mode { WIFI, BLUETOOTH, ONLINE }
    private enum class PassReq { NONE, OPTIONAL, REQUIRED }

    private companion object {
        const val REQ_PERM = 11
        const val REQ_ENABLE = 12
        const val REQ_DISCOVERABLE = 13
        const val REQ_PICK_PHOTO = 14
        const val AVATAR_PX = 96
        const val AVATAR_MAX_BYTES = 9000
        const val KICK_BLOCK_MS = 3 * 60 * 1000L
        const val RATE_STEP = 5
    }

    private lateinit var data: CardData
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var screen = Screen.MENU
    private var session: Session? = null
    private var everInLobby = false
    private var cancelled = false
    private var nick = ""
    private var roomPassword = ""
    private var relayUrl = ""
    private var createPassword = true
    private var creating = false
    private var onlineRooms: List<OnlineRoom> = emptyList()
    private var onlineError = ""
    private var selectedOnline: OnlineRoom? = null
    private var onlineBox: LinearLayout? = null
    private var passBox: LinearLayout? = null
    @Volatile private var pollStop: BooleanArray? = null
    private var lobbyInfo = ""

    // salas das quais o jogador foi expulso: ficam escondidas por 3 minutos
    private var joinKey: String? = null
    private var darkAsked = false
    private val kickedUntil = HashMap<String, Long>()

    // configuração da sala (anfitrião)
    private var createMode = Mode.WIFI
    private val createCats = linkedSetOf(Category.FAMILIA)
    private var createRounds = 5
    private var createPlaySec = 30
    private var createRateSec = 15
    private var createAvatars = true
    private var createMaxPlayers = Settings.DEFAULT_PLAYERS

    // foto de perfil (guardada no aparelho, já reduzida para 96x96 JPEG)
    private var myAvatarB64: String? = null
    private var myAvatarBitmap: Bitmap? = null
    private val bitmapCache = HashMap<String, Bitmap>()

    // entrada na sala (convidado)
    private var joinMode = Mode.WIFI
    private var joinIp = ""
    private var manualIp = false
    private var selectedRoom: LanRoom? = null
    private var foundRooms: List<LanRoom> = emptyList()
    private var roomsBox: LinearLayout? = null
    private var scanner: LanScanner? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var joinPass = ""
    private var selectedDevice: BluetoothDevice? = null
    private val btDevices = LinkedHashMap<String, BluetoothDevice>()
    private var deviceListBox: LinearLayout? = null
    private var receiverRegistered = false

    // views dinâmicas da tela de jogo
    private var nickInput: EditText? = null
    private var timerText: TextView? = null
    private var timerBar: ProgressBar? = null
    private var progressText: TextView? = null
    private var lastSig = ""
    private var selectedCard: String? = null
    private val handViews = ArrayList<Pair<TextView, String>>()
    private var confirmButton: TextView? = null

    // continuações após diálogos do sistema (permissão, ligar Bluetooth...)
    private var pendingPerm: (() -> Unit)? = null
    private var pendingEnable: (() -> Unit)? = null
    private var pendingDiscoverable: (() -> Unit)? = null

    // Aparelhos vistos (qualquer um) e já consultados; só os que oferecem o serviço do jogo vão para btDevices.
    private val btCandidates = LinkedHashMap<String, BluetoothDevice>()
    private val btProbed = HashSet<String>()

    private val discoveryReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    btCandidates[d.address] = d
                    checkCachedServices(d)
                }
                BluetoothDevice.ACTION_UUID -> {
                    val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val ids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                    if (hasGameService(ids)) btDevices[d.address] = d else btDevices.remove(d.address)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> probeCandidates()
                else -> return
            }
            if (screen == Screen.JOIN && joinMode == Mode.BLUETOOTH) refreshDeviceList()
        }
    }

    private fun hasGameService(ids: Array<out android.os.Parcelable>?): Boolean =
        ids != null && ids.any { (it as? android.os.ParcelUuid)?.uuid == Bt.SERVICE_UUID }

    @SuppressLint("MissingPermission")
    private fun checkCachedServices(d: BluetoothDevice) {
        try {
            if (hasGameService(d.uuids)) btDevices[d.address] = d
        } catch (_: SecurityException) { }
    }

    /** Pergunta a cada aparelho visto quais serviços oferece; só os que têm o jogo aparecem na lista. */
    @SuppressLint("MissingPermission")
    private fun probeCandidates() {
        try {
            for (d in btCandidates.values) {
                if (btProbed.add(d.address)) d.fetchUuidsWithSdp()
            }
        } catch (_: SecurityException) { }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 100)
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cartas", MODE_PRIVATE)
        nick = prefs.getString("nick", "") ?: ""
        relayUrl = prefs.getString("relay", "") ?: ""
        setupLanguage()
        loadAvatar()
        loadKicked()
        data = assets.open("deck.json").use { CardData.parse(it.readBytes().toString(Charsets.UTF_8)) }
        window.statusBarColor = Pal.BG
        window.navigationBarColor = Pal.BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        show(Screen.MENU)
    }

    /** Usa o idioma do sistema (pt, en, es, fr, de, it; outros caem em inglês). */
    private fun setupLanguage() {
        val sys = resources.configuration.locales.get(0).language
        val lang = I18n.pick(sys)
        val json = if (lang == "pt") null else try {
            assets.open("i18n/$lang.json").use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) { null }
        I18n.load(lang, json)
        val cards = if (lang == "pt") null else try {
            assets.open("cards/$lang.json").use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) { null }
        CardText.load(cards)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        stopDiscovery()
        if (isFinishing) cleanupSession()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (screen) {
            Screen.MENU -> super.onBackPressed()
            Screen.GAME -> {
                val st = session?.state
                if (session == null) cancelConnect()
                else if (st != null && (st.stage == Stage.PLAYING || st.stage == Stage.RATING || st.stage == Stage.RESULT)) showPause()
                else confirmLeave()
            }
            else -> show(Screen.MENU)
        }
    }

    // ------------------------------------------------------------------ navegação

    private fun show(s: Screen) {
        screen = s
        handler.removeCallbacks(ticker)
        timerText = null; timerBar = null; progressText = null; confirmButton = null; deviceListBox = null
        roomsBox = null
        onlineBox = null
        passBox = null
        nickInput = null
        handViews.clear()
        lastSig = ""
        if (s != Screen.JOIN || joinMode != Mode.BLUETOOTH) stopDiscovery()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        when (s) {
            Screen.MENU -> setContentView(page(buildMenu(), footer = buildFooter()))
            Screen.CREATE -> setContentView(page(buildCreate()))
            Screen.JOIN -> setContentView(page(buildJoin()))
            Screen.HOWTO -> setContentView(page(buildHowTo()))
            Screen.GAME -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                renderGame(session?.state ?: UiState(), force = true)
                handler.post(ticker)
            }
        }
    }

    /** Rodapé do menu: versão no canto esquerdo e autoria no direito. */
    private fun buildFooter(): View {
        val version = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(label("Versão $version", 12f, Pal.MUTED), lp(0, weight = 1f))
        row.addView(label("Feito por Igor Frederico", 12f, Pal.MUTED, gravity = Gravity.END), lp(0, weight = 1f))
        return row
    }

    private fun page(content: View, scroll: Boolean = true, footer: View? = null): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.BG)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        box.addView(content, lp(h = if (scroll) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.MATCH_PARENT, weight = if (footer != null) 1f else 0f))
        if (footer != null) box.addView(footer, lp().margins(0, dp(16), 0, 0))
        if (!scroll) return box.also { it.setPadding(dp(16), dp(12), dp(16), dp(16)) }
        return ScrollView(this).apply {
            setBackgroundColor(Pal.BG)
            isFillViewport = true
            addView(box)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, tr(msg), Toast.LENGTH_LONG).show()

    private fun alert(title: String, msg: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this).setTitle(tr(title)).setMessage(tr(msg)).setPositiveButton("OK", null).show()
    }

    private fun vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun section(title: String, parent: LinearLayout) {
        parent.addView(label(title, 16f, Pal.INK, bold = true), lp().margins(0, dp(22), 0, dp(8)))
    }

    // ------------------------------------------------------------------ menu

    private fun buildMenu(): View {
        val col = vbox()
        col.addView(label("CARTAS\nDO CAOS", 44f, Pal.INK, bold = true, gravity = Gravity.CENTER_HORIZONTAL), lp().margins(0, dp(24), 0, 0))
        col.addView(spacer(28))
        val profile = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        profile.addView(avatarCircle(myAvatarBitmap, nick.ifEmpty { "?" }, 72))
        val photoCol = vbox()
        photoCol.addView(label("Foto de perfil (opcional)", 14f, Pal.MUTED, bold = true))
        val photoButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        photoButtons.addView(chip(if (myAvatarB64 == null) "Escolher da galeria" else "Trocar foto", false) { pickAvatar() })
        if (myAvatarB64 != null) {
            photoButtons.addView(chip("Remover", false) { removeAvatar() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT).margins(dp(8), 0, 0, 0))
        }
        photoCol.addView(photoButtons, lp().margins(0, dp(6), 0, 0))
        profile.addView(photoCol, lp(0, weight = 1f).margins(dp(14), 0, 0, 0))
        col.addView(profile, lp().margins(0, 0, 0, dp(18)))
        col.addView(label("Seu apelido (3 a 15 caracteres)", 14f, Pal.MUTED, bold = true))
        val input = EditText(this).apply {
            setText(nick)
            hint = tr("Ex.: Maria")
            textSize = 18f
            setTextColor(Pal.INK)
            setHintTextColor(Pal.MUTED)
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(Nick.MAX))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            background = rounded(Pal.CARD_WHITE, 12, Pal.LINE, 2)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        nickInput = input
        col.addView(input, lp().margins(0, dp(6), 0, 0))
        val counter = label("", 12f, Pal.MUTED, gravity = Gravity.END)
        fun updateCounter() {
            val n = Nick.clean(input.text.toString()).length
            counter.text = "$n/${Nick.MAX}"
            counter.setTextColor(if (n in Nick.MIN..Nick.MAX) Pal.MUTED else Pal.RED)
        }
        updateCounter()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updateCounter()
        })
        col.addView(counter, lp().margins(0, dp(4), 0, dp(20)))

        col.addView(button("Criar sala") { if (readNick()) show(Screen.CREATE) }, lp().margins(0, 0, 0, dp(12)))
        col.addView(button("Entrar em uma sala", BtnKind.SECONDARY) { if (readNick()) show(Screen.JOIN) }, lp().margins(0, 0, 0, dp(12)))
        col.addView(button("Como jogar", BtnKind.SECONDARY) { show(Screen.HOWTO) }, lp().margins(0, 0, 0, dp(12)))
        col.addView(button("Servidor online", BtnKind.SECONDARY) { askRelayUrl(null) }, lp().margins(0, 0, 0, dp(12)))
        col.addView(button("Sair do jogo", BtnKind.SECONDARY) { finish() }, lp())
        return col
    }

    /** Valida e guarda o apelido digitado no menu. */
    private fun readNick(): Boolean {
        val raw = nickInput?.text?.toString() ?: nick
        val err = Nick.validate(raw)
        if (err != null) {
            nickInput?.error = tr(err)
            toast(err)
            return false
        }
        nick = Nick.clean(raw)
        prefs.edit().putString("nick", nick).apply()
        return true
    }

    private fun buildHowTo(): View {
        val col = vbox()
        col.addView(label("Como jogar", 30f, Pal.INK, bold = true), lp().margins(0, dp(8), 0, dp(16)))
        val steps = listOf(
            "1. Um jogador cria a sala, escolhe as expansões (Família, Picante, Humor negro), o número de rodadas (3 a 15) e os tempos.",
            "2. O anfitrião decide se a sala tem senha. Com senha, o jogo gera 4 dígitos para passar aos amigos; sem senha, qualquer pessoa que achar a sala entra. Por Wi-Fi, todos na mesma rede (ou no hotspot do anfitrião) veem a sala numa lista. Por Bluetooth, escolhem a sala do anfitrião na lista (só aparecem aparelhos com o jogo). Online, as salas aparecem numa lista pública (precisa configurar o endereço do servidor no menu).",
            "3. Em cada rodada o jogo sorteia uma carta preta com uma lacuna (____). Cada jogador escolhe, entre as suas 7 cartas brancas, a que melhor completa a frase. As cartas nunca se repetem na partida.",
            "4. Quando o tempo acaba, as respostas aparecem anônimas, uma de cada vez. Dê uma nota de 1 a 5 para cada uma (menos para a sua).",
            "5. Os pontos de cada jogador são a soma das notas que recebeu. No fim das rodadas aparece o ranking de todos.",
            "O anfitrião escolhe o máximo de jogadores (2 a 20) e, antes da partida começar, pode expulsar alguém pelo botão \"Expulsar\" na lista da sala.",
            "Se quiser, escolha uma foto de perfil da galeria no menu inicial. O anfitrião pode desativar as fotos da sala.",
            "Termos de uso: o modo Humor negro é para maiores de 18 anos. O conteúdo é humor e ficção e não representa a opinião de ninguém. Quem escolhe jogar concorda em não se ofender. Não use o jogo para ofender ou perseguir pessoas de verdade; o anfitrião pode expulsar quem desrespeitar a sala.",
            "O botão de pausa (II) fica no canto da tela durante o jogo e permite sair da partida. A pausa é só no seu aparelho: a partida continua para os outros."
        )
        for (s in steps) col.addView(label(s, 16f, Pal.INK), lp().margins(0, 0, 0, dp(14)))
        col.addView(button("Voltar", BtnKind.SECONDARY) { show(Screen.MENU) }, lp().margins(0, dp(8), 0, 0))
        return col
    }

    // ------------------------------------------------------------------ foto de perfil

    private val avatarFile: File get() = File(filesDir, "avatar.jpg")

    private fun loadAvatar() {
        try {
            val f = avatarFile
            if (f.exists()) {
                val bytes = f.readBytes()
                myAvatarBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                myAvatarB64 = if (myAvatarBitmap != null) java.util.Base64.getEncoder().encodeToString(bytes) else null
            }
        } catch (_: Exception) {
            myAvatarB64 = null
            myAvatarBitmap = null
        }
    }

    /** Guarda o que foi digitado no apelido para não perder o texto quando a tela é redesenhada. */
    private fun keepTypedNick() {
        val typed = nickInput?.text?.toString()
        if (typed != null) nick = typed.trim()
    }

    private fun pickAvatar() {
        keepTypedNick()
        // Seletor de imagens do próprio Android: não precisa de permissão de acesso à galeria.
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(i, "Escolha sua foto"), REQ_PICK_PHOTO)
        } catch (_: Exception) {
            toast("Não encontrei um app de galeria neste aparelho.")
        }
    }

    private fun removeAvatar() {
        keepTypedNick()
        try { avatarFile.delete() } catch (_: Exception) { }
        myAvatarB64 = null
        myAvatarBitmap = null
        if (screen == Screen.MENU) show(Screen.MENU)
    }

    private fun saveAvatarFrom(uri: Uri) {
        Thread({
            val bytes = processAvatar(uri)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (bytes == null) {
                    toast("Não consegui usar essa imagem. Tente outra.")
                } else {
                    try { avatarFile.writeBytes(bytes) } catch (_: Exception) { }
                    myAvatarBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    myAvatarB64 = java.util.Base64.getEncoder().encodeToString(bytes)
                    if (screen == Screen.MENU) show(Screen.MENU)
                }
            }
        }, "avatar").start()
    }

    /** Lê a imagem escolhida, corrige a rotação, recorta em quadrado e reduz para 96x96 JPEG (poucos KB). */
    private fun processAvatar(uri: Uri): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val src = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

            val orientation = try {
                contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            val upright = if (degrees != 0f) {
                Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees) }, true)
            } else src

            val side = minOf(upright.width, upright.height)
            val square = Bitmap.createBitmap(upright, (upright.width - side) / 2, (upright.height - side) / 2, side, side)
            val small = Bitmap.createScaledBitmap(square, AVATAR_PX, AVATAR_PX, true)

            val out = ByteArrayOutputStream()
            var quality = 80
            do {
                out.reset()
                small.compress(Bitmap.CompressFormat.JPEG, quality, out)
                quality -= 10
            } while (out.size() > AVATAR_MAX_BYTES && quality >= 30)
            if (out.size() > AVATAR_MAX_BYTES) null else out.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }

    private fun bitmapOf(b64: String?): Bitmap? {
        if (b64 == null) return null
        val key = "${b64.length}:${b64.hashCode()}"
        bitmapCache[key]?.let { return it }
        return try {
            val bytes = java.util.Base64.getDecoder().decode(b64)
            val check = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, check)
            // Foto de outro jogador: recusa dimensões absurdas (o app só envia 96x96).
            if (check.outWidth !in 1..512 || check.outHeight !in 1..512) return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { bitmapCache[key] = it }
        } catch (_: Throwable) {
            null
        }
    }

    private fun playerAvatar(st: UiState, playerId: Int, nick: String, sizeDp: Int): View {
        val shown = if (st.settings?.avatars == false) null else bitmapOf(st.avatars[playerId])
        return avatarCircle(shown, nick, sizeDp)
    }

    // ------------------------------------------------------------------ criar sala

    private fun buildCreate(): View {
        val col = vbox()
        col.addView(label("Criar sala", 30f, Pal.INK, bold = true), lp().margins(0, dp(8), 0, 0))

        section("Como os amigos vão se conectar", col)
        col.addView(singleChoiceRow(listOf("Wi-Fi", "Bluetooth", "Online"), createMode.ordinal) {
            createMode = Mode.values()[it]
        })
        col.addView(
            label("Wi-Fi: mesma rede ou hotspot. Bluetooth: aparelhos próximos. Online: qualquer lugar, com sala na lista pública.", 12f, Pal.MUTED),
            lp().margins(0, dp(6), 0, 0)
        )

        section("Senha da sala", col)
        col.addView(singleChoiceRow(listOf("Com senha", "Sem senha"), if (createPassword) 0 else 1) {
            createPassword = it == 0
        })
        col.addView(
            label("Com senha: o jogo gera 4 dígitos e só entra quem souber. Sem senha: qualquer pessoa que achar a sala entra.", 12f, Pal.MUTED),
            lp().margins(0, dp(6), 0, 0)
        )

        section("Fotos de perfil dos jogadores", col)
        col.addView(singleChoiceRow(listOf("Permitir", "Desativar"), if (createAvatars) 0 else 1) {
            createAvatars = it == 0
        })

        section("Expansões (escolha uma ou várias)", col)
        for (cat in Category.values()) {
            val title = label("", 16f, Pal.INK, bold = true)
            val row = vbox().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
            fun refresh() {
                val on = cat in createCats
                title.text = (if (on) "✓  " else "○  ") + tr(cat.label)
                row.background = rounded(if (on) Pal.TINT else Pal.CARD_WHITE, 14, if (on) Pal.RED else Pal.LINE, 2)
            }
            row.addView(title)
            row.addView(label(cat.description, 13f, Pal.MUTED), lp().margins(dp(26), dp(2), 0, 0))
            refresh()
            row.setOnClickListener {
                if (cat in createCats) {
                    if (createCats.size > 1) createCats.remove(cat) else toast("Escolha pelo menos uma expansão.")
                    refresh()
                } else if (cat == Category.HUMOR_NEGRO) {
                    askDarkConsent(onYes = { createCats.add(cat); refresh() }, onNo = { })
                } else {
                    createCats.add(cat)
                    refresh()
                }
            }
            col.addView(row, lp().margins(0, 0, 0, dp(8)))
        }

        section("Máximo de jogadores", col)
        val maxLabel = label("", 20f, Pal.INK, bold = true, gravity = Gravity.CENTER)
        fun updateMax() { maxLabel.text = tr("{0} jogadores", createMaxPlayers) }
        updateMax()
        val maxStepper = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        maxStepper.addView(button("−", BtnKind.SECONDARY) {
            if (createMaxPlayers > Settings.MIN_PLAYERS) { createMaxPlayers--; updateMax() }
        }, lp(dp(64)))
        maxStepper.addView(maxLabel, lp(0, weight = 1f))
        maxStepper.addView(button("+", BtnKind.SECONDARY) {
            if (createMaxPlayers < Settings.MAX_PLAYERS) { createMaxPlayers++; updateMax() }
        }, lp(dp(64)))
        col.addView(maxStepper)
        col.addView(label("De ${Settings.MIN_PLAYERS} a ${Settings.MAX_PLAYERS}, contando com você", 12f, Pal.MUTED, gravity = Gravity.CENTER))

        section("Número de rodadas", col)
        val roundsLabel = label("", 20f, Pal.INK, bold = true, gravity = Gravity.CENTER)
        fun updateRounds() { roundsLabel.text = tr("{0} rodadas", createRounds) }
        updateRounds()
        val stepper = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        stepper.addView(button("−", BtnKind.SECONDARY) {
            if (createRounds > Settings.MIN_ROUNDS) { createRounds--; updateRounds() }
        }, lp(dp(64)))
        stepper.addView(roundsLabel, lp(0, weight = 1f))
        stepper.addView(button("+", BtnKind.SECONDARY) {
            if (createRounds < Settings.MAX_ROUNDS) { createRounds++; updateRounds() }
        }, lp(dp(64)))
        col.addView(stepper)
        col.addView(label("De ${Settings.MIN_ROUNDS} a ${Settings.MAX_ROUNDS}", 12f, Pal.MUTED, gravity = Gravity.CENTER))

        section("Tempo para jogar a carta branca", col)
        val options = Settings.PLAY_OPTIONS
        col.addView(singleChoiceRow(options.map { "${it}s" }, options.indexOf(createPlaySec).coerceAtLeast(0)) {
            createPlaySec = options[it]
        })

        section("Tempo de cada resposta na tela (para dar as notas)", col)
        val rateLabel = label("", 16f, Pal.INK, gravity = Gravity.CENTER)
        fun updateRate() { rateLabel.text = tr("{0} por resposta", fmtSecs(createRateSec)) }
        updateRate()
        val seek = SeekBar(this).apply {
            max = (Settings.MAX_RATING - Settings.MIN_RATING) / RATE_STEP
            progress = (createRateSec - Settings.MIN_RATING) / RATE_STEP
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    createRateSec = Settings.MIN_RATING + p * RATE_STEP
                    updateRate()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        col.addView(seek)
        col.addView(rateLabel)
        col.addView(label("De ${fmtSecs(Settings.MIN_RATING)} a ${fmtSecs(Settings.MAX_RATING)}", 12f, Pal.MUTED, gravity = Gravity.CENTER))

        col.addView(button("Criar sala") { onCreateRoom() }, lp().margins(0, dp(28), 0, dp(12)))
        col.addView(button("Voltar", BtnKind.SECONDARY) { show(Screen.MENU) }, lp())
        return col
    }

    private fun currentSettings() = Settings(createCats.toSet(), createRounds, createPlaySec, createRateSec, createAvatars, createMaxPlayers)

    private fun onCreateRoom() {
        if (!currentSettings().isValid()) { toast("Configuração inválida."); return }
        if (createMode == Mode.ONLINE) {
            startHostOnline()
            return
        }
        if (createMode == Mode.WIFI) {
            if (Lan.localAddresses().isEmpty()) {
                alert(
                    "Sem rede",
                    "Conecte-se a uma rede Wi-Fi ou ative o ponto de acesso (hotspot) do aparelho para que os amigos consigam entrar."
                )
                return
            }
            startHostWifi()
        } else {
            withBtPermissions {
                ensureBluetoothOn { adapter ->
                    requestDiscoverable { startHostBluetooth(adapter) }
                }
            }
        }
    }

    private fun newPassword(): String = if (createPassword) (1000 + Random.nextInt(9000)).toString() else ""

    private fun startHostWifi() {
        val pwd = newPassword()
        val hs = HostSession(currentSettings(), nick, pwd, data, avatar = myAvatarB64)
        val server = LanServer(Lan.PORT) { hs.attach(it) }
        try {
            server.start()
        } catch (e: Exception) {
            hs.leave()
            alert("Não foi possível abrir a sala", "Feche outras salas abertas e tente de novo.\n(${e.message})")
            return
        }
        hs.addAcceptor(server)
        // Anuncia a sala na rede para os amigos a encontrarem sem digitar IP (só enquanto o lobby está aberto).
        val beacon = LanBeacon({
            val st = hs.state
            BeaconInfo(nick, Lan.PORT, st.players.size, st.stage == Stage.LOBBY && st.players.size < (st.settings?.maxPlayers ?: 10), locked = pwd.isNotEmpty())
        })
        beacon.start()
        hs.addAcceptor(beacon)
        roomPassword = pwd
        lobbyInfo = tr("Seus amigos na mesma rede verão a sala sozinhos. Se não aparecer, o endereço é:") + " " +
            Lan.localAddresses().joinToString("  ou  ")
        attachSession(hs)
    }

    private fun startHostBluetooth(adapter: BluetoothAdapter) {
        val pwd = newPassword()
        val hs = HostSession(currentSettings(), nick, pwd, data, avatar = myAvatarB64)
        val server = BtServer(adapter) { hs.attach(it) }
        try {
            server.start()
        } catch (e: Exception) {
            hs.leave()
            alert("Não foi possível abrir a sala", "Verifique se o Bluetooth está ligado e as permissões foram concedidas.")
            return
        }
        hs.addAcceptor(server)
        roomPassword = pwd
        lobbyInfo = tr("Bluetooth: peça aos amigos para escolherem \"{0}\" na lista", deviceName(adapter))
        attachSession(hs)
    }

    private fun startHostOnline() {
        if (relayUrl.isBlank()) { askRelayUrl { startHostOnline() }; return }
        if (creating) return
        creating = true
        toast("Criando a sala online…")
        val pwd = newPassword()
        val server = relayUrl
        Thread({
            var hs: HostSession? = null
            try {
                val h = HostSession(currentSettings(), nick, pwd, data, avatar = myAvatarB64)
                hs = h
                val rh = RelayHost(
                    server, nick, pwd.isNotEmpty(),
                    info = {
                        val st = h.state
                        BeaconInfo(nick, 0, st.players.size, st.stage == Stage.LOBBY && st.players.size < (st.settings?.maxPlayers ?: 10), locked = pwd.isNotEmpty())
                    },
                    onConnection = { h.attach(it) },
                    onLost = { runOnUiThread { onRelayLost(h) } }
                )
                val id = rh.start()
                h.addAcceptor(rh)
                runOnUiThread {
                    creating = false
                    roomPassword = pwd
                    lobbyInfo = tr("Sala online {0} — ela aparece na lista pública de salas.", id)
                    attachSession(h)
                }
            } catch (e: Exception) {
                hs?.leave()
                runOnUiThread {
                    creating = false
                    alert("Não foi possível criar a sala online", "Confira o endereço do servidor (Menu > Servidor online) e a sua internet.\n(${e.message})")
                }
            }
        }, "create-online").start()
    }

    private fun onRelayLost(h: HostSession) {
        if (session !== h) return
        cleanupSession()
        show(Screen.MENU)
        alert("Conexão perdida", "A conexão com o servidor online caiu e a sala foi encerrada.")
    }

    /** Pede o endereço do servidor online (guardado no aparelho). [then] roda depois de salvar. */
    private fun askRelayUrl(then: (() -> Unit)?) {
        val input = EditText(this).apply {
            setText(relayUrl)
            hint = "https://meu-servidor.onrender.com"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Servidor online"))
            .setMessage(tr("Cole o endereço do servidor do jogo (quem publicou o relay informa). Todos os jogadores online precisam usar o mesmo."))
            .setView(input)
            .setPositiveButton(tr("Salvar")) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty() || Online.normalize(text) != null) {
                    relayUrl = text
                    prefs.edit().putString("relay", text).apply()
                    if (text.isNotEmpty()) then?.invoke()
                } else toast("Endereço inválido.")
            }
            .setNegativeButton(tr("Cancelar"), null)
            .show()
    }

    // ------------------------------------------------------------------ entrar na sala

    private fun buildJoin(): View {
        val col = vbox()
        col.addView(label("Entrar em uma sala", 30f, Pal.INK, bold = true), lp().margins(0, dp(8), 0, 0))

        section("Como se conectar", col)
        col.addView(singleChoiceRow(listOf("Wi-Fi", "Bluetooth", "Online"), joinMode.ordinal) {
            joinMode = Mode.values()[it]
            show(Screen.JOIN)
        })

        if (joinMode == Mode.WIFI) {
            section("Salas encontradas na sua rede", col)
            val box = vbox()
            roomsBox = box
            col.addView(box)
            refreshRoomList()
            startRoomSearch()
            col.addView(
                button(if (manualIp) "Esconder endereço manual" else "Não achou? Digitar o endereço", BtnKind.SECONDARY) {
                    manualIp = !manualIp
                    show(Screen.JOIN)
                },
                lp().margins(0, dp(8), 0, 0)
            )
            if (manualIp) {
                if (joinIp.isEmpty()) {
                    val mine = Lan.localAddresses().firstOrNull()
                    joinIp = if (mine != null) mine.substringBeforeLast('.') + "." else ""
                }
                val ip = EditText(this).apply {
                    setText(joinIp)
                    hint = "192.168.0.10"
                    textSize = 20f
                    setTextColor(Pal.INK)
                    setSingleLine()
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    keyListener = DigitsKeyListener.getInstance("0123456789.")
                    background = rounded(Pal.CARD_WHITE, 12, Pal.LINE, 2)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    addTextChangedListener(simpleWatcher { joinIp = it.trim() })
                }
                col.addView(ip, lp().margins(0, dp(8), 0, 0))
                col.addView(label("O endereço aparece na sala do anfitrião. Já preenchi o início com o da sua rede.", 12f, Pal.MUTED), lp().margins(0, dp(4), 0, 0))
            }
        } else if (joinMode == Mode.ONLINE) {
            section("Salas online abertas", col)
            val box = vbox()
            onlineBox = box
            col.addView(box)
            refreshOnlineList()
            startOnlinePoll()
            col.addView(button("Atualizar lista", BtnKind.SECONDARY) { stopOnlinePoll(); startOnlinePoll() }, lp().margins(0, dp(8), 0, 0))
        } else {
            section("Salas Bluetooth (só aparelhos com o jogo aberto)", col)
            val box = vbox()
            deviceListBox = box
            col.addView(box)
            col.addView(button("Procurar salas", BtnKind.SECONDARY) { startBtSearch() }, lp().margins(0, dp(8), 0, 0))
            col.addView(label("A busca leva alguns segundos. O anfitrião precisa já ter criado a sala por Bluetooth. Se não aparecer, pareie com o aparelho dele nas configurações de Bluetooth do Android e busque de novo.", 12f, Pal.MUTED), lp().margins(0, dp(4), 0, 0))
            refreshDeviceList()
            startBtSearch()
        }

        val pbox = vbox()
        passBox = pbox
        col.addView(pbox)
        refreshPassSection()

        col.addView(button("Entrar") { onJoin() }, lp().margins(0, dp(28), 0, dp(12)))
        col.addView(button("Voltar", BtnKind.SECONDARY) { show(Screen.MENU) }, lp())
        return col
    }

    private fun simpleWatcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = onChange(s?.toString() ?: "")
    }

    /** Se a sala escolhida exige senha: NONE (sem senha), OPTIONAL (não sabemos) ou REQUIRED. */
    private fun passRequirement(): PassReq = when (joinMode) {
        Mode.WIFI -> selectedRoom?.let { if (it.locked) PassReq.REQUIRED else PassReq.NONE } ?: PassReq.OPTIONAL
        Mode.ONLINE -> selectedOnline?.let { if (it.locked) PassReq.REQUIRED else PassReq.NONE } ?: PassReq.NONE
        Mode.BLUETOOTH -> PassReq.OPTIONAL
    }

    private fun refreshPassSection() {
        val box = passBox ?: return
        box.removeAllViews()
        val req = passRequirement()
        if (req == PassReq.NONE) {
            if ((joinMode == Mode.WIFI && selectedRoom != null) || (joinMode == Mode.ONLINE && selectedOnline != null)) {
                box.addView(label("Esta sala não tem senha. É só entrar.", 14f, Pal.MUTED), lp().margins(0, dp(18), 0, 0))
            }
            return
        }
        box.addView(
            label(if (req == PassReq.REQUIRED) "Senha da sala (4 dígitos)" else "Senha da sala (deixe em branco se a sala não tiver)", 16f, Pal.INK, bold = true),
            lp().margins(0, dp(22), 0, dp(8))
        )
        val pass = EditText(this).apply {
            setText(joinPass)
            hint = "0000"
            textSize = 24f
            setTextColor(Pal.INK)
            setSingleLine()
            gravity = Gravity.CENTER
            filters = arrayOf(InputFilter.LengthFilter(4))
            inputType = InputType.TYPE_CLASS_NUMBER
            background = rounded(Pal.CARD_WHITE, 12, Pal.LINE, 2)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(simpleWatcher { joinPass = it.trim() })
        }
        box.addView(pass)
    }

    private fun onJoin() {
        val req = passRequirement()
        val digits = Regex("^\\d{4}$")
        if (req == PassReq.REQUIRED && !digits.matches(joinPass)) { toast("A senha tem 4 dígitos."); return }
        if (req == PassReq.OPTIONAL && joinPass.isNotEmpty() && !digits.matches(joinPass)) { toast("A senha tem 4 dígitos."); return }
        val pass = if (req == PassReq.NONE) "" else joinPass
        when (joinMode) {
            Mode.WIFI -> {
                val room = selectedRoom
                if (room != null && room in foundRooms) {
                    beginConnect(roomKey(room), pass) { Lan.connect(room.host, room.port) }
                    return
                }
                val ok = manualIp && Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(joinIp)
                    ?.groupValues?.drop(1)?.all { it.toInt() in 0..255 } == true
                if (!ok) {
                    toast(if (manualIp) "Digite o endereço completo, por exemplo 192.168.0.10." else "Escolha uma sala da lista.")
                    return
                }
                beginConnect("lan:$joinIp:${Lan.PORT}", pass) { Lan.connect(joinIp) }
            }
            Mode.ONLINE -> {
                val room = selectedOnline
                if (room == null) { toast("Escolha uma sala da lista."); return }
                val server = relayUrl
                beginConnect(onlineKey(room), pass) { Online.join(server, room.id) }
            }
            Mode.BLUETOOTH -> {
                val dev = selectedDevice
                if (dev == null) { toast("Escolha o aparelho do anfitrião na lista."); return }
                withBtPermissions {
                    ensureBluetoothOn { adapter ->
                        beginConnect(btKey(dev), pass) { BtClient.connect(adapter, dev) }
                    }
                }
            }
        }
    }

    /** Conecta em segundo plano; mostra "Conectando..." enquanto isso. */
    private fun beginConnect(key: String, pass: String, connector: () -> LineConnection) {
        if (isBlocked(key)) {
            toast("Você foi removido desta sala. Ela volta a aparecer em ${blockMinutesLeft(key)} min.")
            return
        }
        joinKey = key
        cancelled = false
        session = null
        everInLobby = false
        darkAsked = false
        show(Screen.GAME)
        Thread({
            try {
                val conn = connector()
                runOnUiThread {
                    if (cancelled || isDestroyed) conn.closeNow()
                    else attachSession(RemoteSession(conn, nick, pass, myAvatarB64))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!cancelled && !isDestroyed) {
                        show(Screen.JOIN)
                        alert(
                            "Não foi possível conectar",
                            when (joinMode) {
                                Mode.WIFI -> "Confira o endereço e se os dois aparelhos estão na mesma rede Wi-Fi, e se a sala já foi criada."
                                Mode.ONLINE -> "A sala pode ter sido encerrada ou o servidor está fora do ar. Atualize a lista e tente de novo."
                                Mode.BLUETOOTH -> "Confira se o anfitrião já criou a sala e se o Bluetooth dele está ligado e visível."
                            }
                        )
                    }
                }
            }
        }, "connect").start()
    }

    private fun cancelConnect() {
        cancelled = true
        cleanupSession()
        show(Screen.JOIN)
    }

    // ------------------------------------------------------------------ Bluetooth

    private fun btAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun deviceName(adapter: BluetoothAdapter): String =
        try { adapter.name ?: "este aparelho" } catch (_: SecurityException) { "este aparelho" }

    private fun safeName(d: BluetoothDevice): String =
        try { d.name ?: "Aparelho sem nome" } catch (_: SecurityException) { "Aparelho sem nome" }

    private fun btPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun withBtPermissions(next: () -> Unit) {
        val missing = btPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) next()
        else {
            pendingPerm = next
            requestPermissions(missing.toTypedArray(), REQ_PERM)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        val next = pendingPerm
        pendingPerm = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) next?.invoke()
        else toast("Sem a permissão de Bluetooth não dá para jogar por Bluetooth.")
    }

    private fun ensureBluetoothOn(next: (BluetoothAdapter) -> Unit) {
        val adapter = btAdapter()
        if (adapter == null) { toast("Este aparelho não tem Bluetooth."); return }
        if (adapter.isEnabled) { next(adapter); return }
        pendingEnable = {
            if (adapter.isEnabled) next(adapter) else toast("O Bluetooth precisa estar ligado.")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE)
    }

    private fun requestDiscoverable(next: () -> Unit) {
        pendingDiscoverable = next
        val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_DISCOVERABLE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        when (requestCode) {
            REQ_ENABLE -> { val n = pendingEnable; pendingEnable = null; n?.invoke() }
            // Mesmo sem ficar "visível", quem já está pareado consegue conectar; segue em frente.
            REQ_DISCOVERABLE -> { val n = pendingDiscoverable; pendingDiscoverable = null; n?.invoke() }
            REQ_PICK_PHOTO -> {
                val uri = intent?.data
                if (resultCode == RESULT_OK && uri != null) saveAvatarFrom(uri)
            }
        }
    }

    private fun startBtSearch() {
        withBtPermissions {
            ensureBluetoothOn { adapter ->
                try {
                    btProbed.clear()
                    for (d in adapter.bondedDevices) {
                        btCandidates[d.address] = d
                        checkCachedServices(d)
                    }
                    if (!receiverRegistered) {
                        val f = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
                            addAction(BluetoothDevice.ACTION_UUID)
                            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                        }
                        registerReceiver(discoveryReceiver, f)
                        receiverRegistered = true
                    }
                    // Consultar serviços atrapalha a busca; por isso é feito quando ela termina.
                    if (!adapter.isDiscovering && !adapter.startDiscovery()) probeCandidates()
                } catch (_: SecurityException) {
                    toast("Permissão de Bluetooth negada.")
                }
                refreshDeviceList()
            }
        }
    }

    /** Consulta a lista pública de salas a cada poucos segundos enquanto a tela de entrada está aberta. */
    private fun startOnlinePoll() {
        if (pollStop != null) return
        if (relayUrl.isBlank()) {
            onlineError = "Nenhum servidor configurado. Toque em \"Servidor online\" no menu."
            refreshOnlineList()
            return
        }
        val stop = BooleanArray(1)
        pollStop = stop
        val server = relayUrl
        Thread({
            while (!stop[0]) {
                var rooms: List<OnlineRoom> = emptyList()
                var err = ""
                try {
                    rooms = Online.listRooms(server)
                } catch (e: Exception) {
                    err = "Não foi possível falar com o servidor. Confira o endereço e a internet."
                }
                if (stop[0]) break
                runOnUiThread { onOnlineRooms(rooms, err) }
                try { Thread.sleep(4000) } catch (_: InterruptedException) { break }
            }
        }, "online-poll").start()
    }

    private fun stopOnlinePoll() {
        pollStop?.set(0, true)
        pollStop = null
    }

    private fun onOnlineRooms(rooms: List<OnlineRoom>, err: String) {
        if (screen != Screen.JOIN || joinMode != Mode.ONLINE) return
        onlineRooms = rooms
        onlineError = err
        val hadSelection = selectedOnline != null
        val sel = selectedOnline
        if (sel != null && rooms.none { it.id == sel.id }) selectedOnline = null
        refreshOnlineList()
        if (hadSelection && selectedOnline == null) refreshPassSection()
    }

    private fun refreshOnlineList() {
        val box = onlineBox ?: return
        box.removeAllViews()
        if (onlineError.isNotEmpty()) {
            box.addView(label(onlineError, 14f, Pal.RED))
            return
        }
        val visible = onlineRooms.filter { !isBlocked(onlineKey(it)) }
        if (visible.isEmpty()) {
            box.addView(label("Nenhuma sala aberta agora. Crie a sua ou aguarde alguém criar.", 14f, Pal.MUTED))
            return
        }
        for (room in visible) {
            val on = room.id == selectedOnline?.id
            val row = TextView(this).apply {
                text = (if (room.locked) "🔒 " else "") + tr(if (room.players == 1) "Sala de {0}\n{1} jogador · código {2}" else "Sala de {0}\n{1} jogadores · código {2}", room.name, room.players, room.id)
                textSize = 16f
                setTextColor(Pal.INK)
                if (on) setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(if (on) Pal.TINT else Pal.CARD_WHITE, 12, if (on) Pal.RED else Pal.LINE, 2)
                setOnClickListener { selectedOnline = room; refreshOnlineList(); refreshPassSection() }
            }
            box.addView(row, lp().margins(0, 0, 0, dp(8)))
        }
    }

    /** Escuta os anúncios de sala na rede (UDP) enquanto a tela de entrada está aberta. */
    private fun startRoomSearch() {
        if (scanner != null) return
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            // Alguns aparelhos descartam pacotes de broadcast para economizar bateria sem este "lock".
            multicastLock = wifi?.createMulticastLock("cartasdocaos")?.also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        } catch (_: Exception) { }
        val sc = LanScanner({ rooms -> runOnUiThread { onRoomsChanged(rooms) } })
        try {
            sc.start()
            scanner = sc
        } catch (_: Exception) {
            toast("Não foi possível procurar salas. Digite o endereço manualmente.")
        }
    }

    private fun stopRoomSearch() {
        scanner?.stop()
        scanner = null
        try { multicastLock?.release() } catch (_: Exception) { }
        multicastLock = null
        foundRooms = emptyList()
    }

    private fun onRoomsChanged(rooms: List<LanRoom>) {
        if (screen != Screen.JOIN || joinMode != Mode.WIFI) return
        foundRooms = rooms
        val hadSelection = selectedRoom != null
        if (selectedRoom !in rooms) selectedRoom = null
        refreshRoomList()
        if (hadSelection && selectedRoom == null) refreshPassSection()
    }

    private fun refreshRoomList() {
        val box = roomsBox ?: return
        box.removeAllViews()
        val visible = foundRooms.filter { !isBlocked(roomKey(it)) }
        if (visible.isEmpty()) {
            box.addView(label("Procurando salas… peça ao anfitrião para criar a sala (os dois aparelhos na mesma rede Wi-Fi).", 14f, Pal.MUTED))
            return
        }
        for (room in visible) {
            val on = room == selectedRoom
            val row = TextView(this).apply {
                text = tr(if (room.players == 1) "Sala de {0}\n{1} jogador" else "Sala de {0}\n{1} jogadores", room.name, room.players)
                textSize = 16f
                setTextColor(Pal.INK)
                if (on) setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(if (on) Pal.TINT else Pal.CARD_WHITE, 12, if (on) Pal.RED else Pal.LINE, 2)
                setOnClickListener { selectedRoom = room; refreshRoomList(); refreshPassSection() }
            }
            box.addView(row, lp().margins(0, 0, 0, dp(8)))
        }
    }

    private fun stopDiscovery() {
        stopRoomSearch()
        stopOnlinePoll()
        try {
            if (receiverRegistered) { unregisterReceiver(discoveryReceiver); receiverRegistered = false }
            btAdapter()?.cancelDiscovery()
        } catch (_: Exception) { }
    }

    private fun refreshDeviceList() {
        val box = deviceListBox ?: return
        box.removeAllViews()
        val visible = btDevices.filter { !isBlocked(btKey(it.value)) }
        if (visible.isEmpty()) {
            box.addView(label("Nenhuma sala Bluetooth encontrada ainda…", 14f, Pal.MUTED))
            return
        }
        for ((addr, dev) in visible) {
            val on = selectedDevice?.address == addr
            val row = TextView(this).apply {
                text = safeName(dev)
                textSize = 15f
                setTextColor(Pal.INK)
                if (on) setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(if (on) Pal.TINT else Pal.CARD_WHITE, 12, if (on) Pal.RED else Pal.LINE, 2)
                setOnClickListener { selectedDevice = dev; refreshDeviceList() }
            }
            box.addView(row, lp().margins(0, 0, 0, dp(8)))
        }
    }

    // ------------------------------------------------------------------ expulsão: sala escondida por 3 minutos

    private fun roomKey(r: LanRoom) = "lan:${r.host}:${r.port}"
    private fun onlineKey(r: OnlineRoom) = "net:${r.id}"
    private fun btKey(d: BluetoothDevice) = "bt:${d.address}"

    private fun loadKicked() {
        kickedUntil.clear()
        val now = System.currentTimeMillis()
        for (part in (prefs.getString("kicked", "") ?: "").split(';')) {
            val i = part.lastIndexOf('|')
            if (i <= 0) continue
            val until = part.substring(i + 1).toLongOrNull() ?: continue
            if (until > now) kickedUntil[part.substring(0, i)] = until
        }
    }

    private fun saveKicked() {
        prefs.edit().putString("kicked", kickedUntil.entries.joinToString(";") { "${it.key}|${it.value}" }).apply()
    }

    private fun isBlocked(key: String): Boolean {
        val until = kickedUntil[key] ?: return false
        if (until > System.currentTimeMillis()) return true
        kickedUntil.remove(key)
        saveKicked()
        return false
    }

    private fun blockMinutesLeft(key: String): Int {
        val left = (kickedUntil[key] ?: return 0) - System.currentTimeMillis()
        return maxOf(1, ((left + 59_999) / 60_000).toInt())
    }

    private fun blockRoom(key: String) {
        kickedUntil[key] = System.currentTimeMillis() + KICK_BLOCK_MS
        saveKicked()
        selectedRoom = null; selectedOnline = null; selectedDevice = null
        // quando o prazo acaba, a sala volta a aparecer nas listas
        handler.postDelayed({ refreshRoomList(); refreshOnlineList(); refreshDeviceList() }, KICK_BLOCK_MS + 500)
    }

    // ------------------------------------------------------------------ sessão

    private fun attachSession(s: Session) {
        session = s
        everInLobby = false
        darkAsked = false
        selectedCard = null
        s.listener = { st -> runOnUiThread { onState(st) } }
        show(Screen.GAME)
    }

    private fun cleanupSession() {
        val s = session
        session = null
        s?.listener = null
        if (s != null) {
            // Fora da thread principal: fechar sockets na UI thread pode violar o StrictMode.
            Thread({ try { s.leave() } catch (_: Exception) { } }, "leave").start()
        }
    }

    /** "45 s", "1 min", "1 min 30 s". */
    private fun fmtSecs(n: Int): String = when {
        n < 60 -> "$n s"
        n % 60 == 0 -> "${n / 60} min"
        else -> "${n / 60} min ${n % 60} s"
    }

    /**
     * Termo de consentimento do Humor negro (18+). Só segue ([onYes]) quem toca em "Sim";
     * "Voltar" ou fechar o aviso chama [onNo], e o aviso volta a aparecer na próxima vez.
     * "Não aparecer mais" só vale se o jogador também aceitar.
     */
    private fun askDarkConsent(onYes: () -> Unit, onNo: () -> Unit) {
        if (prefs.getBoolean("dark_ok", false)) { onYes(); return }
        if (isFinishing || isDestroyed) return
        val box = vbox().apply { setPadding(dp(22), dp(8), dp(22), 0) }
        box.addView(label(
            "O modo Humor negro tem piadas pesadas e chocantes, com temas como crimes, violência e morte.\n\nAo continuar, você declara que:\n• tem 18 anos ou mais;\n• concorda com os termos de uso;\n• entende todo o conteúdo como humor e não se ofende com ele.",
            15f, Pal.INK
        ))
        val never = android.widget.CheckBox(this).apply { text = tr("Não aparecer mais") }
        box.addView(never, lp().margins(0, dp(12), 0, 0))
        var answered = false
        AlertDialog.Builder(this)
            .setTitle(tr("Conteúdo para maiores de 18 anos"))
            .setView(box)
            .setPositiveButton(tr("Sim")) { _, _ ->
                answered = true
                if (never.isChecked) prefs.edit().putBoolean("dark_ok", true).apply()
                onYes()
            }
            .setNegativeButton(tr("Voltar")) { _, _ -> answered = true; onNo() }
            .setOnCancelListener { if (!answered) { answered = true; onNo() } }
            .show()
    }

    private fun leaveToMenu() {
        cleanupSession()
        show(Screen.MENU)
    }

    private fun onState(st: UiState) {
        if (session == null || isDestroyed) return
        if (st.stage == Stage.LOBBY) {
            everInLobby = true
            if (!darkAsked && !st.isHost && st.settings?.categories?.contains(Category.HUMOR_NEGRO) == true) {
                darkAsked = true
                askDarkConsent(onYes = { }, onNo = { leaveToMenu() })
            }
        }
        if (st.stage == Stage.CLOSED) {
            val reason = st.closedReason
            val wasIn = everInLobby
            if (reason == GameEngine.KICKED_REASON) joinKey?.let { blockRoom(it) }
            cleanupSession()
            show(if (wasIn) Screen.MENU else Screen.JOIN)
            alert(if (wasIn) "Sala encerrada" else "Não foi possível entrar", reason)
            return
        }
        if (screen == Screen.GAME) renderGame(st, force = false)
    }

    private fun confirmLeave() {
        val host = session?.isHost == true
        AlertDialog.Builder(this)
            .setTitle(tr("Sair da sala?"))
            .setMessage(tr(if (host) "Você é o anfitrião: se sair, a sala será encerrada para todos." else "Você deixará a partida."))
            .setPositiveButton(tr("Sair")) { _, _ -> leaveToMenu() }
            .setNegativeButton(tr("Ficar"), null)
            .show()
    }

    private fun showPause() {
        val host = session?.isHost == true
        val msg = (if (host) tr("Você é o anfitrião: se sair, a sala será encerrada para todos.") + "\n\n" else "") +
            tr("A partida continua para os outros jogadores enquanto você está na pausa.")
        AlertDialog.Builder(this)
            .setTitle(tr("Jogo pausado"))
            .setMessage(msg)
            .setPositiveButton(tr("Continuar"), null)
            .setNegativeButton(tr("Sair da partida")) { _, _ -> leaveToMenu() }
            .show()
    }

    // ------------------------------------------------------------------ telas do jogo

    private fun renderGame(st: UiState, force: Boolean) {
        val sig = "${st.stage}|${st.screenKey}|${st.submitted}|${st.myRating}|${st.players.map { it.id }}|${st.avatars.size}"
        if (!force && sig == lastSig) {
            updateDynamic(st)
            return
        }
        if (st.screenKey.toString() != lastSig.split("|").getOrNull(1)) selectedCard = null
        lastSig = sig
        timerText = null; timerBar = null; progressText = null; confirmButton = null
        handViews.clear()
        val view: View = when (st.stage) {
            Stage.CONNECTING -> buildConnecting()
            Stage.LOBBY -> page(buildLobby(st))
            Stage.PLAYING -> page(buildPlaying(st), scroll = false)
            Stage.RATING -> page(buildRating(st), scroll = false)
            Stage.RESULT -> page(buildResult(st))
            Stage.FINAL -> page(buildFinal(st))
            Stage.CLOSED -> buildConnecting()
        }
        setContentView(view)
        updateDynamic(st)
        tick()
    }

    private fun updateDynamic(st: UiState) {
        progressText?.text = tr("Já jogaram: {0} de {1}", st.submittedCount, st.playerCount)
    }

    private fun tick() {
        val st = session?.state ?: return
        val t = timerText ?: return
        val total = st.phaseSeconds * 1000L
        if (total <= 0) return
        val remaining = maxOf(0L, total - (System.currentTimeMillis() - st.phaseStartMs))
        t.text = "${(remaining + 999) / 1000}s"
        timerBar?.progress = (remaining * 1000 / total).toInt()
    }

    private fun buildConnecting(): View {
        val col = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(spacer(120))
        col.addView(label("Conectando à sala…", 24f, Pal.INK, bold = true, gravity = Gravity.CENTER))
        col.addView(ProgressBar(this).apply { isIndeterminate = true }, lp().margins(0, dp(24), 0, dp(24)))
        col.addView(button("Cancelar", BtnKind.SECONDARY) { cancelConnect() }, lp())
        return page(col)
    }

    private fun buildLobby(st: UiState): View {
        val host = st.isHost
        val col = vbox()
        col.addView(label(if (host) "Sala criada" else "Você entrou na sala", 30f, Pal.INK, bold = true), lp().margins(0, dp(8), 0, dp(12)))
        if (host) {
            if (roomPassword.isEmpty()) {
                col.addView(label("Sala sem senha — qualquer pessoa que encontrar a sala pode entrar.", 15f, Pal.INK, bold = true), lp().margins(0, 0, 0, dp(10)))
            } else {
                col.addView(label("Senha da sala — passe para seus amigos", 14f, Pal.MUTED, bold = true))
                col.addView(
                    label(roomPassword, 52f, Pal.INK, bold = true, gravity = Gravity.CENTER).apply {
                        letterSpacing = 0.2f
                        background = rounded(Pal.CARD_WHITE, 16, Pal.INK, 2)
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                    },
                    lp().margins(0, dp(6), 0, dp(10))
                )
            }
            col.addView(label(lobbyInfo, 14f, Pal.INK))
        } else {
            col.addView(label("Aguardando o anfitrião iniciar a partida…", 16f, Pal.MUTED))
        }
        val cfg = st.settings
        if (cfg != null) {
            section("Configuração", col)
            col.addView(label(tr("Expansões:") + " " + cfg.categories.joinToString(", ") { tr(it.label) }, 15f))
            col.addView(label("Máximo de jogadores: ${cfg.maxPlayers}", 15f))
            col.addView(label("Rodadas: ${cfg.rounds}", 15f))
            col.addView(label("Tempo para jogar a carta: ${cfg.playSeconds}s", 15f))
            col.addView(label("Tempo de cada resposta na tela: ${fmtSecs(cfg.ratingSeconds)}", 15f))
        }
        section("Jogadores (${st.players.size}/${cfg?.maxPlayers ?: GameEngine.MAX_PLAYERS})", col)
        for (p in st.players) {
            val me = p.id == st.selfId
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Pal.CARD_WHITE, 12, Pal.LINE, 2)
                setPadding(dp(10), dp(8), dp(14), dp(8))
            }
            row.addView(playerAvatar(st, p.id, p.nick, 40))
            row.addView(
                label(p.nick + (if (p.isHost) "  👑" else "") + (if (me) tr("  (você)") else ""), 17f, Pal.INK, bold = me),
                lp(0, weight = 1f).margins(dp(12), 0, 0, 0)
            )
            if (host && !p.isHost) {
                row.addView(chip("Expulsar", false) { confirmKick(p.id, p.nick) }, lp(LinearLayout.LayoutParams.WRAP_CONTENT).margins(dp(8), 0, 0, 0))
            }
            col.addView(row, lp().margins(0, 0, 0, dp(6)))
        }
        if (host) {
            val start = button("Iniciar partida") { session?.start() }
            val enough = st.players.size >= GameEngine.MIN_PLAYERS_TO_START
            start.setEnabledLook(enough)
            col.addView(start, lp().margins(0, dp(20), 0, 0))
            if (!enough) col.addView(label("São necessários pelo menos ${GameEngine.MIN_PLAYERS_TO_START} jogadores.", 12f, Pal.MUTED, gravity = Gravity.CENTER))
        }
        col.addView(button("Sair da sala", BtnKind.SECONDARY) { confirmLeave() }, lp().margins(0, dp(12), 0, 0))
        return col
    }

    private fun confirmKick(id: Int, name: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(tr("Expulsar jogador"))
            .setMessage(tr("Remover {0} da sala?", name))
            .setPositiveButton(tr("Expulsar")) { _, _ -> session?.kick(id) }
            .setNegativeButton(tr("Cancelar"), null)
            .show()
    }

    /** Barra superior do jogo: rodada, tempo e botão de pausa no canto. */
    private fun topBar(st: UiState, title: String): View {
        val box = vbox()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(title, 15f, Pal.MUTED, bold = true), lp(0, weight = 1f))
        val t = label("", 20f, Pal.RED, bold = true, gravity = Gravity.END)
        timerText = t
        row.addView(t, lp(LinearLayout.LayoutParams.WRAP_CONTENT).margins(0, 0, dp(12), 0))
        val pause = TextView(this).apply {
            text = "II"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Pal.INK)
            gravity = Gravity.CENTER
            background = rounded(Pal.CARD_WHITE, 12, Pal.INK, 2)
            contentDescription = tr("Pausar")
            isClickable = true
            setOnClickListener { showPause() }
        }
        row.addView(pause, lp(dp(46), dp(46)))
        box.addView(row)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 1000
            progressTintList = ColorStateList.valueOf(Pal.RED)
        }
        timerBar = bar
        box.addView(bar, lp(h = dp(8)).margins(0, dp(8), 0, dp(10)))
        return box
    }

    private fun buildPlaying(st: UiState): View {
        val col = vbox()
        col.addView(topBar(st, "Rodada ${st.round}/${st.totalRounds}"))
        col.addView(blackCard(st.black), lp().margins(0, 0, 0, dp(12)))
        val prog = label("", 13f, Pal.MUTED, gravity = Gravity.CENTER)
        progressText = prog

        if (st.submitted) {
            col.addView(spacer(40))
            col.addView(label("Carta enviada!", 26f, Pal.GREEN, bold = true, gravity = Gravity.CENTER))
            col.addView(label("Aguardando os outros jogadores…", 16f, Pal.MUTED, gravity = Gravity.CENTER), lp().margins(0, dp(8), 0, dp(16)))
            col.addView(prog)
            return col
        }

        col.addView(label("Escolha a carta que melhor completa a frase:", 14f, Pal.MUTED, bold = true), lp().margins(0, 0, 0, dp(8)))
        val list = vbox()
        for (card in st.hand) {
            val v = whiteCard(card, 17f, selected = card == selectedCard)
            handViews.add(v to card)
            v.setOnClickListener {
                selectedCard = card
                for ((view, text) in handViews) applyWhiteCardLook(view, text == card)
                confirmButton?.setEnabledLook(true)
            }
            list.addView(v, lp().margins(0, 0, 0, dp(10)))
        }
        col.addView(ScrollView(this).apply { addView(list) }, lp(h = 0, weight = 1f))
        col.addView(prog, lp().margins(0, dp(6), 0, dp(6)))
        val confirm = button("Jogar esta carta") {
            val c = selectedCard
            if (c != null) session?.play(c)
        }
        confirm.setEnabledLook(selectedCard != null)
        confirmButton = confirm
        col.addView(confirm)
        return col
    }

    private fun buildRating(st: UiState): View {
        val col = vbox()
        col.addView(topBar(st, "Resposta ${st.revealIndex} de ${st.revealCount}"))
        col.addView(blackCard(st.black, 18f), lp().margins(0, 0, 0, dp(12)))
        col.addView(whiteCard(st.revealText, 24f), lp().margins(0, 0, 0, dp(16)))

        if (st.revealMine) {
            col.addView(label("Esta é a sua resposta!", 20f, Pal.INK, bold = true, gravity = Gravity.CENTER))
            col.addView(label("Os outros jogadores estão dando as notas.", 15f, Pal.MUTED, gravity = Gravity.CENTER), lp().margins(0, dp(6), 0, 0))
            return col
        }
        col.addView(label("Dê uma nota de 1 a 5", 16f, Pal.INK, bold = true, gravity = Gravity.CENTER), lp().margins(0, 0, 0, dp(10)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (n in 1..5) {
            val on = n <= st.myRating
            val star = TextView(this).apply {
                text = "★\n$n"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(if (on) Pal.INK else Pal.MUTED)
                setTypeface(typeface, Typeface.BOLD)
                background = rounded(if (on) Pal.GOLD else Pal.CARD_WHITE, 14, Pal.INK, 2)
                isClickable = true
                contentDescription = tr("Nota {0}", n)
                setOnClickListener { session?.rate(n) }
            }
            row.addView(star, lp(0, dp(78), 1f).margins(if (n > 1) dp(6) else 0, 0, 0, 0))
        }
        col.addView(row)
        col.addView(
            label(if (st.myRating > 0) "Sua nota: ${st.myRating} (toque para mudar)" else "Toque numa estrela", 14f, Pal.MUTED, gravity = Gravity.CENTER),
            lp().margins(0, dp(10), 0, 0)
        )
        return col
    }

    private fun buildResult(st: UiState): View {
        val col = vbox()
        col.addView(topBar(st, "Rodada ${st.round}/${st.totalRounds} — resultado"))
        if (st.resultEntries.isEmpty()) {
            col.addView(label("Ninguém jogou carta nesta rodada.", 17f, Pal.MUTED), lp().margins(0, dp(12), 0, dp(12)))
        }
        for (e in st.resultEntries) {
            val card = vbox().apply {
                background = rounded(Pal.CARD_WHITE, 14, Pal.LINE, 2)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            card.addView(label(CardText.show(e.text), 16f, Pal.INK, bold = true))
            val author = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val authorId = st.scores.firstOrNull { it.nick == e.nick }?.playerId ?: -1
            author.addView(playerAvatar(st, authorId, e.nick, 28))
            author.addView(label(tr("{0}  ·  +{1} pts ({2} notas)", e.nick, e.points, e.votes), 14f, Pal.RED, bold = true), lp().margins(dp(8), 0, 0, 0))
            card.addView(author, lp().margins(0, dp(8), 0, 0))
            col.addView(card, lp().margins(0, 0, 0, dp(10)))
        }
        section("Placar", col)
        for (s in st.scores) {
            val me = s.playerId == st.selfId
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(playerAvatar(st, s.playerId, s.nick, 28))
            line.addView(
                label(tr("{0}º  {1}{2}  —  {3} pts", s.rank, s.nick, if (s.left) tr(" (saiu)") else "", s.score), 16f, Pal.INK, bold = me),
                lp().margins(dp(8), 0, 0, 0)
            )
            col.addView(line, lp().margins(0, 0, 0, dp(6)))
        }
        col.addView(
            label(if (st.round >= st.totalRounds) "Calculando o ranking final…" else "A próxima rodada começa em instantes…", 13f, Pal.MUTED, gravity = Gravity.CENTER),
            lp().margins(0, dp(14), 0, 0)
        )
        return col
    }

    private fun buildFinal(st: UiState): View {
        val col = vbox()
        col.addView(label("🏆 Ranking final", 32f, Pal.INK, bold = true, gravity = Gravity.CENTER), lp().margins(0, dp(12), 0, dp(4)))
        col.addView(label("Pontuação de todos os jogadores", 14f, Pal.MUTED, gravity = Gravity.CENTER), lp().margins(0, 0, 0, dp(18)))
        for (s in st.scores) {
            val me = s.playerId == st.selfId
            val medal = when (s.rank) { 1 -> "🥇"; 2 -> "🥈"; 3 -> "🥉"; else -> "${s.rank}º" }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(if (s.rank == 1) Pal.TINT else Pal.CARD_WHITE, 14, if (me) Pal.RED else Pal.LINE, if (me) 3 else 2)
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            row.addView(label(medal, 22f, Pal.INK, bold = true), lp(dp(48)))
            row.addView(playerAvatar(st, s.playerId, s.nick, 44))
            row.addView(
                label(s.nick + (if (me) tr(" (você)") else "") + (if (s.left) tr(" — saiu") else ""), 18f, Pal.INK, bold = true),
                lp(0, weight = 1f).margins(dp(12), 0, 0, 0)
            )
            row.addView(label(tr("{0} pts", s.score), 18f, Pal.RED, bold = true, gravity = Gravity.END), lp(LinearLayout.LayoutParams.WRAP_CONTENT))
            col.addView(row, lp().margins(0, 0, 0, dp(8)))
        }
        col.addView(button("Voltar ao menu") { leaveToMenu() }, lp().margins(0, dp(20), 0, 0))
        return col
    }
}
