package com.example.gilkok

import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource

interface MapProvider {
    val view: View
    val ready: Boolean
    val zoom: Double
    var onPointSelected: ((GeoPoint) -> Unit)?
    var onViewportChanged: (() -> Unit)?
    fun display(location: GeoPoint?, destination: Place?, koks: List<KokPoint>)
    fun showRoutes(routes: List<RouteOption>, selected: Int)
    fun showPois(pois: List<Poi>, highlighted: Int?)
    fun focus(point: GeoPoint,followLocation:Boolean=false)
    fun zoomBy(amount: Double)
    fun setRestricted(restricted: Boolean)
    fun setVisible(visible: Boolean)
    fun saveState(state: Bundle)
    fun destroy()
    fun lowMemory()
}

const val ROUTE_BASE="#6B7A90"
const val START="#1677FF"
const val DEST="#E5484D"
const val ROUTE_GIL="#0E9F94"
const val KOK="#F5BE54"
const val PLACE="#A259FF"
const val POI_SLOTS=5

class MapLibreProvider(private val context: Context, state: Bundle?, private val onStatus: (String) -> Unit) : MapProvider {
    private val mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var visible = false
    private var restricted = true
    private var appliedRestriction:Boolean?=null
    private var location:GeoPoint?=null
    private var destination:Place?=null
    private var koks:List<KokPoint> = emptyList()
    private var routes:List<RouteOption> = emptyList()
    private var selected=0
    private var pois:List<Poi> = emptyList()
    private var highlighted:Int?=null
    private var renderKey=""
    private var centered=state?.isEmpty==false
    private var userMoved=state?.getBoolean("gilkok_follow",true)==false
    private var pendingFocus:GeoPoint?=null
    override var onPointSelected:((GeoPoint)->Unit)?=null
    override var onViewportChanged:(()->Unit)?=null
    // 지도 위에 명소 번호·이름표를 안드로이드 뷰로 직접 올립니다 (지도 글꼴·레이어 문제와 무관하게 항상 보이도록).
    private val container=android.widget.FrameLayout(context)
    private val overlay=android.widget.FrameLayout(context).apply { isClickable=false; isFocusable=false }
    private val poiViews=mutableListOf<View>()
    override val view:View get()=container
    override val ready:Boolean get()=style!=null
    override val zoom:Double get()=map?.cameraPosition?.zoom ?: 12.0

    init {
        MapLibre.getInstance(context)
        HttpRequestUtil.setOkHttpClient(ExternalServices.client(context))
        mapView=MapView(context)
        container.addView(mapView,android.widget.FrameLayout.LayoutParams(-1,-1))
        container.addView(overlay,android.widget.FrameLayout.LayoutParams(-1,-1))
        mapView.onCreate(state)
        mapView.setMaximumFps(15)
        mapView.setOnTouchListener { _,event -> if(event.actionMasked==MotionEvent.ACTION_DOWN) userMoved=true; false }
        mapView.addOnDidFailLoadingMapListener { onStatus("지도 연결에 실패했습니다. 인터넷 연결을 확인해주세요") }
        mapView.getMapAsync { value ->
            map=value
            value.setMinZoomPreference(4.0); value.setMaxZoomPreference(18.0); value.prefetchZoomDelta=0
            if(state?.isEmpty!=false) value.cameraPosition=CameraPosition.Builder().target(LatLng(37.5665,126.9780)).zoom(12.0).build()
            val url=JSONObject.quote(ExternalServices.tileUrl(context))
            val json="""{"version":8,"glyphs":"https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf","sources":{"osm":{"type":"raster","tiles":[$url],"tileSize":256,"maxzoom":19,"attribution":"© OpenStreetMap contributors"}},"layers":[{"id":"background","type":"background","paint":{"background-color":"#DDE3E8"}},{"id":"osm","type":"raster","source":"osm","paint":{"raster-fade-duration":0}}]}"""
            value.setStyle(Style.Builder().fromJson(json)) { loaded ->
                style=loaded
                (listOf("current","destination","kok","place-wp","route-base","route-gil","poi-hl","poi-link")+(1..POI_SLOTS).map { "poi-$it" }).forEach { loaded.addSource(GeoJsonSource(it,empty())) }
                // 두 경로 모두 굵게: 고른 경로가 조금 더 굵고 위에 그려짐. 굵기는 draw()에서 직접 바꿈.
                loaded.addLayer(LineLayer("route-base-casing","route-base").withProperties(lineColor("#FFFFFF"),lineWidth(12f),lineCap("round"),lineJoin("round")))
                loaded.addLayer(LineLayer("route-base-line","route-base").withProperties(lineColor(ROUTE_BASE),lineWidth(8f),lineCap("round"),lineJoin("round")))
                loaded.addLayer(LineLayer("route-gil-casing","route-gil").withProperties(lineColor("#FFFFFF"),lineWidth(12f),lineCap("round"),lineJoin("round")))
                loaded.addLayer(LineLayer("route-gil-line","route-gil").withProperties(lineColor(ROUTE_GIL),lineWidth(8f),lineCap("round"),lineJoin("round")))
                // 고른 명소 ↔ 경로의 가장 가까운 지점: 점선
                loaded.addLayer(LineLayer("poi-link-line","poi-link").withProperties(lineColor(PLACE),lineWidth(4f),lineDasharray(arrayOf(1.5f,1.5f))))
                // 고른 명소: 크게 + 바깥 고리
                loaded.addLayer(CircleLayer("poi-hl-halo","poi-hl").withProperties(circleColor(PLACE),circleRadius(30f),circleOpacity(.25f)))
                loaded.addLayer(CircleLayer("poi-hl-dot","poi-hl").withProperties(circleColor(PLACE),circleRadius(19f),circleStrokeColor("#FFFFFF"),circleStrokeWidth(4f)))
                // 명소 후보: 번호마다 따로 그린 보라색 원 이미지 + 번호 소스 (글꼴·데이터 기반 표현식 없이 항상 보이게)
                loaded.addLayer(CircleLayer("kok-point","kok").withProperties(circleColor(KOK),circleRadius(12f),circleStrokeColor("#FFFFFF"),circleStrokeWidth(3f)))
                loaded.addLayer(CircleLayer("place-wp-point","place-wp").withProperties(circleColor(PLACE),circleRadius(12f),circleStrokeColor("#FFFFFF"),circleStrokeWidth(3f)))
                loaded.addLayer(CircleLayer("destination-point","destination").withProperties(circleColor(DEST),circleRadius(13f),circleStrokeColor("#FFFFFF"),circleStrokeWidth(3f)))
                loaded.addLayer(CircleLayer("current-halo","current").withProperties(circleColor("#1677FF"),circleRadius(23f),circleOpacity(.17f)))
                loaded.addLayer(CircleLayer("current-dot","current").withProperties(circleColor("#1677FF"),circleRadius(12f),circleStrokeColor("#FFFFFF"),circleStrokeWidth(4f)))
                renderKey=""; draw()
                pendingFocus?.let { move(it,15.0); pendingFocus=null }
                onStatus("지도가 준비되었습니다"); onViewportChanged?.invoke()
            }
            value.addOnMapClickListener { point -> if(!restricted) onPointSelected?.invoke(GeoPoint(point.latitude,point.longitude)); !restricted }
            value.addOnCameraMoveListener { updateOverlay() }
            value.addOnCameraIdleListener {
                updateOverlay()
                mapView.contentDescription="지도 · 확대 수준 ${String.format(java.util.Locale.US,"%.1f",zoom)}"
                onViewportChanged?.invoke()
            }
            setRestricted(restricted)
        }
    }
    private fun empty()="""{"type":"FeatureCollection","features":[]}"""
    private fun pointJson(point:GeoPoint?):String = if(point==null) empty() else JSONObject()
        .put("type","Feature").put("properties",JSONObject()).put("geometry",JSONObject().put("type","Point").put("coordinates",JSONArray(listOf(point.longitude,point.latitude)))).toString()
    private fun pointFeature(point:GeoPoint)=JSONObject().put("type","Feature").put("properties",JSONObject())
        .put("geometry",JSONObject().put("type","Point").put("coordinates",JSONArray(listOf(point.longitude,point.latitude))))
    private fun lineFeature(path:List<GeoPoint>,props:JSONObject=JSONObject())=JSONObject().put("type","Feature").put("properties",props)
        .put("geometry",JSONObject().put("type","LineString").put("coordinates",JSONArray(path.map { JSONArray(listOf(it.longitude,it.latitude)) })))
    private fun collection(features:List<JSONObject>)=JSONObject().put("type","FeatureCollection").put("features",JSONArray(features)).toString()
    override fun display(location:GeoPoint?,destination:Place?,koks:List<KokPoint>) {
        this.location=location;this.destination=destination;this.koks=koks
        draw()
    }
    override fun showRoutes(routes:List<RouteOption>,selected:Int) {
        this.routes=routes;this.selected=selected
        draw()
        val points=routes.flatMap { it.path }
        if(points.size>=2 && ready && !restricted) {
            runCatching {
                val bounds=LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.latitude,it.longitude)) } }.build()
                userMoved=true
                map?.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds,120),600)
            }
        }
    }
    private fun dp(v:Int)=(v*context.resources.displayMetrics.density).toInt()
    /** 명소마다 "번호 원 + 이름표" 뷰를 만듭니다. 터치는 지도로 그대로 넘어갑니다. */
    private fun rebuildOverlay() {
        overlay.removeAllViews(); poiViews.clear()
        pois.forEachIndexed { i,p ->
            val on=i==highlighted
            val row=android.widget.LinearLayout(context).apply {
                orientation=android.widget.LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL; isClickable=false
            }
            val size=dp(if(on) 40 else 30)
            row.addView(android.widget.TextView(context).apply {
                text="${i+1}"; setTextColor(android.graphics.Color.WHITE); textSize=if(on) 18f else 15f
                setTypeface(typeface,android.graphics.Typeface.BOLD); gravity=android.view.Gravity.CENTER
                background=android.graphics.drawable.GradientDrawable().apply {
                    shape=android.graphics.drawable.GradientDrawable.OVAL; setColor(android.graphics.Color.parseColor(PLACE)); setStroke(dp(3),android.graphics.Color.WHITE) }
                elevation=dp(4).toFloat()
            },android.widget.LinearLayout.LayoutParams(size,size))
            row.addView(android.widget.TextView(context).apply {
                text=p.name; textSize=if(on) 15f else 13f; maxLines=1; maxWidth=dp(160); ellipsize=android.text.TextUtils.TruncateAt.END
                setTextColor(android.graphics.Color.parseColor("#1B2B3D")); if(on) setTypeface(typeface,android.graphics.Typeface.BOLD)
                background=android.graphics.drawable.GradientDrawable().apply { cornerRadius=dp(8).toFloat(); setColor(android.graphics.Color.parseColor("#F2FFFFFF")); setStroke(dp(if(on) 2 else 1),android.graphics.Color.parseColor(PLACE)) }
                setPadding(dp(8),dp(3),dp(8),dp(3)); elevation=dp(3).toFloat()
            },android.widget.LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(4) })
            row.tag=size
            overlay.addView(row,android.widget.FrameLayout.LayoutParams(-2,-2))
            poiViews+=row
        }
        overlay.post { updateOverlay() }
    }
    /** 지도가 움직일 때마다 번호 원의 중심이 명소 좌표에 오도록 위치를 다시 맞춥니다. */
    private fun updateOverlay() {
        val m=map ?: return
        poiViews.forEachIndexed { i,v ->
            val p=pois.getOrNull(i) ?: return@forEachIndexed
            val pt=m.projection.toScreenLocation(LatLng(p.point.latitude,p.point.longitude))
            val size=(v.tag as? Int) ?: dp(30)
            v.translationX=pt.x-size/2f; v.translationY=pt.y-v.height.coerceAtLeast(size)/2f
            v.visibility=if(visible) View.VISIBLE else View.INVISIBLE
        }
    }
    override fun showPois(pois:List<Poi>,highlighted:Int?) {
        this.pois=pois;this.highlighted=highlighted
        rebuildOverlay()
        draw()
        val focusPoi=highlighted?.let { pois.getOrNull(it) }
        // 고른 명소를 화면 가운데로. 확대 수준은 경로까지의 점선이 함께 보이도록 거리에 맞춤.
        if(focusPoi!=null && ready && !restricted) {
            userMoved=true
            val z=when { focusPoi.offRouteM>1500 -> 13.0; focusPoi.offRouteM>600 -> 14.0; else -> 15.0 }
            map?.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(focusPoi.point.latitude,focusPoi.point.longitude),z),500)
        }
        else if(pois.isNotEmpty() && ready && !restricted) runCatching {
            val all=pois.map { it.point }+routes.getOrNull(selected)?.path.orEmpty()
            val bounds=LatLngBounds.Builder().apply { all.forEach { include(LatLng(it.latitude,it.longitude)) } }.build()
            userMoved=true; map?.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds,120),500)
        }
    }
    /** 보라색 원 안에 흰 번호를 그린 비트맵 */
    private fun numberIcon(n:Int):android.graphics.Bitmap {
        val d=context.resources.displayMetrics.density
        val size=(34*d).toInt()
        val bmp=android.graphics.Bitmap.createBitmap(size,size,android.graphics.Bitmap.Config.ARGB_8888)
        val c=android.graphics.Canvas(bmp)
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color=android.graphics.Color.WHITE; c.drawCircle(size/2f,size/2f,size/2f,paint)
        paint.color=android.graphics.Color.parseColor(PLACE); c.drawCircle(size/2f,size/2f,size/2f-3*d,paint)
        paint.color=android.graphics.Color.WHITE; paint.textSize=17*d; paint.isFakeBoldText=true; paint.textAlign=android.graphics.Paint.Align.CENTER
        c.drawText("$n",size/2f,size/2f-(paint.descent()+paint.ascent())/2,paint)
        return bmp
    }
    private fun applyRouteWidths(style:Style) {
        fun set(id:String,on:Boolean,casing:Boolean) { style.getLayer(id)?.setProperties(lineWidth(if(casing) (if(on) 15f else 12f) else (if(on) 10f else 7f))) }
        set("route-base-casing",selected==0,true); set("route-base-line",selected==0,false)
        set("route-gil-casing",selected==1,true); set("route-gil-line",selected==1,false)
    }
    private fun draw() {
        val style=style ?: return
        val key="$location|$destination|${koks.joinToString { it.key }}|${routes.hashCode()}|$selected|${pois.hashCode()}|$highlighted"
        if(key!=renderKey) {
            renderKey=key
            style.getSourceAs<GeoJsonSource>("current")?.setGeoJson(pointJson(location))
            style.getSourceAs<GeoJsonSource>("destination")?.setGeoJson(pointJson(destination?.point))
            style.getSourceAs<GeoJsonSource>("kok")?.setGeoJson(collection(koks.filter { !it.place }.map { pointFeature(it.point) }))
            style.getSourceAs<GeoJsonSource>("place-wp")?.setGeoJson(collection(koks.filter { it.place }.map { pointFeature(it.point) }))
            style.getSourceAs<GeoJsonSource>("route-base")?.setGeoJson(collection(listOfNotNull(routes.getOrNull(0)?.takeIf { it.path.size>=2 }).map { lineFeature(it.path) }))
            style.getSourceAs<GeoJsonSource>("route-gil")?.setGeoJson(collection(listOfNotNull(routes.getOrNull(1)?.takeIf { it.path.size>=2 }).map { lineFeature(it.path) }))
            applyRouteWidths(style)
            (1..POI_SLOTS).forEach { n -> style.getSourceAs<GeoJsonSource>("poi-$n")?.setGeoJson(collection(listOfNotNull(pois.getOrNull(n-1)).map { pointFeature(it.point) })) }
            val hl=highlighted?.let { pois.getOrNull(it) }
            style.getSourceAs<GeoJsonSource>("poi-hl")?.setGeoJson(collection(listOfNotNull(hl).map { pointFeature(it.point) }))
            style.getSourceAs<GeoJsonSource>("poi-link")?.setGeoJson(collection(listOfNotNull(hl).map { lineFeature(listOf(it.point,it.nearestOnRoute)) }))
        }
        if(!userMoved && location!=null) {
            val center=map?.cameraPosition?.target
            val offCenter=center==null || location!!.distanceTo(GeoPoint(center.latitude,center.longitude))>20
            if(!centered || offCenter) { val targetZoom=if(centered)zoom else 14.0;centered=true;move(location!!,targetZoom) }
        }
    }
    private fun move(point:GeoPoint,zoom:Double) { map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude,point.longitude),zoom)) }
    override fun focus(point:GeoPoint,followLocation:Boolean) {
        if(restricted) return
        userMoved=!followLocation;centered=true
        if(!ready) pendingFocus=point else move(point,15.0)
    }
    override fun zoomBy(amount:Double) {
        if(restricted || !ready) return
        map?.moveCamera(CameraUpdateFactory.zoomTo((zoom+amount).coerceIn(4.0,18.0)))
    }
    override fun setRestricted(restricted:Boolean) {
        this.restricted=restricted
        if(map==null || appliedRestriction==restricted) return
        appliedRestriction=restricted
        mapView.setMaximumFps(if(restricted) 3 else 15)
        map?.uiSettings?.apply { setAllGesturesEnabled(!restricted); isZoomGesturesEnabled=!restricted; isDoubleTapGesturesEnabled=!restricted; isScrollGesturesEnabled=!restricted; isCompassEnabled=!restricted }
    }
    override fun setVisible(visible:Boolean) {
        if(this.visible==visible) return
        this.visible=visible
        if(visible) {mapView.onStart();mapView.onResume()} else {mapView.onPause();mapView.onStop()}
    }
    override fun saveState(state:Bundle) {mapView.onSaveInstanceState(state);state.putBoolean("gilkok_follow",!userMoved)}
    override fun destroy() {setVisible(false);mapView.onDestroy()}
    override fun lowMemory()=mapView.onLowMemory()
}
