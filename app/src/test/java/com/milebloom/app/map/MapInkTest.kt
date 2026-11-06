package com.milebloom.app.map

import com.milebloom.app.ambient.Oklch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapInkTest {

    /** 낮 지도 육지(`ground`+흰 30%)의 대략 밝기 — 이보다 원이 충분히 어두워야 보인다 */
    private val dayLandL = 0.88
    /** 밤 지도 육지 상한 (D-071) */
    private val nightLandL = 0.41

    private val yellow = 0xFFE3CF6E.toInt()  // 7월 계열 — 5h-02에서 묻히던 색
    private val orange = 0xFFC88C68.toInt()
    private val blue = 0xFF6F8FB8.toInt()

    @Test fun `낮 - 밝은 노란 원도 채움은 상한 밑, 가장자리는 육지보다 확연히 어둡다`() {
        for (c in listOf(yellow, orange, blue)) {
            val ink = MapInk.of(c, night = false)
            assertTrue(Oklch.lightness(ink.fill or (0xFF shl 24)) <= MapInk.DAY_FILL_MAX_L + 0.01)
            assertTrue(dayLandL - Oklch.lightness(ink.edge) >= 0.25)
            assertEquals(MapInk.FILL_ALPHA, ink.fill ushr 24)
        }
    }

    @Test fun `밤 - 어두운 원도 채움은 하한 위, 가장자리는 채움보다 밝다`() {
        for (c in listOf(yellow, orange, blue, 0xFF3A3050.toInt())) {
            val ink = MapInk.of(c, night = true)
            val fillL = Oklch.lightness(ink.fill or (0xFF shl 24))
            assertTrue(fillL >= MapInk.NIGHT_FILL_MIN_L - 0.01)
            assertTrue(fillL - nightLandL >= 0.18)
            assertTrue(Oklch.lightness(ink.edge) > fillL)
        }
    }

    @Test fun `이미 충분히 어두운 낮 원은 색을 그대로 둔다`() {
        val ink = MapInk.of(blue, night = false)
        assertEquals(blue and 0xFFFFFF, ink.fill and 0xFFFFFF)
    }
}
