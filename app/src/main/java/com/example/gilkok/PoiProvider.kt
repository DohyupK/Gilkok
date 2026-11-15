package com.example.gilkok

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.roundToInt

/** 명소 테마. contentTypeId는 한국관광공사 TourAPI 분류입니다. */
enum class PoiTheme(val label: String, val contentTypes: List<Int>, val cat1: String? = null) {
    SIGHTS("볼거리", listOf(12, 14)),
    NATURE("자연·산책", listOf(12), cat1 = "A01"),
    FOOD("먹거리·카페", listOf(39)),
    EVENTS("행사", listOf(15)),
}

data class Poi(
    val id: String,
    val name: String,
    val point: GeoPoint,
    val address: String,
    val hasImage: Boolean,
    val detourMinutes: Int,     // 경로에서 들렀다 돌아오는 추가 시간 (추정)
    val detourKm: Double,
    val routeFraction: Double,  // 경로상 위치 0(출발)~1(도착)
    val demo: Boolean,
    val description: String = "",  // 한두 줄 소개 (TourAPI 개요)
    val offRouteM: Double = 0.0,
    val category: String = "",     // 카페, 박물관, 공원 같은 짧은 분류
    val imageUrl: String = "",     // 대표 이미지 (TourAPI firstimage, https 로 변환)   // 경로에서 떨어진 직선거리
    val nearestOnRoute: GeoPoint = point, // 경로 위에서 가장 가까운 지점 (지도 점선용)
)

interface PoiProvider {
    val demo: Boolean
    fun search(route: RouteOption, theme: PoiTheme, callback: (Result<List<Poi>>) -> Unit)
    fun close() {}
}

/** 경로 주변 명소 점수 매기기: 추가 시간 → 경로 중간~도착 근처 → 사진 있는 곳. */
/** TourAPI 분류 코드(cat2/cat3) → 짧은 카테고리 이름. 모르는 코드는 상위 분류로 대체. */
object PoiCategory {
    private val cat3 = mapOf(
        "A05020900" to "카페", "A05020100" to "한식", "A05020200" to "양식", "A05020300" to "일식", "A05020400" to "중식",
        "A02060100" to "박물관", "A02060200" to "기념관", "A02060300" to "전시관", "A02060500" to "미술관", "A02060600" to "공연장",
        "A02020700" to "공원", "A02010100" to "궁", "A02010800" to "사찰", "A02050200" to "전망대",
    )
    private val cat2 = mapOf(
        "A0101" to "자연", "A0102" to "자연", "A0201" to "역사", "A0202" to "휴양", "A0203" to "체험", "A0204" to "산업",
        "A0205" to "건축물", "A0206" to "문화시설", "A0207" to "축제", "A0208" to "공연·행사", "A0502" to "음식점",
    )
    fun name(c2: String, c3: String): String = cat3[c3] ?: cat2[c2] ?: ""
}

object PoiRanker {
    const val MAX_DETOUR_MIN = 15
    const val LIMIT = 5
    private const val DETOUR_KMH = 25.0
    private const val ROAD_FACTOR = 1.4

    fun place(route: RouteOption, id: String, name: String, point: GeoPoint, address: String, hasImage: Boolean, demo: Boolean): Poi? {
        val path = route.path
        if (path.size < 2 || !point.valid) return null
        val index = path.indices.minBy { path[it].distanceTo(point) }
        val offRoute = Geo.distanceToPath(point, path)
        val detourKm = offRoute * 2 * ROAD_FACTOR / 1000
        val minutes = (detourKm / DETOUR_KMH * 60 + 3).roundToInt() // +3분: 진입·주차 여유
        return Poi(id, name, point, address, hasImage, minutes, detourKm, index.toDouble() / (path.size - 1), demo,
            offRouteM = offRoute, nearestOnRoute = path[index])
    }

    fun rank(items: List<Poi>): List<Poi> = items
        .filter { it.detourMinutes <= MAX_DETOUR_MIN && it.routeFraction in .15..1.0 }
        .distinctBy { it.id }
        .sortedWith(compareBy<Poi> { it.detourMinutes }
            .thenBy { if (it.routeFraction >= .4) 0 else 1 }
            .thenBy { if (it.hasImage) 0 else 1 })
        .take(LIMIT)

    /** 경로를 따라 25%, 50%, 75% 지점에서 검색 */
    fun searchPoints(route: RouteOption): List<GeoPoint> {
        val p = route.path
        if (p.size < 2) return emptyList()
        return listOf(.25, .5, .75).map { p[((p.size - 1) * it).toInt()] }
    }
}

/** 한국관광공사 TourAPI (KorService2). 키는 local.properties → BuildConfig 로만 받습니다. */
class TourPoiProvider(context: Context, private val key: String) : PoiProvider {
    override val demo = false
    private val client = ExternalServices.client(context)
    private val main = Handler(Looper.getMainLooper())
    private val calls = mutableListOf<Call>()

    override fun search(route: RouteOption, theme: PoiTheme, callback: (Result<List<Poi>>) -> Unit) {
        val requests = PoiRanker.searchPoints(route).flatMap { p -> theme.contentTypes.map { p to it } }
        if (requests.isEmpty()) { callback(Result.success(emptyList())); return }
        val found = mutableListOf<Poi>()
        var remaining = requests.size
        var lastError: Throwable? = null
        requests.forEach { (point, type) ->
            val url = "https://apis.data.go.kr/B551011/KorService2/locationBasedList2".toHttpUrl().newBuilder()
                .addQueryParameter("serviceKey", key)
                .addQueryParameter("MobileOS", "AND").addQueryParameter("MobileApp", "Gilkok")
                .addQueryParameter("_type", "json").addQueryParameter("numOfRows", "15").addQueryParameter("pageNo", "1")
                .addQueryParameter("arrange", "E")
                .addQueryParameter("mapX", point.longitude.toString()).addQueryParameter("mapY", point.latitude.toString())
                .addQueryParameter("radius", "1500").addQueryParameter("contentTypeId", type.toString())
                .build()
            val call = client.newCall(Request.Builder().url(url).build())
            synchronized(calls) { calls += call }
            call.enqueue(object : Callback {
                fun done(items: List<Poi>, error: Throwable?) = main.post {
                    found += items; if (error != null) lastError = error
                    if (--remaining == 0) {
                        if (found.isEmpty() && lastError != null) callback(Result.failure(lastError!!))
                        else describe(PoiRanker.rank(found), callback)
                    }
                }
                override fun onFailure(call: Call, e: IOException) {
                    android.util.Log.w("GilkokPoi", "tour api failed", e)
                    done(emptyList(), IOException("관광정보 연결 실패: ${e.javaClass.simpleName}"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val body = it.body?.string().orEmpty()
                            if (!it.isSuccessful || !body.trimStart().startsWith("{")) error("관광정보 응답 오류 (${it.code}) ${body.take(80)}")
                            parse(JSONObject(body), route, theme)
                        }
                    }
                    result.exceptionOrNull()?.let { android.util.Log.w("GilkokPoi", "tour api parse", it) }
                    done(result.getOrDefault(emptyList()), result.exceptionOrNull())
                }
            })
        }
    }

    private fun parse(json: JSONObject, route: RouteOption, theme: PoiTheme): List<Poi> {
        val body = json.optJSONObject("response")?.optJSONObject("body") ?: error("관광정보 형식 오류")
        val items = body.optJSONObject("items") ?: return emptyList() // 결과 없으면 "" 로 옴
        val list = when (val item = items.opt("item")) {
            is JSONArray -> (0 until item.length()).map { item.getJSONObject(it) }
            is JSONObject -> listOf(item)
            else -> emptyList()
        }
        return list.mapNotNull { o ->
            if (theme.cat1 != null && o.optString("cat1") != theme.cat1) return@mapNotNull null
            val p = GeoPoint(o.optString("mapy").toDoubleOrNull() ?: return@mapNotNull null, o.optString("mapx").toDoubleOrNull() ?: return@mapNotNull null)
            PoiRanker.place(route, o.optString("contentid"), o.optString("title"), p, o.optString("addr1"), o.optString("firstimage").isNotBlank(), false)
                ?.copy(category = PoiCategory.name(o.optString("cat2"), o.optString("cat3")),
                    imageUrl = o.optString("firstimage").ifBlank { o.optString("firstimage2") }.replace("http://", "https://"))
        }
    }

    /** 상위 명소만 개요를 받아 붙입니다 (최대 5건 요청). 실패해도 목록은 그대로 보여줍니다. */
    private fun describe(items: List<Poi>, callback: (Result<List<Poi>>) -> Unit) {
        if (items.isEmpty()) { callback(Result.success(items)); return }
        val result = items.toMutableList()
        var remaining = items.size
        items.forEachIndexed { index, poi ->
            val url = "https://apis.data.go.kr/B551011/KorService2/detailCommon2".toHttpUrl().newBuilder()
                .addQueryParameter("serviceKey", key).addQueryParameter("MobileOS", "AND").addQueryParameter("MobileApp", "Gilkok")
                .addQueryParameter("_type", "json").addQueryParameter("contentId", poi.id).build()
            val call = client.newCall(Request.Builder().url(url).build())
            synchronized(calls) { calls += call }
            call.enqueue(object : Callback {
                fun done(text: String?) = main.post {
                    if (!text.isNullOrBlank()) result[index] = poi.copy(description = text)
                    if (--remaining == 0) callback(Result.success(result))
                }
                override fun onFailure(call: Call, e: IOException) = done(null).let {}
                override fun onResponse(call: Call, response: Response) {
                    val text = runCatching {
                        response.use {
                            val item = JSONObject(it.body?.string().orEmpty()).getJSONObject("response").getJSONObject("body")
                                .getJSONObject("items").get("item").let { v -> if (v is JSONArray) v.getJSONObject(0) else v as JSONObject }
                            item.optString("overview").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                        }
                    }.getOrNull()
                    done(text?.let { if (it.length > 90) it.take(88) + "…" else it })
                }
            })
        }
    }

    override fun close() { synchronized(calls) { calls.forEach { it.cancel() }; calls.clear() } }
}

/** 키가 없거나 인터넷이 안 될 때: 데모 시나리오(성수 → 코엑스) 주변 명소. 화면에 "데모"로 표시됩니다. */
class DemoPoiProvider : PoiProvider {
    override val demo = true
    private val main = Handler(Looper.getMainLooper())
    private val samples = mapOf(
        PoiTheme.SIGHTS to listOf("봉은사" to GeoPoint(37.5150, 127.0573), "서울숲 갤러리" to GeoPoint(37.5444, 127.0420), "청담 미술관 거리" to GeoPoint(37.5245, 127.0480)),
        PoiTheme.NATURE to listOf("뚝섬한강공원" to GeoPoint(37.5297, 127.0670), "서울숲" to GeoPoint(37.5444, 127.0374), "청담한강공원" to GeoPoint(37.5260, 127.0600)),
        PoiTheme.FOOD to listOf("성수 카페거리" to GeoPoint(37.5430, 127.0560), "청담 레스토랑 거리" to GeoPoint(37.5240, 127.0500)),
        PoiTheme.EVENTS to listOf("코엑스 전시장" to GeoPoint(37.5125, 127.0590)),
    )
    private val categories = mapOf(
        "봉은사" to "사찰", "서울숲 갤러리" to "전시관", "청담 미술관 거리" to "미술관", "뚝섬한강공원" to "공원", "서울숲" to "공원",
        "청담한강공원" to "공원", "성수 카페거리" to "카페", "청담 레스토랑 거리" to "음식점", "코엑스 전시장" to "전시·박람회",
    )
    private val notes = mapOf(
        "봉은사" to "도심 속 천년 고찰. 코엑스 맞은편이라 도착 직전 잠깐 들르기 좋아요.",
        "서울숲 갤러리" to "서울숲 입구의 작은 전시 공간.",
        "청담 미술관 거리" to "갤러리가 모여 있는 거리. 짧게 둘러보기 좋아요.",
        "뚝섬한강공원" to "한강을 바로 앞에서 볼 수 있는 공원. 주차장이 있어요.",
        "서울숲" to "넓은 도시 숲과 산책로. 출발지 근처예요.",
        "청담한강공원" to "조용한 한강 산책로.",
        "성수 카페거리" to "공장을 고친 카페가 많은 거리.",
        "청담 레스토랑 거리" to "식당과 카페가 모인 거리.",
        "코엑스 전시장" to "전시·박람회가 자주 열리는 곳. 목적지와 같은 건물이에요.",
    )
    override fun search(route: RouteOption, theme: PoiTheme, callback: (Result<List<Poi>>) -> Unit) {
        val items = samples[theme].orEmpty().mapIndexedNotNull { i, (name, p) -> PoiRanker.place(route, "demo-${theme.name}-$i", name, p, "데모 데이터", true, true)?.copy(description = notes[name].orEmpty(), category = categories[name].orEmpty()) }
        main.postDelayed({ callback(Result.success(PoiRanker.rank(items))) }, 300)
    }
}

object PoiSettings {
    fun provider(context: Context): PoiProvider =
        if (BuildConfig.TOUR_API_KEY.isNotBlank() && ExternalServices.allowed(context)) TourPoiProvider(context, BuildConfig.TOUR_API_KEY) else DemoPoiProvider()
}

/** 명소 대표 이미지: 외부 지도 동의 후에만 받고, 메모리에 잠깐 보관. 실패하면 이미지 없이 표시. */
object PoiImages {
    private val cache = android.util.LruCache<String, android.graphics.Bitmap>(12)
    private val main = Handler(Looper.getMainLooper())
    fun load(context: Context, url: String, callback: (android.graphics.Bitmap?) -> Unit) {
        if (url.isBlank() || !ExternalServices.allowed(context)) { callback(null); return }
        cache.get(url)?.let { callback(it); return }
        ExternalServices.client(context).newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { main.post { callback(null) } }
            override fun onResponse(call: Call, response: Response) {
                val bmp = runCatching {
                    response.use { r ->
                        val bytes = r.body?.bytes() ?: return@use null
                        // 차량 화면용으로 줄여서 디코딩 (메모리 절약)
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sample = 1
                        while (bounds.outWidth / (sample * 2) >= 640) sample *= 2
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                }.getOrNull()
                if (bmp != null) cache.put(url, bmp)
                main.post { callback(bmp) }
            }
        })
    }
}
