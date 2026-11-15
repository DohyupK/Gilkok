package com.example.gilkok

import android.Manifest
import android.car.Car
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.location.LocationManager
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.text.DateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 화면 흐름: 목적지 → 지나고 싶은 길 콕 → 경로 비교(시간·거리·혼잡·예상 전력) → 기본 내비로 안내 + 주행 기록
 *            → 주행 후 예측 vs 실제 비교 → 다음 예측에 내 기록 반영
 */
class MainActivity : AppCompatActivity() {
    private var session:TripRecordingService?=null
    private var bound=false
    private var resumed=false
    private var map:MapProvider?=null
    private lateinit var search:PlaceSearchProvider
    private lateinit var deviceLocation:DeviceLocationProvider
    private var savedMapState:Bundle?=null
    private var panel=0
    private var restorePanel:Int?=null
    private var selectedPlace:Place?=null
    private var koks:List<KokPoint> = emptyList()
    private val namer by lazy { KokNamer(this) }
    private var routes:List<RouteOption> = emptyList()
    private var energies:List<EnergyPrediction> = emptyList()
    private var chosen=1
    private var comparing=false
    private var compareError=""
    private var requestToken=0
    private var routeProvider:RouteProvider?=null
    private var reportId:String?=null
    private var reportKey=""
    private var comparisonKey=""
    private var pendingStart=false
    private var pendingPrediction:Double?=null
    private var pendingCrossing="direct"
    private var pendingLocate=false
    private var dialog:AlertDialog?=null
    private var lastRestricted=true
    private var importDestinationAt=0L
    private val openedAt=SystemClock.elapsedRealtime()
    private var mapError=""
    private val handler=Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("selection",MODE_PRIVATE) }
    private val observer:()->Unit={render()}
    private val locationTick=object:Runnable {
        override fun run() { if(resumed) {updateLocationLifecycle();render();handler.postDelayed(this,1000)} }
    }
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName?,binder:IBinder?) {
            session=(binder as TripRecordingService.LocalBinder).service
            restorePanel?.let {restorePanel=null;showPanel(it)}
            session?.observe(observer)
        }
        override fun onServiceDisconnected(name:ComponentName?) {session=null;render()}
    }

    // ───────────────────────── 생명주기 ─────────────────────────
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);setContentView(R.layout.activity_main)
        savedMapState=state?.getBundle("map");panel=state?.getInt("panel") ?: 0;restorePanel=state?.getInt("panel");reportId=state?.getString("reportId")
        val lat=prefs.getString("lat",null)?.toDoubleOrNull();val lon=prefs.getString("lon",null)?.toDoubleOrNull()
        if(lat!=null && lon!=null) selectedPlace=Place(prefs.getString("name","목적지")!!,GeoPoint(lat,lon),prefs.getString("address","")!!)
        koks=KokPoints.decode(prefs.getString("koks",null))
        if(selectedPlace==null && !prefs.contains("name")) selectedPlace=RouteSettings.DEMO_DESTINATION // 첫 실행 시연용
        search=NominatimPlaceSearch(this);deviceLocation=DeviceLocationProvider(this){if(resumed)render()}
        on(R.id.nav_route){showPanel(0)};on(R.id.nav_trip){showPanel(1)};on(R.id.nav_report){showPanel(2)};on(R.id.nav_settings){showSettings()}
        on(R.id.enable_map){showPrivacy()};on(R.id.pick_destination){showSearch()};on(R.id.route_mode){showRouteSource()}
        on(R.id.clear_destination){if(!drivingRestricted()){selectedPlace=null;clearRoutes();persistSelection();currentPoint()?.let{map?.focus(it,true)};render()}}
        on(R.id.compare_routes){compare()};on(R.id.start_guidance){confirmGuidance()}
        on(R.id.zoom_in){map?.zoomBy(1.0)};on(R.id.zoom_out){map?.zoomBy(-1.0)};on(R.id.recenter_map){locate()}
        on(R.id.start_trip){requestRecording(null,crossing(koks))};on(R.id.stop_trip){session?.finishRecording();reportId=null;reportKey="";showPanel(2)}
        on(R.id.report_history){showHistory()}
        setupLegend()
        if(resources.configuration.screenWidthDp<1100) findViewById<View>(R.id.route_sheet).layoutParams.width=dp(420)
        if(ExternalServices.allowed(this))createMap()
        showPanel(panel)
    }
    override fun onStart(){super.onStart();bound=bindService(Intent(this,TripRecordingService::class.java),connection,BIND_AUTO_CREATE);if(checkSelfPermission(Car.PERMISSION_SPEED)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Car.PERMISSION_SPEED),SPEED)}
    override fun onResume(){super.onResume();resumed=true;updateMapLifecycle();handler.post(locationTick)}
    override fun onPause(){resumed=false;handler.removeCallbacks(locationTick);deviceLocation.stop();updateMapLifecycle();dialog?.dismiss();super.onPause()}
    override fun onStop(){session?.removeObserver(observer);if(bound){unbindService(connection);bound=false};session=null;super.onStop()}
    override fun onDestroy(){routeProvider?.close();poiProvider?.close();map?.destroy();deviceLocation.stop();search.close();handler.removeCallbacksAndMessages(null);super.onDestroy()}
    override fun onLowMemory(){super.onLowMemory();map?.lowMemory()}
    override fun onSaveInstanceState(outState:Bundle){outState.putInt("panel",panel);outState.putString("reportId",reportId);val state=Bundle();map?.saveState(state);outState.putBundle("map",state);super.onSaveInstanceState(outState)}

    // ───────────────────────── 공통 도우미 ─────────────────────────
    private fun drivingRestricted()=session?.restricted!=false
    private fun on(id:Int,action:()->Unit){findViewById<View>(id).setOnClickListener{action()}}
    private fun text(id:Int,value:String){val label=findViewById<TextView>(id);if(label.text.toString()!=value)label.text=value}
    private fun toast(value:String){Toast.makeText(this,value,Toast.LENGTH_LONG).show()}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun fmt(value:Number,digits:Int=1)=String.format(Locale.KOREA,"%.${digits}f",value.toDouble())
    private fun minutes(seconds:Double)=(seconds/60).roundToInt().coerceAtLeast(1)
    private fun show(value:AlertDialog){
        dialog?.dismiss();dialog=value;value.show()
        value.window?.setLayout(minOf(dp(820),resources.displayMetrics.widthPixels-dp(48)),android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        fun enlarge(view:View){if(view is TextView){view.textSize=maxOf(view.textSize/resources.displayMetrics.scaledDensity,20f);if(view is Button || view is EditText)view.minimumHeight=dp(64)};if(view is android.view.ViewGroup)for(i in 0 until view.childCount)enlarge(view.getChildAt(i))}
        value.window?.decorView?.let(::enlarge)
    }
    // 주행·분석 화면은 2차 PT 전까지 재설계 예정이라 숨김 (코드는 유지). 항상 경로 화면만 사용.
    private fun showPanel(@Suppress("UNUSED_PARAMETER") requested:Int){ val index=0
        panel=if(index==2 && drivingRestricted())1 else index
        listOf(R.id.route_panel,R.id.trip_panel,R.id.report_panel).forEachIndexed{i,id->findViewById<View>(id).visibility=if(i==panel)View.VISIBLE else View.GONE}
        listOf(R.id.nav_route,R.id.nav_trip,R.id.nav_report).forEachIndexed{i,id->
            val b=findViewById<MaterialButton>(id)
            b.backgroundTintList=ColorStateList.valueOf(if(i==panel)getColor(R.color.road_surface2) else android.graphics.Color.TRANSPARENT)
            b.setTextColor(getColor(if(i==panel)R.color.road_teal_bright else R.color.road_muted))
        }
        updateMapLifecycle();updateLocationLifecycle();reportKey="";render()
    }

    // ───────────────────────── 색 범례 ─────────────────────────
    // 지도 표시와 왼쪽 패널이 같은 색을 쓰도록 한곳에서 정합니다.
    private fun dot(color:String,size:Int=14)=android.graphics.drawable.GradientDrawable().apply{
        shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(android.graphics.Color.parseColor(color))
        setStroke(dp(2),android.graphics.Color.WHITE);setSize(dp(size),dp(size))}
    private fun bar(color:String)=android.graphics.drawable.GradientDrawable().apply{
        cornerRadius=dp(3).toFloat();setColor(android.graphics.Color.parseColor(color));setSize(dp(24),dp(6))}
    private fun setupLegend(){
        findViewById<TextView>(R.id.origin_label).setCompoundDrawablesRelativeWithIntrinsicBounds(dot(START),null,null,null)
        findViewById<TextView>(R.id.dest_label).setCompoundDrawablesRelativeWithIntrinsicBounds(dot(DEST),null,null,null)
        findViewById<TextView>(R.id.kok_label).setCompoundDrawablesRelativeWithIntrinsicBounds(dot(KOK),null,null,null)
        val legend=findViewById<LinearLayout>(R.id.map_legend)
        legend.removeAllViews()
        listOf(dot(START) to "출발",dot(DEST) to "도착",dot(KOK) to "콕",dot(PLACE) to "명소",bar(ROUTE_BASE) to "추천",bar(ROUTE_GIL) to "길콕").forEach{(icon,label)->
            legend.addView(TextView(this).apply{
                text=label;textSize=15f;setTextColor(getColor(R.color.white))
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon,null,null,null);compoundDrawablePadding=dp(8)
                gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(2),0,dp(2))})
        }
    }

    // ───────────────────────── 지도 ─────────────────────────
    private fun createMap(){
        if(map!=null || !ExternalServices.allowed(this))return
        try{
            mapError=""
            map=MapLibreProvider(this,savedMapState){message->if(message.contains("실패"))mapError=message;if(resumed)render()}.also{provider->
                findViewById<FrameLayout>(R.id.map_host).addView(provider.view,FrameLayout.LayoutParams(-1,-1))
                provider.onViewportChanged={if(resumed)render()}
                provider.onPointSelected={point->onMapLongPress(point)}
                if(routes.isNotEmpty()) provider.showRoutes(routes,chosen)
            }
            findViewById<View>(R.id.map_placeholder).visibility=View.GONE;updateMapLifecycle();updateLocationLifecycle()
        }catch(e:Exception){mapError="지도를 열지 못했습니다. 설정에서 다시 시도해주세요";map?.destroy();map=null}
    }
    /** 지도를 탭하면 그 자리가 "콕 지점"이 됩니다. 목적지가 없으면 목적지로 쓸지 묻습니다. */
    private fun onMapLongPress(point:GeoPoint){
        if(drivingRestricted())return
        if(selectedPlace==null){
            show(AlertDialog.Builder(this).setTitle("이 위치로 가시겠어요?").setNegativeButton("취소",null)
                .setPositiveButton("목적지로 선택"){_,_->choosePlace(Place("지도에서 선택한 위치",point))}.create());return
        }
        if(koks.size>=KokPoints.MAX){toast("콕 지점은 최대 ${KokPoints.MAX}곳까지예요 (차량 내비 경유지 한도). 기존 지점을 지우고 찍어주세요");return}
        val fallback=UNNAMED
        val pending=KokPoint(fallback,point)
        namingKeys+=pending.key
        addKok(pending)
        namer.name(point,fallback){name->
            namingKeys-=pending.key
            koks=koks.map{if(it.point==point && name!=fallback)it.copy(name=name) else it};persistSelection();chipKey="";comparisonKey="";render()}
    }
    private fun updateMapLifecycle(){map?.setVisible(resumed && panel==0 && ExternalServices.allowed(this))}
    private fun updateLocationLifecycle(){if(resumed && panel==0 && ExternalServices.allowed(this))deviceLocation.start() else deviceLocation.stop()}
    /** 한국 밖 좌표(에뮬레이터 기본 위치 = 미국 캘리포니아 등)는 위치로 쓰지 않습니다. */
    private fun inKorea(p:GeoPoint)=p.latitude in 33.0..38.7 && p.longitude in 124.5..131.0
    private fun rawPoint():GeoPoint?{
        session?.let{NaviLocationProvider{it.naviState}.latestLocation()}?.let{return it}
        return if(SystemClock.elapsedRealtime()-openedAt>=8000)deviceLocation.latestLocation() else null
    }
    private fun currentPoint():GeoPoint?=rawPoint()?.takeIf{inKorea(it)}
    /** 실제 위치 → 없으면 데모 모드일 때만 데모 출발지 */
    private fun origin():Place?{
        currentPoint()?.let{return Place("현재 위치",it)}
        return RouteSettings.DEMO_ORIGIN // 위치를 못 받으면 데모 출발지 (화면에 표시)
    }
    private fun crossing(list:List<KokPoint>)=if(list.isEmpty())"direct" else "kok:"+list.joinToString("|"){it.key}
    private fun locate(){
        if(drivingRestricted()){toast("정차 중에 지도를 조작할 수 있습니다");return}
        if(!ExternalServices.allowed(this)){showPrivacy();return}
        pendingLocate=true;session?.navigation?.refresh()
        currentPoint()?.let{pendingLocate=false;map?.focus(it,true);render();return}
        if(!deviceLocation.permitted()){requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION),MAP_LOCATION);return}
        if(!deviceLocation.enabled()){toast("현재 위치를 표시하려면 시스템 위치를 켜주세요");runCatching{startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))};return}
        deviceLocation.start()
        val point=currentPoint()
        pendingLocate=false
        if(point!=null) map?.focus(point,true)
        else {
            toast(if(rawPoint()!=null)"기기 위치가 한국 밖으로 잡혀 있어요(에뮬레이터 기본값). 데모 출발지를 보여드려요" else "현재 위치를 아직 받지 못했어요. 데모 출발지를 보여드려요")
            map?.focus(RouteSettings.DEMO_ORIGIN.point,true)
        }
        render()
    }

    // ───────────────────────── 선택 상태 ─────────────────────────
    private fun choosePlace(value:Place){if(drivingRestricted() || !value.point.valid)return;selectedPlace=value;clearRoutes();persistSelection();compare()}
    private fun addKok(value:KokPoint){
        if(drivingRestricted())return
        koks=KokPoints.ordered(origin()?.point,koks+value).take(KokPoints.MAX)
        clearRoutes();persistSelection();if(selectedPlace!=null)compare() else render()
    }
    private fun removeKok(value:KokPoint){
        if(drivingRestricted())return
        koks=koks-value;clearRoutes();persistSelection();if(selectedPlace!=null)compare() else render()
    }
    private fun clearRoutes(){poiToken++;poiProvider?.close();poiOpen=false;pois=emptyList();poiHighlight=null;showPoisEverywhere(emptyList(),null);requestToken++;routeProvider?.close();routes=emptyList();energies=emptyList();comparing=false;compareError="";comparisonKey="";map?.showRoutes(emptyList(),0)}
    private fun persistSelection(){prefs.edit().putString("koks",KokPoints.encode(koks)).putString("name",selectedPlace?.name).putString("lat",selectedPlace?.point?.latitude?.toString()).putString("lon",selectedPlace?.point?.longitude?.toString()).putString("address",selectedPlace?.address).apply()}

    // ───────────────────────── 경로 비교 ─────────────────────────
    /** 목적지가 정해지면 추천(최단) 경로를 바로 보여주고, 콕 지점이 있으면 길콕 경로를 함께 계산해 비교합니다. */
    private fun compare(){
        if(drivingRestricted())return
        val target=selectedPlace ?: run{toast("목적지를 먼저 선택하세요");return}
        val from=origin() ?: run{toast("현재 위치를 확인하는 중입니다. 데모 모드에서는 데모 출발지를 사용합니다");return}
        if(from.point.distanceTo(target.point)>300_000){toast("출발지와 목적지가 300km 넘게 떨어져 있어요. 위치 설정을 확인해주세요");return}
        val picked=KokPoints.bestOrder(from.point,target.point,koks)
        if(picked!=koks){koks=picked;persistSelection()}
        routeProvider?.close()
        var provider=RouteSettings.provider(this).also{routeProvider=it}
        val token=++requestToken
        comparing=true;compareError="";routes=emptyList();energies=emptyList();comparisonKey=""
        val count=if(picked.isEmpty())1 else 2
        val results=arrayOfNulls<RouteOption>(count);var done=0
        fun finish(){
            if(token!=requestToken)return
            if(++done<count)return
            comparing=false
            val list=results.filterNotNull()
            if(list.size==count){
                val s=session
                val history=s?.store?.recent().orEmpty()
                routes=list;energies=list.map{RouteEnergyEstimator.estimate(it,s?.snapshot?.evKmPerKwh,history)};chosen=count-1
                map?.showRoutes(routes,chosen)
                // 서울 구간은 실시간 교통으로 시간·연비 다시 계산
                SeoulTraffic.adjust(this,list){adj->
                    if(token!=requestToken||adj===list)return@adjust
                    routes=adj;energies=adj.map{RouteEnergyEstimator.estimate(it,s?.snapshot?.evKmPerKwh,history)};comparisonKey="";render()
                }
            } else if(compareError.isBlank()) compareError="경로를 계산하지 못했습니다"
            comparisonKey="";render()
        }
        fun request(index:Int,list:List<KokPoint>){
            provider.route(from.point,target.point,list){r->
                if(token!=requestToken)return@route
                r.onSuccess{results[index]=it;finish()}.onFailure{failure->
                    // 네트워크가 없으면 데모 데이터로 대체하고 화면에 표시
                    if(provider.source!=RouteSource.DEMO){DemoRouteProvider().route(from.point,target.point,list){d->d.onSuccess{results[index]=it.copy(note="경로 서버 실패(${failure.message ?: "원인 불명"}) · 데모 데이터로 표시")}.onFailure{compareError=it.message ?: "경로 계산 실패"};finish()}}
                    else{compareError=failure.message ?: "경로 계산 실패";finish()}
                }
            }
        }
        request(0,emptyList());if(count==2)request(1,picked)
        render()
    }

    private var chipKey=""
    // 이름을 받아오는 중인 콕 지점 (도로 이름 표시 상태용)
    private val namingKeys=mutableSetOf<String>()
    /** 콕 지점이 길콕 경로에 실제로 반영됐는지: null = 아직 계산 전/중 */
    private fun kokPassed(kok:KokPoint):Boolean?{
        val gil=routes.getOrNull(1) ?: return null
        if(comparing) return null
        return Geo.distanceToPath(kok.point,gil.path)<=KokValidator.TOLERANCE_M
    }
    private fun renderKoks(restricted:Boolean){
        val key="${koks.joinToString{it.key+it.name+it.place}}|$restricted|${namingKeys.size}|$comparing|${routes.hashCode()}"
        if(key==chipKey)return
        chipKey=key
        val box=findViewById<LinearLayout>(R.id.kok_list);box.removeAllViews()
        koks.forEachIndexed{i,kok->
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;background=getDrawable(R.drawable.card_bg);setPadding(dp(16),dp(6),dp(8),dp(6))}
            row.addView(TextView(this).apply{
                text="${i+1}";textSize=16f;setTypeface(typeface,Typeface.BOLD);gravity=Gravity.CENTER
                setTextColor(android.graphics.Color.parseColor(if(kok.place)"#FFFFFF" else "#101A28"))
                background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(android.graphics.Color.parseColor(if(kok.place)PLACE else KOK))}
            },LinearLayout.LayoutParams(dp(34),dp(34)).apply{marginEnd=dp(12)})
            // 도로 이름을 주인공으로, 아래 줄에 종류 · 반영 상태
            val naming=kok.key in namingKeys
            val title=when{naming->"도로 확인 중…";kok.name==UNNAMED->"선택한 지점";else->kok.name}
            val passed=kokPassed(kok)
            val status=when{comparing->"경로 계산 중";routes.size<2->"경로 계산 대기";passed==true->"경로에 반영됨";else->"이 길을 지나지 못했어요"}
            val texts=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
            texts.addView(TextView(this).apply{text=title;textSize=20f;setTypeface(typeface,Typeface.BOLD);setTextColor(getColor(if(naming)R.color.road_muted else R.color.white));maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
            texts.addView(TextView(this).apply{text="${if(kok.place)"명소 경유지" else "선택한 도로"} · $status";textSize=15f;setTextColor(getColor(if(passed==false)R.color.road_bad else R.color.road_muted))})
            row.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
            if(passed!=null) row.addView(TextView(this).apply{
                text=if(passed)"✓" else "!";textSize=20f;setTypeface(typeface,Typeface.BOLD);gravity=Gravity.CENTER
                setTextColor(getColor(if(passed)R.color.road_good else R.color.road_bad))},LinearLayout.LayoutParams(dp(36),dp(36)))
            row.addView(MaterialButton(this,null,com.google.android.material.R.attr.materialButtonStyle).apply{
                text="✕";isEnabled=!restricted;contentDescription="${kok.name} 지우기"
                backgroundTintList=ColorStateList.valueOf(android.graphics.Color.TRANSPARENT);setTextColor(getColor(R.color.road_muted))
                setOnClickListener{removeKok(kok)}},LinearLayout.LayoutParams(dp(64),dp(64)))
            box.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        }
        val failed=koks.any{kokPassed(it)==false}
        text(R.id.kok_hint,when{failed->"표시한 길을 지나는 경로를 찾지 못했어요. 그 길의 다른 지점을 찍어 보세요.";!comparing && routes.size==2->"선택한 도로를 통과하는 경로를 계산했어요.";koks.isEmpty()->"지도에서 지나고 싶은 길을 탭하세요. 그 길을 지나는 경로를 만들어 비교해요.";koks.size<KokPoints.MAX->"한 곳 더 찍을 수 있어요 (최대 ${KokPoints.MAX}곳)";else->"차량 내비 경유지 한도인 ${KokPoints.MAX}곳을 모두 찍었어요"})
    }

    private fun renderComparison(restricted:Boolean){
        val key="$comparing|$compareError|${routes.hashCode()}|$chosen|$restricted|$poiOpen|$poiTheme|${pois.hashCode()}|$poiStatus|$poiHighlight"
        if(key==comparisonKey)return
        comparisonKey=key
        val box=findViewById<LinearLayout>(R.id.comparison_list);box.removeAllViews()
        fun note(value:String,color:Int=R.color.road_muted)=box.addView(TextView(this).apply{text=value;textSize=18f;setTextColor(getColor(color));setPadding(0,dp(8),0,dp(8))})
        when{
            comparing->note("경로를 계산하고 있습니다…")
            compareError.isNotBlank()->note(compareError,R.color.road_bad)
            routes.size==1->{
                box.addView(compareHeader(routes[0]))
                val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
                row.addView(routeCard(0,routes[0],energies.getOrNull(0),restricted),LinearLayout.LayoutParams(0,-2,1f))
                box.addView(row)
                note("지도에서 지나고 싶은 길을 탭하면 내가 고른 길이 옆에 나란히 비교돼요")
                box.addView(poiButton(restricted))
                if(poiOpen) box.addView(poiSection(restricted))
            }
            routes.size==2->{
                box.addView(compareHeader(routes[1]))
                val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
                routes.forEachIndexed{i,r->row.addView(routeCard(i,r,energies.getOrNull(i),restricted),LinearLayout.LayoutParams(0,-2,1f).apply{if(i==0)marginEnd=dp(10)})}
                box.addView(row)
                box.addView(conclusion())
                box.addView(poiButton(restricted))
                if(poiOpen) box.addView(poiSection(restricted))
            }
            selectedPlace==null->note("목적지를 고르면 추천 경로가 바로 나와요")
        }
    }

    /** "경로 비교" 제목 + 데이터 근거 */
    private fun compareHeader(r:RouteOption):View=LinearLayout(this).apply{
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(4),0,dp(10))
        addView(TextView(this@MainActivity).apply{text="경로 비교";textSize=20f;setTypeface(typeface,Typeface.BOLD);setTextColor(getColor(R.color.white))},LinearLayout.LayoutParams(0,-2,1f))
        val basis=listOf("${Vehicles.current().name} 공인 연비 기준","예측값",if(r.source.hasTraffic)"교통 반영" else "교통정보 미반영",if(r.source==RouteSource.DEMO)"데모" else "").filter{it.isNotBlank()}.joinToString(" · ")
        addView(TextView(this@MainActivity).apply{text=basis;textSize=14f;setTextColor(getColor(R.color.road_muted))})
    }
    /** 한 경로 = 작은 카드 한 칸. 누르면 그 경로를 고르고 지도에서 잠깐 굵게 강조 */
    private fun routeCard(i:Int,r:RouteOption,e:EnergyPrediction?,restricted:Boolean):View{
        val selected=i==chosen
        val lineColor=android.graphics.Color.parseColor(if(i==0)ROUTE_BASE else ROUTE_GIL)
        val card=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(14))
            background=getDrawable(if(selected)R.drawable.card_selected else R.drawable.card_bg)
            setOnClickListener{
                if(restricted)return@setOnClickListener
                if(chosen!=i){chosen=i;if(poiOpen)closePois();map?.showRoutes(routes,chosen);comparisonKey="";render()}
                map?.pulseRoute(i)
            }
        }
        val head=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        head.addView(View(this).apply{background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(lineColor)}},LinearLayout.LayoutParams(dp(12),dp(12)).apply{marginEnd=dp(8)})
        head.addView(TextView(this).apply{text=if(i==0)"추천 경로" else "내가 고른 길";textSize=17f;setTextColor(getColor(R.color.white));maxLines=1},LinearLayout.LayoutParams(0,-2,1f))
        if(i==1) head.addView(TextView(this).apply{text=if(r.passesAll)"✓" else "!";textSize=17f;setTypeface(typeface,Typeface.BOLD);setTextColor(getColor(if(r.passesAll)R.color.road_good else R.color.road_bad))})
        card.addView(head)
        card.addView(TextView(this).apply{text="${minutes(r.durationS)}분";textSize=32f;setTypeface(typeface,Typeface.BOLD);setTextColor(getColor(R.color.white));setPadding(0,dp(6),0,0)})
        card.addView(TextView(this).apply{text="${fmt(r.distanceM/1000)} km";textSize=18f;setTextColor(getColor(R.color.white))})
        // 연료: 공인 도심/고속 연비로 역산 + 도심/고속 비율
        val fuel=FuelModel.estimate(r)
        card.addView(TextView(this).apply{text="연료 ${fmt(fuel.liters,2)} L";textSize=16f;setTextColor(getColor(R.color.road_muted));setPadding(0,dp(4),0,0)})
        card.addView(TextView(this).apply{text="도심 ${fuel.cityPercent}% · 고속 ${fuel.highwayPercent}%";textSize=14f;setTextColor(getColor(R.color.road_muted))})
        return card
    }
    /** 결론 한 문장: 고른 길이 추천 대비 얼마나 다른지 */
    private fun conclusion():View{
        val base=routes[0];val gil=routes[1]
        val dt=minutes(gil.durationS)-minutes(base.durationS)
        val de=FuelModel.estimate(gil).liters-FuelModel.estimate(base).liters   // 연료 차이(L)
        val same=KokValidator.passed(gil.koks,base.path)==gil.koks.size
        fun time()=when{dt>0->"${dt}분 더 걸리고";dt<0->"${-dt}분 빠르고";else->"시간은 같고"}
        fun energy()=when{de>.005->"연료는 ${fmt(de,2)} L 더 필요해요.";de< -.005->"연료는 ${fmt(-de,2)} L 덜 써요.";else->"연료는 비슷해요."}
        val better=dt<=0 && de<=.005
        val main=if(same)"추천 경로도 이 길을 지나요." else "${time()}, ${energy()}"
        val sub=when{same->"다른 길을 찍어 비교해 보세요.";!gil.passesAll->"고른 길을 다 지나지 못했어요. 지도에서 다른 지점을 찍어 보세요.";better->"고른 길이 더 유리해요.";else->"그래도 원하는 길을 선택해 이동할 수 있어요."}
        return LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(16),dp(4),dp(8))
            addView(TextView(this@MainActivity).apply{text="내가 고른 길은";textSize=18f;setTypeface(typeface,Typeface.BOLD);setTextColor(getColor(R.color.white))})
            addView(TextView(this@MainActivity).apply{text=main;textSize=19f;setTextColor(getColor(if(better && !same)R.color.road_good else R.color.white));setPadding(0,dp(4),0,dp(2))})
            addView(TextView(this@MainActivity).apply{text=sub;textSize=15f;setTextColor(getColor(if(!gil.passesAll)R.color.road_bad else R.color.road_muted))})
            if(gil.note.isNotBlank()) addView(TextView(this@MainActivity).apply{text=gil.note;textSize=14f;setTextColor(getColor(R.color.road_muted));setPadding(0,dp(4),0,0)})
        }
    }
    private fun poiButton(restricted:Boolean):View=MaterialButton(this,null,com.google.android.material.R.attr.materialButtonStyle).apply{
        val r=routes.getOrNull(chosen)
        text=if(poiOpen)"주변 명소 닫기" else "${if(chosen==1)"내가 고른 길" else "추천 경로"} 주변 명소 보기";textSize=17f;isEnabled=!restricted && r!=null
        backgroundTintList=ColorStateList.valueOf(getColor(R.color.road_surface2));setTextColor(getColor(R.color.white))
        setOnClickListener{if(poiOpen)closePois() else if(r!=null){poiOpen=true;loadPois(r,poiTheme)}}
        layoutParams=LinearLayout.LayoutParams(-1,dp(64)).apply{topMargin=dp(8)}
    }

    // ───────────────────────── 주변 명소 ─────────────────────────
    // 명소는 팝업 대신 왼쪽 패널에 목록으로, 지도에는 같은 번호의 보라색 원으로 보여줍니다.
    private var poiProvider:PoiProvider?=null
    private var poiOpen=false
    private var poiTheme=PoiTheme.SIGHTS
    private var pois:List<Poi> = emptyList()
    private var poiStatus=""
    private var poiHighlight:Int?=null
    private var poiToken=0
    private var poiDemo=false
    /** 명소 목록은 경로 카드 아래에 붙으므로, 열거나 결과가 오면 그 위치까지 자동으로 스크롤합니다. */
    private fun scrollToPois(){
        val scroll=findViewById<ScrollView>(R.id.route_scroll)
        scroll.post{ findViewWithTagIn(scroll,"poi_section")?.let{ v->
            var y=0; var cur:View?=v
            while(cur!=null && cur!=scroll){y+=cur.top;cur=cur.parent as? View}
            scroll.smoothScrollTo(0,(y-dp(8)).coerceAtLeast(0)) } }
    }
    private fun findViewWithTagIn(root:View,tag:String):View?=root.findViewWithTag(tag)
    private fun closePois(){poiToken++;poiProvider?.close();poiOpen=false;pois=emptyList();poiHighlight=null;poiStatus="";showPoisEverywhere(emptyList(),null);comparisonKey="";render()}
    private fun loadPois(route:RouteOption,theme:PoiTheme){
        val my=++poiToken
        poiTheme=theme;pois=emptyList();poiHighlight=null;poiStatus="경로 주변 ${theme.label}를 찾고 있어요…"
        showPoisEverywhere(emptyList(),null);comparisonKey="";render();scrollToPois()
        poiProvider?.close()
        fun done(items:List<Poi>,note:String){
            if(my!=poiToken)return
            pois=items
            poiStatus=if(items.isEmpty())"경로 가까이에 들를 만한 ${theme.label}가 없어요 (+${PoiRanker.MAX_DETOUR_MIN}분 이내)" else "추가 시간이 짧은 순 · 지도 번호와 같아요$note"
            showPoisEverywhere(items,null);comparisonKey="";render();scrollToPois()
            if(items.isNotEmpty()) toast("주변 ${theme.label} ${items.size}곳을 지도에 보라색 번호로 표시했어요")
        }
        val provider=PoiSettings.provider(this).also{poiProvider=it}
        provider.search(route,theme){r->
            r.onSuccess{poiDemo=provider.demo;done(it,if(provider.demo)"" else " · 한국관광공사")}
             .onFailure{err->DemoPoiProvider().search(route,theme){d->poiDemo=true;android.util.Log.w("GilkokPoi","demo fallback: ${err.message}");done(d.getOrDefault(emptyList()),"")}}
        }
    }
    private fun poiSection(restricted:Boolean):View{
        val route=routes.getOrNull(chosen) ?: return View(this)
        val efficiency=energies.getOrNull(chosen)?.kmPerKwh ?: RouteEnergyEstimator.DEFAULT_KM_PER_KWH
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=getDrawable(R.drawable.card_selected);setPadding(dp(16),dp(16),dp(16),dp(12));tag="poi_section"}
        val head=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        head.addView(TextView(this).apply{text="${if(chosen==1)"내가 고른 길" else "추천 경로"} 주변 추천 명소";textSize=19f;setTypeface(typeface,Typeface.BOLD);setTextColor(getColor(R.color.white))},LinearLayout.LayoutParams(0,-2,1f))
        if(poiDemo) head.addView(TextView(this).apply{text="예시";textSize=14f;background=getDrawable(R.drawable.tag_bg);setTextColor(getColor(R.color.white))})
        box.addView(head)
        val tabs=GridLayout(this).apply{columnCount=2}
        PoiTheme.entries.forEach{theme->
            tabs.addView(MaterialButton(this,null,com.google.android.material.R.attr.materialButtonStyle).apply{
                text=theme.label;textSize=16f;isEnabled=!restricted
                backgroundTintList=ColorStateList.valueOf(getColor(if(theme==poiTheme)R.color.road_teal else R.color.road_navy))
                setOnClickListener{loadPois(route,theme)}},
                GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),GridLayout.spec(GridLayout.UNDEFINED,1f)).apply{width=0;height=dp(56);setMargins(dp(3),dp(6),dp(3),0)})
        }
        box.addView(tabs)
        box.addView(TextView(this).apply{text=poiStatus;textSize=15f;setTextColor(getColor(R.color.road_muted));setPadding(0,dp(10),0,dp(6))})
        pois.forEachIndexed{i,poi->
            val on=i==poiHighlight
            val row=LinearLayout(this).apply{
                orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(8),dp(4),dp(8))
                if(on) background=getDrawable(R.drawable.card_selected)
                // 이름을 누르면 펼침/접힘 (펼친 명소가 지도에서도 강조됨)
                setOnClickListener{poiHighlight=if(on)null else i;showPoisEverywhere(pois,poiHighlight);comparisonKey="";render()}
            }
            row.addView(TextView(this).apply{
                text="${i+1}";textSize=16f;setTypeface(typeface,Typeface.BOLD);gravity=Gravity.CENTER;setTextColor(android.graphics.Color.WHITE)
                background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(android.graphics.Color.parseColor(PLACE))}
            },LinearLayout.LayoutParams(dp(34),dp(34)).apply{marginEnd=dp(12)})
            val texts=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
            texts.addView(TextView(this).apply{text="${poi.name}  ${if(on)"▲" else "▼"}";textSize=20f;setTypeface(typeface,Typeface.BOLD);setTextColor(getColor(R.color.white));maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
            val dist=if(poi.offRouteM>=1000)"${fmt(poi.offRouteM/1000)}km" else "${(poi.offRouteM/10).roundToInt()*10}m"
            texts.addView(TextView(this).apply{text="${poi.category.ifBlank{poiTheme.label}} · 경로에서 약 $dist · 추가 ${poi.detourMinutes}분";textSize=16f;setTextColor(getColor(R.color.road_muted));maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
            row.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
            box.addView(row)
            if(on){
                // 펼친 내용: 대표 이미지 + 1~3줄 소개
                val detail=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),0,dp(8),dp(8))}
                if(poi.imageUrl.isNotBlank()){
                    val image=android.widget.ImageView(this).apply{
                        scaleType=android.widget.ImageView.ScaleType.CENTER_CROP;setBackgroundColor(getColor(R.color.road_navy))
                        clipToOutline=true;outlineProvider=object:android.view.ViewOutlineProvider(){override fun getOutline(v:View,o:android.graphics.Outline){o.setRoundRect(0,0,v.width,v.height,dp(12).toFloat())}}
                        contentDescription="${poi.name} 대표 사진"}
                    detail.addView(image,LinearLayout.LayoutParams(-1,dp(170)).apply{bottomMargin=dp(8)})
                    PoiImages.load(this,poi.imageUrl){bmp-> if(bmp!=null) image.setImageBitmap(bmp) else image.visibility=View.GONE}
                }
                detail.addView(TextView(this).apply{
                    text=poi.description.ifBlank{"소개 정보가 없어요."};textSize=16f;setTextColor(getColor(R.color.white));alpha=.9f
                    maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END;setLineSpacing(dp(2).toFloat(),1f)})
                if(poi.address.isNotBlank() && !poi.demo) detail.addView(TextView(this).apply{text=poi.address;textSize=14f;setTextColor(getColor(R.color.road_muted));maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,dp(4),0,0)})
                box.addView(detail)
            }
            // 버튼 두 개: 지도에서 보기 / 경로에 추가
            val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(dp(4),0,dp(4),dp(10))}
            actions.addView(MaterialButton(this,null,com.google.android.material.R.attr.materialButtonStyle).apply{
                text=if(on)"지도 보는 중" else "지도에서 보기";textSize=16f;isEnabled=!restricted
                backgroundTintList=ColorStateList.valueOf(getColor(if(on)R.color.road_teal else R.color.road_navy));setTextColor(getColor(R.color.white))
                setOnClickListener{poiHighlight=i;showPoisEverywhere(pois,poiHighlight);comparisonKey="";render()}
            },LinearLayout.LayoutParams(0,dp(56),1f).apply{marginEnd=dp(8)})
            actions.addView(MaterialButton(this,null,com.google.android.material.R.attr.materialButtonStyle).apply{
                text="경로에 추가";textSize=16f;isEnabled=!restricted
                backgroundTintList=ColorStateList.valueOf(android.graphics.Color.parseColor(PLACE))
                setOnClickListener{
                    if(koks.size>=KokPoints.MAX){toast("경유지는 최대 ${KokPoints.MAX}곳이에요. 위 목록에서 하나 지우고 추가해주세요");return@setOnClickListener}
                    closePois();addKok(KokPoint(poi.name,poi.point,place=true))
                }},LinearLayout.LayoutParams(0,dp(56),1f))
            box.addView(actions)
        }
        box.addView(TextView(this).apply{text="추가 시간은 경로에서 떨어진 거리로 계산한 추정치예요";textSize=14f;setTextColor(getColor(R.color.road_muted));setPadding(0,dp(6),0,0)})
        return box.also{it.layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)}}
    }

    /** 지도에 명소 표시 (강조 포함) */
    private fun showPoisEverywhere(items:List<Poi>,highlight:Int?){ map?.showPois(items,highlight) }

    // ───────────────────────── 안내 + 기록 ─────────────────────────
    private fun confirmGuidance(){
        val target=selectedPlace ?: return
        if(drivingRestricted())return
        val useKoks=if(routes.size==2 && chosen==1)routes[1].koks else emptyList()
        val prediction=energies.getOrNull(if(routes.size==2)chosen else -1)?.kwh
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),0)}
        // 주행 기록은 주행·분석 화면과 함께 숨김 (재설계 후 다시 켬)
        val record=CheckBox(this).apply{text="주행 기록도 함께 시작";isChecked=false;visibility=View.GONE};box.addView(record)
        val message=if(useKoks.isNotEmpty())"${useKoks.joinToString(", "){it.name}}을(를) 경유지로 차량 기본 내비에 전달합니다.\n내비가 경로를 다시 탐색하므로 미리보기와 실제 안내 경로가 조금 다를 수 있어요."
            else "${target.name}을(를) 차량 기본 내비에 목적지로 전달합니다."
        show(AlertDialog.Builder(this).setTitle("${target.name}(으)로 안내").setMessage(message).setView(box)
            .setNegativeButton("취소",null).setPositiveButton("안내 시작"){_,_->
                if(drivingRestricted())return@setPositiveButton
                session?.requestKokRoute(useKoks,target)
                if(record.isChecked)requestRecording(prediction,crossing(useKoks))
            }.create())
    }
    private fun requestRecording(prediction:Double?,crossing:String) {
        val s=session ?: return
        if(drivingRestricted() || s.recording) return
        pendingPrediction=prediction;pendingCrossing=crossing
        val permissions=mutableListOf<String>()
        if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) permissions+=listOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) permissions+=Manifest.permission.POST_NOTIFICATIONS
        if(permissions.isNotEmpty()) { pendingStart=true; requestPermissions(permissions.toTypedArray(),RECORD); return }
        if(!getSystemService(LocationManager::class.java).isLocationEnabled) {
            toast("주행 기록을 위해 시스템 위치를 켜주세요")
            runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }; return
        }
        s.startRecording(selectedPlace,pendingCrossing,pendingPrediction)
    }
    @Deprecated("Android callback")
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==SPEED) session?.refreshSpeed()
        if(code==MAP_LOCATION){updateLocationLifecycle();if(deviceLocation.permitted())locate()else{pendingLocate=false;toast("위치 권한이 없어 내 위치를 표시할 수 없습니다")}}
        if(code==RECORD && pendingStart) {
            pendingStart=false
            if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) {
                session?.takeIf { !drivingRestricted() }?.startRecording(selectedPlace,pendingCrossing,pendingPrediction)
            } else toast("위치 권한이 없어 주행 기록을 시작하지 않았습니다")
        }
    }

    // ───────────────────────── 렌더링 ─────────────────────────
    private var autoCompared=false
    private fun render(){
        val s=session
        if(!autoCompared && s!=null && selectedPlace!=null && routes.isEmpty() && !comparing){autoCompared=true;handler.post{compare()}}
        val _s=s;val restricted=drivingRestricted();val ready=s?.naviState?.connected==true
        if(importDestinationAt>0 && s!=null && s.naviState.destinationAtMs>=importDestinationAt){importDestinationAt=0;s.naviState.destination?.let{choosePlace(Place(it.name,GeoPoint(it.latitude,it.longitude),it.address))} ?: toast("차량 내비에 설정된 목적지가 없습니다")}
        if(restricted && !lastRestricted){dialog?.dismiss();if(panel==2){showPanel(1);return}};lastRestricted=restricted
        val known=s?.restrictionAvailable==true
        text(R.id.global_status,when{!known->"차량 상태 확인 중";restricted->"주행 중 · 조작이 제한돼요";s?.recording==true->"정차 중 · 주행 기록 중";else->"정차 중"})
        findViewById<View>(R.id.global_status_dot).background=getDrawable(when{
            !known->R.drawable.status_dot_muted
            restricted->R.drawable.status_dot_warn
            else->R.drawable.status_dot_good
        })
        text(R.id.route_mode,RouteSettings.mode(this).label)
        listOf(R.id.pick_destination,R.id.clear_destination,R.id.compare_routes,R.id.enable_map,R.id.report_history,R.id.route_mode).forEach{findViewById<View>(it).isEnabled=!restricted}
        val o=origin()
        text(R.id.origin_text,o?.name ?: "현재 위치 확인 중")
        text(R.id.pick_destination,selectedPlace?.name ?: "목적지 검색")
        findViewById<View>(R.id.clear_destination).visibility=if(selectedPlace==null)View.GONE else View.VISIBLE
        renderKoks(restricted);renderComparison(restricted)
        findViewById<View>(R.id.compare_routes).isEnabled=!restricted && !comparing && selectedPlace!=null
        text(R.id.compare_routes,if(routes.isEmpty())"경로 보기" else "경로 다시 계산")
        findViewById<View>(R.id.start_guidance).isEnabled=!restricted && ready && selectedPlace!=null && s?.naviState?.requestPending!=true
        text(R.id.start_guidance,when{s?.naviState?.requestPending==true->"내비에 요청 중…";routes.size==2 && chosen==1->"길콕 경로로 안내 시작";else->"추천 경로로 안내 시작"})
        val result=s?.naviState?.lastRouteResult.orEmpty()
        text(R.id.route_status,when{!ready->"차량 기본 내비에 연결하고 있습니다";result.contains("시간 초과")->"내비 응답이 없습니다. 다시 시도해주세요";result.contains("완료")->"기본 내비가 경로 탐색을 완료했습니다";result.contains("오류")->"내비 요청에 실패했습니다. 설정 › 연결 상태를 확인하세요";else->"안내는 차량 기본 내비가 맡습니다"})
        findViewById<View>(R.id.zoom_in).isEnabled=!restricted && map?.ready==true && (map?.zoom ?: 18.0)<18
        findViewById<View>(R.id.zoom_out).isEnabled=!restricted && map?.ready==true && (map?.zoom ?: 4.0)>4
        findViewById<View>(R.id.recenter_map).isEnabled=!restricted
        val point=currentPoint()
        if(pendingLocate && point!=null && !restricted){pendingLocate=false;map?.focus(point,true)}
        map?.setRestricted(restricted)
        if(panel==0)map?.display(point ?: o?.point,selectedPlace,koks)
        val statusView=findViewById<View>(R.id.map_status)
        val mapStatus=when{mapError.isNotBlank()->mapError;!ExternalServices.allowed(this)->"";restricted->"주행 중에는 지도 조작이 제한됩니다";selectedPlace!=null && koks.isEmpty()->"지나고 싶은 길을 지도에서 탭하세요";point==null && o!=null->"데모 출발지 기준으로 표시 중";point==null->"현재 위치를 확인하고 있습니다";else->""}
        statusView.visibility=if(mapStatus.isBlank())View.GONE else View.VISIBLE;text(R.id.map_status,mapStatus)
        // 주행 화면
        val snapshot=s?.snapshot ?: VehicleSnapshot()
        text(R.id.live_speed,snapshot.speedMps?.let{"${fmt(abs(it)*3.6,0)} km/h"} ?: "—")
        text(R.id.live_efficiency,snapshot.evKmPerKwh?.let{"${fmt(it)} km/kWh"} ?: snapshot.fuelLPer100Km?.let{"${fmt(it)} L/100km"} ?: "제공되지 않음")
        text(R.id.live_odometer,snapshot.odometerKm?.let{"${fmt(it)} km"} ?: "제공되지 않음")
        text(R.id.live_brake,snapshot.brakePressed?.let{if(it)"밟음" else "놓음"} ?: "제공되지 않음")
        text(R.id.live_mode,snapshot.drivingMode ?: "제공되지 않음")
        val active=s?.store?.active
        text(R.id.trip_state,when{s?.recording==true->"기록 중 · ${active?.samples?.size ?: 0}초 분량 · ${active?.destination ?: "목적지 없음"}";active!=null->"중단된 기록이 있습니다. 이어서 기록할 수 있어요";else->"경로 화면에서 안내를 시작하면 자동으로 기록돼요"})
        text(R.id.trip_prediction,active?.let{PredictionStore.get(this,it.id)}?.let{"출발 전 예측 ${fmt(it,2)} kWh · 도착 후 실제와 비교해요"} ?: "")
        findViewById<View>(R.id.start_trip).visibility=if(s?.recording==true)View.GONE else View.VISIBLE
        findViewById<View>(R.id.stop_trip).visibility=if(active!=null)View.VISIBLE else View.GONE
        findViewById<View>(R.id.start_trip).isEnabled=!restricted && s!=null
        text(R.id.start_trip,if(active!=null)"중단된 기록 이어서" else "주행 기록만 시작")
        text(R.id.service_status,s?.store?.lastSaveError ?: when{s==null->"차량 연결 중";s.recording->"앱을 벗어나도 기록이 이어집니다";snapshot.speedMps==null->"차량 속도 신호를 기다리고 있습니다";else->"차량 데이터 연결됨"})
        if(panel==2)renderReport()
    }

    // ───────────────────────── 설정 ─────────────────────────
    private fun showSettings(){
        val items=arrayOf("경로 데이터 (데모 / 실시간)","지도 이용 및 동의","개인정보 처리 안내","차량 모델 설정","차량 내비 목적지 가져오기","기본 내비 열기","연결 상태 확인","주행 기록 삭제","개발용 지도 서버 설정")
        show(AlertDialog.Builder(this).setTitle("설정 · 버전 ${BuildConfig.VERSION_NAME}").setItems(items){_,i->when(i){
            0->showRouteSource();1->showPrivacy();2->showPrivacyDocument();3->showProfile()
            4->{if(!drivingRestricted()){importDestinationAt=System.currentTimeMillis();if(session?.naviState?.connected==true)session?.navigation?.refresh()else session?.navigation?.reconnect()}}
            5->session?.navigation?.openNavigation();6->showDiagnostics()
            7->if(!drivingRestricted())show(AlertDialog.Builder(this).setTitle("완료한 기록을 삭제할까요?").setMessage("완료한 주행 기록이 기기에서 삭제됩니다.").setNegativeButton("취소",null).setPositiveButton("삭제"){_,_->session?.store?.clearHistory();reportId=null;reportKey="";render()}.create())
            8->showEndpoints()}}.setNegativeButton("닫기",null).create())
    }
    private fun showRouteSource(){
        if(drivingRestricted())return
        var mode=RouteSettings.mode(this).ordinal
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),0)}
        val key=EditText(this).apply{hint="카카오모빌리티 REST API 키 (선택)";setText(RouteSettings.kakaoKey(this@MainActivity));inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;setSingleLine(true)};box.addView(key)
        show(AlertDialog.Builder(this).setTitle("경로 데이터")
            .setSingleChoiceItems(RouteSettings.Mode.entries.map{it.label}.toTypedArray(),mode){_,i->mode=i}
            .setView(box).setNegativeButton("취소",null).setPositiveButton("저장"){_,_->RouteSettings.save(this,RouteSettings.Mode.entries[mode],key.text.toString());clearRoutes();render()}.create())
    }
    private fun showDiagnostics(){
        val s=session
        show(AlertDialog.Builder(this).setTitle("연결 상태").setMessage("경로 데이터: ${RouteSettings.mode(this).label}\n지도: ${if(map?.ready==true)"준비됨" else "대기 중"}\n차량 내비: ${if(s?.naviState?.connected==true)"연결됨" else "연결 대기"}\n차량 위치: ${if(s?.let{NaviLocationProvider{it.naviState}.latestLocation()}!=null)"수신 중" else "수신 대기"}\nGPS 권한: ${if(deviceLocation.permitted())"허용" else "미허용"}\n주행 상태: ${if(s?.restrictionAvailable==true)if(s.restricted)"주행 중" else "정차" else "미확인"}\n\n${s?.naviState?.message.orEmpty()}\n${s?.naviState?.lastRouteResult.orEmpty()}")
            .setNegativeButton("닫기",null).setPositiveButton("다시 연결"){_,_->session?.navigation?.reconnect();deviceLocation.stop();updateLocationLifecycle()}.create())
    }

    // ───────────────────────── 분석 ─────────────────────────
    private fun renderReport() {
        val history=session?.store?.recent().orEmpty()
        val trip=history.firstOrNull { it.id==reportId } ?: history.firstOrNull()
        val key="${trip?.id}:${history.size}:${profile()}"
        if(key==reportKey) return
        reportKey=key
        if(trip==null) {
            text(R.id.report_pred_value,"—");text(R.id.report_pred_detail,"경로를 비교하고 안내를 시작하면, 도착 후 여기서 예측과 실제를 비교해요.")
            text(R.id.report_accel_chip,"급가속 —");text(R.id.report_brake_chip,"급감속 —")
            text(R.id.report_text,"완료한 주행 기록이 없습니다.");text(R.id.energy_text,"");text(R.id.report_comparison,"");return
        }
        val report=TripAnalyzer.analyze(trip)
        // 예측 vs 실제
        val predicted=PredictionStore.get(this,trip.id);val actual=RouteEnergyEstimator.actual(trip)
        when{
            predicted==null->{text(R.id.report_pred_value,actual?.let{"실제 ${fmt(it.kwh,2)} kWh"} ?: "—");text(R.id.report_pred_detail,"이 주행은 출발 전 예측이 없습니다.\n경로 비교 후 안내를 시작하면 예측과 비교할 수 있어요.")}
            actual==null->{text(R.id.report_pred_value,"예측 ${fmt(predicted,2)} kWh");text(R.id.report_pred_detail,"차량 전비 또는 거리 신호가 부족해 실제값을 계산하지 못했습니다.")}
            else->{
                val diff=(actual.kwh/predicted-1)*100
                text(R.id.report_pred_value,"${fmt(predicted,2)} → ${fmt(actual.kwh,2)} kWh")
                val habit=report.hardAccelerations+report.hardBrakes
                val reason=when{abs(diff)<5->"예측과 거의 같았어요.";diff>0 && habit>0->"예측보다 ${fmt(diff,0)}% 더 썼어요. 급가속 ${report.hardAccelerations}회·급감속 ${report.hardBrakes}회가 주요 원인으로 보여요.";diff>0->"예측보다 ${fmt(diff,0)}% 더 썼어요. 교통 상황이나 공조 사용 같은 경로 외 요인일 수 있어요.";else->"예측보다 ${fmt(-diff,0)}% 덜 썼어요. 부드러운 운전이 반영됐어요."}
                text(R.id.report_pred_detail,"$reason\n\n실제값: 이동 ${fmt(actual.distanceKm)} km ÷ 차량 전비 ${fmt(actual.kmPerKwh)} km/kWh\n이 결과는 다음 예측의 ‘내 주행 기록 기반’ 전비에 반영돼요.")
            }
        }
        text(R.id.report_accel_chip,"급가속 ${report.hardAccelerations}회")
        text(R.id.report_brake_chip,"급감속 ${report.hardBrakes}회")
        val withSpeed=trip.samples.count { it.value.speedMps!=null }
        val suggestion=when { withSpeed<2->"속도 데이터가 부족해 습관을 평가할 수 없어요."; report.hardBrakes>0->"앞차와 간격을 여유 있게 두면 급감속을 줄일 수 있어요."; report.hardAccelerations>0->"출발할 때 가속을 조금 더 부드럽게 해보세요."; else->"큰 가감속 없이 안정적으로 달렸어요." }
        text(R.id.report_text,"${date(trip.startMs)} · ${trip.destination ?: "목적지 미설정"}\n${if(trip.crossing?.startsWith("kok:")==true)"콕 지점 ${trip.crossing!!.removePrefix("kok:").split("|").size}곳 경유" else "콕 지점 없이 주행"}\n\n${report.durationMinutes}분 · ${report.distanceKm?.let { "${fmt(it)} km" } ?: "거리 측정 불가"}\n속도 변동 ${report.speedVariabilityPercent?.let { "$it%" } ?: "계산 불가"}${if(trip.interrupted) "\n(중단·복구 이력이 있는 기록)" else ""}\n\n$suggestion")
        val estimate=EnergyModel.estimate(trip,profile())
        text(R.id.energy_text,estimate?.let {
            "지금처럼   ${fmt(it.observedScenarioKwh,3)} kWh\n부드럽게   ${fmt(it.smoothScenarioKwh,3)} kWh   (${fmt(it.changePercent)}% 차이)\n\n물리 모델로 같은 정지 지점·거리를 유지한 채 속도만 완만하게 바꿔 본 가상 계산입니다. 계산 구간 ${fmt(it.coveredMeters/1000)} km · 포함률 ${it.coveragePercent}%"
        } ?: "속도 데이터가 30초·100m 이상, 포함률 80% 이상일 때 계산해요.")
        val previous=history.firstOrNull { it.id!=trip.id && it.startMs<trip.startMs && TripComparison.similar(trip,it) }
        text(R.id.report_comparison,previous?.let {
            val before=EnergyModel.estimate(it,profile())!!; val now=estimate!!
            val change=(now.observedScenarioKwh/now.coveredMeters/(before.observedScenarioKwh/before.coveredMeters)-1)*100
            "${date(it.startMs)} 주행 대비 거리당 소비 ${if(change>=0)"+" else ""}${fmt(change)}%\n출발·도착·콕 지점이 같고 거리·시간이 비슷한 기록끼리 비교했어요."
        } ?: "아직 비교할 비슷한 주행이 없어요. 같은 길을 다시 달리면 변화를 보여드려요.")
    }

    private fun showSearch() {
        if(drivingRestricted()) return
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(24),dp(8),dp(24),dp(8)) }
        val input=EditText(this).apply { hint="장소 이름 · 직접 검색"; setSingleLine(true) }
        val status=TextView(this).apply { text="검색어만 외부 검색 서버에 전송됩니다. © OpenStreetMap contributors"; textSize=18f }
        val results=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val submit=Button(this).apply { text="장소 검색" }
        box.addView(input); box.addView(submit); box.addView(status); box.addView(results)
        fun resultButtons(places:List<Place>) {
            results.removeAllViews()
            places.forEach { place -> results.addView(Button(this).apply { text=place.name; minHeight=dp(64); setOnClickListener { choosePlace(place); dialog?.dismiss() } }) }
        }
        resultButtons(DemoPlaces.all)
        val scroll=ScrollView(this).apply { addView(box) }
        val value=AlertDialog.Builder(this).setTitle("목적지 선택").setView(scroll).setNegativeButton("닫기",null)
            .setNeutralButton("좌표 입력 · 개발용") { _,_->coordinateDialog() }.create()
        submit.setOnClickListener {
            if(drivingRestricted()) return@setOnClickListener
            if(!ExternalServices.allowed(this)) { toast("외부 장소 검색은 지도 이용 동의 후 사용할 수 있습니다"); return@setOnClickListener }
            submit.isEnabled=false; status.text="검색 중"
            search.search(input.text.toString()) { result ->
                if(isFinishing || !resumed || !value.isShowing) return@search
                submit.isEnabled=true
                result.onSuccess { places -> status.text=if(places.isEmpty()) "검색 결과가 없습니다. 다른 이름으로 검색하세요" else "장소를 선택하세요 · © OpenStreetMap contributors"; resultButtons(places) }
                    .onFailure { status.text=it.message ?: "검색에 실패했습니다" }
            }
        }
        show(value)
    }
    private fun coordinateDialog() {
        if(drivingRestricted()) return
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(24),0,dp(24),0) }
        fun field(hint:String)=EditText(this).apply { this.hint=hint; setSingleLine(true); box.addView(this) }
        val name=field("목적지 이름"); val lat=field("위도"); val lon=field("경도")
        val d=AlertDialog.Builder(this).setTitle("좌표 입력 · 개발용").setView(box).setNegativeButton("취소",null).setPositiveButton("선택",null).create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val point=GeoPoint(lat.text.toString().toDoubleOrNull() ?: Double.NaN,lon.text.toString().toDoubleOrNull() ?: Double.NaN)
            if(!point.valid || name.text.isBlank()) lat.error="이름과 유효한 좌표를 입력하세요"
            else { choosePlace(Place(name.text.toString(),point)); d.dismiss() }
        } }
        show(d)
    }
    private fun showPrivacy() {
        if(drivingRestricted()) return
        val enabled=ExternalServices.allowed(this)
        val value=AlertDialog.Builder(this).setTitle("외부 지도 이용")
            .setMessage("지도 화면 영역과 IP 주소는 OpenStreetMap 타일 서버에, 직접 입력한 검색어는 Nominatim 검색 서버에 전송됩니다. 지도에 현재 위치를 표시하면 지도 영역을 통해 대략적인 위치가 전달될 수 있습니다. 주행 기록은 외부로 전송하지 않습니다.\n\n동의는 선택 사항이며 여기서 철회할 수 있습니다. 공용 서비스는 시제품 소규모 시험용으로 사용합니다.")
            .setNegativeButton("닫기",null).setNeutralButton("서비스 설정") { _,_->showEndpoints() }
            .setPositiveButton(if(enabled) "동의 철회" else "동의하고 지도 사용") { _,_->
                ExternalServices.preferences(this).edit().putBoolean("external_maps",!enabled).apply()
                if(enabled) {
                    map?.destroy(); map=null; findViewById<FrameLayout>(R.id.map_host).removeAllViews()
                    findViewById<View>(R.id.map_placeholder).visibility=View.VISIBLE
                    text(R.id.map_status,"외부 지도 이용 동의를 철회했습니다")
                    ExternalServices.client(this).dispatcher.cancelAll()
                } else createMap()
            }.create()
        show(value)
    }
    private fun showEndpoints() {
        if(drivingRestricted()) return
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(24),0,dp(24),0) }
        fun field(label:String,value:String)=EditText(this).apply { hint=label; setText(value); textSize=16f; box.addView(this) }
        val tiles=field("타일 URL · {z}/{x}/{y}",ExternalServices.tileUrl(this))
        val url=field("검색 API · Nominatim 호환",ExternalServices.searchUrl(this))
        val d=AlertDialog.Builder(this).setTitle("외부 서비스 · 개발 설정").setMessage("자체 또는 계약한 서비스로 교체할 수 있습니다. HTTPS만 허용합니다. 지도 출처는 OpenStreetMap 기반 서비스에 맞춰져 있습니다.")
            .setView(box).setNegativeButton("취소",null).setPositiveButton("저장",null).create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val a=tiles.text.toString(); val b=url.text.toString()
            if(!a.startsWith("https://") || !b.startsWith("https://") || !a.contains("{z}") || !a.contains("{x}") || !a.contains("{y}")) { tiles.error="HTTPS 타일 템플릿과 검색 주소를 확인하세요"; return@setOnClickListener }
            ExternalServices.preferences(this).edit().putString("tiles",a).putString("search",b).apply()
            map?.destroy(); map=null; findViewById<FrameLayout>(R.id.map_host).removeAllViews(); createMap(); d.dismiss()
        } }
        show(d)
    }
    private fun showPrivacyDocument() {
        if(drivingRestricted()) return
        val label=TextView(this).apply {
            text=assets.open("privacy.txt").bufferedReader().use { it.readText() }; textSize=20f
            setPadding(dp(24),dp(12),dp(24),dp(12)); setTextIsSelectable(true)
            android.text.util.Linkify.addLinks(this,android.text.util.Linkify.WEB_URLS)
            movementMethod=android.text.method.LinkMovementMethod.getInstance()
        }
        show(AlertDialog.Builder(this).setTitle("개인정보 처리 안내").setView(ScrollView(this).apply { addView(label) }).setPositiveButton("닫기",null).create())
    }
    private fun profile():VehicleProfile {
        val p=getSharedPreferences("vehicle",MODE_PRIVATE)
        return VehicleProfile(massKg=p.getFloat("mass",1700f).toDouble(),regenerationEfficiency=p.getFloat("regen",.6f).toDouble())
    }
    private fun showProfile() {
        if(drivingRestricted()) return
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(24),0,dp(24),0) }
        val mass=EditText(this).apply { hint="차량 질량 kg (700~4000)"; setText(fmt(profile().massKg,0)); inputType=InputType.TYPE_CLASS_NUMBER; box.addView(this) }
        val regen=EditText(this).apply { hint="회생 효율 % (0~90)"; setText(fmt(profile().regenerationEfficiency*100,0)); inputType=InputType.TYPE_CLASS_NUMBER; box.addView(this) }
        val d=AlertDialog.Builder(this).setTitle("가상 에너지 모델 설정").setMessage("EV 모델 · 평지·무풍\nCdA 0.65m² · 구름저항 0.01 · 구동효율 90% · 보조전력 500W\n차량별 실측 보정이 없는 시험 모델입니다.")
            .setView(box).setNegativeButton("취소",null).setPositiveButton("적용",null).create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val m=mass.text.toString().toFloatOrNull(); val r=regen.text.toString().toFloatOrNull()
            if(m==null || r==null || m !in 700f..4000f || r !in 0f..90f) { mass.error="입력 범위를 확인하세요" }
            else { getSharedPreferences("vehicle",MODE_PRIVATE).edit().putFloat("mass",m).putFloat("regen",r/100).apply(); reportKey=""; renderReport(); d.dismiss() }
        } }; show(d)
    }
    private fun showHistory() {
        val history=session?.store?.recent().orEmpty()
        if(history.isEmpty()) { toast("완료한 기록이 없습니다"); return }
        show(AlertDialog.Builder(this).setTitle("주행 기록").setItems(history.map { "${date(it.startMs)} · ${it.destination ?: "목적지 없음"}" }.toTypedArray()) { _,i -> reportId=history[i].id; reportKey=""; renderReport() }.setNegativeButton("닫기",null).create())
    }
    private fun date(time:Long)=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT,Locale.KOREA).format(Date(time))
    companion object { private const val UNNAMED="선택한 지점"; private const val SPEED=101; private const val RECORD=102; private const val MAP_LOCATION=103 }
}
