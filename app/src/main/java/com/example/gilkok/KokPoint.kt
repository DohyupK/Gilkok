package com.example.gilkok

/**
 * 길콕의 기본 단위: 사용자가 지도에서 "이 길로 가고 싶다"고 콕 찍은 지점.
 * 특정 도로 목록에 묶이지 않으며, 차량 내비 경유지 한도(최대 2개)에 맞춰 최대 2곳까지 찍을 수 있습니다.
 */
/** place = true 이면 "주변 명소"에서 고른 경유지 (지도에서 보라색), false 이면 사용자가 콕 찍은 길 (노란색). */
data class KokPoint(val name: String, val point: GeoPoint, val place: Boolean = false) {
    val key get() = "${"%.5f".format(java.util.Locale.US, point.latitude)},${"%.5f".format(java.util.Locale.US, point.longitude)}"
}

object KokPoints {
    const val MAX = 2

    fun encode(points: List<KokPoint>) = points.joinToString(";") {
        "${it.point.latitude},${it.point.longitude},${if (it.place) "P" else "K"},${it.name.replace(";", " ").replace(",", " ")}"
    }

    fun decode(value: String?): List<KokPoint> = value.orEmpty().split(";").mapNotNull { item ->
        val parts = item.split(",", limit = 4)
        if (parts.size < 3) return@mapNotNull null
        val p = GeoPoint(parts[0].toDoubleOrNull() ?: return@mapNotNull null, parts[1].toDoubleOrNull() ?: return@mapNotNull null)
        if (!p.valid) return@mapNotNull null
        if (parts.size == 4 && (parts[2] == "P" || parts[2] == "K")) KokPoint(parts[3], p, parts[2] == "P")
        else KokPoint(parts.drop(2).joinToString(","), p) // 이전 저장 형식
    }.take(MAX)

    /** 출발지에서 가까운 순서로 정렬해 자연스러운 통과 순서를 만듭니다. */
    fun ordered(origin: GeoPoint?, points: List<KokPoint>) =
        if (origin == null) points else points.sortedBy { it.point.distanceTo(origin) }

    /** 출발 → 경유지들 → 도착 총거리가 가장 짧은 방문 순서 (경유지 최대 2곳이라 모든 순서를 비교). */
    fun bestOrder(origin: GeoPoint, destination: GeoPoint, points: List<KokPoint>): List<KokPoint> {
        if (points.size < 2) return points
        fun cost(order: List<KokPoint>) = (listOf(origin) + order.map { it.point } + destination).zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
        return listOf(points, points.reversed()).minBy(::cost)
    }
}
