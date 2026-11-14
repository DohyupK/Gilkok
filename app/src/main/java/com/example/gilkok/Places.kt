package com.example.gilkok

import kotlin.math.*

data class GeoPoint(val latitude: Double, val longitude: Double) {
    val valid: Boolean get() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0 && (latitude != 0.0 || longitude != 0.0)
    fun distanceTo(other: GeoPoint): Double {
        val a = Math.toRadians(latitude); val b = Math.toRadians(other.latitude)
        val h = sin((b-a)/2).pow(2) + cos(a)*cos(b)*sin(Math.toRadians(other.longitude-longitude)/2).pow(2)
        return 6371000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}

data class Place(val name: String, val point: GeoPoint, val address: String = "")

interface LocationProvider { fun latestLocation(): GeoPoint? }

class NaviLocationProvider(private val state: () -> NaviState) : LocationProvider {
    override fun latestLocation(): GeoPoint? {
        val current=state()
        if(System.currentTimeMillis()-current.locationAtMs !in 0..15_000) return null
        return current.location?.let { GeoPoint(it.latitude,it.longitude) }?.takeIf { it.valid }
    }
}

enum class CrossingDirection(val label: String) { NORTH("강북 방향"), SOUTH("강남 방향") }

/** Direction points are prototypes, never silently presented as verified road routing points. */
fun BridgeOption.waypoint(direction: CrossingDirection): GeoPoint = when (this) {
    BridgeOption.YEONGDONG -> if (direction == CrossingDirection.NORTH) GeoPoint(37.5336,127.0590) else GeoPoint(37.5249,127.0568)
    else -> if (direction == CrossingDirection.NORTH) GeoPoint(37.5290,127.0643) else GeoPoint(37.5205,127.0635)
}

object DemoPlaces {
    val all = listOf(
        Place("서울숲", GeoPoint(37.5445,127.0374), "서울 성동구 · 시범 장소"),
        Place("코엑스", GeoPoint(37.5117,127.0592), "서울 강남구 · 시범 장소"),
        Place("잠실종합운동장", GeoPoint(37.5153,127.0727), "서울 송파구 · 시범 장소"),
        Place("올림픽공원", GeoPoint(37.5219,127.1240), "서울 송파구 · 시범 장소"),
    )
}

object WaypointCatalog {
    fun get(context:android.content.Context,bridge:BridgeOption,direction:CrossingDirection):GeoPoint {
        val p=context.getSharedPreferences("waypoints",0); val key="${bridge.name}:${direction.name}"
        val lat=p.getString("$key:lat",null)?.toDoubleOrNull()
        val lon=p.getString("$key:lon",null)?.toDoubleOrNull()
        return if(lat!=null && lon!=null) GeoPoint(lat,lon).takeIf { it.valid } ?: bridge.waypoint(direction) else bridge.waypoint(direction)
    }
    fun set(context:android.content.Context,bridge:BridgeOption,direction:CrossingDirection,point:GeoPoint) {
        require(point.valid)
        val key="${bridge.name}:${direction.name}"
        context.getSharedPreferences("waypoints",0).edit().putString("$key:lat",point.latitude.toString()).putString("$key:lon",point.longitude.toString()).apply()
    }
}
