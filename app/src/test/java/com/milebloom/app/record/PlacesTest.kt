package com.milebloom.app.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** 전국 지도의 장소 기록 — 이름 붙이기·장소 지우기(D-030·D-042)·시연 주입 */
class PlacesTest {

    /** 주행 하나를 시작해 [lat],[lon]에 도착시킨다 */
    private fun arrive(r: Records, at: Long, lat: Double, lon: Double, color: Int? = null): Pair<Records, Long> {
        val (s, id) = r.startJourney(at - 1000)
        return s.endJourney(id, at, 10f, color, "잘 다녀오셨어요.").parkAt(id, at, lat, lon) to id
    }

    @Test fun `장소 이름은 사용자가 붙인 것만 - 다듬고 자르고 빈 글이면 없앤다`() {
        val (r0, _) = arrive(Records(), 10, 37.5, 127.0)
        val p = r0.parkPoints.single().id
        assertNull(r0.parkPoints.single().label) // 자동으로 붙이지 않는다
        val named = r0.renameParkPoint(p, "  단골 카페 ")
        assertEquals("단골 카페", named.parkPoint(p)!!.label)
        assertEquals(Records.LABEL_MAX, named.renameParkPoint(p, "가".repeat(40)).parkPoint(p)!!.label!!.length)
        assertNull(named.renameParkPoint(p, "   ").parkPoint(p)!!.label)
        assertEquals(named, named.renameParkPoint(999L, "없는 장소"))
    }

    @Test fun `장소 하나 지우기 - 점과 도착지만 끊고 주행·음성 기록·풍경은 남는다`() {
        var (r, j1) = arrive(Records(), 10, 37.5, 127.0)
        val (r2, j2) = arrive(r, 20, 35.18, 129.07)
        r = r2.addMemo(j1, "세차 맡기기", 15)
        val seoul = r.journey(j1)!!.parkPointId!!
        val busan = r.journey(j2)!!.parkPointId!!
        val after = r.deleteParkPoint(seoul)
        assertEquals(listOf(busan), after.parkPoints.map { it.id })
        assertNull(after.journey(j1)!!.parkPointId)
        assertEquals(busan, after.journey(j2)!!.parkPointId)
        assertEquals(r.journeys.size, after.journeys.size)
        assertEquals(1, after.memos.size)
        assertEquals(r.forest, after.forest)
        assertEquals(after, after.deleteParkPoint(seoul)) // 두 번 지워도 같다
    }

    @Test fun `지운 장소에 다시 세우면 새 점으로 시작한다`() {
        val (r, j) = arrive(Records(), 10, 37.5, 127.0)
        val gone = r.deleteParkPoint(r.journey(j)!!.parkPointId!!)
        val (again, _) = arrive(gone, 20, 37.5, 127.0)
        assertEquals(1, again.parkPoints.single().visitCount)
    }

    @Test fun `장소의 방문 기록은 최근 것이 위이고 대표 색은 마지막 도착의 색`() {
        var r = Records()
        r = arrive(r, 10, 37.5, 127.0, color = 0xFF0000).first
        r = arrive(r, 30, 37.5005, 127.0, color = 0x00FF00).first   // 200 m 안 → 같은 장소
        r = arrive(r, 40, 37.5, 127.0, color = null).first           // 색이 없는 도착은 건너뛴다
        val p = r.parkPoints.single()
        assertEquals(3, p.visitCount)
        assertEquals(listOf(40L, 30L, 10L), r.journeysAt(p.id).map { it.endedAt })
        assertEquals(0x00FF00, r.colorOf(p.id))
    }

    @Test fun `시연 주입은 여러 장소를 만들되 풍경을 바꾸지 않는다`() {
        val now = 1_762_400_000_000L // 2025-11
        val base = Records()
        val r = DemoPlaces.inject(base, now, TimeZone.getTimeZone("Asia/Seoul"))
        assertEquals(9, r.parkPoints.size)
        assertEquals(DemoPlaces.totalVisits, r.parkPoints.sumOf { it.visitCount })
        assertEquals(DemoPlaces.totalVisits, r.journeys.size)
        assertEquals(base.forest, r.forest)
        assertTrue(r.journeys.all { it.distKm == 0f && it.growth == 0f && it.endedAt!! < now })
        assertEquals(listOf("단골 카페"), r.parkPoints.mapNotNull { it.label })
        assertTrue(r.memos.isNotEmpty() && r.memos.all { it.confirmed })
        assertTrue(r.parkPoints.all { r.colorOf(it.id) != null })
        // 장소의 최근 방문일은 그 장소 방문 기록의 맨 위와 같다
        assertTrue(r.parkPoints.all { p -> p.lastAt == r.journeysAt(p.id).first().endedAt })
    }

    @Test fun `늦게 들어온 옛 도착이 최근 방문일을 되돌리지 않는다`() {
        var r = arrive(Records(), 50, 37.5, 127.0).first
        r = arrive(r, 20, 37.5, 127.0).first
        assertEquals(50L, r.parkPoints.single().lastAt)
        assertEquals(20L, r.parkPoints.single().firstAt)
    }
}
