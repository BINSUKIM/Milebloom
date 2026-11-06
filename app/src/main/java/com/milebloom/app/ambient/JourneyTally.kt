package com.milebloom.app.ambient

import com.milebloom.app.vehicle.VehicleSnapshot
import com.milebloom.app.vehicle.WiperLevel
import kotlin.math.abs
import kotlin.math.min

/**
 * 한 번의 주행에서 오늘의 색에 들어가는 값(rain·night·straight)과 도착 인사용 멈춘 횟수를 메모리에서 센다.
 * 정의는 docs/12-idea-v2.md 4.2 표. 저장은 하지 않는다 — 5-B가 기록을 만들면 그쪽 집계로 옮길 자리다.
 *
 * 안드로이드 의존 없음 (단위 테스트 가능).
 */
class JourneyTally(private val isSunDown: (epochMs: Long) -> Boolean) {

    private var startedMs = 0L
    private var lastMs = 0L
    private var manualWiper = false
    private var lowBeam = false
    private var wiperRunMs = 0L
    private var rainMs = 0L
    private var nightMs = 0L
    private var lastSteer: Float? = null
    private var turns = 0
    private var stops = 0
    private var wasStopped = false

    fun start(nowMs: Long, s: VehicleSnapshot) {
        startedMs = nowMs
        lastMs = nowMs
        wiperRunMs = 0L
        rainMs = 0L
        nightMs = 0L
        turns = 0
        stops = 0
        wasStopped = s.stopped == true
        lastSteer = s.steeringDeg
        manualWiper = isManual(s)
        lowBeam = s.lowBeamOn == true
    }

    /** @param nowMs epoch 밀리초 — 해 진 시간 계산에 쓴다 */
    fun onSnapshot(nowMs: Long, s: VehicleSnapshot) {
        advance(nowMs)
        val manual = isManual(s)
        if (manualWiper && !manual) closeWiperRun()
        manualWiper = manual
        lowBeam = s.lowBeamOn == true
        // 멈춘 횟수 — 도착 인사에만 쓴다 (D-041). 정차 판정(StopDetector)이 '섰다'로 바뀐 순간만 센다
        val nowStopped = s.stopped == true
        if (nowStopped && !wasStopped) stops++
        wasStopped = nowStopped
        // 조향은 값이 그대로여도 3초마다 재전송된다 — 5° 이상 달라졌을 때만 굽이로 센다
        s.steeringDeg?.let { a ->
            val prev = lastSteer
            if (prev == null) lastSteer = a
            else if (abs(a - prev) >= TURN_DEG) { turns++; lastSteer = a }
        }
    }

    fun finish(nowMs: Long, distKm: Float): Result {
        advance(nowMs)
        closeWiperRun()
        val total = (nowMs - startedMs).coerceAtLeast(1L).toFloat()
        // 거리를 모르면(0) 굽이 수를 km로 나눌 수 없다 — 곧은 길로 둔다
        val straight = if (distKm > 0f) 1f - min(1f, turns / distKm / 4f) else 1f
        return Result(rainMs / total, nightMs / total, straight, stops)
    }

    private fun advance(nowMs: Long) {
        val dt = nowMs - lastMs
        if (dt <= 0L) return
        if (manualWiper) wiperRunMs += dt
        if (lowBeam && isSunDown(nowMs)) nightMs += dt
        lastMs = nowMs
    }

    /** 30초 미만 작동은 비로 치지 않는다 (D-043) */
    private fun closeWiperRun() {
        if (wiperRunMs >= MIN_WIPER_RUN_MS) rainMs += wiperRunMs
        wiperRunMs = 0L
    }

    /** 수동 단수만 센다 — AUTO는 에뮬레이터에서 비와 무관하게 ON이다 (D-045) */
    private fun isManual(s: VehicleSnapshot) = s.wiperSwitch == WiperLevel.LOW || s.wiperSwitch == WiperLevel.HIGH

    data class Result(val rain: Float, val night: Float, val straight: Float, val stops: Int = 0)

    private companion object {
        const val TURN_DEG = 5f
        const val MIN_WIPER_RUN_MS = 30_000L
    }
}
