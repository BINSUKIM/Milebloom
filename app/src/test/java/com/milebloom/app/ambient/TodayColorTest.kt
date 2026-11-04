package com.milebloom.app.ambient

import org.junit.Assert.assertEquals
import org.junit.Test

class TodayColorTest {

    private fun hex(argb: Int) = "%06X".format(argb and 0xFFFFFF)

    /** 30번 7.1 표: 맑음·낮·곧은 길 (rain 0, night 0, straight 1) */
    @Test fun `맑은 낮 곧은 길은 표와 같다`() {
        val table = listOf("70A6CC", "CC70AD", "A6CC70", "CC708F", "A670CC", "7096CC", "CCBA70", "70CCB5", "CC709E", "CC9E70", "CC9270", "CC7078")
        table.forEachIndexed { i, want ->
            assertEquals("${i + 1}월", want, hex(TodayColor.of(i + 1, 0f, 0f, 1f).ambientArgb))
        }
    }

    /** 30번 7.1 표: 비·낮. 표는 생성기가 V를 0.68로 반올림해 만들었다 — 공식의 straight 0.5(V 0.675)와 한 칸 다르므로 V 0.68이 되는 0.52로 맞춘다 */
    @Test fun `비 오는 낮은 표와 같다`() {
        val table = listOf("7998AD", "AD799C", "98AD79", "AD798B", "9879AD", "798FAD", "ADA379", "79ADA0", "AD7993", "AD9379", "AD8C79", "AD797E")
        table.forEachIndexed { i, want ->
            assertEquals("${i + 1}월", want, hex(TodayColor.of(i + 1, 1f, 0f, 0.52f).ambientArgb))
        }
    }

    /** 30번 7.2 표 예: 4월 맑은 낮 */
    @Test fun `화면 주차색은 표와 같다`() {
        val c = TodayColor.of(4, 0f, 0f, 1f)
        assertEquals("F2B6CA", hex(c.memoryDayArgb))
        assertEquals("6B4E58", hex(c.memoryNightArgb))
    }

    /** D-051: 옛 식의 오류 사례 — 6월은 청록이 아니라 230° 쪽, 7월은 빨강이 아니라 채도만 낮춘다 */
    @Test fun `밤에는 230도 쪽으로 돌고 따뜻한 달은 채도를 낮춘다`() {
        assertEquals(230f, TodayColor.of(6, 0f, 1f, 1f).hue, 0.01f) // 215 → 15° 차이에서 멈춤
        assertEquals(205f, TodayColor.of(8, 0f, 1f, 1f).hue, 0.01f) // 165 + 40
        assertEquals(315f, TodayColor.of(12, 0f, 1f, 1f).hue, 0.01f) // 355 − 40
        val july = TodayColor.of(7, 0f, 1f, 1f)
        assertEquals(48f, july.hue, 0.01f)
        assertEquals(0.35f, july.sat, 0.001f)
    }

    @Test fun `채도 하한은 0점3이다`() {
        assertEquals(0.30f, TodayColor.of(10, 1f, 1f, 1f).sat, 0.001f)
    }

    @Test fun `월 기본 색상은 생성기 값과 같다`() {
        org.junit.Assert.assertArrayEquals(com.milebloom.app.render.MbPalette.ambientBaseHue, TodayColor.BASE_HUE, 0f)
    }
}
