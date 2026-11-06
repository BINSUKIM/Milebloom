package com.milebloom.app

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.milebloom.app.location.ParkLocator
import com.milebloom.app.render.Season
import com.milebloom.app.ui.MbUi
import com.milebloom.app.ui.Motion
import com.milebloom.app.ui.MbUi.dp
import com.milebloom.app.ui.UiTone

/**
 * 첫 실행 권한 안내 — **기어 P에서** 풍경 위 카드 한 장 (D-061·D-080·D-083).
 *
 * 런타임 권한 2개(P 지점 좌표 `FUSED_LOCATION`, 정차 판별 `CAR_SPEED`)를 한 흐름으로 묻는다.
 * 시스템 창 문구가 용도와 다르게 나오므로(속도 → '위치 정보', Pleos 위치 → '알 수 없는 작업', 5-C 실측)
 * 묻기 전에 무엇에 쓰는지와 창에 보일 문구를 먼저 알린다. 실차 문구는 미확인이라 '보일 수 있습니다' (D-080).
 *
 * - 주행 중(DO 요구)·기어 P가 아닐 때는 보이지 않는다
 * - '나중에'는 이번 실행 동안만 미룬다. 다음에 앱을 켜고 P이면 다시 보인다
 * - 한 번 답했으면 다시 띄우지 않는다. 거부했으면 무엇이 달라지는지 한 번 알리고, 다시 켜는 길은 설정 화면에 둔다
 * - 거부해도 앱은 동작한다: 주차한 곳이 남지 않고, 차가 서도 풍경이 계속 흐른다
 */
class PermissionIntro(private val activity: Activity, root: FrameLayout) {

    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val layer = FrameLayout(activity).apply {
        isClickable = true // 카드 밖을 눌러도 뒤의 버튼이 눌리지 않게
        visibility = View.GONE
    }

    /** 카드가 떠 있는 동안 뒤 풍경을 흐리게 하는 일 — 화면이 맡는다(풍경 뷰에 흐림 효과) */
    var onBackdrop: (Boolean) -> Unit = {}
    private val card = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var tone = UiTone(night = false, season = Season.AUTUMN)
    private var parked = false
    private var deferred = false
    private var page = Page.NONE

    private enum class Page { NONE, INTRO, RESULT }

    /** 카드가 떠 있는 동안 주차 화면의 인사·버튼을 숨긴다 */
    var onShowingChanged: (Boolean) -> Unit = {}
    val isShowing: Boolean get() = layer.visibility == View.VISIBLE

    init {
        layer.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            val m = dp(activity, 40)
            setMargins(m, m, m, m)
        })
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
    }

    fun missing(): List<String> = PERMISSIONS.filter {
        activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    fun setTone(t: UiTone) {
        if (t.night == tone.night && t.season == tone.season) return
        tone = t
        if (page != Page.NONE) build(page)
    }

    /** 신호가 바뀔 때마다 부른다. @param parkedNow 기어 P이고 DO 요구가 없다 */
    fun onParked(parkedNow: Boolean) {
        parked = parkedNow
        if (parked && page == Page.NONE && !prefs.getBoolean(KEY_DONE, false) && !deferred) {
            if (missing().isEmpty()) markDone() else build(Page.INTRO)
        }
        refresh()
    }

    /** 설정 화면의 '다시 묻기' */
    fun askAgain() {
        val m = missing()
        if (m.isNotEmpty()) activity.requestPermissions(m.toTypedArray(), REQ_AGAIN)
    }

    /**
     * [Activity.onRequestPermissionsResult]에서 부른다. 이 클래스가 물은 것이면 true.
     * 허용·거부와 관계없이 한 번 답했으면 첫 안내는 끝이다.
     */
    fun onResult(requestCode: Int): Boolean {
        if (requestCode == REQ_AGAIN) return true
        if (requestCode != REQ) return false
        markDone()
        if (missing().isEmpty()) close() else build(Page.RESULT)
        return true
    }

    private fun markDone() = prefs.edit().putBoolean(KEY_DONE, true).apply()

    /** 사용자가 닫을 때만 짧게 사라진다. 기어가 P에서 벗어나 닫힐 때는 [refresh]가 바로 숨긴다 (D-088) */
    private fun close() {
        page = Page.NONE
        if (isShowing && parked) Motion.exit(card, dy = dp(activity, 12).toFloat()) { refresh() } else refresh()
    }

    private fun refresh() {
        val show = page != Page.NONE && parked
        val was = isShowing
        if (!show) Motion.snap(card) // 진행 중인 움직임을 기다리지 않는다
        layer.visibility = if (show) View.VISIBLE else View.GONE
        if (show && !was) Motion.enter(card, dy = dp(activity, 20).toFloat())
        // 뒤 풍경을 옅게 어둡혀 카드가 풍경과 섞이지 않게 한다 (흐림은 [onBackdrop])
        layer.setBackgroundColor(if (tone.night) 0x59000000 else 0x33000000)
        if (was != show) { onBackdrop(show); onShowingChanged(show) }
    }

    private fun build(p: Page) {
        page = p
        val c = activity
        card.removeAllViews()
        val pad = dp(c, 48)
        card.setPadding(pad, dp(c, 44), pad, dp(c, 40))
        card.background = MbUi.rounded(ColorUtils.setAlphaComponent(tone.panel, 0xF5), dp(c, 32).toFloat())
        card.elevation = dp(c, 6).toFloat()
        // 단독 화면에서는 너무 넓어지지 않게 가운데 760 dp로 묶는다
        val maxW = dp(c, 760)
        card.layoutParams = (card.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (layer.width > 0 && layer.width - dp(c, 80) <= maxW) -1 else maxW
        }
        val wasShowing = isShowing
        if (p == Page.INTRO) buildIntro() else buildResult()
        refresh()
        // 이미 떠 있던 카드의 내용이 바뀌면(안내 → 결과) 항목만 차례로 (D-088)
        if (wasShowing) Motion.stagger(card, dy = dp(activity, 12).toFloat())
    }

    private fun buildIntro() {
        val c = activity
        card.addView(MbUi.text(c, 36f, tone.text, bold = true, s = "권한 두 가지를 묻습니다"))
        row("주차한 곳", "차를 세운 곳만 남깁니다. 지나온 길은 남기지 않습니다.", top = 32)
        row("차가 섰는지", "차가 서면 풍경도 쉬어 가게 합니다. 속도는 저장하지 않습니다.", top = 24)
        // D-080: 낯선 문구 때문에 거부하면 다시 묻지 않으므로 미리 알린다
        val notice = MbUi.text(c, 24f, tone.text, s = "권한 창이 두 번 뜹니다. 속도는 ‘위치 정보’로, Pleos 위치 권한은 ‘알 수 없는 작업’으로 보일 수 있습니다.").apply {
            val p = dp(c, 24)
            setPadding(p, dp(c, 20), p, dp(c, 20))
            background = MbUi.rounded(tone.card(null), dp(c, 20).toFloat())
        }
        card.addView(notice, lp(top = 32))
        card.addView(MbUi.text(c, 24f, tone.sub, s = "허용하지 않아도 앱은 쓸 수 있습니다.\n설정에서 언제든 다시 켤 수 있습니다."), lp(top = 20))
        buttons(
            MbUi.pill(c, "계속", tone.text, tone.panel, bold = true) {
                val m = missing()
                if (m.isEmpty()) { markDone(); close() } else activity.requestPermissions(m.toTypedArray(), REQ)
            },
            MbUi.pill(c, "나중에", tone.card(null), tone.text) { deferred = true; close() },
        )
    }

    private fun buildResult() {
        val c = activity
        val m = missing()
        card.addView(MbUi.text(c, 36f, tone.text, bold = true, s = "꺼진 권한이 있습니다"))
        val lines = buildList {
            if (ParkLocator.PERMISSION in m) add("주차한 곳이 남지 않습니다.")
            if (SPEED_PERMISSION in m) add("차가 서도 풍경이 계속 흐릅니다.")
        }
        card.addView(MbUi.text(c, 28f, tone.text, s = lines.joinToString("\n")), lp(top = 28))
        card.addView(MbUi.text(c, 24f, tone.sub, s = "설정 › 위치·속도 권한에서 다시 켤 수 있습니다."), lp(top = 20))
        buttons(MbUi.pill(c, "확인", tone.text, tone.panel, bold = true) { close() })
    }

    private fun row(label: String, body: String, top: Int) {
        val c = activity
        card.addView(MbUi.text(c, 28f, tone.text, bold = true, s = label), lp(top = top))
        card.addView(MbUi.text(c, 24f, tone.sub, s = body), lp(top = 8))
    }

    private fun buttons(vararg b: TextView) {
        val c = activity
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        b.forEachIndexed { i, v ->
            v.minWidth = dp(c, 200)
            row.addView(v, LinearLayout.LayoutParams(-2, dp(c, MbUi.PILL_H)).apply {
                if (i > 0) marginStart = dp(c, 16)
            })
        }
        card.addView(row, lp(top = 36))
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, top) }

    companion object {
        const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
        val PERMISSIONS = listOf(ParkLocator.PERMISSION, SPEED_PERMISSION)
        private const val PREFS = "milebloom"
        private const val KEY_DONE = "permission_intro_done"
        private const val REQ = 1
        const val REQ_AGAIN = 2
    }
}
