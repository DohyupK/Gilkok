package com.example.gilkok

import kotlin.math.max

/**
 * 연비 계산 기준 (팀 합의, 11/15)
 * - 공인 "도심 연비 / 고속 연비"를 구간별로 나눠 역산: 연료(L) = 도심 km ÷ 도심 연비 + 고속 km ÷ 고속 연비
 * - 고속 구간 = 고속도로 + 자동차전용도로 (경로 서버의 구간 속도가 70km/h 이상인 도로로 판별)
 * - 화면에는 두 경로의 "연료 차이(L)"와 도심/고속 비율만 표시
 * - 나중에: 차량 실제 누적 연비로 보정, 차종 선택, 전기차(공인 도심/고속 전비)
 */
enum class Powertrain { HYBRID, GASOLINE, EV }

data class VehicleSpec(
    val name: String,
    val powertrain: Powertrain,
    val cityKmPerL: Double,      // 공인 도심 연비 (전기차는 km/kWh)
    val highwayKmPerL: Double,   // 공인 고속 연비
    val combinedKmPerL: Double,  // 공인 복합 연비 (보정 기준)
    val unit: String = "L",
)

object Vehicles {
    // 그랜저 하이브리드 공인 연비 범위의 중간값 (복합 15.9~18.4, 도심 15.5~18.2, 고속 16.3~18.5 km/L)
    val GRANDEUR_HEV = VehicleSpec(
        name = "그랜저 하이브리드",
        powertrain = Powertrain.HYBRID,
        cityKmPerL = (15.5 + 18.2) / 2,     // 16.85
        highwayKmPerL = (16.3 + 18.5) / 2,  // 17.4
        combinedKmPerL = (15.9 + 18.4) / 2, // 17.15
    )
    /** 지금은 시연 차종 하나. 차종 선택은 추후 설정 화면에 추가 */
    fun current(): VehicleSpec = GRANDEUR_HEV
}

data class FuelEstimate(
    val liters: Double,
    val cityKm: Double,
    val highwayKm: Double,
    val corrected: Boolean,      // 실제 누적 연비 보정 적용 여부 (추후)
) {
    val highwayPercent get() = if (cityKm + highwayKm > 0) (highwayKm / (cityKm + highwayKm) * 100).toInt() else 0
    val cityPercent get() = 100 - highwayPercent
}

object FuelModel {
    const val HIGHWAY_SPEED_MPS = 70 / 3.6  // 70km/h 이상 구간 = 고속(고속도로·자동차전용도로)

    /**
     * @param actualKmPerL 차량에서 받은 실제 누적 연비 (있으면 보정). 지금은 화면에 보정을 표시하지 않음.
     */
    fun estimate(route: RouteOption, spec: VehicleSpec = Vehicles.current(), actualKmPerL: Double? = null): FuelEstimate {
        val hwyKm = route.highwayM / 1000
        val cityKm = max(0.0, route.distanceM / 1000 - hwyKm)
        var liters = cityKm / spec.cityKmPerL + hwyKm / spec.highwayKmPerL
        val corrected = actualKmPerL != null && actualKmPerL in 3.0..40.0
        if (corrected) liters *= spec.combinedKmPerL / actualKmPerL!!   // 실제가 공인보다 나쁘면 연료 증가
        return FuelEstimate(liters, cityKm, hwyKm, corrected)
    }
}
