package com.cartasdocaos.game.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.cartasdocaos.game.core.CardText
import com.cartasdocaos.game.core.I18n

/** Traduz um texto da interface para o idioma do sistema (veja [I18n]). */
fun tr(s: String): String = I18n.tr(s)

/** Monta uma frase traduzida: modelo em português com {0}, {1}... */
fun tr(template: String, vararg args: Any): String = I18n.tr(template, *args)

/** Paleta: papel creme, tinta preta, vermelho e amarelo como destaques. */
object Pal {
    const val BG = 0xFFF3EEE5.toInt()
    const val INK = 0xFF16130F.toInt()
    const val MUTED = 0xFF6B645A.toInt()
    const val CARD_WHITE = 0xFFFFFFFF.toInt()
    const val LINE = 0xFFD9D2C5.toInt()
    const val RED = 0xFFD62839.toInt()
    const val GOLD = 0xFFF2B705.toInt()
    const val GREEN = 0xFF2E8B57.toInt()
    const val TINT = 0xFFFFF4D6.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.rounded(fill: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
    }

fun Context.label(
    text: CharSequence,
    sp: Float = 16f,
    color: Int = Pal.INK,
    bold: Boolean = false,
    gravity: Int = Gravity.START
): TextView = TextView(this).apply {
    this.text = tr(text.toString())
    textSize = sp
    setTextColor(color)
    this.gravity = gravity
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

enum class BtnKind { PRIMARY, SECONDARY, DANGER }

fun Context.button(text: String, kind: BtnKind = BtnKind.PRIMARY, onClick: () -> Unit): TextView {
    val (fill, fg, stroke) = when (kind) {
        BtnKind.PRIMARY -> Triple(Pal.INK, 0xFFFFFFFF.toInt(), Pal.INK)
        BtnKind.SECONDARY -> Triple(Pal.CARD_WHITE, Pal.INK, Pal.INK)
        BtnKind.DANGER -> Triple(Pal.RED, 0xFFFFFFFF.toInt(), Pal.RED)
    }
    return TextView(this).apply {
        this.text = tr(text)
        textSize = 17f
        setTextColor(fg)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        minHeight = dp(54)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = RippleDrawable(ColorStateList.valueOf(0x33808080), rounded(fill, 14, stroke, 2), null)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
}

/** Marca o botão como desabilitado/habilitado (visual e clique). */
fun TextView.setEnabledLook(enabled: Boolean) {
    isEnabled = enabled
    alpha = if (enabled) 1f else 0.4f
}

fun Context.chip(text: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
    this.text = tr(text)
    textSize = 15f
    gravity = Gravity.CENTER
    setPadding(dp(14), dp(10), dp(14), dp(10))
    applyChipLook(this, selected)
    isClickable = true
    setOnClickListener { onClick() }
}

fun Context.applyChipLook(v: TextView, selected: Boolean) {
    v.setTextColor(if (selected) 0xFFFFFFFF.toInt() else Pal.INK)
    v.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    v.background = rounded(if (selected) Pal.INK else Pal.CARD_WHITE, 20, Pal.INK, 2)
}

/** Linha de opções de escolha única. [onSelect] recebe o índice escolhido. */
fun Context.singleChoiceRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val chips = ArrayList<TextView>()
    options.forEachIndexed { i, text ->
        val c = chip(text, i == selected) {
            chips.forEachIndexed { j, other -> applyChipLook(other, j == i) }
            onSelect(i)
        }
        chips.add(c)
        row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (i > 0) leftMargin = dp(8)
        })
    }
    return row
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

fun LinearLayout.LayoutParams.margins(l: Int, t: Int, r: Int, b: Int) = apply { setMargins(l, t, r, b) }

fun Context.spacer(heightDp: Int): View = View(this).also {
    it.layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
}

/** Carta preta: texto branco sobre fundo preto. */
fun Context.blackCard(text: String, sp: Float = 22f): TextView = TextView(this).apply {
    this.text = CardText.show(text)
    textSize = sp
    setTextColor(0xFFFFFFFF.toInt())
    setTypeface(typeface, Typeface.BOLD)
    setPadding(dp(20), dp(20), dp(20), dp(20))
    background = rounded(Pal.INK, 18)
}

/** Carta branca: texto escuro sobre fundo branco com borda. */
fun Context.whiteCard(text: String, sp: Float = 17f, selected: Boolean = false): TextView = TextView(this).apply {
    this.text = CardText.show(text)
    textSize = sp
    setTextColor(Pal.INK)
    setTypeface(typeface, Typeface.BOLD)
    setPadding(dp(16), dp(16), dp(16), dp(16))
    applyWhiteCardLook(this, selected)
}

fun Context.applyWhiteCardLook(v: TextView, selected: Boolean) {
    v.background = if (selected) rounded(Pal.TINT, 16, Pal.RED, 3) else rounded(Pal.CARD_WHITE, 16, Pal.LINE, 2)
}

private val AVATAR_COLORS = intArrayOf(
    0xFFD62839.toInt(), 0xFF2E8B57.toInt(), 0xFF3A6EA5.toInt(),
    0xFF8E5BB5.toInt(), 0xFFE07A1F.toInt(), 0xFF1B998B.toInt()
)

/** Foto de perfil redonda; sem foto, mostra a inicial do apelido sobre uma cor. */
fun Context.avatarCircle(bitmap: Bitmap?, nick: String, sizeDp: Int): View {
    val size = dp(sizeDp)
    if (bitmap != null) {
        return ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }
    val color = AVATAR_COLORS[(nick.lowercase().hashCode() and 0x7fffffff) % AVATAR_COLORS.size]
    return TextView(this).apply {
        text = nick.trim().firstOrNull()?.uppercase() ?: "?"
        gravity = Gravity.CENTER
        textSize = sizeDp * 0.4f
        setTextColor(0xFFFFFFFF.toInt())
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(size, size)
    }
}
