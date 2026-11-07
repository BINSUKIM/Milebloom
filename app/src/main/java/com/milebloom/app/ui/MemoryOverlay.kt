package com.milebloom.app.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.milebloom.app.ambient.ArrivalGreeting
import com.milebloom.app.map.KoreaMapView
import com.milebloom.app.map.MapGeo
import com.milebloom.app.record.Records
import com.milebloom.app.ui.MbUi.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 주차 화면 (Memory Mode) — 풍경 위에 얹는 글자와 버튼, 그리고 주차 중에만 여는 판 (32번 시안 M01~M04·M11~M13·P10·P12, D-082).
 *
 * **기어 P에서만 보인다.** [setParked]가 false가 되는 순간(D·R·N, DO 요구) 연출·판·편집을 모두 거두고 즉시 숨긴다.
 * 남은 연출은 다음 P로 넘기지 않는다 (30번 6.4).
 *
 * 홈(풍경 위)
 * - 도착(P 체결): 0~1.5초 비움(풍경이 주차 톤으로 바뀌는 동안) → 인사 한 줄 + TTS → 버튼
 * - 승차(앱을 켰을 때 P): 짧은 인사(소리 없음)와 버튼
 * - 버튼: 전국 지도·음성 기록·설정 (M01). 지도는 버튼으로 연다 — 자동으로 넘어가지 않는다 (D-071)
 *
 * 판(주차 중에만)
 * - 전국 지도(P01~P08, 5-H): 차를 세운 곳에만 원. 확대 없이 눌러서 좁혀 본다 —
 *   겹친 원 → 근처 장소 목록 → 그 장소의 방문 기록(이름 붙이기·이 장소 지우기) → 그날 (D-042·D-030).
 *   단독 화면은 지도 왼쪽 + 판 오른쪽 640 dp, 내비 옆 분할은 판이 화면을 채우고 작은 지도를 남긴다 (32번 2.2)
 * - 음성 기록: 시간순 전체 목록(P12) → 그날(P10의 글 부분): 도착 인사와 그 주행에서 남긴 말, 확인·수정·삭제
 * - 설정: 실내 조명 연동, 주차한 곳 남기기, 권한 다시 묻기, 기록 모두 지우기 (D-030)
 */
class MemoryOverlay(private val activity: Activity, root: FrameLayout, private val deps: Deps) {

    interface Deps {
        val records: Records
        fun confirmMemo(id: Long)
        fun editMemo(id: Long, text: String)
        fun deleteMemo(id: Long)
        fun clearAll()
        fun renamePlace(id: Long, label: String?)
        fun deletePlace(id: Long)
        var ambientLink: Boolean
        var keepParkPoints: Boolean
        fun missingPermissions(): List<String>
        fun askPermissions()
        fun openSystemSettings(): Boolean
        fun speak(text: String)
        fun stopSpeaking()
    }

    /** 인사·버튼이 차지하는 자리(풍경 뷰 좌표). 보이지 않으면 null — 풍경이 그 자리의 풀을 비운다 */
    var onHomeBounds: (android.graphics.RectF?) -> Unit = {}

    private val c = activity
    private val main = Handler(Looper.getMainLooper())

    private var tone = UiTone(night = false, season = com.milebloom.app.render.Season.AUTUMN)
    private var parked = false

    // ── 홈 ──
    private val home = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(c, 40), 0, dp(c, 40), dp(c, 68))
        visibility = View.GONE
    }
    private val greeting = MbUi.text(c, 44f, tone.text, bold = true).apply { gravity = Gravity.CENTER }
    private val buttonRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    private var greetingText: String? = null
    private var sequenceAt = 0L

    // ── 판 ──
    private val sheet = FrameLayout(c).apply { isClickable = true; visibility = View.GONE }
    private val scroll = ScrollView(c).apply { isFillViewport = true }
    private val page = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val stack = ArrayDeque<Page>()

    private sealed interface Page {
        data object VoiceList : Page
        data class Day(val journeyId: Long) : Page
        data object Settings : Page
        /** 전국 지도 첫 화면 (P01·P03) */
        data object MapHome : Page
        /** 겹친 원의 근처 장소 목록 (P05·P06). [all]이면 지도 밖 장소까지 모든 장소 */
        data class Cluster(val ids: List<Long>, val all: Boolean = false) : Page
        /** 고른 장소의 방문 기록 (P07·P08) */
        data class Place(val id: Long) : Page
    }

    // 판 안의 편집 상태 — 기록이 바뀌어 판을 다시 그려도 유지한다
    private var editingId: Long? = null
    private var editDraft = ""
    private var deletingId: Long? = null
    private var clearing = false
    private var askedOnce = false
    private var renamingPlace: Long? = null
    private var renameDraft = ""
    private var deletingPlace: Long? = null

    // ── 전국 지도 ──
    /** 단독 화면(넓음)에서 판 왼쪽을 채우는 지도 */
    private val bigMap = KoreaMapView(c).apply { visibility = View.GONE }
    /** 내비 옆 분할(좁음)에서 판 안에 넣는 지도 — 첫 화면은 크게, 목록·방문 기록에서는 작게 */
    private val inlineMap = KoreaMapView(c)
    /** 오늘 도착한 원의 테두리 연출을 이미 보인 도착(주행 id) — 한 번만 */
    private var ringShownFor: Long? = null

    /** 이번 도착의 주행. 음성 기록 버튼이 확인 전 기록이 있으면 그날로 바로 연다 */
    private var arrivalJourneyId: Long? = null

    init {
        home.addView(greeting, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        home.addView(buttonRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 12) })
        root.addView(home, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // 글자·버튼 묶음의 실제 자리. 여백을 조금 더해 풀 끝이 글자에 닿지 않게 한다
        home.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reportHome() }
        // 단독 ↔ 내비 옆 분할로 폭이 바뀌면 버튼 크기를 다시 정한다 (좁으면 세 버튼이 한 줄에 들어가게)
        root.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ ->
            val narrow = (r - l) in 1 until dp(c, 1200)
            if ((r - l) != (or_ - ol) && narrow != homeNarrow) { homeNarrow = narrow; v.post { if (parked) styleHome() } }
        }
        home.viewTreeObserver.addOnGlobalLayoutListener { reportHome() }
        scroll.addView(page, FrameLayout.LayoutParams(-1, -2))
        sheet.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(bigMap, FrameLayout.LayoutParams(-1, -1, Gravity.START))
        root.addView(sheet, FrameLayout.LayoutParams(-1, -1))
        bigMap.onTap = ::onMapTap
        inlineMap.onTap = ::onMapTap
    }

    // ───────────── 바깥에서 부르는 것 ─────────────

    /** 기어 P이고 DO 요구가 없고 권한 안내가 떠 있지 않을 때만 true */
    fun setParked(p: Boolean) {
        if (p == parked) return
        parked = p
        if (!p) {
            // 연출·판·편집을 모두 거둔다. 남은 연출은 다음 P로 넘기지 않는다
            main.removeCallbacksAndMessages(null)
            buttonsPending = false
            deps.stopSpeaking()
            hideKeyboard()
            stack.clear()
            editingId = null; deletingId = null; clearing = false
            renamingPlace = null; deletingPlace = null
            // 움직이던 것을 기다리지 않고 끊는다 (D-088) — 끝 동작(GONE 등)도 돌지 않는다
            sheetClosing = false
            Motion.snap(home); Motion.snap(sheet); Motion.snap(bigMap)
            bigMap.stopRing(); inlineMap.stopRing()
            sheet.visibility = View.GONE
            bigMap.visibility = View.GONE
            home.visibility = View.GONE
            return
        }
        // 도착 사건이 기어 P 반영보다 먼저 왔으면 여기서 연출을 시작한다 (순서는 수집 순서에 따라 바뀐다)
        val a = pendingArrival
        pendingArrival = null
        if (a != null && SystemClock.elapsedRealtime() - a.second < PENDING_ARRIVAL_MS) { runArrival(a.first); return }
        showHome(animate = true)
    }

    /** 아직 parked가 아닐 때 온 도착 연출 (줄, 받은 시각) */
    private var pendingArrival: Pair<String, Long>? = null

    fun setTone(t: UiTone) {
        if (t.night == tone.night && t.season == tone.season) return
        tone = t
        styleHome()
        if (stack.isNotEmpty()) render()
    }

    /** 승차(1막). 소리 내지 않는다 */
    fun onBoarding() {
        arrivalJourneyId = null
        greetingText = ArrivalGreeting.BOARDING
        if (parked && stack.isEmpty()) showHome(animate = false)
    }

    /**
     * 도착(3막). P 체결 직후 화면이 보이고 있으면 연출을 한 번 돌린다.
     * @param fresh P 체결 직후인가 — 앱이 뒤에 있다가 나중에 돌아왔으면 연출·소리 없이 인사만 둔다
     */
    fun onArrival(journeyId: Long?, line: String, fresh: Boolean) {
        arrivalJourneyId = journeyId
        greetingText = line
        stack.clear()
        sheetClosing = false
        renamingPlace = null; deletingPlace = null
        Motion.snap(sheet); Motion.snap(bigMap)
        bigMap.stopRing()
        sheet.visibility = View.GONE
        bigMap.visibility = View.GONE
        main.removeCallbacksAndMessages(null)
        if (!parked) {
            pendingArrival = if (fresh) line to SystemClock.elapsedRealtime() else null
            return
        }
        if (!fresh) { showHome(animate = false); return }
        runArrival(line)
    }

    private fun runArrival(line: String) {
        // 0 ~ 1.5초: 풍경이 주차 톤으로 넘어가는 동안 비워 둔다 (30번 6.4)
        Motion.snap(home)
        home.visibility = View.VISIBLE
        greeting.alpha = 0f
        buttonRow.alpha = 0f
        buttonRow.visibility = View.INVISIBLE
        styleHome()
        main.postDelayed({
            greeting.animate().alpha(1f).setDuration(600).start()
            deps.speak(line)
        }, 1_500)
        // 약 4초 ~: 오늘 생긴 요소가 풍경에서 자란다(LandscapeView). 버튼은 그것이 끝난 뒤에 연다 — 끝 시각은 [holdButtonsUntil]
        arrivalAt = SystemClock.elapsedRealtime()
        buttonsPending = true
        sheet.visibility = View.GONE
        main.postDelayed(openButtons, maxOf(BUTTONS_MIN_MS, holdUntil - arrivalAt))
    }

    private var arrivalAt = 0L
    private var holdUntil = 0L
    private var buttonsPending = false

    private val openButtons = Runnable {
        buttonsPending = false
        buttonRow.visibility = View.VISIBLE
        Motion.enter(buttonRow, dy = dp(c, 12).toFloat(), duration = 300L)
    }

    /** 도착 연출이 [endElapsedMs](elapsedRealtime)에 끝난다. 아직 버튼을 열지 않았으면 그때까지 미룬다 */
    fun holdButtonsUntil(endElapsedMs: Long) {
        holdUntil = endElapsedMs
        if (!buttonsPending) return
        main.removeCallbacks(openButtons)
        val now = SystemClock.elapsedRealtime()
        main.postDelayed(openButtons, maxOf(arrivalAt + BUTTONS_MIN_MS - now, endElapsedMs + BUTTONS_AFTER_GROW_MS - now, 0L))
    }

    fun onRecordsChanged() {
        if (parked) {
            styleHome()
            if (stack.isNotEmpty() && editingId == null && renamingPlace == null) render()
        }
    }

    /** 뒤로 가기. 처리했으면 true */
    fun onBack(): Boolean {
        if (!parked || stack.isEmpty()) return false
        var popped = false
        when {
            editingId != null -> { editingId = null; hideKeyboard() }
            renamingPlace != null -> { renamingPlace = null; hideKeyboard() }
            deletingId != null -> deletingId = null
            deletingPlace != null -> deletingPlace = null
            clearing -> clearing = false
            else -> { stack.removeLast(); popped = true }
        }
        if (stack.isEmpty()) closeSheet()
        else if (popped) { render(Dir.BACK); scroll.scrollTo(0, 0) }
        else render()
        return true
    }

    /** 권한을 다시 물은 뒤 */
    fun onPermissionsAnswered() {
        askedOnce = true
        if (stack.lastOrNull() == Page.Settings) render()
    }

    // ───────────── 홈 ─────────────

    private var lastBounds: android.graphics.RectF? = null
    private var homeNarrow = false

    private fun reportHome() {
        val r = if (home.visibility != View.VISIBLE || home.width == 0) null else {
            var left = Float.MAX_VALUE; var right = 0f
            for (v in listOf(greeting, buttonRow)) {
                val inner = if (v === greeting) textBounds(greeting) else childBounds(buttonRow)
                left = minOf(left, v.left + inner.first); right = maxOf(right, v.left + inner.second)
            }
            val pad = dp(c, 36).toFloat()
            android.graphics.RectF(home.left + left - pad, home.top + greeting.top - pad, home.left + right + pad, home.bottom.toFloat())
        }
        if (r != lastBounds) { lastBounds = r; onHomeBounds(r) }
    }

    private fun textBounds(t: TextView): Pair<Float, Float> {
        val w = t.paint.measureText(t.text.toString())
        val mid = t.width / 2f
        return (mid - w / 2f) to (mid + w / 2f)
    }

    private fun childBounds(g: LinearLayout): Pair<Float, Float> {
        if (g.childCount == 0) return 0f to 0f
        return g.getChildAt(0).left.toFloat() to g.getChildAt(g.childCount - 1).right.toFloat()
    }

    private fun showHome(animate: Boolean) {
        if (stack.isNotEmpty()) { openSheet(); return }
        home.visibility = View.VISIBLE
        greeting.alpha = 1f
        buttonRow.alpha = 1f
        buttonRow.visibility = View.VISIBLE
        styleHome()
        if (animate) Motion.enter(home, dy = dp(c, 8).toFloat()) else Motion.snap(home)
    }

    private fun styleHome() {
        greeting.setTextColor(tone.text)
        greeting.text = greetingText ?: ArrivalGreeting.BOARDING
        // 풀밭 위에 글자만 얹으면 어색하다는 사용자 판단으로 받침을 둔다 (D-084, 30번 6.4 '판 없음'을 바꿈).
        // 버튼과 같은 알약이면 글자가 버튼처럼 보인다(사용자 지적) → 테두리 없는 타원형 번짐으로 구분한다
        // 인사 뒤 받침 없음 (D-091, D-084의 번짐을 바꿈). 글자 자리 풀은 풍경이 비우므로([onHomeBounds]) 글자는 맨 땅 위에 놓인다 —
        // 땅 대비 낮 6.3:1 이상·밤 8.4:1 이상(계절 4종). 판 색 후광은 남은 꽃잎·그림자 끝에서 글자 윤곽만 지킨다.
        // 화면에서 바탕이 칠해진 것은 버튼뿐이라 '읽는 것'과 '누르는 것'이 갈린다
        greeting.background = null
        greeting.setPadding(dp(c, 24), dp(c, 16), dp(c, 24), dp(c, 16))
        greeting.setShadowLayer(dp(c, 10).toFloat(), 0f, 0f, ColorUtils.setAlphaComponent(tone.panel, 0xF0))
        buttonRow.removeAllViews()
        val unconfirmed = deps.records.unconfirmedCount
        val voiceLabel = if (unconfirmed > 0) "음성 기록 · 확인 전 $unconfirmed" else "음성 기록"
        // 내비 옆 분할(좁음)은 세 버튼이 한 줄에 들어가도록 최소 폭과 간격을 줄인다
        val narrow = (home.parent as? View)?.let { it.width in 1 until dp(c, 1200) } ?: false
        homeNarrow = narrow
        addHomePill("전국 지도", narrow) { stack.clear(); push(Page.MapHome) }
        addHomePill(voiceLabel, narrow, bold = unconfirmed > 0) { openVoice() }
        addHomePill("설정", narrow) { push(Page.Settings) }
    }

    private fun addHomePill(label: String, narrow: Boolean, bold: Boolean = false, onClick: () -> Unit) {
        val v = MbUi.pill(c, label, tone.pill, tone.text, bold = bold, onClick = onClick)
        v.minWidth = dp(c, if (narrow) 160 else 220)
        if (narrow) v.setPadding(dp(c, 28), 0, dp(c, 28), 0)
        buttonRow.addView(v, LinearLayout.LayoutParams(-2, dp(c, MbUi.PILL_H)).apply {
            if (buttonRow.childCount > 0) marginStart = dp(c, if (narrow) 14 else 20)
        })
    }

    /** 이번 도착에 확인 전 기록이 있으면 그날로, 아니면 전체 목록으로 */
    private fun openVoice() {
        stack.clear()
        stack.addLast(Page.VoiceList)
        val j = arrivalJourneyId
        if (j != null && deps.records.memosOf(j).any { !it.confirmed }) stack.addLast(Page.Day(j))
        openSheet()
    }

    // ───────────── 판 ─────────────

    private fun push(p: Page) {
        stack.addLast(p)
        openSheet()
    }

    /** 판이 닫히는 움직임 중인가 — 그 사이에 다시 열면 처음부터 연다 */
    private var sheetClosing = false

    private enum class Dir { NONE, FORWARD, BACK }

    private fun openSheet() {
        main.removeCallbacksAndMessages(null)
        buttonsPending = false
        val fresh = sheet.visibility != View.VISIBLE || sheetClosing
        sheetClosing = false
        // 단독 화면(넓음)은 오른쪽 640 dp 판, 내비 옆 분할(좁음)은 화면 전체 (32번 2.2)
        val wide = (sheet.parent as View).width >= dp(c, 1200)
        if (fresh) {
            if (wide && home.visibility == View.VISIBLE) Motion.exit(home) { home.visibility = View.GONE }
            else { Motion.snap(home); home.visibility = View.GONE }
            sheet.layoutParams = FrameLayout.LayoutParams(if (wide) dp(c, 640) else -1, -1, Gravity.END)
            sheet.visibility = View.VISIBLE
            // 판이 들어온다: 넓으면 오른쪽에서 조금, 좁으면 아래에서 조금 (D-088)
            if (wide) Motion.enter(sheet, dx = dp(c, 40).toFloat()) else Motion.enter(sheet, dy = dp(c, 32).toFloat())
        }
        render(Dir.FORWARD)
        scroll.scrollTo(0, 0)
    }

    private fun closeSheet() {
        hideKeyboard()
        stack.clear()
        if (bigMap.visibility == View.VISIBLE) {
            bigMap.stopRing()
            Motion.exit(bigMap) { bigMap.visibility = View.GONE }
        }
        val wide = sheet.width >= dp(c, 600) && sheet.width < (sheet.parent as View).width
        sheetClosing = true
        Motion.exit(sheet, dx = if (wide) dp(c, 40).toFloat() else 0f, dy = if (wide) 0f else dp(c, 32).toFloat()) {
            sheetClosing = false
            sheet.visibility = View.GONE
        }
        showHome(animate = true)
    }

    /**
     * 판 내용을 다시 그린다. [dir] FORWARD = 새 쪽(항목이 위에서부터 차례로), BACK = 앞 쪽(왼쪽에서 조금),
     * NONE = 같은 쪽의 상태 변화(확인·수정·삭제·색 바뀜) — 움직이지 않는다
     */
    private fun render(dir: Dir = Dir.NONE) {
        // 내비 옆 분할의 지도 첫 화면은 판 전체가 지도 바탕 (P03)
        sheet.setBackgroundColor(if (stack.lastOrNull() == Page.MapHome && !isWide()) tone.mapBg else tone.panel)
        page.removeAllViews()
        page.setPadding(dp(c, 44), dp(c, 36), dp(c, 44), dp(c, 48))
        when (val p = stack.lastOrNull() ?: return) {
            Page.VoiceList -> renderVoiceList()
            is Page.Day -> renderDay(p.journeyId)
            Page.Settings -> renderSettings()
            Page.MapHome -> renderMapHome()
            is Page.Cluster -> renderCluster(p)
            is Page.Place -> renderPlace(p.id)
        }
        updateBigMap()
        when (dir) {
            Dir.FORWARD -> { Motion.snap(page); Motion.stagger(page, dy = dp(c, 16).toFloat()) }
            Dir.BACK -> { Motion.snap(page); Motion.enter(page, dx = -dp(c, 24).toFloat(), duration = Motion.PAGE_MS) }
            Dir.NONE -> Motion.snap(page)
        }
    }

    /** 뒤로 버튼 글 — 바로 앞 쪽의 이름 */
    private fun prevLabel(): String = when (stack.getOrNull(stack.size - 2)) {
        null -> "오늘의 숲"
        Page.VoiceList -> "음성 기록"
        is Page.Day -> "그날"
        Page.Settings -> "설정"
        Page.MapHome -> "전국 지도"
        is Page.Cluster -> "장소 목록"
        is Page.Place -> "방문 기록"
    }

    private fun backTo(label: String) {
        // 꺾쇠는 글자보다 크게 — 시안 P10·P12의 뒤로 버튼
        val text = android.text.SpannableString("‹  $label").apply {
            setSpan(android.text.style.RelativeSizeSpan(1.7f), 0, 1, 0)
            setSpan(android.text.style.CharacterStyle.wrap(android.text.style.StyleSpan(android.graphics.Typeface.BOLD)), 0, 1, 0)
        }
        val b = MbUi.pill(c, text, tone.card(null), tone.text, heightDp = MbUi.SMALL_PILL_H, sp = 26f) { onBack() }
        b.setPadding(dp(c, 28), 0, dp(c, 36), dp(c, 4))
        page.addView(b, LinearLayout.LayoutParams(-2, dp(c, MbUi.SMALL_PILL_H)))
    }

    private fun title(s: String, sub: String?) {
        page.addView(MbUi.text(c, 36f, tone.text, bold = true, s = s), lp(top = 36))
        if (sub != null) page.addView(MbUi.text(c, 24f, tone.sub, s = sub), lp(top = 10))
    }

    private fun rule(top: Int = 24) {
        page.addView(View(c).apply { setBackgroundColor(tone.rule) }, LinearLayout.LayoutParams(-1, dp(c, 2)).apply { topMargin = dp(c, top) })
    }

    private fun lp(top: Int, w: Int = -1) = LinearLayout.LayoutParams(w, -2).apply { topMargin = dp(c, top) }

    // ── 음성 기록 전체 (P12) ──

    private fun renderVoiceList() {
        val r = deps.records
        backTo(prevLabel())
        title("음성 기록","시간순 · 받아쓴 글만 남고 소리는 저장하지 않아요")
        val memos = r.memosNewestFirst()
        if (memos.isEmpty()) {
            rule()
            page.addView(MbUi.text(c, 28f, tone.text, s = "아직 남긴 말이 없어요."), lp(top = 32))
            page.addView(MbUi.text(c, 24f, tone.sub, s = "주행 중에 오른쪽 아래 마이크 버튼을 한 번 누르고 말하면, 받아쓴 글이 그 주행의 도착지에 묶여 여기에 남아요."), lp(top = 12))
            return
        }
        memos.forEach { m ->
            rule(top = if (m === memos.first()) 24 else 0)
            val j = r.journey(m.journeyId)
            val meta = buildList {
                add(dateOf(m.recordedAt))
                add(placeOf(r, j))
                if (!m.confirmed) add("확인 전")
            }.joinToString(" · ")
            page.addView(listRow(dotColor = tone.dot(j?.colorArgb), main = m.text, meta = meta, strongMeta = !m.confirmed) {
                push(Page.Day(m.journeyId))
            })
        }
        rule(top = 0)
    }

    private fun listRow(dotColor: Int, main: String, meta: String, strongMeta: Boolean, pencil: Boolean = false, onClick: () -> Unit): View {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(c, 112)
            setPadding(0, dp(c, 22), 0, dp(c, 22))
            background = MbUi.rounded(0x00000000, 0f, tone.text)
            isClickable = true
            setOnClickListener { onClick() }
        }
        val dot = View(c).apply { background = MbUi.rounded(dotColor, dp(c, 14).toFloat()) }
        row.addView(dot, LinearLayout.LayoutParams(dp(c, 28), dp(c, 28)).apply { marginEnd = dp(c, 24); gravity = Gravity.TOP; topMargin = dp(c, 4) })
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(MbUi.text(c, 28f, tone.text, s = main).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            if (pencil) withPencil(this)
        })
        col.addView(MbUi.text(c, 24f, if (strongMeta) tone.text else tone.sub, bold = false, s = meta), lp(top = 10))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(MbUi.text(c, 36f, tone.sub, s = "›"), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 16) })
        return row
    }

    // ── 그날 (P10의 글 부분) ──

    private fun renderDay(journeyId: Long) {
        val r = deps.records
        val j = r.journey(journeyId)
        backTo(prevLabel())
        val at = j?.endedAt ?: j?.startedAt
        page.addView(MbUi.text(c, 24f, tone.sub, s = listOfNotNull(at?.let { dateOf(it) }, "그날의 도착 인사").joinToString(" · ")), lp(top = 36))
        page.addView(MbUi.text(c, 44f, tone.text, bold = true, s = j?.greeting ?: "도착 기록이 없습니다"), lp(top = 8))
        val memos = r.memosOf(journeyId)
        page.addView(MbUi.text(c, 28f, tone.text, bold = true, s = if (memos.isEmpty()) "이 길에서 남긴 말이 없습니다" else "이 길에서 남긴 말 ${memos.size}개"), lp(top = 40))
        page.addView(MbUi.text(c, 24f, tone.sub, s = "${placeOf(r, j)} · 이 주행의 도착지에 묶여 있어요"), lp(top = 8))
        memos.forEach { m -> page.addView(memoCard(m, j?.colorArgb), lp(top = 24)) }
    }

    private fun memoCard(m: Records.VoiceMemo, accent: Int?): View {
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(c, 28)
            setPadding(p, dp(c, 28), p, dp(c, 24))
            background = MbUi.rounded(tone.card(accent), dp(c, 24).toFloat())
        }
        val actions = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(label: String, bold: Boolean = false, onClick: () -> Unit) {
            // bold = 확정 버튼(확인·저장·지우기)
            val b = MbUi.pill(c, label, if (bold) tone.primary else tone.panel, if (bold) tone.onPrimary else tone.text, bold = bold, heightDp = MbUi.SMALL_PILL_H, sp = 26f, onClick = onClick)
            b.minWidth = dp(c, 120)
            actions.addView(b, LinearLayout.LayoutParams(-2, dp(c, MbUi.SMALL_PILL_H)).apply { if (actions.childCount > 0) marginStart = dp(c, 14) })
        }
        when (m.id) {
            editingId -> {
                val edit = EditText(c).apply {
                    setText(editDraft)
                    typeface = MbUi.regular(c)
                    textSize = 28f
                    setTextColor(tone.text)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    background = MbUi.rounded(tone.panel, dp(c, 16).toFloat())
                    setPadding(dp(c, 20), dp(c, 16), dp(c, 20), dp(c, 16))
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) = Unit
                        override fun afterTextChanged(s: Editable?) { editDraft = s?.toString().orEmpty() }
                    })
                }
                card.addView(edit, LinearLayout.LayoutParams(-1, -2))
                card.addView(MbUi.text(c, 24f, tone.sub, s = "고친 글은 확인한 것으로 남습니다"), lp(top = 12))
                action("저장", bold = true) {
                    hideKeyboard()
                    val id = m.id
                    editingId = null
                    deps.editMemo(id, editDraft)
                    render()
                }
                action("취소") { editingId = null; hideKeyboard(); render() }
                card.addView(actions, lp(top = 20))
                edit.post { edit.requestFocus(); edit.setSelection(edit.text.length); showKeyboard(edit) }
                return card
            }
            deletingId -> {
                card.addView(MbUi.text(c, 28f, tone.text, s = m.text))
                card.addView(MbUi.text(c, 24f, tone.text, bold = true, s = "지우면 되돌릴 수 없어요."), lp(top = 12))
                action("지우기", bold = true) { deletingId = null; deps.deleteMemo(m.id) }
                action("취소") { deletingId = null; render() }
                card.addView(actions, lp(top = 20))
                return card
            }
        }
        card.addView(MbUi.text(c, 28f, tone.text, s = m.text))
        if (!m.confirmed) card.addView(MbUi.text(c, 24f, tone.sub, s = "받아쓴 그대로예요. 맞는지 확인해 주세요"), lp(top = 10))
        if (!m.confirmed) action("확인", bold = true) { deps.confirmMemo(m.id) }
        action("수정") { editingId = m.id; editDraft = m.text; deletingId = null; render() }
        action("삭제") { deletingId = m.id; editingId = null; render() }
        card.addView(actions, lp(top = 20))
        return card
    }

    // ── 전국 지도 (P01~P08, 5-H) ──

    private fun isWide() = ((sheet.parent as View).width) >= dp(c, 1200)

    private fun dots(r: Records) = r.parkPoints.map { p ->
        KoreaMapView.Dot(p.id, p.lat, p.lon, p.visitCount, tone.dot(r.colorOf(p.id)))
    }

    /** 지금 쪽에서 강조할 원 — 근처 장소 목록이면 그 묶음 전체, 방문 기록이면 그 장소 */
    private fun selectedIds(): Set<Long> = when (val p = stack.lastOrNull()) {
        is Page.Cluster -> if (p.all) emptySet() else p.ids.toSet()
        is Page.Place -> setOf(p.id)
        else -> emptySet()
    }

    /** 단독 화면: 지도 흐름 안에 있는 동안 판 왼쪽을 지도가 채운다. 그 밖에서는 숨긴다 */
    private fun updateBigMap() {
        val show = isWide() && stack.contains(Page.MapHome)
        if (!show) {
            if (bigMap.visibility == View.VISIBLE && !sheetClosing) { bigMap.stopRing(); Motion.snap(bigMap); bigMap.visibility = View.GONE }
            return
        }
        val w = (sheet.parent as View).width - dp(c, 640)
        if (bigMap.layoutParams.width != w) bigMap.layoutParams = FrameLayout.LayoutParams(w, -1, Gravity.START)
        bigMap.compact = false
        bigMap.set(tone, dots(deps.records), selectedIds())
        if (bigMap.visibility != View.VISIBLE) {
            bigMap.visibility = View.VISIBLE
            Motion.enter(bigMap) // 옅게 나타남. 판은 오른쪽에서 들어온다
        }
        maybeRing(bigMap)
    }

    /** 오늘 도착한 장소의 원에 테두리를 한 번 퍼뜨린다 — 이번 도착에 한 번만 (30번 6.5) */
    private fun maybeRing(map: KoreaMapView) {
        val jid = arrivalJourneyId ?: return
        if (ringShownFor == jid || stack.lastOrNull() != Page.MapHome) return
        val pid = deps.records.journey(jid)?.parkPointId ?: return
        ringShownFor = jid
        map.post { map.playRing(pid) }
    }

    /** 내비 옆 분할 — 판 안에 지도를 넣는다. 한 뷰를 다시 쓰므로 먼저 떼어 낸다 */
    private fun placeInline(compact: Boolean): KoreaMapView {
        (inlineMap.parent as? ViewGroup)?.removeView(inlineMap)
        inlineMap.compact = compact
        inlineMap.set(tone, dots(deps.records), selectedIds())
        inlineMap.background = if (compact) MbUi.rounded(tone.mapBg, dp(c, 16).toFloat()) else null
        inlineMap.clipToOutline = compact
        return inlineMap
    }

    private fun onMapTap(ids: List<Long>) {
        if (!parked || ids.isEmpty()) return
        renamingPlace = null; deletingPlace = null
        while (stack.isNotEmpty() && stack.last() != Page.MapHome) stack.removeLast()
        if (stack.isEmpty()) stack.addLast(Page.MapHome)
        stack.addLast(if (ids.size == 1) Page.Place(ids[0]) else Page.Cluster(ids))
        render(Dir.FORWARD)
        scroll.scrollTo(0, 0)
    }

    private fun placeTitle(p: Records.ParkPoint) = p.label ?: "방문 ${p.visitCount}회"

    private fun placeMeta(p: Records.ParkPoint) =
        if (p.label != null) "방문 ${p.visitCount}회 · 최근 ${shortDate(p.lastAt)}" else "최근 ${shortDate(p.lastAt)}"

    private fun withPencil(t: TextView) {
        val d = androidx.core.content.ContextCompat.getDrawable(c, com.milebloom.app.R.drawable.mb_pencil)?.mutate() ?: return
        val s = (t.textSize * 0.8f).toInt()
        d.setBounds(0, 0, s, s)
        d.setTint(t.currentTextColor)
        t.setCompoundDrawables(d, null, null, null)
        t.compoundDrawablePadding = dp(c, 10)
    }

    /** 단독: 판에 안내 / 분할: 머리줄 + 큰 지도 + 아래 버튼 (P01·P03) */
    private fun renderMapHome() {
        val r = deps.records
        val outside = r.parkPoints.count { !MapGeo.inRange(it.lat, it.lon) }
        val note = when {
            r.parkPoints.isEmpty() && !deps.keepParkPoints -> "설정의 '주차한 곳 남기기'가 꺼져 있어 원이 생기지 않아요."
            r.parkPoints.isEmpty() && deps.missingPermissions().any { it.contains("FUSED_LOCATION") } ->
                "위치 권한이 꺼져 있어 원이 생기지 않아요. 설정 › 위치·속도 권한에서 다시 물을 수 있어요."
            // 앱을 켠 주행만 기록된다 (D-046)
            r.parkPoints.isEmpty() -> "아직 차를 세운 곳이 없어요. 앱을 켠 채 달린 뒤 기어를 P에 놓으면 그 자리에 원이 하나 남아요."
            outside > 0 -> "지도 범위 밖에 세운 곳 ${outside}곳은 원으로 그리지 않았어요. 장소 목록에는 있어요."
            else -> null
        }
        if (isWide()) {
            backTo(prevLabel())
            title("전국 지도", "차를 세운 곳에 원이 하나씩 남아요")
            if (r.parkPoints.isEmpty()) {
                // 원이 없을 때는 원 읽는 법 대신 왜 없는지만 (5-I)
                if (note != null) page.addView(MbUi.text(c, 28f, tone.text, s = note), lp(top = 40))
                val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
                smallPill(row, "음성 기록 전체") { push(Page.VoiceList) }
                page.addView(row, lp(top = 48))
                return
            }
            val legend = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            legend.addView(View(c).apply { background = MbUi.rounded(tone.dot(r.journeys.lastOrNull { it.colorArgb != null }?.colorArgb), dp(c, 20).toFloat()) },
                LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)).apply { marginEnd = dp(c, 20) })
            legend.addView(MbUi.text(c, 28f, tone.text, s = "자주 머문 곳일수록 커져요"))
            page.addView(legend, lp(top = 40))
            page.addView(MbUi.text(c, 24f, tone.sub, s = "10번째 방문부터는 크기가 같아요"), lp(top = 12))
            page.addView(MbUi.text(c, 28f, tone.text, bold = true, s = "원을 눌러 그곳의 기록을 보세요"), lp(top = 56))
            page.addView(MbUi.text(c, 24f, tone.sub, s = "겹친 원은 근처 장소 목록으로 먼저 열려요"), lp(top = 12))
            if (note != null) page.addView(MbUi.text(c, 24f, tone.text, s = note), lp(top = 40))
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            if (r.parkPoints.isNotEmpty()) smallPill(row, "장소 목록 ${r.parkPoints.size}곳") { push(Page.Cluster(r.parkPoints.map { it.id }, all = true)) }
            smallPill(row, "음성 기록 전체") { push(Page.VoiceList) }
            page.addView(row, lp(top = 48))
            return
        }
        // 분할: 머리줄(뒤로 + 제목) → 지도 → 안내와 버튼
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        page.addView(head, LinearLayout.LayoutParams(-1, -2))
        val before = page.childCount
        backTo(prevLabel())
        val back = page.getChildAt(before); page.removeView(back)
        head.addView(back, LinearLayout.LayoutParams(-2, dp(c, MbUi.SMALL_PILL_H)))
        head.addView(MbUi.text(c, 36f, tone.text, bold = true, s = "전국 지도"), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 24) })
        if (note != null) page.addView(MbUi.text(c, 24f, tone.text, s = note), lp(top = 20))
        val avail = (sheet.parent as View).height
        val used = dp(c, 36 + MbUi.SMALL_PILL_H + 16 + 16 + MbUi.SMALL_PILL_H + 48 + 24) + if (note != null) dp(c, 90) else 0
        page.addView(placeInline(compact = false), LinearLayout.LayoutParams(-1, maxOf(dp(c, 300), avail - used)).apply { topMargin = dp(c, 16) })
        maybeRing(inlineMap)
        val foot = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // 원이 없으면 누르는 법 대신 빈자리만 둔다 — 이유는 위 안내(note)가 말한다 (5-I)
        foot.addView(MbUi.text(c, 24f, tone.sub, s = if (r.parkPoints.isEmpty()) "" else "원을 눌러 그곳의\n기록을 보세요"), LinearLayout.LayoutParams(0, -2, 1f))
        if (r.parkPoints.isNotEmpty()) smallPill(foot, "장소 목록") { push(Page.Cluster(r.parkPoints.map { it.id }, all = true)) }
        smallPill(foot, "음성 기록 전체") { push(Page.VoiceList) }
        page.addView(foot, lp(top = 16))
    }

    private fun smallPill(row: LinearLayout, label: String, bold: Boolean = false, onCard: Boolean = false, onClick: () -> Unit): TextView {
        // 카드 안의 버튼은 판 색으로 — 카드와 같은 색이면 버튼이 보이지 않는다
        // bold = 확정 버튼(저장·지우기) — 글자색으로 채운다
        val fill = if (bold) tone.primary else if (onCard) tone.panel else tone.card(null)
        val b = MbUi.pill(c, label, fill, if (bold) tone.onPrimary else tone.text, bold = bold, heightDp = MbUi.SMALL_PILL_H, sp = 26f, onClick = onClick)
        b.minWidth = dp(c, 120)
        b.setPadding(dp(c, 30), 0, dp(c, 30), 0)
        row.addView(b, LinearLayout.LayoutParams(-2, dp(c, MbUi.SMALL_PILL_H)).apply { if (row.childCount > 0) marginStart = dp(c, 14) })
        return b
    }

    /** 분할 화면의 목록·방문 기록 머리: 뒤로 버튼 왼쪽, 작은 지도 오른쪽 위 (P06·P08) */
    private fun headWithSmallMap() {
        if (isWide()) { backTo(prevLabel()); return }
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        page.addView(head, LinearLayout.LayoutParams(-1, -2))
        val before = page.childCount
        backTo(prevLabel())
        val back = page.getChildAt(before); page.removeView(back)
        head.addView(back, LinearLayout.LayoutParams(-2, dp(c, MbUi.SMALL_PILL_H)))
        head.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(placeInline(compact = true), LinearLayout.LayoutParams(dp(c, 170), dp(c, 190)))
    }

    /** 근처 장소 목록 (P05·P06) — 방문 횟수·최근 방문일·대표 색. 이름은 사용자가 붙인 것만 (D-042) */
    private fun renderCluster(p: Page.Cluster) {
        val r = deps.records
        val places = p.ids.mapNotNull { r.parkPoint(it) }.sortedWith(compareByDescending<Records.ParkPoint> { it.visitCount }.thenByDescending { it.lastAt })
        headWithSmallMap()
        if (p.all) title("장소 ${places.size}곳", "방문이 많은 곳부터. 이름은 직접 붙인 것만 보여요")
        else title("이 근처 ${places.size}곳", "원이 겹쳐 있어요. 장소를 고르세요")
        if (places.isEmpty()) {
            rule()
            page.addView(MbUi.text(c, 28f, tone.text, s = "남은 장소가 없어요."), lp(top = 32))
            return
        }
        places.forEach { pt ->
            rule(top = if (pt === places.first()) 24 else 0)
            page.addView(listRow(tone.dot(r.colorOf(pt.id)), placeTitle(pt), placeMeta(pt), strongMeta = false, pencil = pt.label != null) {
                renamingPlace = null; deletingPlace = null
                push(Page.Place(pt.id))
            })
        }
        rule(top = 0)
    }

    /** 고른 장소의 방문 기록 (P07·P08) — 음성 기록이 있던 날은 개수 제한 없이, 방문은 최근 10회 (D-042) */
    private fun renderPlace(id: Long) {
        val r = deps.records
        val pt = r.parkPoint(id)
        headWithSmallMap()
        if (pt == null) {
            page.addView(MbUi.text(c, 28f, tone.text, s = "지운 장소입니다."), lp(top = 36))
            return
        }
        val visits = r.journeysAt(id)
        // 제목: 대표 색 점 + 이름(직접 붙인 것) 또는 방문 횟수
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(View(c).apply { background = MbUi.rounded(tone.dot(r.colorOf(id)), dp(c, 24).toFloat()) },
            LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)).apply { marginEnd = dp(c, 24) })
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(MbUi.text(c, 36f, tone.text, bold = true, s = placeTitle(pt)).apply { if (pt.label != null) withPencil(this) })
        col.addView(MbUi.text(c, 24f, tone.sub, s = if (pt.label != null) placeMeta(pt) else "이름 없는 장소 · ${placeMeta(pt)}"), lp(top = 8))
        head.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(head, lp(top = 32))

        when (id) {
            renamingPlace -> renameBox(pt)
            deletingPlace -> deleteBox(pt, visits.size)
            else -> {
                val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
                withPencil(smallPill(row, if (pt.label == null) "이름 붙이기" else "이름 고치기") {
                    renamingPlace = id; renameDraft = pt.label.orEmpty(); deletingPlace = null; render()
                })
                smallPill(row, "이 장소 지우기") { deletingPlace = id; renamingPlace = null; render() }
                page.addView(row, lp(top = 28))
            }
        }

        val voiced = visits.filter { j -> r.memos.any { it.journeyId == j.id } }
        if (voiced.isNotEmpty()) {
            page.addView(MbUi.text(c, 28f, tone.text, bold = true, s = "음성 기록이 있던 날"), lp(top = 44))
            voiced.forEachIndexed { i, j ->
                if (i > 0) rule(top = 0)
                page.addView(dateRow(j, "${r.memosOf(j.id).size}개"), LinearLayout.LayoutParams(-1, -2))
            }
        }
        val recent = visits.take(RECENT_VISITS)
        page.addView(MbUi.text(c, 28f, tone.text, bold = true, s = "최근 방문 ${recent.size}회"), lp(top = 44))
        if (recent.isEmpty()) {
            page.addView(MbUi.text(c, 24f, tone.sub, s = "남은 방문 기록이 없어요."), lp(top = 12))
            return
        }
        // 두 줄씩 — 날짜를 누르면 그날 (P07의 격자)
        recent.chunked(2).forEach { pair ->
            val line = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { j ->
                // 누를 수 있는 칸임이 보이게 칩 모양으로 (5-I: 글자만 있으면 눌리는지 알 수 없었다)
                val cell = MbUi.text(c, 26f, tone.text, s = dateOf(j.endedAt!!) + "  ›").apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(c, MbUi.SMALL_PILL_H)
                    setPadding(dp(c, 24), 0, dp(c, 16), 0)
                    background = MbUi.rounded(tone.card(j.colorArgb), dp(c, 18).toFloat(), tone.text)
                    isClickable = true
                    setOnClickListener { push(Page.Day(j.id)) }
                }
                line.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { if (line.childCount > 0) marginStart = dp(c, 14) })
            }
            if (pair.size == 1) line.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(c, 14) })
            page.addView(line, lp(top = 14))
        }
        if (visits.size > RECENT_VISITS) page.addView(MbUi.text(c, 24f, tone.sub, s = "그보다 오래된 방문 ${visits.size - RECENT_VISITS}회는 음성 기록이 있던 날만 위에 남겨 둡니다."), lp(top = 12))
    }

    private fun dateRow(j: Records.Journey, right: String): View {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(c, 88)
            background = MbUi.rounded(0x00000000, 0f, tone.text)
            isClickable = true
            setOnClickListener { push(Page.Day(j.id)) }
        }
        row.addView(View(c).apply { background = MbUi.rounded(tone.dot(j.colorArgb), dp(c, 12).toFloat()) },
            LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)).apply { marginEnd = dp(c, 20) })
        row.addView(MbUi.text(c, 28f, tone.text, s = dateOf(j.endedAt!!)), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(MbUi.text(c, 24f, tone.sub, s = right))
        row.addView(MbUi.text(c, 36f, tone.sub, s = "›"), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 16) })
        return row
    }

    private fun renameBox(pt: Records.ParkPoint) {
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 28), dp(c, 28), dp(c, 28), dp(c, 24))
            background = MbUi.rounded(tone.card(null), dp(c, 24).toFloat())
        }
        val edit = EditText(c).apply {
            setText(renameDraft)
            hint = "예: 단골 카페"
            setHintTextColor(tone.sub)
            typeface = MbUi.regular(c)
            textSize = 28f
            setTextColor(tone.text)
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(Records.LABEL_MAX))
            background = MbUi.rounded(tone.panel, dp(c, 16).toFloat())
            setPadding(dp(c, 20), dp(c, 16), dp(c, 20), dp(c, 16))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) = Unit
                override fun afterTextChanged(s: Editable?) { renameDraft = s?.toString().orEmpty() }
            })
        }
        card.addView(edit, LinearLayout.LayoutParams(-1, -2))
        card.addView(MbUi.text(c, 24f, tone.sub, s = "직접 붙인 이름만 보여요. 주소나 장소 종류는 앱이 찾지 않습니다."), lp(top = 12))
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        fun done(label: String?) { hideKeyboard(); renamingPlace = null; deps.renamePlace(pt.id, label); render() }
        smallPill(row, "저장", bold = true, onCard = true) { done(renameDraft) }
        if (pt.label != null) smallPill(row, "이름 지우기", onCard = true) { done(null) }
        smallPill(row, "취소", onCard = true) { renamingPlace = null; hideKeyboard(); render() }
        card.addView(row, lp(top = 20))
        page.addView(card, lp(top = 28))
        edit.post { edit.requestFocus(); edit.setSelection(edit.text.length); showKeyboard(edit) }
    }

    private fun deleteBox(pt: Records.ParkPoint, visits: Int) {
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 28), dp(c, 28), dp(c, 28), dp(c, 24))
            background = MbUi.rounded(tone.card(null), dp(c, 24).toFloat())
        }
        card.addView(MbUi.text(c, 28f, tone.text, bold = true, s = "이 장소를 지도에서 지웁니다."))
        card.addView(MbUi.text(c, 24f, tone.text, s = "방문 ${visits}회의 도착지 표시와 이름이 함께 사라집니다. 음성 기록과 풍경은 남습니다. 되돌릴 수 없습니다."), lp(top = 10))
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        smallPill(row, "지우기", bold = true, onCard = true) {
            deletingPlace = null
            deps.deletePlace(pt.id)
            // 지운 장소의 쪽을 닫는다. 근처 장소 목록에서 왔으면 남은 장소로, 하나도 없으면 지도로
            if (stack.lastOrNull() == Page.Place(pt.id)) stack.removeLast()
            val top = stack.lastOrNull()
            if (top is Page.Cluster && top.ids.none { deps.records.parkPoint(it) != null }) stack.removeLast()
            if (stack.isEmpty()) closeSheet() else { render(Dir.BACK); scroll.scrollTo(0, 0) }
        }
        smallPill(row, "취소", onCard = true) { deletingPlace = null; render() }
        card.addView(row, lp(top = 20))
        page.addView(card, lp(top = 28))
    }

    // ── 설정 (D-030) ──

    private fun renderSettings() {
        val r = deps.records
        backTo(prevLabel())
        title("설정", null)

        settingRow(
            "실내 조명 연동",
            "도착하면 조명 색을 오늘의 색으로 한 번 바꿉니다. 스위치와 밝기는 그대로 둡니다.",
            toggle = deps.ambientLink,
        ) { deps.ambientLink = !deps.ambientLink; render() }

        settingRow(
            "주차한 곳 남기기",
            "차를 세운 곳 한 점만 남깁니다. 지나온 길은 남기지 않습니다.",
            toggle = deps.keepParkPoints,
        ) { deps.keepParkPoints = !deps.keepParkPoints; render() }

        val missing = deps.missingPermissions()
        if (missing.isEmpty()) {
            settingRow("위치·속도 권한", "허용됨") {}
        } else {
            val effects = buildList {
                if (missing.any { it.contains("FUSED_LOCATION") }) add("주차한 곳이 남지 않습니다.")
                if (missing.any { it.contains("CAR_SPEED") }) add("차가 서도 풍경이 계속 흐릅니다.")
            }.joinToString(" ")
            if (!askedOnce) {
                settingRow("위치·속도 권한", "꺼져 있습니다. $effects", "다시 묻기", primary = true) { deps.askPermissions() }
            } else {
                settingRow("위치·속도 권한", "꺼져 있습니다. 권한 창이 더 뜨지 않으면 시스템 설정의 Milebloom 화면을 열어 확인해 주세요.", "시스템 설정", primary = true) {
                    if (!deps.openSystemSettings()) deps.askPermissions()
                }
            }
        }

        settingRow("음성 기록", "${r.memos.size}개 · 소리는 저장하지 않습니다", "보기") { push(Page.VoiceList) }

        if (r.parkPoints.isEmpty() && r.memos.isEmpty()) {
            clearing = false
            settingRow("기록 모두 지우기", "지울 주차한 곳과 음성 기록이 없습니다.") {}
        } else if (!clearing) {
            settingRow("기록 모두 지우기", "주차한 곳과 음성 기록을 모두 지웁니다. 풍경은 남습니다.", "지우기") { clearing = true; render() }
        } else {
            // 다른 지우기 확인(음성 기록·장소)과 같은 카드 모양 (5-I)
            rule(top = 0)
            val card = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(c, 28), dp(c, 28), dp(c, 28), dp(c, 24))
                background = MbUi.rounded(tone.card(null), dp(c, 24).toFloat())
            }
            card.addView(MbUi.text(c, 28f, tone.text, bold = true, s = "기록을 모두 지웁니다."))
            card.addView(MbUi.text(c, 24f, tone.text, s = "주차한 곳 ${r.parkPoints.size}곳과 음성 기록 ${r.memos.size}개가 사라집니다. 풍경은 그대로 남습니다. 되돌릴 수 없습니다."), lp(top = 10))
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            smallPill(row, "모두 지우기", bold = true, onCard = true) { clearing = false; deps.clearAll() }
            smallPill(row, "취소", onCard = true) { clearing = false; render() }
            card.addView(row, lp(top = 20))
            page.addView(card, lp(top = 24))
            page.addView(View(c), LinearLayout.LayoutParams(-1, dp(c, 28)))
        }
        rule(top = 0)
        page.addView(MbUi.text(c, 24f, tone.sub, s = "기록은 이 앱 안에만 저장되고 차 밖으로 보내지 않습니다. 앱을 삭제하면 기록도 함께 사라집니다."), lp(top = 28))
        page.addView(MbUi.text(c, 24f, tone.sub, s = "글꼴 Pretendard · SIL Open Font License 1.1"), lp(top = 16))
    }

    /**
     * 설정 한 줄. [toggle]이 있으면 켜짐/꺼짐 상태 버튼 — 켜짐은 점과 함께 굵게, 꺼짐은 옅게(5-I: 상태와 동작이 같은 모양이라 지금 켜져 있는지 헷갈렸다).
     * [primary]는 확정 버튼 색. 버튼 폭은 모두 같게 해 오른쪽 끝과 왼쪽 끝을 맞춘다
     */
    private fun settingRow(label: String, body: String, action: String? = null, primary: Boolean = false, toggle: Boolean? = null, onClick: () -> Unit) {
        rule(top = 0)
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(c, 26), 0, dp(c, 26))
        }
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(MbUi.text(c, 28f, tone.text, bold = true, s = label))
        col.addView(MbUi.text(c, 24f, tone.sub, s = body), lp(top = 8))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val text = toggle?.let { if (it) "●  켜짐" else "꺼짐" } ?: action
        if (text != null) {
            val fill = if (primary) tone.primary else tone.card(null)
            val fg = when { primary -> tone.onPrimary; toggle == false -> tone.sub; else -> tone.text }
            val b = MbUi.pill(c, text, fill, fg, bold = primary || toggle == true, heightDp = MbUi.SMALL_PILL_H, sp = 26f, onClick = onClick)
            b.minWidth = dp(c, SETTING_BTN_W)
            b.setPadding(dp(c, 24), 0, dp(c, 24), 0)
            row.addView(b, LinearLayout.LayoutParams(-2, dp(c, MbUi.SMALL_PILL_H)).apply { marginStart = dp(c, 24) })
        }
        page.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ───────────── 글 ─────────────

    private fun dateOf(ms: Long): String = SimpleDateFormat("M월 d일 (E)", Locale.KOREAN).format(Date(ms))
    private fun shortDate(ms: Long): String = SimpleDateFormat("M월 d일", Locale.KOREAN).format(Date(ms))

    /** 장소 이름은 사용자가 붙인 것만. 없으면 방문 횟수가 제목이다 (D-042) */
    private fun placeOf(r: Records, j: Records.Journey?): String {
        if (j == null || j.endedAt == null) return "도착 전"
        val p = r.parkPoint(j.parkPointId) ?: return "도착한 곳: 위치 없음"
        return "도착한 곳: " + (p.label ?: "방문 ${p.visitCount}회")
    }

    private fun showKeyboard(v: View) {
        (c.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = c.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(sheet.windowToken, 0)
    }

    private companion object {
        /** P 체결 뒤 버튼이 가장 빨리 열리는 때 — 자랄 것이 없는 날 (30번 6.4 '약 4초') */
        const val BUTTONS_MIN_MS = 4_000L
        /** 마지막 요소가 다 자란 뒤 버튼까지 */
        const val BUTTONS_AFTER_GROW_MS = 300L
        /** 도착 사건 뒤 기어 P 반영을 기다리는 한도 */
        const val PENDING_ARRIVAL_MS = 3_000L
        /** 장소의 방문 기록에 보이는 최근 방문 수 (D-042) */
        const val RECENT_VISITS = 10
        /** 설정 줄 버튼 폭 — "시스템 설정"이 들어가는 폭 */
        const val SETTING_BTN_W = 176
    }
}
