package com.example.gilkok

import android.content.Context
import kotlin.math.min
import kotlin.math.roundToInt

enum class EnergyBasis(val label: String) {
    PERSONAL("내 주행 기록 기반"),
    VEHICLE("차량 전비 기반"),
    DEFAULT("기본값 추정 · 미확인"),
}

data class EnergyPrediction(
    val kwh: Double,
    val kmPerKwh: Double,
    val basis: EnergyBasis,
    val congestionPercent: Int,
    val habitPercent: Int,
)

data class EnergyActual(val kwh: Double, val distanceKm: Double, val kmPerKwh: Double)

/**
 * 출발 전 경로별 전력 예측.
 * 전비 = 내 최근 기록 중앙값 > 현재 차량 전비 > 기본값 순으로 사용하고,
 * 정체 비율과 내 급가속·급감속 빈도로 보정합니다.
 */
object RouteEnergyEstimator {
    const val DEFAULT_KM_PER_KWH = 5.0

    fun lastEfficiency(trip: TripRecord): Double? =
        trip.samples.lastOrNull { it.value.evKmPerKwh != null }?.value?.evKmPerKwh?.toDouble()?.takeIf { it in 1.0..12.0 }

    fun personalEfficiency(history: List<TripRecord>): Double? {
        val values = history.take(5).mapNotNull(::lastEfficiency).sorted()
        return if (values.isEmpty()) null else values[values.size / 2]
    }

    /** 최근 주행의 km당 급가속·급감속 횟수 → 최대 +10% */
    fun habitPenalty(history: List<TripRecord>): Double {
        val reports = history.take(5).map(TripAnalyzer::analyze).filter { (it.distanceKm ?: 0.0) >= 1.0 }
        if (reports.isEmpty()) return 0.0
        val events = reports.sumOf { it.hardAccelerations + it.hardBrakes }
        val km = reports.sumOf { it.distanceKm ?: 0.0 }
        return min(.10, events / km * .05)
    }

    fun estimate(route: RouteOption, vehicleKmPerKwh: Float?, history: List<TripRecord>): EnergyPrediction {
        val personal = personalEfficiency(history)
        val vehicle = vehicleKmPerKwh?.toDouble()?.takeIf { it in 1.0..12.0 }
        val (efficiency, basis) = when {
            personal != null -> personal to EnergyBasis.PERSONAL
            vehicle != null -> vehicle to EnergyBasis.VEHICLE
            else -> DEFAULT_KM_PER_KWH to EnergyBasis.DEFAULT
        }
        val congestion = .12 * route.congestedRatio
        val habit = habitPenalty(history)
        val kwh = route.distanceM / 1000 / efficiency * (1 + congestion + habit)
        return EnergyPrediction(kwh, efficiency, basis, (congestion * 100).roundToInt(), (habit * 100).roundToInt())
    }

    /** 주행 후 실제값: 누적거리(또는 속도 적분) ÷ 종료 시점 차량 전비. */
    fun actual(trip: TripRecord): EnergyActual? {
        val odo = trip.samples.mapNotNull { it.value.odometerKm?.toDouble() }
        val distance = if (odo.size >= 2 && odo.last() > odo.first()) odo.last() - odo.first()
            else TripAnalyzer.analyze(trip).distanceKm ?: return null
        val efficiency = lastEfficiency(trip) ?: return null
        if (distance < .3) return null
        return EnergyActual(distance / efficiency, distance, efficiency)
    }
}

/** 출발 전 예측값을 주행 기록 ID와 연결해 기기 안에만 저장. */
object PredictionStore {
    private fun prefs(c: Context) = c.getSharedPreferences("predictions", Context.MODE_PRIVATE)
    fun save(c: Context, tripId: String, kwh: Double) = prefs(c).edit().putFloat(tripId, kwh.toFloat()).apply()
    fun get(c: Context, tripId: String): Double? = prefs(c).getFloat(tripId, -1f).takeIf { it > 0 }?.toDouble()
}
