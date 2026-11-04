package com.milebloom.app.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StopDetectorTest {

    /** 10 Hz로 [fromMs, toMs) 동안 같은 속도를 넣는다 */
    private fun StopDetector.feed(fromMs: Long, toMs: Long, mps: Float) {
        var t = fromMs
        while (t < toMs) { onSample(t, mps); t += 100 }
    }

    @Test
    fun movingIsNotStopped() {
        val d = StopDetector()
        d.feed(0, 3_000, 11.1f)
        assertEquals(false, d.stopped)
    }

    @Test
    fun stopNeedsTwoSecondsBelowThreshold() {
        val d = StopDetector()
        d.feed(0, 2_000, 11.1f)
        // 0으로 떨어진 뒤 1초 평균이 0.5 아래로 내려가는 데 약 1초, 그 뒤 2초를 더 기다린다
        d.feed(2_000, 4_500, 0f)
        assertEquals(false, d.stopped)
        d.feed(4_500, 5_200, 0f)
        assertEquals(true, d.stopped)
    }

    @Test
    fun resumeNeedsOneSecondAboveOneMps() {
        val d = StopDetector()
        d.feed(0, 4_000, 0f)
        assertEquals(true, d.stopped)
        d.feed(4_000, 4_900, 5f)   // 1초 평균이 1.0을 넘은 지 1초가 안 됐다
        assertEquals(true, d.stopped)
        d.feed(4_900, 5_400, 5f)
        assertEquals(false, d.stopped)
    }

    @Test
    fun creepingBetweenThresholdsStaysStopped() {
        val d = StopDetector()
        d.feed(0, 4_000, 0f)
        d.feed(4_000, 9_000, 0.7f)   // 0.5 이상 1.0 미만 — 멈춤↔흐름이 왔다 갔다 하지 않는다
        assertEquals(true, d.stopped)
    }

    @Test
    fun briefCrawlBelowThresholdDoesNotStop() {
        val d = StopDetector()
        d.feed(0, 2_000, 8f)
        d.feed(2_000, 3_000, 0.2f)   // 1초만 기어감
        d.feed(3_000, 5_000, 8f)
        assertEquals(false, d.stopped)
    }

    @Test
    fun noChangeIsNotReportedTwice() {
        val d = StopDetector()
        d.feed(0, 3_000, 0f)
        assertFalse(d.onSample(3_000, 0f))
    }
}
