package com.example.gilkok

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.cos

/**
 * 서울시 실시간 도로소통정보(TOPIS, 열린데이터광장 TrafficInfo)로 경로 시간을 보정합니다.
 *  1) assets/seoul_links.bin : 서울 도로 링크 모양 (표준노드링크에서 가공, 줄마다 "링크ID,lat lon;lat lon;...")
 *  2) 경로의 각 구간을 가장 가까운 같은 방향 링크에 맞춤 (25m 이내, 방향 차 45° 이내)
 *  3) 맞춰진 링크의 현재 속도를 받아 시간 = 길이 ÷ 속도 로 다시 계산
 * 서울 밖이거나 키·데이터가 없으면 원래 경로를 그대로 돌려줍니다.
 */
object SeoulTraffic {
    private const val MATCH_M = 25.0
    private const val MAX_ANGLE = 45
    private const val CACHE_MS = 5 * 60_000L
    const val JAM_KMH = 15.0          // 이 속도 미만이면 정체
    private const val MAX_LINKS = 200 // 경로 하나당 조회할 링크 수 상한
    private const val CELL = 0.002    // 격자 약 200m

    private class Link(val id: String, val pts: DoubleArray) // lat,lon,lat,lon...
    private var links: List<Link>? = null
    private val grid = HashMap<Long, MutableList<Int>>()
    private val speeds = ConcurrentHashMap<String, Pair<Double, Long>>()
    private val pool = Executors.newFixedThreadPool(6)
    private val main = Handler(Looper.getMainLooper())

    // 링크 데이터: TOPIS LinkVerInfo (tools/교통링크_받기.bat → make_topis_links.py)
    const val ENABLED = true
    fun available() = ENABLED && BuildConfig.SEOUL_TRAFFIC_KEY.isNotBlank()

    @Synchronized private fun load(context: Context): List<Link> {
        links?.let { return it }
        val out = ArrayList<Link>()
        runCatching {
            GZIPInputStream(context.assets.open("seoul_links.bin")).bufferedReader().forEachLine { line ->
                val c = line.indexOf(','); if (c < 0) return@forEachLine
                val nums = line.substring(c + 1).split(';').flatMap { it.trim().split(' ').map(String::toDouble) }
                if (nums.size >= 4) out += Link(line.substring(0, c), nums.toDoubleArray())
            }
        }.onFailure { android.util.Log.w("GilkokTraffic", "link data missing", it) }
        out.forEachIndexed { i, l ->
            val seen = HashSet<Long>()
            var k = 0
            while (k < l.pts.size) { val key = cell(l.pts[k], l.pts[k + 1]); if (seen.add(key)) grid.getOrPut(key) { mutableListOf() } += i; k += 2 }
        }
        links = out
        return out
    }
    private fun cell(lat: Double, lon: Double) = (Math.floor(lat / CELL).toLong() shl 32) xor (Math.floor(lon / CELL).toLong() and 0xffffffffL)

    /** 점 p에서 링크까지 거리(m)와 그 부분의 방향 */
    private fun nearest(l: Link, lat: Double, lon: Double): Pair<Double, Int> {
        val k = cos(Math.toRadians(lat)); var best = Double.MAX_VALUE; var dir = 0
        var i = 2
        while (i < l.pts.size) {
            val ax = (l.pts[i - 1] - lon) * k; val ay = l.pts[i - 2] - lat
            val bx = (l.pts[i + 1] - lon) * k; val by = l.pts[i] - lat
            val dx = bx - ax; val dy = by - ay; val len = dx * dx + dy * dy
            val t = if (len == 0.0) 0.0 else (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
            val x = ax + t * dx; val y = ay + t * dy
            val d = Math.sqrt(x * x + y * y) * 111_320.0
            if (d < best) { best = d; dir = Geo.bearing(GeoPoint(l.pts[i - 2], l.pts[i - 1]), GeoPoint(l.pts[i], l.pts[i + 1])) }
            i += 2
        }
        return best to dir
    }
    private fun angle(a: Int, b: Int) = abs(((a - b) % 360 + 540) % 360 - 180)

    private fun match(all: List<Link>, a: GeoPoint, b: GeoPoint): String? {
        val lat = (a.latitude + b.latitude) / 2; val lon = (a.longitude + b.longitude) / 2
        val dir = Geo.bearing(a, b); var best: String? = null; var bestD = MATCH_M
        for (dy in -1..1) for (dx in -1..1) {
            val ids = grid[cell(lat + dy * CELL, lon + dx * CELL)] ?: continue
            for (i in ids) { val (d, ld) = nearest(all[i], lat, lon); if (d < bestD && angle(dir, ld) <= MAX_ANGLE) { bestD = d; best = all[i].id } }
        }
        return best
    }

    private fun speed(context: Context, id: String): Double? {
        speeds[id]?.let { if (System.currentTimeMillis() - it.second < CACHE_MS) return it.first }
        val url = "http://openapi.seoul.go.kr:8088/${BuildConfig.SEOUL_TRAFFIC_KEY}/xml/TrafficInfo/1/5/$id"
        return runCatching {
            ExternalServices.client(context).newCall(Request.Builder().url(url).build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                Regex("<prcs_spd>([0-9.]+)</prcs_spd>").find(body)?.groupValues?.get(1)?.toDouble()
            }
        }.getOrNull()?.takeIf { it > 0 }?.also { speeds[id] = it to System.currentTimeMillis() }
    }

    /** 경로들을 교통 반영 값으로 바꿔 메인 스레드로 돌려줍니다. 실패하면 원래 경로 그대로. */
    fun adjust(context: Context, routes: List<RouteOption>, callback: (List<RouteOption>) -> Unit) {
        if (!available() || routes.none { it.source == RouteSource.OSRM }) { android.util.Log.i("GilkokTraffic", "skip: available=${available()} sources=${routes.map { it.source }}"); callback(routes); return }
        val app = context.applicationContext
        pool.execute {
            val result = runCatching {
                val all = load(app)
                if (all.isEmpty()) return@runCatching routes
                // 1) 구간 → 링크
                val matched = routes.map { r -> (1 until r.path.size).map { i -> match(all, r.path[i - 1], r.path[i]) } }
                val ids = matched.flatten().filterNotNull().distinct().take(MAX_LINKS).toSet()
                if (ids.isEmpty()) { android.util.Log.i("GilkokTraffic", "no match: links=${all.size}"); return@runCatching routes }
                // 2) 속도 병렬 조회
                val futures = ids.associateWith { id -> pool.submit<Double?> { speed(app, id) } }
                val v = futures.mapValues { runCatching { it.value.get() }.getOrNull() }
                android.util.Log.i("GilkokTraffic", "links=${all.size} matched=${ids.size} speeds=${v.values.count { it != null }} sample=${v.entries.take(5)}")
                // 3) 다시 계산
                routes.mapIndexed { ri, r -> recompute(r, matched[ri], v) }
            }.getOrElse { android.util.Log.w("GilkokTraffic", "adjust failed", it); routes }
            main.post { callback(result) }
        }
    }

    private fun recompute(r: RouteOption, links: List<String?>, v: Map<String, Double?>): RouteOption {
        if (r.source != RouteSource.OSRM || r.path.size < 2) return r
        val segLen = (1 until r.path.size).map { r.path[it - 1].distanceTo(r.path[it]) }
        val total = segLen.sum().takeIf { it > 0 } ?: return r
        val baseSecPerM = r.durationS / total
        val hwShare = (r.highwayM / r.distanceM).coerceIn(0.0, 1.0)
        var sec = 0.0; var liveM = 0.0; var jamM = 0.0; var hwM = 0.0
        segLen.forEachIndexed { i, len ->
            val kmh = links[i]?.let { v[it] }
            if (kmh != null) {
                sec += len / (kmh / 3.6); liveM += len
                if (kmh < JAM_KMH) jamM += len
                if (kmh * 1.0 >= 70) hwM += len
            } else { sec += len * baseSecPerM; hwM += len * hwShare }
        }
        if (liveM <= 0.0) return r // 실시간 속도가 하나도 없으면 보정하지 않음
        val scale = r.distanceM / total
        return r.copy(
            durationS = sec * scale, congestedRatio = jamM / total, highwayM = hwM * scale,
            note = "서울시 실시간 교통 반영 (${(liveM / total * 100).toInt()}% 구간)", traffic = true,
        )
    }
}
