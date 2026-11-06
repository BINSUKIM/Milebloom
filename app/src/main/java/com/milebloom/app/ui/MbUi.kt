package com.milebloom.app.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.graphics.text.LineBreakConfig
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.milebloom.app.R
import com.milebloom.app.ambient.Oklch
import com.milebloom.app.render.MbPalette
import com.milebloom.app.render.Season
import com.milebloom.app.render.Tone

/**
 * 주차 화면 UI의 색·글꼴·크기 (30번 2.7 UI 색, 6.6 글꼴, 32번 2장).
 *
 * - 글자는 **주차 톤(MD/MN)에만** 있다. 주행 톤에는 글자가 없다
 * - 글꼴 Pretendard Regular·SemiBold 둘만. 차량 화면 최소 24 sp
 * - 터치 목표는 높이 88 dp(작은 것도 76 dp 이상)
 */
class UiTone(val night: Boolean, val season: Season) {
    val text: Int = if (night) 0xFFE6E2D8.toInt() else 0xFF2F3A35.toInt()
    val sub: Int = if (night) 0xFFA9B0AA.toInt() else 0xFF5E6A64.toInt()
    val icon: Int = if (night) 0xFFC9CFCB.toInt() else 0xFF3E4A45.toInt()
    /** 풍경 위에 얹는 버튼 바탕 — `voiceBtnFill` MD 85% / MN */
    val pill: Int = if (night) 0xFF2A3236.toInt() else 0xD9FFFFFF.toInt()

    /** 목록·설정 판 바탕 (시안 `panelBg`): 낮은 구름색, 밤은 하늘 위쪽과 구름색 7:3 */
    val panel: Int = run {
        val p = MbPalette.scene.getValue(season)
        if (night) p.getValue(Tone.MEMORY_NIGHT).let { ColorUtils.blendARGB(it.skyTop, it.cloud, 0.3f) }
        else p.getValue(Tone.MEMORY_DAY).cloud
    }
    /** 판 위 구분선 */
    val rule: Int = ColorUtils.setAlphaComponent(sub, 0x40)

    /** 기록 카드 바탕 — 그날의 색을 판 위에 조금 섞는다 (시안 P10) */
    fun card(accent: Int?): Int = if (accent == null) ColorUtils.blendARGB(panel, sub, if (night) 0.18f else 0.10f)
        else ColorUtils.blendARGB(panel, accent or (0xFF shl 24), if (night) 0.22f else 0.16f)

    /** 그날의 색 점 — 목록 왼쪽 (지도 원과 같은 계산: 낮 HSV(H, S×0.90, 0.86) / 밤 (S×0.85, 0.66), D-071) */
    fun dot(accent: Int?): Int {
        if (accent == null) return sub
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(accent, hsv)
        hsv[1] *= if (night) 0.85f else 0.90f
        hsv[2] = if (night) 0.66f else 0.86f
        return android.graphics.Color.HSVToColor(hsv)
    }

    // ── 전국 지도 (30번 6.5, D-071) ──
    private val memory = MbPalette.scene.getValue(season).getValue(if (night) Tone.MEMORY_NIGHT else Tone.MEMORY_DAY)
    /** 지도 바탕 `skyLow` */
    val mapBg: Int = memory.skyLow
    /** 실루엣: `ground`를 흰색 쪽으로 낮 30% · 밤 12%(OKLCH L 0.41에서 자름) */
    val mapLand: Int = if (night) Oklch.capLightness(ColorUtils.blendARGB(memory.ground, 0xFFFFFFFF.toInt(), 0.12f), 0.41)
        else ColorUtils.blendARGB(memory.ground, 0xFFFFFFFF.toInt(), 0.30f)
    /** 누른 원 테두리: 낮 흰색 · 밤 `uiText` */
    val mapSelect: Int = if (night) text else 0xFFFFFFFF.toInt()
}

object MbUi {
    const val PILL_H = 88
    const val SMALL_PILL_H = 76

    fun regular(c: Context): Typeface = ResourcesCompat.getFont(c, R.font.pretendard_regular) ?: Typeface.DEFAULT
    fun semibold(c: Context): Typeface = ResourcesCompat.getFont(c, R.font.pretendard_semibold) ?: Typeface.DEFAULT_BOLD

    fun dp(c: Context, v: Number): Int = (v.toFloat() * c.resources.displayMetrics.density).toInt()

    fun text(c: Context, sp: Float, color: Int, bold: Boolean = false, s: CharSequence = ""): TextView = TextView(c).apply {
        text = s
        typeface = if (bold) semibold(c) else regular(c)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        includeFontPadding = false
        setLineSpacing(0f, 1.3f)
        lineBreakWordStyle = LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE // 어절 중간에서 줄을 나누지 않는다
    }

    /** 둥근 알약 버튼. 버튼처럼 보이되 머티리얼 장식(그림자·대문자)은 쓰지 않는다 */
    fun pill(
        c: Context,
        label: CharSequence,
        fill: Int,
        textColor: Int,
        bold: Boolean = false,
        heightDp: Int = PILL_H,
        sp: Float = 28f,
        onClick: () -> Unit,
    ): TextView = text(c, sp, textColor, bold, label).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        val h = dp(c, heightDp)
        minHeight = h
        minimumHeight = h
        val padH = dp(c, 40)
        setPadding(padH, 0, padH, 0)
        background = rounded(fill, h / 2f, textColor)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        Motion.pressable(this) // 누르는 동안 0.97배 (D-088)
    }

    fun rounded(fill: Int, radius: Float, rippleBase: Int? = null): android.graphics.drawable.Drawable {
        val shape = GradientDrawable().apply {
            cornerRadius = radius
            setColor(fill)
        }
        if (rippleBase == null) return shape
        val mask = GradientDrawable().apply { cornerRadius = radius; setColor(0xFFFFFFFF.toInt()) }
        return RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(rippleBase, 0x30)), shape, mask)
    }

    fun gone(vararg v: View) = v.forEach { it.visibility = View.GONE }
}

/**
 * 글자 뒤 받침 — 테두리 없는 타원형 번짐 (D-084). 버튼(단색 알약)과 모양이 달라 눌리는 것으로 읽히지 않는다.
 * 가운데는 [color]의 [alpha], 가장자리로 갈수록 투명해진다.
 */
class GlowDrawable(private val color: Int, private val alpha: Int) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var key = 0L

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val h = b.height().toFloat()
        val k = b.width().toLong() shl 32 or b.height().toLong()
        if (k != key) {
            key = k
            val c = ColorUtils.setAlphaComponent(color, alpha)
            paint.shader = android.graphics.RadialGradient(0f, 0f, h / 2f,
                intArrayOf(c, c, ColorUtils.setAlphaComponent(color, 0)), floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP)
        }
        canvas.save()
        canvas.translate(b.exactCenterX(), b.exactCenterY())
        canvas.scale(b.width() / h, 1f)
        canvas.drawCircle(0f, 0f, h / 2f, paint)
        canvas.restore()
    }

    override fun setAlpha(a: Int) = Unit
    override fun setColorFilter(cf: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
