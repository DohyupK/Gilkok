package com.example.gilkok

import org.junit.Assert.*
import org.junit.Test

class RouteEngineTest {
    private val origin = GeoPoint(37.5446, 127.0557)   // 성수역
    private val coex = GeoPoint(37.5117, 127.0592)     // 코엑스
    private val kokA = KokPoint("콕 지점 1", GeoPoint(37.5280, 127.0650))
    private val kokFar = KokPoint("먼 지점", GeoPoint(37.5280, 127.0900))

    private fun route(koks: List<KokPoint>, ratio: Double = 0.0) =
        RouteOption("t", koks, 10_000.0, 900.0, emptyList(), ratio, RouteSource.DEMO, 0)

    @Test fun pathThroughKokPasses() {
        val path = Geo.densify(listOf(origin, kokA.point, coex), 80.0)
        assertEquals(1, KokValidator.passed(listOf(kokA), path))
    }

    @Test fun pathAwayFromKokFails() {
        val path = Geo.densify(listOf(origin, kokA.point, coex), 80.0)
        assertEquals(0, KokValidator.passed(listOf(kokFar), path))
    }

    @Test fun twoKoksMustBePassedInOrder() {
        val b = KokPoint("콕 지점 2", GeoPoint(37.5200, 127.0620))
        val path = Geo.densify(listOf(origin, kokA.point, b.point, coex), 80.0)
        assertEquals(2, KokValidator.passed(listOf(kokA, b), path))
        assertEquals(KokPoints.ordered(origin, listOf(b, kokA)), listOf(kokA, b))
    }

    @Test fun kokPointsSurviveSaving() {
        val saved = KokPoints.decode(KokPoints.encode(listOf(kokA, kokFar)))
        assertEquals(listOf(kokA, kokFar), saved)
        assertTrue(KokPoints.decode("garbage").isEmpty())
    }

    @Test fun energyUsesDefaultWithoutDataAndAddsCongestion() {
        val free = RouteEnergyEstimator.estimate(route(emptyList(), 0.0), null, emptyList())
        val jam = RouteEnergyEstimator.estimate(route(emptyList(), 1.0), null, emptyList())
        assertEquals(EnergyBasis.DEFAULT, free.basis)
        assertEquals(2.0, free.kwh, 1e-9)            // 10 km ÷ 5 km/kWh
        assertTrue(jam.kwh > free.kwh)
        assertEquals(EnergyBasis.VEHICLE, RouteEnergyEstimator.estimate(route(emptyList()), 6.2f, emptyList()).basis)
    }
}
