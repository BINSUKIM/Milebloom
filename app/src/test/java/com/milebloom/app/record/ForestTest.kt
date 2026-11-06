package com.milebloom.app.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForestTest {

    private val nov = "2026-11"
    private val dec = "2026-12"

    @Test fun `성장량은 거리만으로 20km에 1, 상한 1_5`() {
        assertEquals(0f, Forest.growthOf(0f), 1e-6f)
        assertEquals(0.5f, Forest.growthOf(10f), 1e-6f)
        assertEquals(1f, Forest.growthOf(20f), 1e-6f)
        assertEquals(1.5f, Forest.growthOf(30.8f), 1e-6f)
        assertEquals(1.5f, Forest.growthOf(500f), 1e-6f)
    }

    @Test fun `단계 문턱은 3 8 18 35 60 100`() {
        assertEquals(1, Forest.stageOf(0f))
        assertEquals(1, Forest.stageOf(2.99f))
        assertEquals(2, Forest.stageOf(3f))
        assertEquals(3, Forest.stageOf(8f))
        assertEquals(4, Forest.stageOf(18f))
        assertEquals(5, Forest.stageOf(35f))
        assertEquals(6, Forest.stageOf(60f))
        assertEquals(7, Forest.stageOf(100f))
        assertEquals(7, Forest.stageOf(1000f))
    }

    @Test fun `처음 설치한 풍경은 1단계 빈 들판이다`() {
        val v = Forest(seed = 1L).view(nov)
        assertEquals(1, v.stage)
        assertEquals(0f, v.meadowGrowth, 0f)
        assertFalse(v.stream); assertFalse(v.moon)
        assertEquals(0, v.puddles + v.fireflies + v.seedFluff + v.birds)
    }

    @Test fun `일평균 30_8km를 출퇴근 두 번으로 달리면 65일째 7단계 — 주 5일이면 약 3개월`() {
        var f = Forest(seed = 1L)
        var days = 0
        while (f.stage < 7) {
            repeat(2) { f = f.grow(null, 0L, nov, 15.4f, Forest.Signs(), null) }
            days++
        }
        // 하루 2 × 0.77 = 1.54, 100 / 1.54 → 65번째 날 = 주 5일 13주 (12번 4.4 '3~4개월')
        assertEquals(65, days)
    }

    @Test fun `거리 0인 주행은 풍경을 바꾸지 않고 색만 남긴다`() {
        val t = Forest.Today(330f, 0.45f, 0.8f)
        val f = Forest(seed = 1L).grow(7L, 10L, nov, 0f, Forest.Signs(rain = 1f, rainLv = 2, night = 1f, guests = 2), t)
        assertEquals(0, f.journeyCount)
        assertEquals(0f, f.totalGrowth, 0f)
        assertTrue(f.elements.isEmpty())
        assertEquals(t, f.today)
    }

    @Test fun `요소 배정 12번 4_3 표`() {
        val e = Forest.elementsOf(Forest.Signs(rain = 0.7f, rainLv = 2, night = 0.8f, wind = 0.5f, guests = 2)).toMap()
        assertEquals(1, e[Forest.Type.STREAM])
        assertEquals(1 + 1, e[Forest.Type.PUDDLE])          // 1 + floor(0.7 × 2)
        assertEquals(4, e[Forest.Type.FIREFLY])             // floor(0.8 × 6)
        assertEquals(1, e[Forest.Type.MOON])
        assertEquals(2, e[Forest.Type.SEED_FLUFF])          // floor(0.5 × 5)
        assertEquals(2, e[Forest.Type.BIRD])
        assertTrue(Forest.elementsOf(Forest.Signs(rain = 0.29f, night = 0.39f, wind = 0.19f)).isEmpty())
    }

    @Test fun `들판은 달마다 새로 시작하고 숲과 지난달 요소는 남는다`() {
        val rainy = Forest.Signs(rain = 0.7f, rainLv = 1, night = 0.5f)
        val f1 = Forest(seed = 1L).grow(1L, 1L, nov, 40f, rainy, null)
        assertEquals(1.5f, f1.view(nov).meadowGrowth, 1e-6f)
        assertEquals(3, f1.view(nov).fireflies)
        val f2 = f1.rollMonth(dec)
        val v = f2.view(dec)
        assertEquals(0f, v.meadowGrowth, 0f)
        assertEquals(0, v.fireflies)                        // 들판 요소는 화면에서 내려간다
        assertTrue(v.stream)                                // 개울은 숲 층 — 남는다
        assertEquals(f1.totalGrowth, f2.totalGrowth, 0f)
        assertEquals(f1.elements, f2.elements)              // 지우지 않는다
    }

    @Test fun `화면 개수 상한`() {
        var f = Forest(seed = 1L)
        repeat(10) { f = f.grow(it.toLong(), 0L, nov, 5f, Forest.Signs(rain = 1f, rainLv = 2, night = 1f, wind = 1f, guests = 3), null) }
        val v = f.view(nov)
        assertEquals(Forest.MAX_PUDDLES, v.puddles)
        assertEquals(Forest.MAX_FIREFLIES, v.fireflies)
        assertEquals(Forest.MAX_FLUFF, v.seedFluff)
        assertEquals(Forest.MAX_BIRDS, v.birds)
    }

    @Test fun `debug 주입은 하루 단위로 나눠 더하고 따로 센다`() {
        val f = Forest(seed = 1L).debugAddKm(61.6f, nov)
        assertEquals(3f, f.totalGrowth, 1e-4f)              // 30.8 × 2 → 1.5 × 2
        assertEquals(61.6f, f.debugKm, 1e-4f)
        assertEquals(0, f.journeyCount)
        assertEquals(2, f.stage)
    }

    @Test fun `기록을 지우면 풍경은 남고 주행과의 연결만 끊긴다`() {
        val (r0, j) = Records(forest = Forest(seed = 9L)).startJourney(0L)
        val r1 = r0.arrive(j, 100L, nov, 40f, Forest.Signs(night = 0.9f), 0, null, null, "잘 다녀오셨어요.")
        val r2 = r1.clearAll(null)
        assertTrue(r2.journeys.isEmpty())
        assertEquals(r1.forest.totalGrowth, r2.forest.totalGrowth, 0f)
        assertEquals(r1.forest.elements.size, r2.forest.elements.size)
        assertTrue(r2.forest.elements.all { it.journeyId == null && it.createdAt == 0L })
    }

    @Test fun `같은 주행으로 두 번 키우지 않는다, 시작을 못 본 주행은 키우지 않는다`() {
        val (r0, j) = Records(forest = Forest(seed = 9L)).startJourney(0L)
        val r1 = r0.arrive(j, 100L, nov, 20f, Forest.Signs(), 0, null, null, null)
        val r2 = r1.arrive(j, 200L, nov, 20f, Forest.Signs(), 0, null, null, null)
        assertEquals(1f, r2.forest.totalGrowth, 1e-6f)
        assertEquals(1f, r1.journey(j)!!.growth, 1e-6f)
        val t = Forest.Today(1f, 0.4f, 0.7f)
        val r3 = r1.arrive(null, 300L, nov, 50f, Forest.Signs(), 0, t, null, null)
        assertEquals(1f, r3.forest.totalGrowth, 1e-6f)
        assertEquals(t, r3.forest.today)
        assertNull(Records().journey(null))
    }
}
