package com.example.gilkok

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper

data class BridgeOption(val name: String, val latitude: Double, val longitude: Double) {
    companion object {
        // Prototype bridge center points; routing to the correct carriageway needs field validation.
        val YEONGDONG = BridgeOption("영동대교", 37.5299444, 127.0570556)
        val CHEONGDAM = BridgeOption("청담대교", 37.5261, 127.0642)
    }
}

data class NaviState(
    val connected: Boolean = false,
    val destination: DestinationInfo? = null,
    val destinationAtMs: Long = 0L,
    val requestPending: Boolean = false,
    val lastRouteResult: String = "",
    val location: CurrentLocationInfo? = null,
    val locationAtMs: Long = 0L,
    val routeStart: RouteStartInfo? = null,
    val message: String = "내비게이션 연결 중",
)

class NaviClient(private val context: Context, private val onState: (NaviState) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null
    private var active = false
    private var generation = 0
    private var requestSerial = 0
    private var state = NaviState()
    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val valid = destinationInfo.latitude in -90.0..90.0 &&
                destinationInfo.longitude in -180.0..180.0 &&
                (destinationInfo.latitude != 0.0 || destinationInfo.longitude != 0.0)
            if (valid) {
                change { copy(destination = destinationInfo, destinationAtMs=System.currentTimeMillis(), message = "목적지 정보를 받았습니다") }
            } else {
                change { copy(destination = null, destinationAtMs=System.currentTimeMillis(), message = "내비 목적지가 없습니다") }
            }
        }
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) =
            change { copy(location = currentLocationInfo, locationAtMs=System.currentTimeMillis()) }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) =
            change { copy(routeStart = routeStartInfo, requestPending=true, message = "선택한 경로 탐색 중") }
        override fun onRoutingCompleted() {
            android.util.Log.i("GilkokNavi","Routing completed")
            change { copy(requestPending=false, lastRouteResult="최근 요청 · 경로 탐색 완료", message = "경로 탐색 완료") }
            refresh()
        }
        override fun onDestinationChanged() { refresh() }
        override fun onRouteCancelled() = change { copy(destination = null, requestPending=false, routeStart = null, message = "경로 안내 종료") }
        override fun onError(errorCode: NaviErrorCode) {
            android.util.Log.w("GilkokNavi","Navigation error: ${errorCode.name}")
            change { copy(requestPending=false, lastRouteResult="최근 내비 오류 · ${errorCode.name}", message = "내비 오류: ${errorCode.name}") }
        }
    }

    fun start() {
        if (active) return
        active = true
        val token = ++generation
        onState(state)
        try {
            val nav = NaviHelper(context.applicationContext)
            helper = nav
            nav.initialize(
                    onConnected = {
                        main.post {
                            if (active && token == generation) {
                                nav.addListener(listener)
                                change { copy(connected = true, message = "내비게이션 연결됨") }
                                refresh()
                            }
                        }
                    },
                    onFailed = { error ->
                        if (active && token == generation) {
                            change { copy(message = "내비 연결 실패: ${error.message ?: error.javaClass.simpleName}") }
                        }
                    },
                )
        } catch (e: Exception) {
            change { copy(message = "내비 연결 실패: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    fun refresh() {
        if (!active) return
        if (!state.connected) return
        try {
            helper?.getDestinationInfo()
            helper?.getCurrentLocationInfo()
        } catch (e: Exception) {
            change { copy(message = "내비 조회 실패: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    fun requestVia(bridge:BridgeOption,selected:Place?=null,direction:CrossingDirection=CrossingDirection.NORTH) {
        val target=selected ?: state.destination?.let{Place(it.name,GeoPoint(it.latitude,it.longitude),it.address)}
        if(target==null){change{copy(message="목적지를 먼저 선택해주세요")};return}
        submitRoute(target,listOf(bridge.name to WaypointCatalog.get(context,bridge,direction)))
    }
    fun requestDestination(target:Place)=submitRoute(target,emptyList())
    /** NaviHelper 경유지는 최대 2개(FIRST, SECOND) → 콕 지점 그대로 전달 */
    fun requestViaKoks(target:Place,koks:List<KokPoint>)=submitRoute(target,koks.take(KokPoints.MAX).map { it.name to it.point })
    private fun submitRoute(target:Place,via:List<Pair<String,GeoPoint>>) {
        if(!active || !state.connected || !target.point.valid){change{copy(message="차량 내비 연결과 목적지를 확인해주세요")};return}
        val request=++requestSerial;val token=generation
        try {
            change{copy(requestPending=true,lastRouteResult="내비 응답 대기 중",message="차량 내비에 안내를 요청했습니다")}
            val route=RouteInfo(latitude=target.point.latitude,longitude=target.point.longitude,poiName=target.name,poiId="",address=target.address,poiSubId="")
            if(via.isEmpty())helper?.requestRoute(route)
            else {
                val waypoints=via.mapIndexed { i,(viaName,point) ->
                    RequestWaypointInfo(latitude=point.latitude,longitude=point.longitude,poiName=viaName,waypointIndex=if(i==0)WaypointIndex.FIRST else WaypointIndex.SECOND,poiId="",address=viaName,poiSubId="")
                }
                helper?.requestRouteWithWaypoints(RouteWithWaypointsRequest(route,waypoints))
            }
            main.postDelayed({if(active && token==generation && request==requestSerial && state.requestPending)change{copy(requestPending=false,lastRouteResult="최근 요청 · 응답 시간 초과",message="내비 응답을 받지 못했습니다. 다시 시도해주세요")}},30_000)
        }catch(e:Exception){change{copy(requestPending=false,lastRouteResult="최근 내비 오류 · 요청 실패",message="경로 요청 실패: ${e.message ?: e.javaClass.simpleName}")}}
    }

    fun openNavigation() {
        if (!active || !state.connected) {
            change { copy(message = "내비 연결 후 다시 시도하세요") }
            return
        }
        try {
            helper?.requestNavigationScreen(NavigationScreenRequest.Foreground)
            change { copy(message = "기본 내비 화면을 요청했습니다") }
        } catch (e: Exception) {
            change { copy(message = "기본 내비 열기 실패: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    fun stop() {
        if (!active) return
        active = false
        ++generation
        try { helper?.removeListener(listener) } catch (_: Exception) {}
        try { helper?.release() } catch (_: Exception) {}
        helper = null
        state = state.copy(connected = false)
    }

    private fun change(update: NaviState.() -> NaviState) {
        val token=generation
        val apply=Runnable { if(active && token==generation) { state=state.update(); onState(state) } }
        if(Looper.myLooper()==Looper.getMainLooper()) apply.run() else main.post(apply)
    }

    fun reconnect() { stop(); start() }
}
