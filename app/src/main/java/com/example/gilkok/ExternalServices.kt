package com.example.gilkok

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl

object ExternalServices {
    const val USER_AGENT = "GilkokPrototype/0.2 (Android; com.example.gilkok)"
    private var instance: OkHttpClient? = null
    fun preferences(context: Context) = context.getSharedPreferences("privacy", Context.MODE_PRIVATE)
    fun allowed(context: Context) = preferences(context).getBoolean("external_maps", false)
    @Synchronized fun client(context: Context): OkHttpClient {
        val app = context.applicationContext
        return instance ?: OkHttpClient.Builder()
            .cache(Cache(File(app.cacheDir, "external-http"), 32L*1024*1024))
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                if (!allowed(app)) throw IOException("외부 지도 이용에 동의하지 않았습니다")
                val request = chain.request().newBuilder().header("User-Agent", USER_AGENT).build()
                chain.proceed(request)
            }.build().also { instance = it }
    }
    fun tileUrl(context: Context) = preferences(context).getString("tiles", "https://tile.openstreetmap.org/{z}/{x}/{y}.png")!!
    fun searchUrl(context: Context) = preferences(context).getString("search", "https://nominatim.openstreetmap.org/search")!!
}

interface PlaceSearchProvider {
    fun search(query: String, callback: (Result<List<Place>>) -> Unit)
    fun close()
}

/** Explicit user searches only. Single worker, throttled, cached, no location sent. */
class NominatimPlaceSearch(private val context: Context) : PlaceSearchProvider {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    override fun search(query: String, callback: (Result<List<Place>>) -> Unit) {
        val term = query.trim().take(120)
        worker.execute {
            if(closed) return@execute
            val result = runCatching {
                require(term.length >= 2) { "장소 이름을 두 글자 이상 입력하세요" }
                val key=ExternalServices.searchUrl(context)+"|"+term
                cache[key] ?: run {
                    val remaining = 1100L - (android.os.SystemClock.elapsedRealtime()-lastRequest)
                    if (remaining > 0) Thread.sleep(remaining)
                    lastRequest = android.os.SystemClock.elapsedRealtime()
                    val url = ExternalServices.searchUrl(context).toHttpUrl().newBuilder()
                        .addQueryParameter("q", term).addQueryParameter("format", "jsonv2")
                        .addQueryParameter("countrycodes", "kr").addQueryParameter("limit", "6")
                        .addQueryParameter("accept-language", "ko").build()
                    ExternalServices.client(context).newCall(Request.Builder().url(url).build()).execute().use { response ->
                        check(response.isSuccessful) { "장소 검색 응답 ${response.code}. 잠시 후 다시 시도하세요" }
                        val array = JSONArray(response.body?.string() ?: "[]")
                        buildList {
                            for(i in 0 until array.length()) {
                                val item = array.getJSONObject(i)
                                val point = GeoPoint(item.getString("lat").toDouble(), item.getString("lon").toDouble())
                                if(point.valid) add(Place(item.optString("name").ifBlank { item.getString("display_name").substringBefore(',') }, point, item.getString("display_name")))
                            }
                        }.also { cache[key] = it; if(cache.size > 30) cache.remove(cache.keys.first()) }
                    }
                }
            }
            main.post { if(!closed) callback(result) }
        }
    }
    override fun close() { closed = true }
    companion object {
        private val worker=Executors.newSingleThreadExecutor()
        private val cache=linkedMapOf<String,List<Place>>()
        private var lastRequest=0L
    }
}
