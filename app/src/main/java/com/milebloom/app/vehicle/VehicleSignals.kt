package com.milebloom.app.vehicle

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 차량 신호의 단일 입구. Pleos Vehicle SDK + AAOS(기어·UX 제약)를 하나의 [StateFlow]로 합친다.
 *
 * 앱 프로세스에 하나만 둔다 ([com.milebloom.app.MilebloomApp.signals]).
 * 앱을 켠 동안에만 수집한다 — 화면 없는 백그라운드 기록은 하지 않는다 (D-046).
 *
 * 신호 변화는 Logcat 태그 `MBSIG`로 남는다. 조향각처럼 같은 값이 주기적으로 재전송되는 신호는
 * 값이 바뀔 때만 찍는다.
 */
class VehicleSignals(context: Context) {

    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    private val _support = MutableStateFlow(Signal.entries.associateWith<Signal, SignalSupport> { SignalSupport.Checking })
    val support: StateFlow<Map<Signal, SignalSupport>> = _support.asStateFlow()

    private val lastLogged = HashMap<String, Any?>()

    private val pleos = PleosVehicleSource(context.applicationContext, ::apply, ::setSupport)
    private val car = CarStateSource(
        context.applicationContext,
        onGear = { g -> apply("gear", g) { copy(gear = g) } },
        onUx = { requiresDo, active ->
            apply("ux", "DO=$requiresDo active=0x${active.toString(16)}") {
                copy(uxRequiresDo = requiresDo, uxActiveRestrictions = active)
            }
        },
        onSupport = ::setSupport,
        onSpeed = ::onSpeed,
    )

    private val stopDetector = StopDetector()

    private var running = false

    fun start() {
        if (running) return
        running = true
        Log.i(TAG, "수집 시작")
        car.start()
        pleos.start()
    }

    fun stop(onReleased: () -> Unit = {}) {
        if (!running) { onReleased(); return }
        running = false
        Log.i(TAG, "수집 종료")
        car.stop()
        pleos.stop(onReleased)
    }

    /** 런타임 권한(CAR_SPEED)을 받은 뒤 부른다. 수집 중이면 속도 구독을 시작한다 */
    fun onPermissionsChanged() {
        if (running) car.startSpeed()
    }

    /** 10 Hz로 오지만 snapshot은 정차 판정이 바뀔 때만 갱신한다 — 화면·기록을 10 Hz에 묶지 않는다 (13번 17-2) */
    private fun onSpeed(mps: Float) {
        synchronized(stopDetector) {
            if (!stopDetector.onSample(SystemClock.elapsedRealtime(), mps)) return
            val s = stopDetector.stopped
            apply("stopped", s) { copy(stopped = s) }
        }
    }

    /** 앰비언트 색 쓰기. 언제 쓰는가는 [com.milebloom.app.ambient.AmbientDirector]가 정하고, 여기는 SDK 호출 입구만 둔다. */
    fun setAmbientColor(argb: Int, onResult: (Boolean) -> Unit = {}) = pleos.setAmbientColor(argb, onResult)

    fun readAmbientSwitchOn(onResult: (Boolean?) -> Unit) = pleos.readAmbientSwitchOn(onResult)

    fun readAmbientColor(onResult: (Int?) -> Unit) = pleos.readAmbientColor(onResult)

    private fun apply(key: String, logValue: Any?, change: VehicleSnapshot.() -> VehicleSnapshot) {
        _snapshot.update { it.change().copy(updatedAtMs = SystemClock.elapsedRealtime()) }
        synchronized(lastLogged) {
            if (lastLogged.containsKey(key) && lastLogged[key] == logValue) return
            lastLogged[key] = logValue
        }
        Log.i(TAG, "▶ $key = $logValue")
    }

    private fun setSupport(signal: Signal, s: SignalSupport) {
        _support.update { it + (signal to s) }
        Log.i(TAG, "지원 ${signal.name}: $s")
    }

    private companion object {
        const val TAG = "MBSIG"
    }
}
