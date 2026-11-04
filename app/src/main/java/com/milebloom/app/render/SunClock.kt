package com.milebloom.app.render

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 해가 졌는가 — `dark`의 시간 쪽 절반 (D-043). 기기 안에서 계산하고 통신·위치 조회를 하지 않는다.
 *
 * 태양 고도를 근사식으로 구해 −0.833°(대기 굴절 + 해의 반지름) 아래면 진 것으로 본다.
 * 오차는 수 분 이내. 시각은 epoch(UTC) 기준이라 기기 시간대와 무관하다.
 * 좌표는 마지막 P 지점(5-B 저장 후) → 없으면 서울.
 */
object SunClock {
    const val SEOUL_LAT = 37.5665
    const val SEOUL_LON = 126.9780

    fun isSunDown(epochMs: Long, lat: Double = SEOUL_LAT, lon: Double = SEOUL_LON): Boolean =
        altitudeDeg(epochMs, lat, lon) < -0.833

    fun altitudeDeg(epochMs: Long, lat: Double, lon: Double): Double {
        val d = epochMs / 86_400_000.0 + 2440587.5 - 2451545.0
        val g = Math.toRadians(357.529 + 0.98560028 * d)
        val q = 280.459 + 0.98564736 * d
        val l = Math.toRadians(q + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = Math.toDegrees(atan2(cos(e) * sin(l), cos(l)))
        val dec = asin(sin(e) * sin(l))
        val gmstDeg = (18.697374558 + 24.06570982441908 * d) * 15.0
        val ha = Math.toRadians(gmstDeg + lon - ra)
        val phi = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(ha)))
    }
}
