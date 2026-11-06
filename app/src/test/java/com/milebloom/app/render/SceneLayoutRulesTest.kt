package com.milebloom.app.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneLayoutRulesTest {

    @Test fun `퍼지는 순서는 모든 칸을 한 번씩, 앞쪽이 고르게`() {
        assertArrayEquals(intArrayOf(0, 4, 2, 6, 1, 3, 5, 7), SceneLayout.spreadOrder(8))
        for (n in 1..12) {
            val o = SceneLayout.spreadOrder(n)
            assertEquals(n, o.size)
            assertEquals((0 until n).toSet(), o.toSet())
        }
    }

    @Test fun `단계가 오를수록 줄마다 나무가 줄지 않는다 — 쌓이는 배치의 전제`() {
        for (stage in 1..6) {
            val a = SceneLayout.treesPerRow(stage)
            val b = SceneLayout.treesPerRow(stage + 1)
            for (row in a.indices) assertTrue("stage $stage row $row", b[row] >= a[row])
            for (row in b.indices) assertTrue(b[row] <= SceneLayout.MAX_TREES[row])
        }
    }

    @Test fun `들판 밀도 하한 0_15, 25에서 만개`() {
        assertEquals(0.15f, SceneLayout.meadowDensity(0f), 1e-6f)
        assertEquals(1f, SceneLayout.meadowDensity(25f), 1e-6f)
        assertEquals(1f, SceneLayout.meadowDensity(80f), 1e-6f)
        assertTrue(SceneLayout.sproutsOf(1, 0.15f) <= SceneLayout.MAX_SPROUTS)
        assertTrue(SceneLayout.sproutsOf(2, 0.15f) <= SceneLayout.MAX_SPROUTS)
    }
}
