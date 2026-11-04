package com.milebloom.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.milebloom.app.mode.AppMode
import com.milebloom.app.render.CarModel
import com.milebloom.app.render.LandscapeView
import com.milebloom.app.speech.VoiceIn
import com.milebloom.app.speech.VoiceOut
import com.milebloom.app.vehicle.Signal
import com.milebloom.app.vehicle.SignalSupport
import com.milebloom.app.vehicle.VehicleSnapshot
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 앱의 유일한 화면. 풍경([LandscapeView], 5-C)이 모드·차량 신호를 받아 그린다. 주차 화면 UI는 5-E가 위에 얹는다.
 *
 * 디버그 빌드에만 신호 패널과 SDK 시험 버튼이 붙는다. 시험 버튼은 **주차 모드에서만** 보인다 (주행 중 터치 요구 금지).
 */
class MainActivity : AppCompatActivity() {

    private val app get() = application as MilebloomApp

    private lateinit var scene: LandscapeView
    private lateinit var permissionIntro: PermissionIntro
    private var debugReceiver: BroadcastReceiver? = null
    private var debugText: TextView? = null
    private var debugButtons: View? = null

    private val voiceOut by lazy { VoiceOut(this) }
    private val voiceIn by lazy { VoiceIn(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val root = FrameLayout(this)
        scene = LandscapeView(this)
        root.addView(scene, FrameLayout.LayoutParams(-1, -1))
        permissionIntro = PermissionIntro(this, root)
        if (isDebuggable()) {
            addDebugOverlay(root)
            registerSceneDebugReceiver()
        }
        setContentView(root)

        app.signals.start()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.modes.mode.collect { m -> scene.mode = m }
                }
                launch {
                    app.ambient.today.collect { c -> scene.today = c }
                }
                launch {
                    app.signals.snapshot.collect { s ->
                        scene.snapshot = s
                        permissionIntro.onSnapshot(s)
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 권한은 첫 실행 P에서 [PermissionIntro]가 한 번만 묻는다. CAR_SPEED를 받았으면 속도 구독을 시작한다
        if (permissionIntro.onResult(requestCode)) app.signals.onPermissionsChanged()
    }

    override fun onDestroy() {
        debugReceiver?.let { unregisterReceiver(it) }
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
     * `adb shell am broadcast -a com.milebloom.app.SCENE --es sun down --ei stage 7 --es car TUCSON --ei month 4 --ez hud false`
     * - sun: up | down | auto — 낮 시간대에 밤을, 밤에 낮을 보여 줄 때 (헤드램프는 Virtual Console로)
     * - stage 1..7, month 1..12, car GRANDEUR|AVANTE|TUCSON — 주행 중에 보내면 다음 P에서 적용된다
     * - hud false — 신호 패널을 숨긴다 (화면 녹화용)
     * - wheel true|false — 바퀴 회전 시험(D-063) 켜고 끄기. 사용자 비교용
     * - sun·month는 다음 P의 오늘의 색에도 적용된다
     * - ambientLink false — 설정의 '실내 조명 연동' 끔(D-053): 사용자 원래 색으로 되돌린다
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
                if (i.hasExtra("ambientLink")) app.ambient.setLinkEnabled(i.getBooleanExtra("ambientLink", true))
                val stage = i.getIntExtra("stage", 0)
                val month = i.getIntExtra("month", 0)
                if (month in 1..12) app.ambient.monthOverride = month
                if (stage in 1..7 || month in 1..12) {
                    scene.state = scene.state.copy(
                        stage = if (stage in 1..7) stage else scene.state.stage,
                        month = if (month in 1..12) month else scene.state.month,
                    )
                }
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
        const val ACTION_SCENE = "com.milebloom.app.SCENE"
    }
}
