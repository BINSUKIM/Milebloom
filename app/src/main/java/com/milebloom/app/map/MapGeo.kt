package com.milebloom.app.map

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 전국 지도의 계산 (D-029·D-042, 30번 6.5). 안드로이드 의존 없음 — 단위 테스트 대상.
 *
 * - 좌표는 K1 실루엣(`LandscapeShapes.koreaMap`)과 같은 **단위 좌표**: 왼쪽 위 원점, 높이 1, 폭 [MAP_WIDTH], 아래가 +y.
 *   위경도 → 단위 좌표는 선형 변환 하나다(정밀 측량이 아니다). 상수는 생성기가 `LandscapeShapes.mapX·mapY`에 쓴 값과 같다
 * - 원은 **차를 세운 곳(P 지점)**에만 그린다. 점끼리 잇지 않는다 (D-005)
 * - 확대가 없으므로 겹친 원은 한 묶음으로 보고, 누르면 근처 장소 목록을 연다 (D-042)
 */
object MapGeo {
    /** K1 실루엣 폭 (높이 1 기준). `LandscapeShapes.MAP_WIDTH`와 같은 값 */
    const val MAP_WIDTH = 0.85869f

    private const val LON0 = 125.95
    private const val LAT0 = 38.75
    private const val KX = 0.141933
    private const val KY = 0.175439

    /** 실루엣 밖으로 이만큼(단위 좌표)까지는 그린다 — 해안·섬 끝의 점이 잘리지 않게 */
    private const val MARGIN = 0.02f

    fun x(lon: Double): Float = ((lon - LON0) * KX).toFloat()
    fun y(lat: Double): Float = ((LAT0 - lat) * KY).toFloat()

    /**
     * 지도 그림 범위 안인가. 밖(예: 에뮬레이터 기본 좌표 Mountain View)이면 원을 그리지 않는다 — 목록에는 남는다.
     * 범위는 사각형이라 바다 위 점도 그려진다(실루엣 안팎은 판정하지 않는다)
     */
    fun inRange(lat: Double, lon: Double): Boolean {
        val px = x(lon); val py = y(lat)
        return px >= -MARGIN && px <= MAP_WIDTH + MARGIN && py >= -MARGIN && py <= 1f + MARGIN
    }

    /**
     * 원 반지름 — 지도 높이 1000 기준 `10 + 34 × √(min(방문, 10) / 10)`을 [mapHeight]에 비례시킨다.
     * 면적이 방문 수에 비례하다가 **10회에서 멈춘다** (D-042). 1회는 약 20.8, 10회 이상은 44
     */
    fun radius(visits: Int, mapHeight: Float): Float {
        val v = min(max(visits, 1), 10)
        return (10f + 34f * sqrt(v / 10f)) * mapHeight / 1000f
    }

    /** 화면에 그린 원 하나 (픽셀) */
    data class Circle(val id: Long, val cx: Float, val cy: Float, val r: Float)

    /** 실루엣을 [w]×[h] 안에 [pad]만큼 띄워 맞춘다. 비율은 유지한다 */
    data class Fit(val left: Float, val top: Float, val scale: Float) {
        fun px(lon: Double) = left + x(lon) * scale
        fun py(lat: Double) = top + y(lat) * scale
    }

    fun fit(w: Float, h: Float, pad: Float): Fit {
        val scale = max(0f, min((w - 2 * pad) / MAP_WIDTH, h - 2 * pad))
        return Fit((w - MAP_WIDTH * scale) / 2f, (h - scale) / 2f, scale)
    }

    /**
     * 겹친 원끼리 묶는다(이어서 겹치면 같은 묶음). 원의 순서를 유지하고, 묶음은 첫 원의 순서대로.
     * 맞닿기만 한 원(중심 거리 = 반지름 합)은 겹친 것으로 보지 않는다
     */
    fun groups(circles: List<Circle>): List<List<Circle>> {
        val parent = IntArray(circles.size) { it }
        fun find(i: Int): Int { var a = i; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
        for (i in circles.indices) for (j in i + 1 until circles.size) {
            val a = circles[i]; val b = circles[j]
            if (hypot(a.cx - b.cx, a.cy - b.cy) < a.r + b.r) {
                val ra = find(i); val rb = find(j)
                if (ra != rb) parent[max(ra, rb)] = min(ra, rb)
            }
        }
        return circles.indices.groupBy { find(it) }.toSortedMap().values.map { idx -> idx.map { circles[it] } }
    }

    /**
     * 누른 자리에서 가장 가까운 원. 작은 원은 보이는 크기보다 넓게 [minTouchR]까지 받는다(32번 2.2, 76 dp 터치).
     * 원 안을 눌렀으면 그 원들 중 중심이 가장 가까운 것, 아니면 터치 반경 안에서 가장 가까운 것. 없으면 null
     */
    fun hit(circles: List<Circle>, x: Float, y: Float, minTouchR: Float): Circle? =
        circles.map { it to hypot(it.cx - x, it.cy - y) }
            .filter { (c, d) -> d <= max(c.r, minTouchR) }
            .minWithOrNull(compareBy({ (c, d) -> if (d <= c.r) 0 else 1 }, { (_, d) -> d }))
            ?.first

    /**
     * 누름 → 열 것: 겹친 원이 없으면 그 장소 하나, 겹쳐 있으면 그 묶음 전체(근처 장소 목록).
     * 아무 원도 맞지 않으면 빈 목록
     */
    fun tap(circles: List<Circle>, x: Float, y: Float, minTouchR: Float): List<Long> {
        val h = hit(circles, x, y, minTouchR) ?: return emptyList()
        return groups(circles).first { g -> g.any { it.id == h.id } }.map { it.id }
    }
}
