package com.milebloom.app.ambient

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * OKLCH에서 색상각을 최단 경로로 옮겨 섞는다 — 하늘 물들임 전용 (D-071).
 * 보색끼리 RGB로 섞으면 회색이 된다. 계수는 시안 `assets/art/screens/parking_scene.js`의 `mixHue`와 같다.
 *
 * 안드로이드 의존 없음 (단위 테스트 가능). 매 프레임 불려도 값 몇 개만 계산한다.
 */
object Oklch {

    fun mixHue(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        val (l1, c1, h1) = toLch(a)
        val (l2, c2, h2) = toLch(b)
        var d = h2 - h1
        while (d > PI) d -= 2 * PI
        while (d < -PI) d += 2 * PI
        return fromLch(l1 + (l2 - l1) * t, c1 + (c2 - c1) * t, h1 + d * t)
    }

    /** 밝기(OKLCH L)를 [maxL] 이하로 자른다. 색상각·채도는 그대로 (지도 밤 실루엣 상한 0.41, D-071) */
    fun capLightness(argb: Int, maxL: Double): Int {
        val (l, c, h) = toLch(argb)
        return if (l <= maxL) argb else fromLch(maxL, c, h)
    }

    /** OKLCH 밝기 L (0~1) */
    fun lightness(argb: Int): Double = toLch(argb).first

    /** 밝기만 [l]로 바꾼다. 색상각·채도는 그대로 (지도 원 테두리, D-090) */
    fun withLightness(argb: Int, l: Double): Int {
        val (_, c, h) = toLch(argb)
        return fromLch(l.coerceIn(0.0, 1.0), c, h)
    }

    private fun toLch(argb: Int): Triple<Double, Double, Double> {
        fun lin(shift: Int): Double {
            val v = ((argb shr shift) and 0xFF) / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val r = lin(16); val g = lin(8); val b = lin(0)
        val l = cbrt(0.4122 * r + 0.5363 * g + 0.0514 * b)
        val m = cbrt(0.2119 * r + 0.6807 * g + 0.1074 * b)
        val s = cbrt(0.0883 * r + 0.2817 * g + 0.6300 * b)
        val aa = 1.9780 * l - 2.4286 * m + 0.4506 * s
        val bb = 0.0259 * l + 0.7828 * m - 0.8087 * s
        return Triple(0.2105 * l + 0.7936 * m - 0.0041 * s, hypot(aa, bb), atan2(bb, aa))
    }

    private fun fromLch(lum: Double, c: Double, h: Double): Int {
        val aa = c * cos(h)
        val bb = c * sin(h)
        val l = (lum + 0.3963 * aa + 0.2158 * bb).pow(3)
        val m = (lum - 0.1056 * aa - 0.0639 * bb).pow(3)
        val s = (lum - 0.0895 * aa - 1.2915 * bb).pow(3)
        fun gamma(x: Double): Int {
            val v = x.coerceIn(0.0, 1.0)
            val e = if (v <= 0.0031308) 12.92 * v else 1.055 * abs(v).pow(1 / 2.4) - 0.055
            return Math.round(e * 255).toInt()
        }
        val r = gamma(4.0767 * l - 3.3077 * m + 0.2310 * s)
        val g = gamma(-1.2684 * l + 2.6098 * m - 0.3413 * s)
        val b = gamma(-0.0041 * l - 0.7034 * m + 1.6995 * s)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
