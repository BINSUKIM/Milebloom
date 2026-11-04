package com.milebloom.app.ambient

import com.milebloom.app.vehicle.VehicleSnapshot
import com.milebloom.app.vehicle.WiperLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyTallyTest {

    private fun tally(sunDown: Boolean = false) = JourneyTally { sunDown }

    @Test fun `수동 와이퍼 시간 비율이 rain이다`() {
        val t = tally()
        t.start(0, VehicleSnapshot())
        t.onSnapshot(60_000, VehicleSnapshot(wiperSwitch = WiperLevel.LOW))
        t.onSnapshot(120_000, VehicleSnapshot(wiperSwitch = WiperLevel.OFF))
        assertEquals(0.5f, t.finish(120_000, 1f).rain, 0.001f)
    }

    @Test fun `30초 미만 와이퍼와 AUTO는 비로 치지 않는다`() {
        val t = tally()
        t.start(0, VehicleSnapshot())
        t.onSnapshot(10_000, VehicleSnapshot(wiperSwitch = WiperLevel.HIGH))
        t.onSnapshot(30_000, VehicleSnapshot(wiperSwitch = WiperLevel.AUTO))
        t.onSnapshot(90_000, VehicleSnapshot(wiperSwitch = WiperLevel.OFF))
        assertEquals(0f, t.finish(100_000, 1f).rain, 0.001f)
    }

    @Test fun `night는 헤드램프와 해 진 시간이 함께일 때만 센다`() {
        val day = tally(sunDown = false)
        day.start(0, VehicleSnapshot(lowBeamOn = true))
        assertEquals(0f, day.finish(60_000, 1f).night, 0.001f)
        val dusk = tally(sunDown = true)
        dusk.start(0, VehicleSnapshot())
        dusk.onSnapshot(30_000, VehicleSnapshot(lowBeamOn = true))
        assertEquals(0.5f, dusk.finish(60_000, 1f).night, 0.001f)
    }

    @Test fun `같은 조향값 재전송은 굽이가 아니다`() {
        val t = tally()
        t.start(0, VehicleSnapshot(steeringDeg = 0f))
        repeat(10) { t.onSnapshot(it * 3000L, VehicleSnapshot(steeringDeg = 0f)) }
        t.onSnapshot(40_000, VehicleSnapshot(steeringDeg = 10f))
        t.onSnapshot(41_000, VehicleSnapshot(steeringDeg = -10f))
        // 굽이 2회 / 1 km → straight = 1 − 2/4
        assertEquals(0.5f, t.finish(50_000, 1f).straight, 0.001f)
    }

    @Test fun `거리를 모르면 곧은 길로 둔다`() {
        val t = tally()
        t.start(0, VehicleSnapshot(steeringDeg = 0f))
        t.onSnapshot(1000, VehicleSnapshot(steeringDeg = 50f))
        assertEquals(1f, t.finish(2000, 0f).straight, 0.001f)
    }
}
