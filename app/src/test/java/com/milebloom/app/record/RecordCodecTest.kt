package com.milebloom.app.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordCodecTest {

    private fun sample(): Records {
        var r = Records(forest = Forest(seed = -1234567890123L, meadowMonthKey = "2026-11"))
        val (r1, j) = r.startJourney(1_000L); r = r1
        r = r.addMemo(j, "세차 맡기기", 2_000L)
        r = r.arrive(j, 3_000L, "2026-11", 33.5f, Forest.Signs(rain = 0.7f, rainLv = 2, night = 0.5f, wind = 0.25f, guests = 1), 4,
            Forest.Today(340f, 0.45f, 0.775f), 0xFFCC708F.toInt(), "비 오는 길이었네요.")
        r = r.parkAt(j, 3_000L, 37.5665, 126.978)
        r = r.copy(forest = r.forest.debugAddKm(12f, "2026-11"))
        return r
    }

    @Test fun `저장 왕복 — 기록과 풍경이 그대로 돌아온다`() {
        val r = sample()
        val back = RecordCodec.decode(RecordCodec.encode(r))
        assertEquals(r, back)
        assertEquals(r.forest.stage, back.forest.stage)
        assertEquals(Forest.Today(340f, 0.45f, 0.775f), back.forest.today)
    }

    @Test fun `버전 1 파일은 끝난 주행 거리로 숲을 다시 세운다`() {
        val v1 = """{"version":1,"nextId":4,
            "journeys":[{"id":1,"startedAt":0,"endedAt":10,"distKm":40.0},{"id":2,"startedAt":20,"distKm":0.0},
                        {"id":3,"startedAt":30,"endedAt":40,"distKm":10.0}],
            "parkPoints":[],"memos":[]}"""
        val r = RecordCodec.decode(v1)
        assertEquals(3, r.journeys.size)
        assertEquals(50f, r.forest.totalDistKm, 1e-4f)
        assertEquals(2.0f, r.forest.totalGrowth, 1e-4f)   // 1.5 + 0.5
        assertEquals(2, r.forest.journeyCount)
        assertTrue(r.forest.elements.isEmpty())
    }

    @Test fun `모르는 요소 종류는 건너뛴다`() {
        val json = RecordCodec.encode(sample()).replace("\"type\":\"MOON\"", "\"type\":\"RAINBOW\"")
            .replace("\"type\":\"STREAM\"", "\"type\":\"RAINBOW\"")
        val r = RecordCodec.decode(json)
        assertTrue(r.forest.elements.none { it.type == Forest.Type.STREAM })
    }
}
