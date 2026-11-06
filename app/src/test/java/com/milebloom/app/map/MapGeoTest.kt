package com.milebloom.app.map

import com.milebloom.app.map.MapGeo.Circle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapGeoTest {

    // ── 좌표 → 지도 투영 ──

    @Test fun `독도는 실루엣 생성기가 그린 독도 점 위에 떨어진다`() {
        // LandscapeShapes.koreaMap의 독도 원: 중심 약 (0.8390, 0.2644)
        assertEquals(0.8390f, MapGeo.x(131.8667), 0.003f)
        assertEquals(0.2644f, MapGeo.y(37.2417), 0.003f)
    }

    @Test fun `제주는 실루엣 왼쪽 아래 섬 범위 안이다`() {
        // 제주 섬 경로의 범위: x 0.03~0.142, y 0.908~0.974
        val x = MapGeo.x(126.5312); val y = MapGeo.y(33.4996)
        assertTrue(x in 0.03f..0.142f)
        assertTrue(y in 0.908f..0.974f)
    }

    @Test fun `서울은 부산보다 위(작은 y)·왼쪽(작은 x)이다`() {
        assertTrue(MapGeo.y(37.5665) < MapGeo.y(35.1796))
        assertTrue(MapGeo.x(126.978) < MapGeo.x(129.0756))
    }

    @Test fun `한국 밖 좌표는 지도 범위 밖이다 - 에뮬레이터 기본 좌표`() {
        assertFalse(MapGeo.inRange(37.42, -122.08)) // Mountain View
        assertFalse(MapGeo.inRange(39.03, 125.75))  // 평양 — 실루엣 북쪽 끝(군사분계선) 위
        assertTrue(MapGeo.inRange(37.5665, 126.978))
        assertTrue(MapGeo.inRange(33.4996, 126.5312))
        assertTrue(MapGeo.inRange(37.2417, 131.8667))
    }

    @Test fun `맞춤은 비율을 지키고 가운데에 놓는다`() {
        val f = MapGeo.fit(1400f, 1210f, 60f)
        assertEquals(1090f, f.scale, 0.01f)                          // 높이가 먼저 찬다
        assertEquals((1400f - MapGeo.MAP_WIDTH * 1090f) / 2f, f.left, 0.01f)
        assertEquals(60f, f.top, 0.01f)
        val narrow = MapGeo.fit(400f, 1210f, 20f)
        assertEquals(360f / MapGeo.MAP_WIDTH, narrow.scale, 0.01f) // 폭이 먼저 찬다
    }

    // ── 원 크기 ──

    @Test fun `원은 10회에서 최대이고 1회보다 작아지지 않는다`() {
        assertEquals(44f, MapGeo.radius(10, 1000f), 0.001f)
        assertEquals(44f, MapGeo.radius(52, 1000f), 0.001f)
        assertEquals(10f + 34f * kotlin.math.sqrt(0.1f), MapGeo.radius(1, 1000f), 0.001f)
        assertEquals(MapGeo.radius(1, 1000f), MapGeo.radius(0, 1000f), 0.001f)
        assertTrue(MapGeo.radius(5, 1000f) < MapGeo.radius(6, 1000f))
        assertEquals(22f, MapGeo.radius(10, 500f), 0.001f) // 지도 높이에 비례
    }

    // ── 원 겹침 묶기 ──

    @Test fun `겹친 원은 이어서 한 묶음, 떨어진 원은 따로`() {
        val a = Circle(1, 100f, 100f, 40f)
        val b = Circle(2, 150f, 100f, 20f)   // a와 겹침
        val c = Circle(3, 185f, 100f, 20f)   // b와 겹침 → a와도 같은 묶음
        val d = Circle(4, 500f, 500f, 40f)   // 혼자
        val g = MapGeo.groups(listOf(a, b, c, d))
        assertEquals(listOf(listOf(1L, 2L, 3L), listOf(4L)), g.map { grp -> grp.map { it.id } })
    }

    @Test fun `맞닿기만 한 원은 겹친 것이 아니다`() {
        val g = MapGeo.groups(listOf(Circle(1, 0f, 0f, 10f), Circle(2, 20f, 0f, 10f)))
        assertEquals(2, g.size)
    }

    @Test fun `순서가 뒤섞여도 같은 묶음을 찾는다`() {
        val g = MapGeo.groups(listOf(Circle(1, 0f, 0f, 10f), Circle(2, 100f, 0f, 10f), Circle(3, 50f, 0f, 45f)))
        assertEquals(1, g.size)
        assertEquals(listOf(1L, 2L, 3L), g[0].map { it.id })
    }

    // ── 누르기 ──

    private val cluster = listOf(Circle(1, 100f, 100f, 40f), Circle(2, 130f, 110f, 30f))
    private val alone = Circle(3, 400f, 400f, 12f)

    @Test fun `겹친 원을 누르면 묶음 전체, 혼자인 원을 누르면 그 장소 하나`() {
        val all = cluster + alone
        assertEquals(listOf(1L, 2L), MapGeo.tap(all, 105f, 100f, 38f))
        assertEquals(listOf(3L), MapGeo.tap(all, 400f, 400f, 38f))
    }

    @Test fun `작은 원은 보이는 크기보다 넓게 눌린다`() {
        // 반지름 12, 터치 반경 38 → 중심에서 30 떨어진 곳도 눌린다
        assertEquals(listOf(3L), MapGeo.tap(listOf(alone), 430f, 400f, 38f))
        assertTrue(MapGeo.tap(listOf(alone), 440f, 400f, 38f).isEmpty())
    }

    @Test fun `원 안을 누른 것이 터치 여유로 닿은 것보다 먼저다`() {
        val big = Circle(1, 100f, 100f, 50f)
        val small = Circle(2, 175f, 100f, 8f)   // 큰 원과 안 겹침(거리 75 > 58)
        // (140,100): 큰 원 안(거리 40), 작은 원은 터치 여유로만 닿음(거리 35)
        assertEquals(1L, MapGeo.hit(listOf(big, small), 140f, 100f, 38f)!!.id)
    }

    @Test fun `빈 곳을 누르면 아무것도 열지 않는다`() {
        assertNull(MapGeo.hit(cluster, 700f, 700f, 38f))
        assertTrue(MapGeo.tap(emptyList(), 0f, 0f, 38f).isEmpty())
    }
}
