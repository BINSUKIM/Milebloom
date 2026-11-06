package com.milebloom.app.mode

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayLevelTest {

    @Test fun `주차와 평소 주행은 Lv1이다`() {
        assertEquals(DisplayLevel.LV1, DisplayLevel.of(null))
        assertEquals(DisplayLevel.LV1, DisplayLevel.of(0))       // P
        assertEquals(DisplayLevel.LV1, DisplayLevel.of(0x50))    // D: NO_VIDEO | NO_SETUP
        assertEquals(DisplayLevel.LV1, DisplayLevel.of(0x10))    // 평소의 일부만
    }

    @Test fun `평소 밖의 비트가 하나라도 켜지면 Lv3이다`() {
        assertEquals(DisplayLevel.LV3, DisplayLevel.of(0x50 or 0x80))   // + LIMIT_STRING_LENGTH
        assertEquals(DisplayLevel.LV3, DisplayLevel.of(0x50 or 0x200))  // + LIMIT_CONTENT
        assertEquals(DisplayLevel.LV3, DisplayLevel.of(0xFF))           // FULLY_RESTRICTED
        assertEquals(DisplayLevel.LV3, DisplayLevel.of(0x01))           // NO_DIALPAD 단독
    }
}
