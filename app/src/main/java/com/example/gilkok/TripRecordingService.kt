package com.example.gilkok

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** One owner for vehicle listeners, NaviHelper and trip storage, shared with the bound screen. */
class TripRecordingService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@TripRecordingService }
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val observers = linkedSetOf<() -> Unit>()
    lateinit var store: TripStore; private set
    lateinit var navigation: NaviClient; private set
    private lateinit var telemetry: VehicleTelemetry
    var snapshot = VehicleSnapshot(); private set
    var naviState = NaviState(); private set
    var restricted = true; private set
    var restrictionAvailable=false;private set
    var status = "차량 연결 중"; private set
    var recording = false; private set
    private var running = false
    private var bound = false
    private var ticks = 0
    private var holdUntil=0L
    private val ticker = object : Runnable {
        override fun run() {
            if(!running) return
            telemetry.pollSpeed()
            if(ticks++ % 5 == 0) navigation.refresh()
            if(recording) {
                val now = System.currentTimeMillis()
                val point = naviState.location?.takeIf { now-naviState.locationAtMs in 0..15_000 }?.let { GeoPoint(it.latitude,it.longitude) }?.takeIf { it.valid }
                store.append(snapshot.copy(
                    speedMps=snapshot.speedMps?.takeIf { now-snapshot.speedAtMs in 0..5000 },
                    latitude=point?.latitude,longitude=point?.longitude))
                if((store.active?.samples?.size ?: 0)>=21_600) { status="6시간 기록 한도에 도달해 저장했습니다"; finishRecording() }
            }
            notifyObservers()
            handler.postDelayed(this,1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        store=TripStore.get(this)
        store.active?.interrupted=true
        navigation=NaviClient(this) { naviState=it; notifyObservers() }
        telemetry=VehicleTelemetry(this,{ snapshot=it; notifyObservers() },{ status=it; notifyObservers() },{ restricted=it; notifyObservers() },{restrictionAvailable=it;notifyObservers()})
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"주행 기록",NotificationManager.IMPORTANCE_LOW))
    }
    private fun begin() {
        if(running) return
        running=true
        telemetry.start(); navigation.start(); handler.post(ticker)
    }
    override fun onBind(intent: Intent): IBinder { bound=true; begin(); return binder }
    override fun onRebind(intent: Intent) { bound=true; begin() }
    override fun onUnbind(intent: Intent): Boolean { bound=false; if(!recording && System.currentTimeMillis()>=holdUntil) stopSelf(); return true }
    fun observe(observer: () -> Unit) { observers+=observer; observer() }
    fun removeObserver(observer: () -> Unit) { observers-=observer }
    private fun notifyObservers() { observers.toList().forEach { it() } }
    fun refreshSpeed() = telemetry.refreshSpeed()
    fun startRecording(destination: Place?, crossing: String, predictedKwh: Double? = null) {
        if(recording || restricted) return
        val intent=Intent(this,TripRecordingService::class.java).setAction(START)
            .putExtra("name",destination?.name).putExtra("lat",destination?.point?.latitude ?: Double.NaN)
            .putExtra("lon",destination?.point?.longitude ?: Double.NaN).putExtra("crossing",crossing).putExtra("predicted",predictedKwh ?: Double.NaN)
        ContextCompat.startForegroundService(this,intent)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action==STOP) { finishRecording(); return START_NOT_STICKY }
        if(intent?.action==HOLD) {
            holdUntil=System.currentTimeMillis()+31_000
            handler.postDelayed({ if(!bound && !recording) stopSelf() },31_000)
            return START_NOT_STICKY
        }
        if(intent?.action!=START) { stopSelf(); return START_NOT_STICKY }
        try {
            val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop=PendingIntent.getService(this,1,Intent(this,TripRecordingService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)
            val notification=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("길콕 · 주행 기록 중").setContentText("차량 데이터와 경로를 기기 안에 기록합니다")
                .setContentIntent(open).setOngoing(true).addAction(0,"기록 종료",stop).build()
            ServiceCompat.startForeground(this,7,notification,if(Build.VERSION.SDK_INT>=29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            val point=GeoPoint(intent.getDoubleExtra("lat",Double.NaN),intent.getDoubleExtra("lon",Double.NaN)).takeIf { it.valid }
            val trip=store.start(intent.getStringExtra("name"),point,intent.getStringExtra("crossing"))
            intent.getDoubleExtra("predicted",Double.NaN).takeIf { it.isFinite() && it>0 }?.let { PredictionStore.save(this,trip.id,it) }
            recording=true; begin(); notifyObservers()
        } catch(e: Exception) {
            status="기록 시작 실패 · 위치 권한과 위치 설정을 확인하세요"
            recording=false
            stopForeground(STOP_FOREGROUND_REMOVE)
            notifyObservers()
            if(!bound) stopSelf()
        }
        return START_NOT_STICKY
    }
    fun finishRecording() {
        store.finish(); recording=false
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifyObservers()
        stopSelf()
    }
    fun requestRoute(bridge:BridgeOption,target:Place,direction:CrossingDirection) {
        if(restricted || naviState.requestPending) return
        startService(Intent(this,TripRecordingService::class.java).setAction(HOLD))
        navigation.requestVia(bridge,target,direction)
    }
    fun requestKokRoute(koks:List<KokPoint>,target:Place) {
        if(koks.isEmpty()) { requestDestination(target); return }
        if(restricted || naviState.requestPending) return
        startService(Intent(this,TripRecordingService::class.java).setAction(HOLD))
        navigation.requestViaKoks(target,koks)
    }
    fun requestDestination(target:Place) {
        if(restricted || naviState.requestPending)return
        startService(Intent(this,TripRecordingService::class.java).setAction(HOLD))
        navigation.requestDestination(target)
    }
    override fun onDestroy() {
        running=false; handler.removeCallbacksAndMessages(null)
        if(recording) store.active?.interrupted=true
        store.close(); telemetry.release(); navigation.stop(); observers.clear()
        super.onDestroy()
    }
    companion object {
        const val START="com.example.gilkok.RECORD"
        const val STOP="com.example.gilkok.STOP"
        private const val HOLD="com.example.gilkok.HOLD_ROUTE"
        private const val CHANNEL="trip-recording"
    }
}
