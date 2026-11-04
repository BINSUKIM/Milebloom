package com.milebloom.app.mode

import com.milebloom.app.vehicle.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeMachineTest {

    @Test
    fun parkToDriveToPark_isOneJourney() {
        val m = ModeMachine()
        m.onOdometer(100f)
        assertNull(m.onGear(Gear.PARK, 0))
        assertEquals(AppMode.BOARDING, m.mode)

        val started = m.onGear(Gear.DRIVE, 1_000)
        assertTrue(started is ModeEvent.JourneyStarted)
        assertEquals(AppMode.JOURNEY, m.mode)

        m.onOdometer(137f)
        val ended = m.onGear(Gear.PARK, 5_000) as ModeEvent.JourneyEnded
        assertEquals(AppMode.ARRIVAL, m.mode)
        assertEquals(37f, ended.distKm, 0.001f)
        assertEquals(1_000, ended.startedAtMs)
    }

    @Test
    fun reverseInParkingLot_isNotAJourney() {
        val m = ModeMachine()
        assertNull(m.onGear(Gear.REVERSE, 0))
        assertNull(m.onGear(Gear.PARK, 10))
        assertEquals(AppMode.BOARDING, m.mode)
    }

    @Test
    fun neutralDuringDrive_keepsJourney() {
        val m = ModeMachine()
        m.onGear(Gear.DRIVE, 0)
        assertNull(m.onGear(Gear.NEUTRAL, 10))
        assertNull(m.onGear(Gear.DRIVE, 20))
        assertEquals(AppMode.JOURNEY, m.mode)
    }

    @Test
    fun odometerArrivingAfterDrive_becomesStart() {
        val m = ModeMachine()
        m.onGear(Gear.DRIVE, 0)
        m.onOdometer(50f)
        m.onOdometer(70f)
        val ended = m.onGear(Gear.PARK, 10) as ModeEvent.JourneyEnded
        assertEquals(20f, ended.distKm, 0.001f)
    }

    @Test
    fun missingOdometer_givesZeroDistance() {
        val m = ModeMachine()
        m.onGear(Gear.DRIVE, 0)
        val ended = m.onGear(Gear.PARK, 10) as ModeEvent.JourneyEnded
        assertEquals(0f, ended.distKm, 0.001f)
    }

    @Test
    fun arrivalThenDrive_startsNewJourney() {
        val m = ModeMachine()
        m.onGear(Gear.DRIVE, 0)
        m.onGear(Gear.PARK, 10)
        assertTrue(m.onGear(Gear.DRIVE, 20) is ModeEvent.JourneyStarted)
    }

    @Test
    fun unknownGear_isIgnored() {
        val m = ModeMachine()
        assertNull(m.onGear(Gear.UNKNOWN, 0))
        assertEquals(AppMode.BOARDING, m.mode)
    }
}
