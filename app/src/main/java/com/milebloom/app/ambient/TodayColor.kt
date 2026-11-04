package com.milebloom.app.ambient

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 오늘의 색 (docs/30-art-direction.md 7.1, D-051·D-052). P 체결 때 한 번 계산한다.
 *
 * H·S는 실내 조명과 화면이 같이 쓰고 V만 각자 정한다. [ambientArgb]가 실내 조명에 보내는 값이다.
 * LED와 화면은 재현 방식이 달라 '똑같은 색'은 아니다 — "같은 색에서 나온다"까지만 말할 것.
 *
 * 안드로이드 의존 없음 (단위 테스트 가능).
 */
data class TodayColor(val hue: Float, val sat: Float, val ambientValue: Float) {

    val ambientArgb: Int get() = hsvToArgb(hue, sat, ambientValue)

    /** 주차 낮 화면색 — 하늘 물들임·지도 원 (30번 7.2) */
    val memoryDayArgb: Int get() = hsvToArgb(hue, sat * 0.55f, 0.95f)

    /** 주차 밤 화면색 (30번 7.2) */
    val memoryNightArgb: Int get() = hsvToArgb(hue, sat * 0.60f, 0.42f)

    companion object {
        /** 월 기본 색상 (D-051). `MbPalette.ambientBaseHue`와 같은 값 — 원천은 `assets/art/gen_palette.py` */
        val BASE_HUE = floatArrayOf(205f, 320f, 85f, 340f, 275f, 215f, 48f, 165f, 330f, 30f, 22f, 355f)

        private const val NIGHT_HUE = 230f
        private const val NIGHT_TURN_LIMIT = 130f
        private const val NIGHT_TURN_MAX = 40f
        private const val SAT_MIN = 0.30f

        /**
         * @param month 1..12
         * @param rain 와이퍼 수동 작동 시간 비율 0..1
         * @param night 헤드램프 켜짐 + 해 진 시간 비율 0..1
         * @param straight 곧은 길 정도 0..1. 실내 조명 밝기에만 쓴다 (D-041)
         */
        fun of(month: Int, rain: Float, night: Float, straight: Float): TodayColor {
            val base = BASE_HUE[(month - 1).coerceIn(0, 11)]
            val r = rain.coerceIn(0f, 1f)
            val n = night.coerceIn(0f, 1f)
            val d = shortestDelta(base, NIGHT_HUE)
            var h = base
            var s = 0.45f - 0.15f * r
            if (abs(d) <= NIGHT_TURN_LIMIT) {
                // 밤일수록 푸르게
                val turn = min(NIGHT_TURN_MAX * n, abs(d))
                h = if (d >= 0f) base + turn else base - turn
            } else {
                // 따뜻한 달(3·7·10·11월)은 푸르게 만들 길이 없다 — 대신 차분하게
                s -= 0.10f * n
            }
            return TodayColor(wrap(h), max(s, SAT_MIN), 0.55f + 0.25f * straight.coerceIn(0f, 1f))
        }

        /** a에서 b로 가는 최단 각도 차 (−180..180) */
        private fun shortestDelta(a: Float, b: Float): Float {
            val d = (b - a) % 360f
            return if (d > 180f) d - 360f else if (d < -180f) d + 360f else d
        }

        private fun wrap(h: Float) = ((h % 360f) + 360f) % 360f

        /** `android.graphics.Color.HSVToColor`와 같은 변환. 단위 테스트를 위해 직접 둔다 */
        fun hsvToArgb(h: Float, s: Float, v: Float): Int {
            val hh = wrap(h) / 60f
            val i = floor(hh).toInt()
            val f = hh - i
            val p = v * (1f - s)
            val q = v * (1f - s * f)
            val t = v * (1f - s * (1f - f))
            val (r, g, b) = when (i) {
                0 -> Triple(v, t, p)
                1 -> Triple(q, v, p)
                2 -> Triple(p, v, t)
                3 -> Triple(p, q, v)
                4 -> Triple(t, p, v)
                else -> Triple(v, p, q)
            }
            fun c(x: Float) = (x * 255f + 0.5f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
        }
    }
}
