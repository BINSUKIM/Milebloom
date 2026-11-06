package com.milebloom.app.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordsTest {

    private fun journey(r: Records = Records(), at: Long = 0L) = r.startJourney(at)

    @Test fun `음성 기록은 확인 전으로 들어가고 그 주행에 묶인다`() {
        val (r0, j) = journey()
        val r = r0.addMemo(j, "  세차 맡기기 ", 10L)
        assertEquals(1, r.memos.size)
        assertEquals("세차 맡기기", r.memos[0].text)
        assertEquals(j, r.memos[0].journeyId)
        assertFalse(r.memos[0].confirmed)
        assertEquals(1, r.unconfirmedCount)
    }

    @Test fun `빈 글과 없는 주행에는 남기지 않는다`() {
        val (r0, j) = journey()
        assertEquals(0, r0.addMemo(j, "   ", 1L).memos.size)
        assertEquals(0, r0.addMemo(999L, "글", 1L).memos.size)
    }

    @Test fun `확인과 수정은 confirmed를 켜고 빈 글로는 고치지 않는다`() {
        val (r0, j) = journey()
        val r1 = r0.addMemo(j, "a", 1L)
        val id = r1.memos[0].id
        assertTrue(r1.confirmMemo(id).memos[0].confirmed)
        val edited = r1.editMemo(id, "b")
        assertEquals("b", edited.memos[0].text)
        assertTrue(edited.memos[0].confirmed)
        assertEquals("a", r1.editMemo(id, " ").memos[0].text)
    }

    @Test fun `삭제는 그 항목만 지운다`() {
        val (r0, j) = journey()
        val r1 = r0.addMemo(j, "a", 1L).addMemo(j, "b", 2L)
        val r2 = r1.deleteMemo(r1.memos[0].id)
        assertEquals(listOf("b"), r2.memos.map { it.text })
    }

    @Test fun `200m 안에 다시 세우면 새 점 없이 방문 횟수만 오른다`() {
        val (a0, j1) = journey()
        val a1 = a0.endJourney(j1, 100L, 3f, null, "인사").parkAt(j1, 100L, 37.5665, 126.9780)
        val (b0, j2) = a1.startJourney(200L)
        // 약 111 m 북쪽
        val b1 = b0.endJourney(j2, 300L, 3f, null, "인사").parkAt(j2, 300L, 37.5675, 126.9780)
        assertEquals(1, b1.parkPoints.size)
        assertEquals(2, b1.parkPoints[0].visitCount)
        assertEquals(b1.parkPoints[0].id, b1.journey(j2)?.parkPointId)
        // 약 1.1 km 떨어진 곳은 새 점
        val (c0, j3) = b1.startJourney(400L)
        val c1 = c0.endJourney(j3, 500L, 3f, null, "인사").parkAt(j3, 500L, 37.5765, 126.9780)
        assertEquals(2, c1.parkPoints.size)
    }

    @Test fun `도착지는 한 번만 정해진다`() {
        val (a0, j) = journey()
        val a1 = a0.endJourney(j, 1L, 0f, null, null).parkAt(j, 1L, 37.0, 127.0)
        val a2 = a1.parkAt(j, 1L, 37.0, 127.0)
        assertEquals(1, a2.parkPoints[0].visitCount)
    }

    @Test fun `전체 초기화는 진행 중인 주행 하나만 남긴다`() {
        val (a0, j1) = journey()
        val a1 = a0.endJourney(j1, 1L, 0f, null, null).parkAt(j1, 1L, 37.0, 127.0).addMemo(j1, "x", 1L)
        val (a2, j2) = a1.startJourney(2L)
        val cleared = a2.clearAll(keepJourneyId = j2)
        assertEquals(listOf(j2), cleared.journeys.map { it.id })
        assertTrue(cleared.parkPoints.isEmpty())
        assertTrue(cleared.memos.isEmpty())
        assertNull(cleared.journey(j2)?.parkPointId)
        // id는 되돌리지 않는다
        assertTrue(cleared.startJourney(3L).second > j2)
    }

    @Test fun `목록은 최근 것이 위`() {
        val (a0, j) = journey()
        val r = a0.addMemo(j, "old", 1L).addMemo(j, "new", 5L)
        assertEquals(listOf("new", "old"), r.memosNewestFirst().map { it.text })
        assertEquals(listOf("old", "new"), r.memosOf(j).map { it.text })
    }
}
