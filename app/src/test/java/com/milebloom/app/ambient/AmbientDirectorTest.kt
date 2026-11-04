package com.milebloom.app.ambient

import com.milebloom.app.mode.ModeEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** D-053 규칙 */
class AmbientDirectorTest {

    private class FakePort(var switchOn: Boolean? = true, var color: Int? = 0x00FFFFFF) : AmbientDirector.Port {
        val sets = ArrayList<Int>()
        var colorReads = 0
        override fun readSwitchOn(onResult: (Boolean?) -> Unit) = onResult(switchOn)
        override fun readColor(onResult: (Int?) -> Unit) { colorReads++; onResult(color) }
        override fun setColor(argb: Int, onResult: (Boolean) -> Unit) { sets += argb; onResult(true) }
    }

    private class FakeStore : AmbientDirector.Store {
        override var originalColor: Int? = null
        override var linkEnabled = true
    }

    private val port = FakePort()
    private val store = FakeStore()
    private val d = AmbientDirector(port, store, { false }, { 4 }, log = {})

    private fun drive() {
        d.onEvent(0, ModeEvent.JourneyStarted(0, 0f))
        d.onEvent(60_000, ModeEvent.JourneyEnded(0, 60_000, 0f, 1f))
    }

    @Test fun `P 체결 때 오늘의 색으로 한 번 쓴다`() {
        drive()
        assertEquals(listOf(0xFFCC708F.toInt()), port.sets)
    }

    @Test fun `주행 시작과 주행 중에는 쓰지 않는다`() {
        d.onEvent(0, ModeEvent.JourneyStarted(0, 0f))
        d.onSnapshot(1000, com.milebloom.app.vehicle.VehicleSnapshot(lowBeamOn = true))
        assertEquals(emptyList<Int>(), port.sets)
    }

    /** 13번 19장: 에뮬레이터 스위치는 늘 OFF로 읽힌다. 이 값으로 건너뛰면 색이 한 번도 안 바뀐다 (D-073) */
    @Test fun `스위치 읽기 값은 쓰기를 막지 않는다`() {
        for (v in listOf(false, null)) {
            port.switchOn = v
            val n = port.sets.size
            drive()
            assertEquals(n + 1, port.sets.size)
        }
    }

    @Test fun `원래 색은 처음 쓰기 전에 한 번만 읽는다`() {
        drive()
        drive()
        assertEquals(1, port.colorReads)
        assertEquals(0x00FFFFFF, store.originalColor)
        assertEquals(2, port.sets.size)
    }

    @Test fun `연동을 끄면 원래 색으로 되돌리고 다음 P에 쓰지 않는다`() {
        drive()
        d.setLinkEnabled(false)
        assertEquals(0xFFFFFFFF.toInt(), port.sets.last())
        val n = port.sets.size
        drive()
        assertEquals(n, port.sets.size)
    }
}
