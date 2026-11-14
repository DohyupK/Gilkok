package com.example.gilkok

import org.junit.Assert.*
import org.junit.Test

class EnergyModelTest {
    private fun steadyTrip():TripRecord = TripRecord(startMs=0,endMs=60_000,destinationPoint=GeoPoint(37.55,127.05),crossing="영동대교:NORTH").apply {
        for(i in 0..60) samples+=TripSample(i*1000L,VehicleSnapshot(speedMps=10f,latitude=37.5+i*.0001,longitude=127.05))
    }
    @Test fun constantSpeedAgreesWithFlatRoadEnergyBalance() {
        val result=EnergyModel.estimate(steadyTrip())!!
        val watts=(1700*9.80665*.01*10 + .5*1.225*.65*1000)/.9+500
        assertEquals(watts*60/3_600_000,result.observedScenarioKwh,.000001)
        assertEquals(result.observedScenarioKwh,result.smoothScenarioKwh,.000001)
        assertEquals(600.0,result.coveredMeters,.001)
        assertEquals(100,result.coveragePercent)
    }
    @Test fun missingDataDoesNotBecomeAnEnergySaving() {
        val trip=steadyTrip()
        for(i in 10..50) trip.samples[i]=trip.samples[i].copy(value=VehicleSnapshot())
        assertNull(EnergyModel.estimate(trip))
    }
    @Test fun differentCrossingIsExcludedFromComparableTrips() {
        val a=steadyTrip(); val b=steadyTrip()
        assertTrue(TripComparison.similar(a,b))
        b.crossing="청담대교:SOUTH"
        assertFalse(TripComparison.similar(a,b))
    }
    @Test fun invalidProfileIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { VehicleProfile(massKg=-1.0) }
    }
}
