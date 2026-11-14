package com.example.gilkok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripAnalyzerTest {
    @Test
    fun doesNotReportEnergyWhenSpeedWasUnavailable() {
        val trip = TripRecord(startMs = 0L, endMs = 1000L)
        trip.samples += TripSample(0L, VehicleSnapshot(brakePressed = false))
        trip.samples += TripSample(1000L, VehicleSnapshot(brakePressed = true))

        val result = TripAnalyzer.analyze(trip)

        assertNull(result.brakingLossUpperBoundKwh)
        assertNull(result.distanceKm)
    }

    @Test
    fun detectsHardEventsAndFallsBackWhenOdometerIsFrozen() {
        val trip = TripRecord(startMs = 0L, endMs = 3000L)
        listOf(0f, 10f, 10f, 0f).forEachIndexed { index, speed ->
            trip.samples += TripSample(index * 1000L, VehicleSnapshot(speedMps = speed, odometerKm = 0f))
        }

        val result = TripAnalyzer.analyze(trip)

        assertEquals(1, result.hardAccelerations)
        assertEquals(1, result.hardBrakes)
        assertEquals(0.02, result.distanceKm ?: 0.0, 0.0001)
        assertTrue((result.brakingLossUpperBoundKwh ?: 0.0) > 0.02)
    }
}
