package com.milebloom.app.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
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
 * - 버튼은 시안 중 구현한 것만: 음성 기록·설정. 전국 지도는 아직 없다
 *
 * 판(주차 중에만)
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
    }

    // 판 안의 편집 상태 — 기록이 바뀌어 판을 다시 그려도 유지한다
    private var editingId: Long? = null
    private var editDraft = ""
    private var deletingId: Long? = null
    private var clearing = false
    private var askedOnce = false

    /** 이번 도착의 주행. 음성 기록 버튼이 확인 전 기록이 있으면 그날로 바로 연다 */
    private var arrivalJourneyId: Long? = null

    init {
        home.addView(greeting, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        home.addView(buttonRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 12) })
        root.addView(home, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // 글자·버튼 묶음의 실제 자리. 여백을 조금 더해 풀 끝이 글자에 닿지 않게 한다
        home.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reportHome() }
        home.viewTreeObserver.addOnGlobalLayoutListener { reportHome() }
        scroll.addView(page, FrameLayout.LayoutParams(-1, -2))
        sheet.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(sheet, FrameLayout.LayoutParams(-1, -1))
    }

    // ───────────── 바깥에서 부르는 것 ─────────────

    /** 기어 P이고 DO 요구가 없고 권한 안내가 떠 있지 않을 때만 true */
    fun setParked(p: Boolean) {
        if (p == parked) return
        parked = p
        if (!p) {
            // 연출·판·편집을 모두 거둔다. 남은 연출은 다음 P로 넘기지 않는다
            main.removeCallbacksAndMessages(null)
            deps.stopSpeaking()
            hideKeyboard()
            stack.clear()
            editingId = null; deletingId = null; clearing = false
            sheet.visibility = View.GONE
            home.visibility = View.GONE
            return
        }
        showHome(animate = false)
    }

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
        sheet.visibility = View.GONE
        main.removeCallbacksAndMessages(null)
        if (!parked) return
        if (!fresh) { showHome(animate = false); return }
        // 0 ~ 1.5초: 풍경이 주차 톤으로 넘어가는 동안 비워 둔다 (30번 6.4)
        home.visibility = View.VISIBLE
        greeting.alpha = 0f
        buttonRow.alpha = 0f
        buttonRow.visibility = View.INVISIBLE
        styleHome()
        main.postDelayed({
            greeting.animate().alpha(1f).setDuration(600).start()
            deps.speak(line)
        }, 1_500)
        // 약 4초 ~: 시안의 '오늘 생긴 요소' 자리. 요소 연출은 아직 없어 그 뒤 버튼만 연다
        main.postDelayed({
            buttonRow.visibility = View.VISIBLE
            buttonRow.animate().alpha(1f).setDuration(500).start()
        }, 4_000)
    }

    fun onRecordsChanged() {
        if (parked) {
            styleHome()
            if (stack.isNotEmpty() && editingId == null) render()
        }
    }

    /** 뒤로 가기. 처리했으면 true */
    fun onBack(): Boolean {
        if (!parked || stack.isEmpty()) return false
        when {
            editingId != null -> { editingId = null; hideKeyboard() }
            deletingId != null -> deletingId = null
            clearing -> clearing = false
            else -> stack.removeLast()
        }
        if (stack.isEmpty()) closeSheet() else render()
        return true
    }

    /** 권한을 다시 물은 뒤 */
    fun onPermissionsAnswered() {
        askedOnce = true
        if (stack.lastOrNull() == Page.Settings) render()
    }

    // ───────────── 홈 ─────────────

    private var lastBounds: android.graphics.RectF? = null

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
        if (animate) home.alpha = 0f.also { home.animate().alpha(1f).setDuration(400).start() }
    }

    private fun styleHome() {
        greeting.setTextColor(tone.text)
        greeting.text = greetingText ?: ArrivalGreeting.BOARDING
        // 풀밭 위에 글자만 얹으면 어색하다는 사용자 판단으로 받침을 둔다 (D-084, 30번 6.4 '판 없음'을 바꿈).
        // 버튼과 같은 알약이면 글자가 버튼처럼 보인다(사용자 지적) → 테두리 없는 타원형 번짐으로 구분한다
        val padH = dp(c, 120); val padV = dp(c, 36)
        greeting.setPadding(padH, padV, padH, padV)
        greeting.background = GlowDrawable(tone.panel, if (tone.night) 0xD0 else 0xC8)
        greeting.setShadowLayer(0f, 0f, 0f, 0)
        buttonRow.removeAllViews()
        val unconfirmed = deps.records.unconfirmedCount
        val voiceLabel = if (unconfirmed > 0) "음성 기록 · 확인 전 $unconfirmed" else "음성 기록"
        addHomePill(voiceLabel, bold = unconfirmed > 0) { openVoice() }
        addHomePill("설정") { push(Page.Settings) }
    }

    private fun addHomePill(label: String, bold: Boolean = false, onClick: () -> Unit) {
        val v = MbUi.pill(c, label, tone.pill, tone.text, bold = bold, onClick = onClick)
        v.minWidth = dp(c, 220)
        buttonRow.addView(v, LinearLayout.LayoutParams(-2, dp(c, MbUi.PILL_H)).apply {
            if (buttonRow.childCount > 0) marginStart = dp(c, 20)
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

    private fun openSheet() {
        main.removeCallbacksAndMessages(null)
        home.visibility = View.GONE
        // 단독 화면(넓음)은 오른쪽 640 dp 판, 내비 옆 분할(좁음)은 화면 전체 (32번 2.2)
        val wide = (sheet.parent as View).width >= dp(c, 1200)
        sheet.layoutParams = FrameLayout.LayoutParams(if (wide) dp(c, 640) else -1, -1, Gravity.END)
        sheet.visibility = View.VISIBLE
        render()
        scroll.scrollTo(0, 0)
    }

    private fun closeSheet() {
        hideKeyboard()
        stack.clear()
        sheet.visibility = View.GONE
        showHome(animate = true)
    }

    private fun render() {
        sheet.setBackgroundColor(tone.panel)
        page.removeAllViews()
        page.setPadding(dp(c, 44), dp(c, 36), dp(c, 44), dp(c, 48))
        when (val p = stack.lastOrNull() ?: return) {
            Page.VoiceList -> renderVoiceList()
            is Page.Day -> renderDay(p.journeyId)
            Page.Settings -> renderSettings()
        }
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
        backTo(if (stack.size > 1) "설정" else "오늘의 숲")
        title("음성 기록", "시간순 · 받아쓴 글만 남고 소리는 저장하지 않아요")
        val memos = r.memosNewestFirst()
        if (memos.isEmpty()) {
            rule()
            page.addView(MbUi.text(c, 28f, tone.text, s = "아직 남긴 말이 없습니다."), lp(top = 32))
            page.addView(MbUi.text(c, 24f, tone.sub, s = "주행 중에 오른쪽 아래 마이크 버튼을 한 번 누르고 말하면, 받아쓴 글이 그 주행의 도착지에 묶여 여기에 남습니다."), lp(top = 12))
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

    private fun listRow(dotColor: Int, main: String, meta: String, strongMeta: Boolean, onClick: () -> Unit): View {
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
        col.addView(MbUi.text(c, 28f, tone.text, s = main).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        col.addView(MbUi.text(c, 24f, if (strongMeta) tone.text else tone.sub, bold = false, s = meta), lp(top = 10))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(MbUi.text(c, 36f, tone.sub, s = "›"), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 16) })
        return row
    }

    // ── 그날 (P10의 글 부분) ──

    private fun renderDay(journeyId: Long) {
        val r = deps.records
        val j = r.journey(journeyId)
        backTo("음성 기록")
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
            val b = MbUi.pill(c, label, tone.panel, tone.text, bold = bold, heightDp = MbUi.SMALL_PILL_H, sp = 26f, onClick = onClick)
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

    // ── 설정 (D-030) ──

    private fun renderSettings() {
        val r = deps.records
        backTo("오늘의 숲")
        title("설정", null)

        settingRow(
            "실내 조명 연동",
            "도착하면 조명 색을 오늘의 색으로 한 번 바꿉니다. 스위치와 밝기는 그대로 둡니다.",
            if (deps.ambientLink) "켜짐" else "꺼짐", bold = deps.ambientLink,
        ) { deps.ambientLink = !deps.ambientLink; render() }

        settingRow(
            "주차한 곳 남기기",
            "차를 세운 곳 한 점만 남깁니다. 지나온 길은 남기지 않습니다.",
            if (deps.keepParkPoints) "켜짐" else "꺼짐", bold = deps.keepParkPoints,
        ) { deps.keepParkPoints = !deps.keepParkPoints; render() }

        val missing = deps.missingPermissions()
        if (missing.isEmpty()) {
            settingRow("위치·속도 권한", "허용됨", null) {}
        } else {
            val effects = buildList {
                if (missing.any { it.contains("FUSED_LOCATION") }) add("주차한 곳이 남지 않습니다.")
                if (missing.any { it.contains("CAR_SPEED") }) add("차가 서도 풍경이 계속 흐릅니다.")
            }.joinToString(" ")
            if (!askedOnce) {
                settingRow("위치·속도 권한", "꺼져 있습니다. $effects", "다시 묻기", bold = true) { deps.askPermissions() }
            } else {
                settingRow("위치·속도 권한", "꺼져 있습니다. 권한 창이 뜨지 않으면 시스템 설정 › 앱 › Milebloom › 권한에서 켜 주세요.", "시스템 설정", bold = true) {
                    if (!deps.openSystemSettings()) deps.askPermissions()
                }
            }
        }

        settingRow("음성 기록", "${r.memos.size}개 · 소리는 저장하지 않습니다", "보기") { push(Page.VoiceList) }

        if (!clearing) {
            settingRow("기록 모두 지우기", "주차한 곳과 음성 기록을 모두 지웁니다.", "지우기") { clearing = true; render() }
        } else {
            rule(top = 0)
            page.addView(MbUi.text(c, 28f, tone.text, bold = true, s = "기록 모두 지우기"), lp(top = 28))
            page.addView(MbUi.text(c, 24f, tone.text, s = "주차한 곳 ${r.parkPoints.size}곳과 음성 기록 ${r.memos.size}개를 지웁니다. 되돌릴 수 없습니다."), lp(top = 8))
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("모두 지우기" to true, "취소" to false).forEach { (label, yes) ->
                val b = MbUi.pill(c, label, tone.card(null), tone.text, bold = yes, heightDp = MbUi.SMALL_PILL_H, sp = 26f) {
                    clearing = false
                    if (yes) deps.clearAll() else render()
                }
                b.minWidth = dp(c, 140)
                row.addView(b, LinearLayout.LayoutParams(-2, dp(c, MbUi.SMALL_PILL_H)).apply { if (row.childCount > 0) marginStart = dp(c, 14) })
            }
            page.addView(row, lp(top = 20))
            page.addView(View(c), LinearLayout.LayoutParams(-1, dp(c, 28)))
        }
        rule(top = 0)
        page.addView(MbUi.text(c, 24f, tone.sub, s = "기록은 이 앱 안에만 저장되고 차 밖으로 보내지 않습니다. 앱을 삭제하면 기록도 함께 사라집니다."), lp(top = 28))
        page.addView(MbUi.text(c, 24f, tone.sub, s = "글꼴 Pretendard · SIL Open Font License 1.1"), lp(top = 16))
    }

    private fun settingRow(label: String, body: String, action: String?, bold: Boolean = false, onClick: () -> Unit) {
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
        if (action != null) {
            val b = MbUi.pill(c, action, tone.card(null), tone.text, bold = bold, heightDp = MbUi.SMALL_PILL_H, sp = 26f, onClick = onClick)
            b.minWidth = dp(c, 140)
            row.addView(b, LinearLayout.LayoutParams(-2, dp(c, MbUi.SMALL_PILL_H)).apply { marginStart = dp(c, 24) })
        }
        page.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ───────────── 글 ─────────────

    private fun dateOf(ms: Long): String = SimpleDateFormat("M월 d일 (E)", Locale.KOREAN).format(Date(ms))

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
}
