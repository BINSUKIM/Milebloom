package com.milebloom.app.ambient

import org.junit.Assert.assertEquals
import org.junit.Test

class ArrivalGreetingTest {

    private fun r(rain: Float = 0f, night: Float = 0f, straight: Float = 1f, stops: Int = 0) = JourneyTally.Result(rain, night, straight, stops)

    @Test fun `비가 가장 먼저`() = assertEquals(ArrivalGreeting.RAIN, ArrivalGreeting.of(r(rain = 0.5f, straight = 0.1f, night = 1f), 10f))
    @Test fun `km당 1번 이상 서면 막히는 길`() = assertEquals(ArrivalGreeting.STOPS, ArrivalGreeting.of(r(stops = 5), 4f))
    @Test fun `거리를 모르면 막힘을 말하지 않는다`() = assertEquals(ArrivalGreeting.PLAIN, ArrivalGreeting.of(r(stops = 5), 0f))
    @Test fun `굽이`() = assertEquals(ArrivalGreeting.TURNS, ArrivalGreeting.of(r(straight = 0.4f), 10f))
    @Test fun `밤`() = assertEquals(ArrivalGreeting.NIGHT, ArrivalGreeting.of(r(night = 0.8f), 10f))
    @Test fun `기본`() = assertEquals(ArrivalGreeting.PLAIN, ArrivalGreeting.of(r(), 10f))

    @Test fun `정차로 바뀐 순간만 센다`() {
        val t = JourneyTally { false }
        t.start(0, com.milebloom.app.vehicle.VehicleSnapshot(stopped = true))
        t.onSnapshot(1_000, com.milebloom.app.vehicle.VehicleSnapshot(stopped = true))
        t.onSnapshot(2_000, com.milebloom.app.vehicle.VehicleSnapshot(stopped = false))
        t.onSnapshot(3_000, com.milebloom.app.vehicle.VehicleSnapshot(stopped = true))
        t.onSnapshot(4_000, com.milebloom.app.vehicle.VehicleSnapshot(stopped = true))
        t.onSnapshot(5_000, com.milebloom.app.vehicle.VehicleSnapshot(stopped = null))
        t.onSnapshot(6_000, com.milebloom.app.vehicle.VehicleSnapshot(stopped = true))
        assertEquals(2, t.finish(6_000, 1f).stops)
    }
}
