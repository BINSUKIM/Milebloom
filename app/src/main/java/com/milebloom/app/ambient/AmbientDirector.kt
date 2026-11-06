package com.milebloom.app.ambient

import android.util.Log
import com.milebloom.app.mode.ModeEvent
import com.milebloom.app.vehicle.VehicleSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 실내 조명 연출 규칙 (D-053, 30번 7.3).
 * - P 체결(JourneyEnded) 때 오늘의 색으로 **set 1회**. 주행 중·승차 때는 호출하지 않는다
 * - 색만 쓴다. **스위치를 쓰지 않고, 스위치로 판단하지도 않는다** — 에뮬레이터의 스위치 값은 Console과 연결돼 있지 않고 늘 OFF이며,
 *   색을 써도 스위치는 켜지지 않는다 (05 #32, 13번 19장, D-073). 읽은 값은 실차 확인을 위해 로그로만 남긴다
 * - 처음 set 직전에 사용자 원래 색을 읽어 보관한다. 밝기(%)는 건드리지 않는다
 *
 * 화면(하늘 물들임)은 [today]를 받는다.
 * 모드·기어가 아니라 사건만 받으므로 이 클래스가 set을 부르는 곳은 P 체결 한 곳과 [setLinkEnabled] 복원뿐이다.
 *
 * SDK 콜백은 SDK 스레드에서 온다. 이 클래스의 상태는 메인 스레드에서만 바꾼다.
 */
class AmbientDirector(
    private val port: Port,
    private val store: Store,
    isSunDown: (epochMs: Long) -> Boolean,
    private val currentMonth: () -> Int,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {

    interface Port {
        /** 기록용. @param onResult true = 켜짐(ON·AUTO), false = 꺼짐, null = 읽지 못함 */
        fun readSwitchOn(onResult: (Boolean?) -> Unit)
        fun readColor(onResult: (Int?) -> Unit)
        fun setColor(argb: Int, onResult: (Boolean) -> Unit)
    }

    /** 사용자 원래 색·연동 설정 보관소 */
    interface Store {
        var originalColor: Int?
        var linkEnabled: Boolean
    }

    private val tally = JourneyTally(isSunDown)
    private var last = VehicleSnapshot()
    private var inJourney = false

    private val _today = MutableStateFlow<TodayColor?>(null)
    /** 이번 P 체결의 오늘의 색. 앱을 켠 뒤 첫 P 체결 전에는 null */
    val today: StateFlow<TodayColor?> = _today.asStateFlow()

    /** 마지막 P 체결의 집계. 도착 인사([ArrivalGreeting])가 쓴다 */
    var lastTally: JourneyTally.Result? = null
        private set

    /** 시연용(debug): 들판의 달을 바꿔 보여 줄 때 색도 그 달로 맞춘다 */
    var monthOverride: Int? = null

    fun onSnapshot(epochMs: Long, s: VehicleSnapshot) {
        last = s
        if (inJourney) tally.onSnapshot(epochMs, s)
    }

    fun onEvent(epochMs: Long, e: ModeEvent) {
        when (e) {
            is ModeEvent.JourneyStarted -> {
                inJourney = true
                tally.start(epochMs, last)
            }
            is ModeEvent.JourneyEnded -> {
                inJourney = false
                val r = tally.finish(epochMs, e.distKm)
                lastTally = r
                val c = TodayColor.of(monthOverride ?: currentMonth(), r.rain, r.night, r.straight)
                _today.value = c
                log("오늘의 색 ${hex(c.ambientArgb)} (H=${c.hue} S=${c.sat} V=${c.ambientValue}, rain=${r.rain} night=${r.night} straight=${r.straight})")
                applyToCabin(c.ambientArgb)
            }
        }
    }

    private fun applyToCabin(argb: Int) {
        if (!store.linkEnabled) {
            log("건너뜀: 실내 조명 연동 꺼짐")
            return
        }
        port.readSwitchOn { on -> log("실내 조명 스위치 읽기 = $on (판단에 쓰지 않음)") }
        keepOriginalThenSet(argb)
    }

    private fun keepOriginalThenSet(argb: Int) {
        if (store.originalColor != null) {
            set(argb)
            return
        }
        port.readColor { original ->
            if (original != null) {
                store.originalColor = original
                log("사용자 원래 색 보관 ${hex(original)}")
            }
            set(argb)
        }
    }

    private fun set(argb: Int) = port.setColor(argb) { ok -> log("실내 조명 ${hex(argb)} ${if (ok) "적용" else "실패"}") }

    val linkEnabled: Boolean get() = store.linkEnabled

    /** 설정의 '실내 조명 연동' (D-053). 끄면 보관해 둔 사용자 원래 색으로 되돌린다. 화면은 주차 화면 설정 */
    fun setLinkEnabled(enabled: Boolean) {
        store.linkEnabled = enabled
        if (enabled) return
        val original = store.originalColor ?: return
        store.originalColor = null
        port.setColor(original or (0xFF shl 24)) { ok -> log("원래 색 복원 ${hex(original)} ${if (ok) "적용" else "실패"}") }
    }

    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

    private companion object {
        const val TAG = "MBAMB"
    }
}
