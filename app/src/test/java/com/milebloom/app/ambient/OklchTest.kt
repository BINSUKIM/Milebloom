package com.milebloom.app.ambient

import org.junit.Assert.assertEquals
import org.junit.Test

class OklchTest {

    /** 기대값은 시안 `parking_scene.js`의 mixHue를 node로 돌린 결과 */
    @Test fun `시안의 하늘 물들임과 같은 값이 나온다`() {
        fun mix(a: Long, b: Long) = "%06X".format(Oklch.mixHue(a.toInt(), b.toInt(), 0.3f) and 0xFFFFFF)
        assertEquals("DED6F3", mix(0xFFCFE3F7, 0xFFEFC5A9))
        assertEquals("463B50", mix(0xFF27354A, 0xFF6B5A4C))
        assertEquals("D3D7F8", mix(0xFFCFE3F7, 0xFFF2B6CA))
    }

    @Test fun `비율이 0이면 원래 색 그대로다`() {
        val c = 0xFFCFE3F7.toInt()
        assertEquals(c, Oklch.mixHue(c, 0xFFEFC5A9.toInt(), 0f))
    }
}
