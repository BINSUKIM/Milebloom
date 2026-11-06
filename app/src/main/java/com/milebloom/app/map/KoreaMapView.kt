package com.milebloom.app.map

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.milebloom.app.render.LandscapeShapes
import com.milebloom.app.ui.Motion
import com.milebloom.app.ui.UiTone
import kotlin.math.hypot

/**
 * 전국 지도 — **한 장의 그림** (D-029, 30번 6.5, 32번 P01~P08).
 *
 * - 내장 실루엣(K1) 위에 차를 세운 곳마다 원 하나. **선·경로·지명·도로 없음**, 원끼리 잇지 않는다 (D-005)
 * - 확대·끌기 없음. 누르면 [onTap]으로 그 장소(겹쳤으면 묶음 전체)의 id를 넘긴다 (D-042)
 * - 원: 대표 색(마지막 도착의 오늘의 색) 불투명도 0.6, 외곽선 없음 — 겹치면 짙어진다. 누른 원은 테두리 3 dp
 * - 오늘 도착한 원만 테두리가 한 번 퍼졌다 사라진다(1.2초, [playRing]). 반복 없음
 * - [compact]: 목록 옆 작은 지도(내비 옆 분할). 누르지 않는다
 */
class KoreaMapView(context: Context) : View(context) {

    data class Dot(val id: Long, val lat: Double, val lon: Double, val visits: Int, val color: Int)

    var onTap: (List<Long>) -> Unit = {}

    private val density = resources.displayMetrics.density
    private var dots: List<Dot> = emptyList()
    private var selected: Set<Long> = emptySet()
    private var tone: UiTone? = null
    var compact = false
        set(v) { field = v; isClickable = !v; invalidate() }

    private val land = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val mapPath = Path()
    private val m = Matrix()
    private var fit = MapGeo.Fit(0f, 0f, 0f)
    private var circles: List<MapGeo.Circle> = emptyList()

    private var ringId: Long? = null
    private var ringT = -1f
    private var ringAnim: ValueAnimator? = null

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun set(tone: UiTone, dots: List<Dot>, selected: Set<Long>) {
        this.tone = tone
        this.dots = dots.filter { MapGeo.inRange(it.lat, it.lon) }
        this.selected = selected
        contentDescription = "전국 지도, 원 ${this.dots.size}개"
        relayout()
        invalidate()
    }

    /** 오늘 도착한 원에 퍼지는 테두리 한 번. 움직임이 허용되지 않으면(Lv3·시스템 설정) 하지 않는다 */
    fun playRing(id: Long) {
        stopRing()
        if (!Motion.on || dots.none { it.id == id }) return
        ringId = id
        ringAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RING_MS
            interpolator = Motion.EASE_OUT
            addUpdateListener { ringT = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { ringT = -1f; ringId = null; invalidate() }
            })
            start()
        }
    }

    /** 기어 P 이탈·화면 닫힘 — 끝 상태로 */
    fun stopRing() {
        ringAnim?.cancel(); ringAnim = null
        ringT = -1f; ringId = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = relayout()

    private fun relayout() {
        if (width == 0 || height == 0) return
        val pad = (if (compact) 10f else 48f) * density
        fit = MapGeo.fit(width.toFloat(), height.toFloat(), pad)
        m.setScale(fit.scale, fit.scale)
        m.postTranslate(fit.left, fit.top)
        mapPath.reset()
        LandscapeShapes.koreaMap.transform(m, mapPath)
        // 큰 원을 먼저 그려 작은 원이 위에 보이게
        circles = dots.map { MapGeo.Circle(it.id, fit.px(it.lon), fit.py(it.lat), MapGeo.radius(it.visits, fit.scale)) }
            .sortedByDescending { it.r }
    }

    override fun onDraw(canvas: Canvas) {
        val t = tone ?: return
        canvas.drawColor(t.mapBg)
        land.color = t.mapLand
        canvas.drawPath(mapPath, land)
        val byId = dots.associateBy { it.id }
        for (c in circles) {
            fill.color = ColorUtils.setAlphaComponent(byId.getValue(c.id).color, 0x99) // 0.6
            canvas.drawCircle(c.cx, c.cy, c.r, fill)
        }
        ring.strokeWidth = (if (compact) 2f else 3f) * density
        ring.color = t.mapSelect
        for (c in circles) if (c.id in selected) canvas.drawCircle(c.cx, c.cy, c.r, ring)
        val rid = ringId
        if (rid != null && ringT >= 0f) circles.find { it.id == rid }?.let { c ->
            ring.color = ColorUtils.setAlphaComponent(byId.getValue(c.id).color, (0xCC * (1f - ringT)).toInt())
            canvas.drawCircle(c.cx, c.cy, c.r + RING_GROW_DP * density * ringT, ring)
        }
    }

    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (compact) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; return true }
            MotionEvent.ACTION_UP -> {
                if (hypot(e.x - downX, e.y - downY) > ViewConfiguration.get(context).scaledTouchSlop * 2) return true
                val ids = MapGeo.tap(circles, e.x, e.y, TOUCH_R_DP * density)
                if (ids.isNotEmpty()) { performClick(); onTap(ids) }
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDetachedFromWindow() { stopRing(); super.onDetachedFromWindow() }

    private companion object {
        /** 보이는 원보다 넓게 받는 터치 반경 — 지름 76 dp (32번 2.2) */
        const val TOUCH_R_DP = 38f
        const val RING_MS = 1_200L
        const val RING_GROW_DP = 28f
    }
}
