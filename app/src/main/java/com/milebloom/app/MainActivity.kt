package com.milebloom.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.milebloom.app.mode.AppMode
import com.milebloom.app.mode.DisplayLevel
import com.milebloom.app.render.CarModel
import com.milebloom.app.render.LandscapeView
import com.milebloom.app.speech.VoiceIn
import com.milebloom.app.speech.VoiceOut
import com.milebloom.app.ui.MbUi
import com.milebloom.app.ui.MemoryOverlay
import com.milebloom.app.ui.UiTone
import com.milebloom.app.ui.Motion
import com.milebloom.app.ui.VoiceButton
import com.milebloom.app.vehicle.Gear
import com.milebloom.app.vehicle.Signal
import com.milebloom.app.vehicle.SignalSupport
import com.milebloom.app.vehicle.VehicleSnapshot
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 앱의 유일한 화면. 풍경([LandscapeView], 5-C)이 모드·차량 신호를 받아 그린다.
 * 그 위에 주행 중에는 음성 기록 버튼 하나([VoiceButton])만, 주차(P) 중에는 인사·버튼·판([MemoryOverlay])과 첫 실행 권한 안내([PermissionIntro])를 얹는다.
 *
 * 디버그 빌드에만 신호 패널과 SDK 시험 버튼이 붙는다. 시험 버튼은 **주차 모드에서만** 보인다 (주행 중 터치 요구 금지).
 */
class MainActivity : AppCompatActivity() {

    private val app get() = application as MilebloomApp

    private lateinit var scene: LandscapeView
    private lateinit var permissionIntro: PermissionIntro
    private lateinit var overlay: MemoryOverlay
    private lateinit var voiceButton: VoiceButton
    private var debugReceiver: BroadcastReceiver? = null
    private var debugText: TextView? = null
    private var debugButtons: View? = null

    private val voiceOut by lazy { VoiceOut(this) }
    private val voiceIn by lazy { VoiceIn(this) }
    private val main = Handler(Looper.getMainLooper())

    private var lastSnapshot = VehicleSnapshot()
    private var firstMode = true
    private var shownArrivalAt = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val root = FrameLayout(this)
        scene = LandscapeView(this)
        root.addView(scene, FrameLayout.LayoutParams(-1, -1))

        // 주행 중 음성 기록 버튼 — 오른쪽 아래 고정, 지름 120 dp, 여백 48 dp (30번 3.3)
        voiceButton = VoiceButton(this).apply {
            visibility = View.GONE
            setOnClickListener { recordVoice() }
        }
        root.addView(voiceButton, FrameLayout.LayoutParams(MbUi.dp(this, 120), MbUi.dp(this, 120), Gravity.BOTTOM or Gravity.END).apply {
            val m = MbUi.dp(this@MainActivity, 48)
            setMargins(m, m, m, m)
        })

        overlay = MemoryOverlay(this, root, overlayDeps())
        permissionIntro = PermissionIntro(this, root)
        permissionIntro.onShowingChanged = { refreshParked() }
        // 권한 카드 뒤 풍경은 흐리게 (사용자 요청). 주차 중에만 뜨는 카드라 흐림 비용은 그동안만 든다
        permissionIntro.onBackdrop = { on ->
            val r = MbUi.dp(this, 18).toFloat()
            scene.setRenderEffect(if (on) RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null)
        }
        overlay.onHomeBounds = { rect -> scene.clearZone = rect }
        // 오늘 생긴 요소가 다 자란 뒤에 버튼을 연다 (30번 6.4 '그 뒤 기록 열람')
        scene.onGrowthPlanned = { end -> overlay.holdButtonsUntil(end) }
        scene.onNightChanged = { applyTone() }
        if (isDebuggable()) {
            addDebugOverlay(root)
            registerSceneDebugReceiver()
        }
        setContentView(root)
        applyTone()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (overlay.onBack()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        app.signals.start()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.modes.mode.collect { m ->
                        scene.mode = m
                        // 음성 버튼은 주행(Journey Mode)에만. 주차 중 녹음은 두지 않는다 (05 #50)
                        voiceButton.visibility = if (m == AppMode.JOURNEY) View.VISIBLE else View.GONE
                        if (firstMode && m == AppMode.BOARDING) overlay.onBoarding()
                        firstMode = false
                        refreshParked()
                    }
                }
                launch {
                    app.arrival.collect { a ->
                        if (a == null || a.atElapsedMs == shownArrivalAt) return@collect
                        shownArrivalAt = a.atElapsedMs
                        // P 체결 직후에 화면이 보이고 있었을 때만 연출과 소리를 낸다
                        val fresh = SystemClock.elapsedRealtime() - a.atElapsedMs < FRESH_ARRIVAL_MS
                        overlay.onArrival(a.journeyId, a.greeting, fresh)
                    }
                }
                launch {
                    app.ambient.today.collect { c -> scene.today = c }
                }
                launch {
                    // 쌓인 풍경 (D-087). 방금 P 체결에서 자란 것이면 4초 뒤부터 그것만 자라나게, 아니면 연출 없이
                    app.scene.collect { si ->
                        val at = si.growAtElapsedMs
                        if (at != null && SystemClock.elapsedRealtime() - at < FRESH_ARRIVAL_MS) scene.growInto(si.state, at + GROW_DELAY_MS)
                        else scene.state = si.state
                    }
                }
                launch {
                    app.records.records.collect { overlay.onRecordsChanged() }
                }
                launch {
                    app.signals.snapshot.collect { s ->
                        scene.snapshot = s
                        lastSnapshot = s
                        applyLevel(s.uxActiveRestrictions)
                        refreshParked()
                    }
                }
                debugText?.let { tv ->
                    launch {
                        combine(app.signals.snapshot, app.signals.support, app.modes.mode, ::Triple)
                            .collect { (s, sup, m) ->
                                tv.text = describe(s, sup, m)
                                // 주행 전 R·N에서도 플랫폼은 DO를 요구한다 (5-A 실측) → 모드가 아니라 UX 제약을 따른다
                                val touchAllowed = m != AppMode.JOURNEY && s.uxRequiresDo != true
                                debugButtons?.visibility = if (touchAllowed) View.VISIBLE else View.GONE
                            }
                    }
                }
            }
        }
    }

    /**
     * 터치 UI(주차 화면·권한 안내)는 **기어 P이고, 플랫폼이 DO를 요구하지 않고, Lv1일 때만** 보인다.
     * D·R·N으로 바뀌면 그 자리에서 숨긴다 (절대 규칙 2·3). 권한 안내가 떠 있으면 주차 화면은 비켜 준다.
     */
    private fun refreshParked() {
        val s = lastSnapshot
        val parked = s.gear == Gear.PARK && s.uxRequiresDo != true &&
            scene.level == DisplayLevel.LV1 && app.modes.mode.value != AppMode.JOURNEY
        permissionIntro.onParked(parked)
        overlay.setParked(parked && !permissionIntro.isShowing)
    }

    private fun applyTone() {
        val t = UiTone(night = scene.isNight, season = scene.state.season)
        overlay.setTone(t)
        permissionIntro.setTone(t)
        voiceButton.night = scene.isNight
    }

    // ───────────── 음성 기록 (D-010, 12번 8장) ─────────────

    /**
     * 단일 탭으로 시작하고, 끝은 STT의 무음 감지 또는 [VOICE_TIMEOUT_MS]로 자동이다. 두 번째 탭을 요구하지 않는다.
     * 피드백은 소리로: 시작음 한 번, 끝나면 짧은 TTS. 글만 저장하고 소리는 저장하지 않는다 (13.5).
     */
    private fun recordVoice() {
        if (voiceIn.isListening || voiceButton.listening) return
        if (app.currentJourneyId == null) return
        voiceButton.listening = true
        voiceOut.stop()
        beep()
        var done = false
        var partial = ""
        fun finish(text: String?) {
            if (done) return
            done = true
            main.removeCallbacks(voiceTimeout)
            voiceButton.listening = false
            val t = text?.trim().orEmpty()
            when {
                t.isNotEmpty() && app.addVoiceMemo(t) -> { Log.i(TAG, "음성 기록 저장 \"$t\""); voiceOut.speak("기록했어요.") }
                text == null -> voiceOut.speak("기록하지 못했어요.")
                else -> voiceOut.speak("들리지 않아 남기지 않았어요.")
            }
        }
        voiceIn.listen(object : VoiceIn.Callback {
            override fun onPartial(text: String) { partial = text }
            override fun onFinal(text: String) = runOnUiThread { finish(text) }
            override fun onEnded() = runOnUiThread { finish(partial) }
            override fun onError(reason: String) = runOnUiThread { Log.w(TAG, "음성 기록 실패: $reason"); finish(null) }
        })
        main.postDelayed(voiceTimeout, VOICE_TIMEOUT_MS)
    }

    private val voiceTimeout = Runnable { voiceIn.stop() ; main.postDelayed({ if (voiceButton.listening) { voiceButton.listening = false; voiceOut.speak("기록하지 못했어요.") } }, 2_000) }

    private fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            main.postDelayed({ tg.release() }, 400)
        } catch (t: Throwable) {
            Log.w(TAG, "시작음 실패: ${t.message}")
        }
    }

    private fun overlayDeps() = object : MemoryOverlay.Deps {
        override val records get() = app.records.current
        override fun confirmMemo(id: Long) = app.records.update { it.confirmMemo(id) }
        override fun editMemo(id: Long, text: String) = app.records.update { it.editMemo(id, text) }
        override fun deleteMemo(id: Long) = app.records.update { it.deleteMemo(id) }
        override fun clearAll() = app.clearAllRecords()
        override var ambientLink: Boolean
            get() = app.ambient.linkEnabled
            set(v) = app.ambient.setLinkEnabled(v)
        override var keepParkPoints: Boolean
            get() = app.keepParkPoints
            set(v) { app.keepParkPoints = v }
        override fun missingPermissions() = permissionIntro.missing()
        override fun askPermissions() = permissionIntro.askAgain()
        override fun openSystemSettings(): Boolean = try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "앱 설정 화면 열기 실패: ${t.message}")
            false
        }
        override fun speak(text: String) = voiceOut.speak(text)
        override fun stopSpeaking() = voiceOut.stop()
    }

    /** 제한이 평소보다 강해지면 즉시 Lv3 (D-081). debug 빌드는 [uxOverride]로 강화된 제한을 흉내 낼 수 있다 */
    private fun applyLevel(active: Int?) {
        lastUxActive = active
        val level = DisplayLevel.of(uxOverride ?: active)
        if (scene.level != level) Log.i("MBMODE", "표시 수준 ${scene.level} → $level (active=0x${(uxOverride ?: active)?.toString(16)}${if (uxOverride != null) ", debug 주입" else ""})")
        scene.level = level
        // Lv3에서는 주차 화면의 움직임도 끈다 (D-088). 터치 UI는 Lv1에서만 보이지만 경계에서 남는 움직임이 없게
        Motion.allowed = level == DisplayLevel.LV1
    }

    private var lastUxActive: Int? = null

    /** debug 전용: 플랫폼 값 대신 쓸 activeRestrictions. null = 실제 값 */
    private var uxOverride: Int? = null

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 권한은 첫 실행 P에서 [PermissionIntro]가 묻고, 설정 화면에서 다시 물을 수 있다. CAR_SPEED를 받았으면 속도 구독을 시작한다
        if (permissionIntro.onResult(requestCode)) {
            app.signals.onPermissionsChanged()
            if (requestCode == PermissionIntro.REQ_AGAIN) overlay.onPermissionsAnswered()
            refreshParked()
        }
    }

    override fun onDestroy() {
        debugReceiver?.let { unregisterReceiver(it) }
        main.removeCallbacksAndMessages(null)
        if (isFinishing) {
            voiceIn.release()
            voiceOut.release()
            app.parkLocator.release()
            app.signals.stop()
        }
        super.onDestroy()
    }

    // ───────────── 디버그 오버레이 ─────────────

    /**
     * 시연·녹화용 풍경 조작 (debug 빌드 전용). 예:
     * `adb shell am broadcast --user 10 -a com.milebloom.app.SCENE --es sun down --es car TUCSON --ei month 4 --ez hud false`
     * - sun: up | down | auto — 낮 시간대에 밤을, 밤에 낮을 보여 줄 때 (헤드램프는 Virtual Console로)
     * - month 1..12, car GRANDEUR|AVANTE|TUCSON — 주행 중에 보내면 다음 P에서 적용된다. month는 저장된 풍경을 바꾸지 않는다
     * - km 60.5 — 주행 없이 누적 거리를 더한다(시연 시작 상태). 기록에 debug 거리로 따로 남는다. 숲 단계가 그만큼 바뀐다
     * - forestReset true — 풍경을 처음 설치한 상태로 (주행·장소·음성 기록은 그대로)
     * - 실제 주행 거리는 Virtual Console `POST /vehicle/odometer?traveled_distance_km=N`으로 넣는다 (SDK 경로 그대로)
     * - hud false — 신호 패널을 숨긴다 (화면 녹화용)
     * - wheel true|false — 바퀴 회전 시험(D-063) 켜고 끄기. 사용자 비교용
     * - sun·month는 다음 P의 오늘의 색에도 적용된다
     * - ambientLink false — 설정의 '실내 조명 연동' 끔(D-053): 사용자 원래 색으로 되돌린다
     * - memo "글" — 음성 인식 없이 음성 기록 한 줄을 지금(주차 중이면 마지막) 주행에 넣는다. 에뮬레이터에서 STT를 쓸 수 없을 때 시연용
     * - uxActive N — 플랫폼 activeRestrictions 대신 N을 쓴다 (D-081, 에뮬레이터에서 제한 강화를 못 일으킬 때). -1이면 해제
     */
    private fun registerSceneDebugReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val sun = when (i.getStringExtra("sun")) {
                    "up" -> false
                    "down" -> true
                    else -> null
                }
                if (i.hasExtra("sun")) {
                    scene.sunDownOverride = sun
                    app.sunDownOverride = sun
                }
                if (i.hasExtra("uxActive")) {
                    uxOverride = i.getIntExtra("uxActive", -1).takeIf { it >= 0 }
                    applyLevel(lastUxActive)
                }
                i.getStringExtra("memo")?.let { t -> Log.i(TAG, "debug 음성 기록 주입 \"$t\" → ${app.addVoiceMemo(t)}") }
                if (i.hasExtra("ambientLink")) app.ambient.setLinkEnabled(i.getBooleanExtra("ambientLink", true))
                val month = i.getIntExtra("month", 0)
                if (month in 1..12) app.monthOverride = month
                if (i.getBooleanExtra("forestReset", false)) app.debugResetForest()
                if (i.hasExtra("km")) app.debugAddKm(i.getFloatExtra("km", 0f))
                if (i.hasExtra("wheel")) scene.wheelSpin = i.getBooleanExtra("wheel", true)
                i.getStringExtra("car")?.let { name -> CarModel.entries.find { it.name == name }?.let { scene.carModel = it } }
                if (i.hasExtra("hud")) {
                    val show = i.getBooleanExtra("hud", true)
                    (debugText?.parent as? View)?.visibility = if (show) View.VISIBLE else View.GONE
                }
            }
        }
        registerReceiver(r, IntentFilter(ACTION_SCENE), RECEIVER_EXPORTED)
        debugReceiver = r
    }

    private fun isDebuggable() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun addDebugOverlay(root: FrameLayout) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 16, 24, 16)
        }
        debugText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        panel.addView(debugText)

        val status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFB0FFB0.toInt())
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            buttons.addView(this)
        }
        button("TTS 시험") { voiceOut.speak("오늘 숲이 조금 자랐어요") }
        button("STT 시험") {
            status.text = "STT 초기화 중…"
            voiceIn.listen(object : VoiceIn.Callback {
                override fun onListening() = runOnUiThread { status.text = "듣는 중…" }
                override fun onPartial(text: String) = runOnUiThread { status.text = "인식 중: $text" }
                override fun onFinal(text: String) = runOnUiThread { status.text = "결과: $text" }
                override fun onError(reason: String) = runOnUiThread { status.text = "STT 오류: $reason" }
            })
        }
        button("앰비언트 시험") {
            app.signals.setAmbientColor(0xFF78C88C.toInt()) { ok ->
                runOnUiThread { status.text = "앰비언트 #78C88C ${if (ok) "적용" else "실패"}" }
            }
        }
        voiceOut.onState = { s -> runOnUiThread { status.text = "TTS $s" } }
        panel.addView(buttons)
        panel.addView(status)
        debugButtons = buttons

        root.addView(panel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
    }

    private fun describe(s: VehicleSnapshot, sup: Map<Signal, SignalSupport>, m: AppMode): String {
        fun mark(sig: Signal) = when (sup[sig]) {
            SignalSupport.Supported -> " "
            is SignalSupport.Unsupported -> "✕"
            else -> "…"
        }
        fun Map<*, *>.fmt() = if (isEmpty()) "-" else entries.joinToString(" ") { "${it.key.toString().take(7)}=${it.value}" }
        return buildString {
            appendLine("mode     $m")
            appendLine("${mark(Signal.GEAR)}gear     ${s.gear}")
            appendLine("${mark(Signal.UX_RESTRICTIONS)}ux       DO=${s.uxRequiresDo} active=0x${s.uxActiveRestrictions?.toString(16)}")
            appendLine("${mark(Signal.ODOMETER)}odo      ${s.odometerKm} km")
            appendLine("${mark(Signal.WIPER)}wiper    on=${s.wiperOn} sw=${s.wiperSwitch}")
            appendLine("${mark(Signal.LOW_BEAM)}lowBeam  ${s.lowBeamOn}")
            appendLine("${mark(Signal.WINDOW)}window   ${s.windows.fmt()}")
            appendLine("${mark(Signal.DOOR)}door     ${s.doorsOpen.fmt()}")
            appendLine("${mark(Signal.STEERING)}steer    ${s.steeringDeg}°")
            appendLine("${mark(Signal.BRAKE)}brake    ${s.brakePressed}")
            appendLine("${mark(Signal.SPEED)}stopped  ${s.stopped}")
            append("${mark(Signal.AMBIENT)}ambient  ${if (sup[Signal.AMBIENT] == SignalSupport.Supported) "writable" else sup[Signal.AMBIENT]}")
        }
    }

    private companion object {
        const val TAG = "MBVOICE"
        const val ACTION_SCENE = "com.milebloom.app.SCENE"
        /** 이보다 늦게 화면에 돌아오면 도착 연출·소리 없이 인사만 둔다 */
        const val FRESH_ARRIVAL_MS = 10_000L
        /** P 체결 뒤 '오늘 생긴 요소'가 자라기 시작하는 때 (30번 6.4 '약 4초 ~') */
        const val GROW_DELAY_MS = 4_000L
        const val VOICE_TIMEOUT_MS = 15_000L
    }
}
