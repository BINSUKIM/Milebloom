package com.milebloom.app.vehicle

/**
 * 차 속도 → 정차/주행 두 상태 (D-061, 13번 17장). 안드로이드 의존 없는 순수 로직.
 *
 * - 속도는 10 Hz로 들어오므로 **최근 1초 평균**으로 솎는다
 * - 1초 평균이 [STOP_MPS] 미만인 상태가 [STOP_HOLD_MS] 이상 이어지면 정차
 * - 정차 중에는 1초 평균이 [RESUME_MPS] 이상인 상태가 [RESUME_HOLD_MS] 이상 이어져야 주행으로 돌아간다.
 *   멈춤 임계와 간격을 둬 서행(크리핑)에서 멈춤↔흐름이 왔다 갔다 하지 않게 한다 (30번 6.2-B 권고)
 * - 속도에 비례하는 값은 내보내지 않는다 — 두 상태뿐이다 (속도감 금지, D-052·D-061)
 *
 * 임계값은 에뮬레이터 기준이고 실차 노이즈는 모른다 (조정 대상).
 */
class StopDetector {

    private val times = LongArray(CAP)
    private val values = FloatArray(CAP)
    private var head = 0
    private var size = 0
    private var belowSinceMs = -1L
    private var aboveSinceMs = -1L

    /** 현재 판정. 아직 1초치 표본이 없으면 null */
    var stopped: Boolean? = null
        private set

    /** 표본 하나를 넣고, 판정이 바뀌었으면 true */
    fun onSample(nowMs: Long, speedMps: Float): Boolean {
        val v = if (speedMps.isNaN()) 0f else kotlin.math.abs(speedMps)
        push(nowMs, v)
        while (size > 0 && nowMs - times[head] > WINDOW_MS) { head = (head + 1) % CAP; size-- }
        if (size == 0) return false

        var sum = 0f
        for (i in 0 until size) sum += values[(head + i) % CAP]
        val avg = sum / size

        val next = if (stopped == true) {
            if (avg >= RESUME_MPS) {
                if (aboveSinceMs < 0) aboveSinceMs = nowMs
                nowMs - aboveSinceMs < RESUME_HOLD_MS
            } else {
                aboveSinceMs = -1
                true
            }
        } else if (avg < STOP_MPS) {
            if (belowSinceMs < 0) belowSinceMs = nowMs
            nowMs - belowSinceMs >= STOP_HOLD_MS
        } else {
            belowSinceMs = -1
            false
        }
        if (next) belowSinceMs = -1 else aboveSinceMs = -1
        val changed = next != stopped
        stopped = next
        return changed
    }

    private fun push(t: Long, v: Float) {
        if (size == CAP) { head = (head + 1) % CAP; size-- }
        val i = (head + size) % CAP
        times[i] = t
        values[i] = v
        size++
    }

    companion object {
        const val STOP_MPS = 0.5f
        const val STOP_HOLD_MS = 2_000L
        const val RESUME_MPS = 1.0f
        const val RESUME_HOLD_MS = 1_000L
        const val WINDOW_MS = 1_000L
        private const val CAP = 64
    }
}
