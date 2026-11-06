package com.milebloom.app.map

import com.milebloom.app.ambient.Oklch
import kotlin.math.max
import kotlin.math.min

/**
 * 지도 원의 칠 (D-090 — D-071·30번 6.5 '불투명도 0.6, 외곽선 없음'을 바꿈). 안드로이드 의존 없음.
 *
 * 0.6 채움만으로는 낮 바탕(`skyLow` 바다·`ground`+흰 30% 육지, OKLCH L 약 0.88~0.94) 위에서 노란 계열 원이 묻혔다.
 * 색(오늘의 색에서 나온 원 색)은 바꾸지 않고 **밝기만** 조정한다:
 * - 낮: 채움 밝기 상한 [DAY_FILL_MAX_L], 불투명도 [FILL_ALPHA]. 가장자리는 채움보다 [DAY_EDGE_DROP] 어둡게
 * - 밤: 채움 밝기 하한 [NIGHT_FILL_MIN_L](바탕 L 약 0.25~0.41). 가장자리는 채움보다 [NIGHT_EDGE_RISE] 밝게
 */
object MapInk {
    const val FILL_ALPHA = 0xD9 // 0.85
    const val DAY_FILL_MAX_L = 0.80
    const val DAY_EDGE_DROP = 0.22
    const val NIGHT_FILL_MIN_L = 0.60
    const val NIGHT_EDGE_RISE = 0.16

    data class Ink(val fill: Int, val edge: Int)

    fun of(color: Int, night: Boolean): Ink {
        val l = Oklch.lightness(color)
        val fillL = if (night) max(l, NIGHT_FILL_MIN_L) else min(l, DAY_FILL_MAX_L)
        val base = if (fillL == l) color or (0xFF shl 24) else Oklch.withLightness(color, fillL)
        val edgeL = if (night) min(fillL + NIGHT_EDGE_RISE, 0.92) else max(fillL - DAY_EDGE_DROP, 0.30)
        return Ink(
            fill = (base and 0x00FFFFFF) or (FILL_ALPHA shl 24),
            edge = Oklch.withLightness(color, edgeL),
        )
    }
}
