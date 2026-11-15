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
    /** 고른 경로를 잠깐 굵게 강조 (정차 중에만 호출됨) */
    fun pulseRoute(index: Int)
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
    private var overlayKey=""
    private var centered=state?.isEmpty==false
    private var userMoved=state?.getBoolean("gilkok_follow",true)==false
    private var pendingFocus:GeoPoint?=null
    override var onPointSelected:((GeoPoint)->Unit)?=null
    override var onViewportChanged:(()->Unit)?=null
    // 지도 위에 명소·콕 번호표를 안드로이드 뷰로 직접 올립니다 (지도 글꼴·레이어 문제와 무관하게 항상 보이도록).
    private val container=android.widget.FrameLayout(context)
    private val overlay=android.widget.FrameLayout(context).apply { isClickable=false; isFocusable=false }
    private val poiViews=mutableListOf<View>()
    private val kokViews=mutableListOf<View>()
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
            // 동일 타일 URL 유지. 배경·채도만 낮춰 경로 색이 먼저 읽히게 함.
            val json="""{"version":8,"glyphs":"https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf","sources":{"osm":{"type":"raster","tiles":[$url],"tileSize":256,"maxzoom":19,"attribution":"© OpenStreetMap contributors"}},"layers":[{"id":"background","type":"background","paint":{"background-color":"#9AA7B4"}},{"id":"osm","type":"raster","source":"osm","paint":{"raster-fade-duration":0,"raster-opacity":0.88,"raster-saturation":-0.35,"raster-contrast":-0.08}}]}"""
            value.setStyle(Style.Builder().fromJson(json)) { loaded ->
                style=loaded
                (listOf("current","destination","kok","place-wp","route-base","route-gil","route-top","poi-hl","poi-link")+(1..POI_SLOTS).map { "poi-$it" }).forEach { loaded.addSource(GeoJsonSource(it,empty())) }
                // 두 경로 모두 굵게: 고른 경로가 조금 더 굵고 위에 그려짐. 굵기는 draw()에서 직접 바꿈.
                loaded.addLayer(LineLayer("route-base-casing","route-base").withProperties(lineColor("#FFFFFF"),lineWidth(14f),lineCap("round"),lineJoin("round")))
                loaded.addLayer(LineLayer("route-base-line","route-base").withProperties(lineColor(ROUTE_BASE),lineWidth(9f),lineCap("round"),lineJoin("round")))
                loaded.addLayer(LineLayer("route-gil-casing","route-gil").withProperties(lineColor("#FFFFFF"),lineWidth(14f),lineCap("round"),lineJoin("round")))
                loaded.addLayer(LineLayer("route-gil-line","route-gil").withProperties(lineColor(ROUTE_GIL),lineWidth(9f),lineCap("round"),lineJoin("round")))
                // 고른 경로를 맨 위에 한 번 더 그림 → 두 경로가 겹친 구간에서도 고른 경로 색이 보임 (레이어 순서는 바꾸지 않음)
                loaded.addLayer(LineLayer("route-top-casing","route-top").withProperties(lineColor("#FFFFFF"),lineWidth(17f),lineCap("round"),lineJoin("round")))
                loaded.addLayer(LineLayer("route-top-line","route-top").withProperties(lineColor(ROUTE_GIL),lineWidth(11f),lineCap("round"),lineJoin("round")))
                // 고른 명소 ↔ 경로의 가장 가까운 지점: 점선
                loaded.addLayer(LineLayer("poi-link-line","poi-link").withProperties(lineColor(PLACE),lineWidth(4f),lineDasharray(arrayOf(1.5f,1.5f))))
                // 고른 명소: 크게 + 바깥 고리
                loaded.addLayer(CircleLayer("poi-hl-halo","poi-hl").withProperties(circleColor(PLACE),circleRadius(30f),circleOpacity(.25f)))
                loaded.addLayer(CircleLayer("poi-hl-dot","poi-hl").withProperties(circleColor(PLACE),circleRadius(19f),circleStrokeColor("#FFFFFF"),circleStrokeWidth(4f)))
                loaded.addLayer(CircleLayer("kok-point","kok").withProperties(circleColor(KOK),circleRadius(13f),circleStrokeColor("#FFFFFF"),circleStrokeWidth(3f)))
                loaded.addLayer(CircleLayer("place-wp-point","place-wp").withProperties(circleColor(PLACE),circleRadius(13f),circleStrokeColor("#FFFFFF"),circleStrokeWidth(3f)))
                loaded.addLayer(CircleLayer("destination-point","destination").withProperties(circleColor(DEST),circleRadius(13f),circleStrokeColor("#FFFFFF"),circleStrokeWidth(3f)))
                loaded.addLayer(CircleLayer("current-halo","current").withProperties(circleColor("#1677FF"),circleRadius(23f),circleOpacity(.17f)))
                loaded.addLayer(CircleLayer("current-dot","current").withProperties(circleColor("#1677FF"),circleRadius(12f),circleStrokeColor("#FFFFFF"),circleStrokeWidth(4f)))
                renderKey=""; overlayKey=""; draw(); fitRoutes()
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
        fitPending=true
        fitRoutes()
    }
    /** 전체 경로가 화면에 들어오게 맞춤. 지도가 아직 준비 안 됐거나 크기가 0이면 준비된 뒤 다시 시도. */
    private var fitPending=false
    private fun fitRoutes() {
        val points=routes.flatMap { it.path }
        if(!fitPending || points.size<2 || restricted) return
        if(!ready || mapView.width==0 || mapView.height==0) { mapView.post { if(ready && mapView.width>0) fitRoutes() }; return }
        fitPending=false
        runCatching {
            val bounds=LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.latitude,it.longitude)) } }.build()
            val pad=(minOf(mapView.width,mapView.height)*0.12).toInt().coerceIn(40,140)
            userMoved=true
            map?.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds,pad,pad+dp(60),pad,pad),500)
        }
    }
    private fun dp(v:Int)=(v*context.resources.displayMetrics.density).toInt()
    private fun numberBadge(n:Int,color:String,on:Boolean):View {
        val size=dp(if(on) 36 else 28)
        return android.widget.TextView(context).apply {
            text="$n"; setTextColor(android.graphics.Color.WHITE); textSize=if(on) 16f else 14f
            setTypeface(typeface,android.graphics.Typeface.BOLD); gravity=android.view.Gravity.CENTER
            background=android.graphics.drawable.GradientDrawable().apply {
                shape=android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor(color)); setStroke(dp(3),android.graphics.Color.WHITE)
            }
            elevation=dp(4).toFloat()
            tag=size
            layoutParams=android.widget.FrameLayout.LayoutParams(size,size)
        }
    }
    /** 명소: 번호는 항상, 이름표는 고른 항목만. 콕: 방문 순서 번호. */
    private fun rebuildOverlay() {
        val key="${koks.joinToString { it.key+it.place }}|${pois.hashCode()}|$highlighted"
        if(key==overlayKey && overlay.childCount>0) { updateOverlay(); return }
        overlayKey=key
        overlay.removeAllViews(); poiViews.clear(); kokViews.clear()
        koks.forEachIndexed { i,kok ->
            val color=if(kok.place) PLACE else KOK
            val badge=numberBadge(i+1,color,false)
            overlay.addView(badge)
            kokViews+=badge
        }
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
            if(on) {
                row.addView(android.widget.TextView(context).apply {
                    text=p.name; textSize=15f; maxLines=1; maxWidth=dp(160); ellipsize=android.text.TextUtils.TruncateAt.END
                    setTextColor(android.graphics.Color.WHITE); setTypeface(typeface,android.graphics.Typeface.BOLD)
                    background=android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius=dp(8).toFloat(); setColor(android.graphics.Color.parseColor("#E016212E"))
                        setStroke(dp(2),android.graphics.Color.parseColor(PLACE))
                    }
                    setPadding(dp(8),dp(3),dp(8),dp(3)); elevation=dp(3).toFloat()
                },android.widget.LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(4) })
            }
            row.tag=size
            overlay.addView(row,android.widget.FrameLayout.LayoutParams(-2,-2))
            poiViews+=row
        }
        overlay.post { updateOverlay() }
    }
    /** 지도가 움직일 때마다 번호의 중심이 좌표에 오도록 위치를 다시 맞춥니다. */
    private fun updateOverlay() {
        val m=map ?: return
        kokViews.forEachIndexed { i,v ->
            val kok=koks.getOrNull(i) ?: return@forEachIndexed
            val pt=m.projection.toScreenLocation(LatLng(kok.point.latitude,kok.point.longitude))
            val size=(v.tag as? Int) ?: dp(28)
            v.translationX=pt.x-size/2f; v.translationY=pt.y-size/2f
            v.visibility=if(visible) View.VISIBLE else View.INVISIBLE
        }
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
        overlayKey="" // 하이라이트 변경 시 이름표 밀도 다시 구성
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
    private val pulseHandler=android.os.Handler(android.os.Looper.getMainLooper())
    override fun pulseRoute(index:Int) {
        val style=style ?: return
        if(restricted) return
        val prefix=if(index==selected) "route-top" else if(index==0) "route-base" else "route-gil"
        pulseHandler.removeCallbacksAndMessages(null)
        // 굵기를 단계적으로 키웠다가 원래대로 (데이터 기반 표현식 없이 setProperties만 사용)
        val steps=listOf(13f to 19f, 16f to 22f, 13f to 19f, 11f to 16f)
        steps.forEachIndexed { n,(line,casing) ->
            pulseHandler.postDelayed({
                style.getLayer("$prefix-line")?.setProperties(lineWidth(line))
                style.getLayer("$prefix-casing")?.setProperties(lineWidth(casing))
            },n*120L)
        }
        pulseHandler.postDelayed({ applyRouteWidths(style) },steps.size*120L+80)
    }
    private fun applyRouteWidths(style:Style) {
        fun set(id:String,on:Boolean,casing:Boolean) { style.getLayer(id)?.setProperties(lineWidth(if(casing) (if(on) 17f else 13f) else (if(on) 11f else 7.5f))) }
        set("route-base-casing",selected==0,true); set("route-base-line",selected==0,false)
        set("route-gil-casing",selected==1,true); set("route-gil-line",selected==1,false)
        style.getLayer("route-top-casing")?.setProperties(lineWidth(17f)); style.getLayer("route-top-line")?.setProperties(lineWidth(11f))
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
            val top=routes.getOrNull(selected)?.takeIf { it.path.size>=2 && routes.count { r -> r.path.size>=2 }>=2 }
            style.getSourceAs<GeoJsonSource>("route-top")?.setGeoJson(collection(listOfNotNull(top).map { lineFeature(it.path) }))
            style.getLayer("route-top-line")?.setProperties(lineColor(if(selected==0) ROUTE_BASE else ROUTE_GIL))
            applyRouteWidths(style)
            (1..POI_SLOTS).forEach { n -> style.getSourceAs<GeoJsonSource>("poi-$n")?.setGeoJson(collection(listOfNotNull(pois.getOrNull(n-1)).map { pointFeature(it.point) })) }
            val hl=highlighted?.let { pois.getOrNull(it) }
            style.getSourceAs<GeoJsonSource>("poi-hl")?.setGeoJson(collection(listOfNotNull(hl).map { pointFeature(it.point) }))
            style.getSourceAs<GeoJsonSource>("poi-link")?.setGeoJson(collection(listOfNotNull(hl).map { lineFeature(listOf(it.point,it.nearestOnRoute)) }))
            rebuildOverlay()
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
        updateOverlay()
    }
    override fun saveState(state:Bundle) {mapView.onSaveInstanceState(state);state.putBoolean("gilkok_follow",!userMoved)}
    override fun destroy() {setVisible(false);mapView.onDestroy()}
    override fun lowMemory()=mapView.onLowMemory()
}
