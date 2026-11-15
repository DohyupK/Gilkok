package com.example.gilkok

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

enum class RouteSource(val label: String, val hasTraffic: Boolean) {
    DEMO("데모 데이터", true), OSRM("실제 도로 · 교통정보 없음", false), KAKAO("실시간 교통 반영", true)
}

data class RouteOption(
    val title: String,
    val koks: List<KokPoint>,       // 사용자가 콕 찍은 지점 (기본 경로는 비어 있음)
    val distanceM: Double,
    val durationS: Double,
    val path: List<GeoPoint>,
    val congestedRatio: Double,     // 정체·지체 구간 비율 0~1
    val source: RouteSource,
    val passed: Int,                // 경로가 실제로 지나는 콕 지점 수
    val note: String = "",
) {
    val passesAll get() = passed >= koks.size
}

interface RouteProvider {
    val source: RouteSource
    fun route(origin: GeoPoint, destination: GeoPoint, koks: List<KokPoint>, callback: (Result<RouteOption>) -> Unit)
    fun close() {}
}

object Geo {
    fun distanceToPath(p: GeoPoint, path: List<GeoPoint>): Double {
        if (path.isEmpty()) return Double.MAX_VALUE
        if (path.size == 1) return p.distanceTo(path[0])
        val k = cos(Math.toRadians(p.latitude))
        var best = Double.MAX_VALUE
        for (i in 1 until path.size) {
            val ax = (path[i - 1].longitude - p.longitude) * k; val ay = path[i - 1].latitude - p.latitude
            val bx = (path[i].longitude - p.longitude) * k; val by = path[i].latitude - p.latitude
            val dx = bx - ax; val dy = by - ay; val len = dx * dx + dy * dy
            val t = if (len == 0.0) 0.0 else (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
            val x = ax + t * dx; val y = ay + t * dy
            best = min(best, sqrt(x * x + y * y) * 111_320.0)
        }
        return best
    }
    /** a → b 방향 (북쪽 0°, 시계 방향) */
    fun bearing(a: GeoPoint, b: GeoPoint): Int {
        val y = kotlin.math.sin(Math.toRadians(b.longitude - a.longitude)) * cos(Math.toRadians(b.latitude))
        val x = cos(Math.toRadians(a.latitude)) * kotlin.math.sin(Math.toRadians(b.latitude)) -
            kotlin.math.sin(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * cos(Math.toRadians(b.longitude - a.longitude))
        return ((Math.toDegrees(kotlin.math.atan2(y, x)) + 360) % 360).toInt()
    }
    fun length(path: List<GeoPoint>) = path.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
    /** 지도 그리기·검증 부담을 줄이려고 최대 600점으로 줄입니다 (첫·끝점 유지). */
    fun thin(path: List<GeoPoint>, max: Int = 600): List<GeoPoint> {
        if (path.size <= max) return path
        val step = path.size.toDouble() / (max - 1)
        return (0 until max - 1).map { path[(it * step).toInt()] } + path.last()
    }
    fun densify(points: List<GeoPoint>, stepM: Double = 150.0): List<GeoPoint> {
        if (points.size < 2) return points
        val out = mutableListOf(points.first())
        points.zipWithNext().forEach { (a, b) ->
            val n = (a.distanceTo(b) / stepM).toInt().coerceAtLeast(1)
            for (i in 1..n) {
                val t = i.toDouble() / n
                out += GeoPoint(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
            }
        }
        return out
    }
}

/** 경로가 콕 지점을 순서대로 지나는지 검증 (지점에서 경로까지 60m 이내). */
object KokValidator {
    const val TOLERANCE_M = 60.0
    fun passed(koks: List<KokPoint>, path: List<GeoPoint>): Int {
        var from = 0; var count = 0
        for (kok in koks) {
            if (path.size < 2) break
            val index = (from until path.size).minByOrNull { path[it].distanceTo(kok.point) } ?: break
            if (Geo.distanceToPath(kok.point, path.subList(maxOf(0, index - 1), minOf(path.size, index + 2))) <= TOLERANCE_M) { count++; from = index }
        }
        return count
    }
}

object RouteSettings {
    enum class Mode(val label: String) { OSRM("실제 도로 (무료)"), DEMO("데모 데이터"), KAKAO("카카오 실시간 (키 필요)") }
    private fun prefs(c: Context) = c.getSharedPreferences("routes", Context.MODE_PRIVATE)
    fun kakaoKey(c: Context) = prefs(c).getString("kakao_key", "").orEmpty()
    fun mode(c: Context): Mode {
        val m = Mode.entries.getOrElse(prefs(c).getInt("mode", 0)) { Mode.OSRM }
        return if (m == Mode.KAKAO && kakaoKey(c).isBlank()) Mode.OSRM else m
    }
    fun save(c: Context, mode: Mode, key: String) = prefs(c).edit().putInt("mode", mode.ordinal).putString("kakao_key", key.trim()).apply()
    fun provider(c: Context): RouteProvider = when (mode(c)) {
        Mode.DEMO -> DemoRouteProvider()
        Mode.KAKAO -> KakaoRouteProvider(c, kakaoKey(c))
        Mode.OSRM -> OsrmRouteProvider(c)
    }
    /** 데모 시나리오: 성수에서 코엑스로. 내비는 영동대교를 추천하지만 운전자는 한산한 청담대교 쪽을 알고 있다. */
    val DEMO_ORIGIN = Place("성수역 (데모 출발지)", GeoPoint(37.5446, 127.0557))
    val DEMO_DESTINATION = Place("코엑스", GeoPoint(37.5117, 127.0592), "서울 강남구 · 데모 목적지")
}

/** 시연용 결정적 데이터. 화면에 항상 "데모 데이터"로 표시됩니다. 인터넷 없이 동작합니다. */
class DemoRouteProvider : RouteProvider {
    override val source = RouteSource.DEMO
    private val main = Handler(Looper.getMainLooper())

    override fun route(origin: GeoPoint, destination: GeoPoint, koks: List<KokPoint>, callback: (Result<RouteOption>) -> Unit) {
        // 기본 추천 경로는 직선 근처(번잡한 간선도로 가정), 콕 경로는 찍은 지점을 순서대로 통과
        val baseBend = GeoPoint((origin.latitude + destination.latitude) / 2, (origin.longitude + destination.longitude) / 2 + .003)
        val anchors = if (koks.isEmpty()) listOf(origin, baseBend, destination) else listOf(origin) + koks.map { it.point } + destination
        val path = Geo.densify(anchors, 80.0)
        val distance = Geo.length(path) * 1.25
        val ratio = if (koks.isEmpty()) .55 else .20
        val kmh = 44 * (1 - ratio) + 13 * ratio
        main.postDelayed({
            callback(Result.success(RouteOption(
                title = if (koks.isEmpty()) "기본 추천 경로" else "길콕 경로",
                koks = koks, distanceM = distance, durationS = distance / (kmh / 3.6), path = path,
                congestedRatio = ratio, source = source, passed = KokValidator.passed(koks, path),
            )))
        }, 400)
    }
}

/** OSRM 공개 서버: 무료·키 없음·실제 도로망. 교통정보는 없으며 시험·시연 규모에서만 사용합니다. */
class OsrmRouteProvider(context: Context) : RouteProvider {
    override val source = RouteSource.OSRM
    private val client = ExternalServices.client(context)
    private val main = Handler(Looper.getMainLooper())
    private val calls = mutableListOf<Call>()

    override fun route(origin: GeoPoint, destination: GeoPoint, koks: List<KokPoint>, callback: (Result<RouteOption>) -> Unit) {
        val all = listOf(origin) + koks.map { it.point } + destination
        val coords = all.joinToString(";") { "${it.longitude},${it.latitude}" }
        // 콕 지점은 진행 방향(앞 지점 → 뒤 지점)과 맞는 차로에, 80m 안의 도로에만 붙입니다.
        // 이렇게 해야 다리 위를 찍었을 때 반대 차로나 다리 밑 도로로 붙어 되돌아가는 경로가 생기지 않습니다.
        val bearings = all.indices.joinToString(";") { i ->
            val kok = koks.getOrNull(i - 1)
            if (i == 0 || i == all.lastIndex || kok == null || kok.place) "" else "${Geo.bearing(all[i - 1], all[i + 1])},90"
        }
        val radiuses = all.indices.joinToString(";") { i ->
            val kok = koks.getOrNull(i - 1)
            if (i == 0 || i == all.lastIndex || kok == null) "unlimited" else if (kok.place) "400" else "80"
        }
        val url = "https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson&continue_straight=true&bearings=$bearings&radiuses=$radiuses"
        val call = client.newCall(Request.Builder().url(url).build())
        synchronized(calls) { calls += call }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.w("GilkokRoute", "route request failed", e)
                if (!call.isCanceled()) main.post { callback(Result.failure(IOException("연결 실패: ${e.javaClass.simpleName} ${e.message.orEmpty()}".take(120)))) }
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) error("경로 서버 응답 오류 (${it.code})")
                        val json = JSONObject(it.body?.string().orEmpty())
                        if (json.optString("code") != "Ok") error("경로 없음: ${json.optString("code")} ${json.optString("message")}".take(120))
                        val route = json.getJSONArray("routes").getJSONObject(0)
                        val coordinates = route.getJSONObject("geometry").getJSONArray("coordinates")
                        val path = Geo.thin((0 until coordinates.length()).map { i -> coordinates.getJSONArray(i).let { c -> GeoPoint(c.getDouble(1), c.getDouble(0)) } })
                        RouteOption(
                            title = if (koks.isEmpty()) "기본 추천 경로" else "길콕 경로",
                            koks = koks, distanceM = route.getDouble("distance"), durationS = route.getDouble("duration"),
                            path = path, congestedRatio = 0.0, source = source, passed = KokValidator.passed(koks, path),
                            note = "교통정보 미반영 시간",
                        )
                    }
                }
                result.exceptionOrNull()?.let { android.util.Log.w("GilkokRoute", "route parse failed", it) }
                main.post { callback(result) }
            }
        })
    }
    override fun close() { synchronized(calls) { calls.forEach { it.cancel() }; calls.clear() } }
}

/** 카카오모빌리티 길찾기 API. 키는 기기 설정에만 저장하며 로그에 남기지 않습니다. */
class KakaoRouteProvider(context: Context, private val key: String) : RouteProvider {
    override val source = RouteSource.KAKAO
    private val client = ExternalServices.client(context)
    private val main = Handler(Looper.getMainLooper())
    private val calls = mutableListOf<Call>()

    override fun route(origin: GeoPoint, destination: GeoPoint, koks: List<KokPoint>, callback: (Result<RouteOption>) -> Unit) {
        val builder = "https://apis-navi.kakaomobility.com/v1/directions".toHttpUrl().newBuilder()
            .addQueryParameter("origin", "${origin.longitude},${origin.latitude}")
            .addQueryParameter("destination", "${destination.longitude},${destination.latitude}")
            .addQueryParameter("priority", "RECOMMEND")
        if (koks.isNotEmpty()) builder.addQueryParameter("waypoints", koks.joinToString("|") { "${it.point.longitude},${it.point.latitude}" })
        val call = client.newCall(Request.Builder().url(builder.build()).header("Authorization", "KakaoAK $key").build())
        synchronized(calls) { calls += call }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.w("GilkokRoute", "route request failed", e)
                if (!call.isCanceled()) main.post { callback(Result.failure(IOException("연결 실패: ${e.javaClass.simpleName} ${e.message.orEmpty()}".take(120)))) }
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) error("경로 서버 응답 오류 (${it.code})")
                        parse(JSONObject(it.body?.string().orEmpty()), koks)
                    }
                }
                result.exceptionOrNull()?.let { android.util.Log.w("GilkokRoute", "route parse failed", it) }
                main.post { callback(result) }
            }
        })
    }

    private fun parse(json: JSONObject, koks: List<KokPoint>): RouteOption {
        val route = json.getJSONArray("routes").getJSONObject(0)
        if (route.optInt("result_code", -1) != 0) error(route.optString("result_msg", "경로를 찾지 못했습니다"))
        val summary = route.getJSONObject("summary")
        val path = mutableListOf<GeoPoint>()
        var congested = 0.0; var total = 0.0
        val sections = route.getJSONArray("sections")
        for (i in 0 until sections.length()) {
            val roads = sections.getJSONObject(i).getJSONArray("roads")
            for (j in 0 until roads.length()) {
                val road = roads.getJSONObject(j)
                val d = road.optDouble("distance", 0.0); total += d
                if (road.optInt("traffic_state", 0) in 1..2) congested += d // 1 정체, 2 지체
                val v = road.getJSONArray("vertexes")
                var k = 0
                while (k + 1 < v.length()) { path += GeoPoint(v.getDouble(k + 1), v.getDouble(k)); k += 2 }
            }
        }
        return RouteOption(
            title = if (koks.isEmpty()) "기본 추천 경로" else "길콕 경로",
            koks = koks, distanceM = summary.getDouble("distance"), durationS = summary.getDouble("duration"),
            path = path, congestedRatio = if (total > 0) congested / total else 0.0, source = source,
            passed = KokValidator.passed(koks, path),
        )
    }

    override fun close() { synchronized(calls) { calls.forEach { it.cancel() }; calls.clear() } }
}

/** 콕 지점 이름: 지도 이용 동의 시 OSM 역지오코딩으로 도로 이름을 붙이고, 실패하면 "콕 지점 N". */
class KokNamer(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    fun name(point: GeoPoint, fallback: String, callback: (String) -> Unit) {
        if (!ExternalServices.allowed(context)) { callback(fallback); return }
        val url = ExternalServices.searchUrl(context).replace("/search", "/reverse").toHttpUrl().newBuilder()
            .addQueryParameter("lat", point.latitude.toString()).addQueryParameter("lon", point.longitude.toString())
            .addQueryParameter("format", "jsonv2").addQueryParameter("zoom", "17").addQueryParameter("accept-language", "ko").build()
        ExternalServices.client(context).newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { main.post { callback(fallback) } }
            override fun onResponse(call: Call, response: Response) {
                val name = runCatching {
                    response.use { JSONObject(it.body?.string().orEmpty()).optJSONObject("address")?.let { a -> a.optString("road").ifBlank { a.optString("bridge") } } }
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
                main.post { callback(name) }
            }
        })
    }
}
