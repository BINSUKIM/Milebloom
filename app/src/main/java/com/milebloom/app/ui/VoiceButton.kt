package com.milebloom.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * 주행 중 음성 기록 버튼 (D-010, 12번 8장 필수 설계 조건, 30번 3.3·2.7).
 *
 * - 위치 고정: 오른쪽 아래(조수석 쪽), 여백 48 dp, 지름 120 dp
 * - 단일 탭. 녹음 종료는 자동이고 피드백은 소리로 한다 — 이 뷰는 듣는 동안 얇은 테두리 하나만 더한다
 * - 주행 중에 쓰는 버튼이라 움직임이 없다: 맥동·확대·회전 없이 상태가 바로 바뀐다. 누르면 바탕색만 조금 짙어진다 (D-088)
 * - 경고색(빨강·주황)을 쓰지 않는다
 */
class VoiceButton(context: Context) : View(context) {

    var night: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }

    var listening: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        contentDescription = "음성 기록"
        isClickable = true
        isFocusable = true
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) / 2f
        // voiceBtnFill JD #FFFFFF 72% · JN #2A3236 90% / voiceBtnIcon JD #5F6B66 · JN #A9B3B0
        val icon = if (night) 0xFFA9B3B0.toInt() else 0xFF5F6B66.toInt()
        val base = if (night) 0xE62A3236.toInt() else 0xB8FFFFFF.toInt()
        // 눌린 동안 바탕만 아이콘 색 쪽으로 조금 — 크기·위치는 움직이지 않는다 (주행 중 새 움직임 없음, D-088)
        fill.color = if (isPressed) androidx.core.graphics.ColorUtils.blendARGB(base, icon or (0xFF shl 24), 0.18f) else base
        val inset = if (listening) r * 0.08f else 0f
        canvas.drawCircle(cx, cy, r - inset, fill)
        if (listening) {
            stroke.color = icon
            stroke.strokeWidth = r * 0.04f
            canvas.drawCircle(cx, cy, r - stroke.strokeWidth / 2f, stroke)
        }
        // 마이크: 캡슐 몸통 + 받침 호 + 기둥 + 밑받침
        val u = r / 60f // 지름 120 dp 기준 단위
        body.color = icon
        rect.set(cx - 9 * u, cy - 28 * u, cx + 9 * u, cy + 6 * u)
        canvas.drawRoundRect(rect, 9 * u, 9 * u, body)
        stroke.color = icon
        stroke.strokeWidth = 4.5f * u
        rect.set(cx - 17 * u, cy - 14 * u, cx + 17 * u, cy + 15 * u)
        canvas.drawArc(rect, 10f, 160f, false, stroke)
        canvas.drawLine(cx, cy + 15 * u, cx, cy + 25 * u, stroke)
        canvas.drawLine(cx - 10 * u, cy + 26 * u, cx + 10 * u, cy + 26 * u, stroke)
    }
}
