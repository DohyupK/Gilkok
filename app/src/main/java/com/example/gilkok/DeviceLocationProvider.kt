package com.example.gilkok

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock

/** Optional platform GNSS fallback for map display; no GMS or network geolocation SDK. */
class DeviceLocationProvider(context:Context,private val onChanged:()->Unit) : LocationProvider {
    private val app=context.applicationContext
    private val manager=app.getSystemService(LocationManager::class.java)
    private var running=false
    private var latest:Location?=null
    private val listener=object:LocationListener {
        override fun onLocationChanged(location:Location) { latest=location;onChanged() }
        override fun onProviderEnabled(provider:String) { onChanged() }
        override fun onProviderDisabled(provider:String) { onChanged() }
        @Deprecated("Legacy location callback") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?) {}
    }
    fun permitted()=app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED || app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    fun start() {
        if(running || !permitted() || !manager.isLocationEnabled) return
        running=true
        for(provider in listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER)) {
            if(!manager.isProviderEnabled(provider)) continue
            try {
                manager.getLastKnownLocation(provider)?.let { if(latest==null || it.elapsedRealtimeNanos>latest!!.elapsedRealtimeNanos) latest=it }
                manager.requestLocationUpdates(provider,2000L,2f,listener,Looper.getMainLooper())
            } catch(_:SecurityException) {} catch(_:IllegalArgumentException) {}
        }
    }
    override fun latestLocation():GeoPoint? {
        val fix=latest ?: return null
        if((SystemClock.elapsedRealtimeNanos()-fix.elapsedRealtimeNanos)/1_000_000 !in 0..30_000) return null
        return GeoPoint(fix.latitude,fix.longitude).takeIf { it.valid }
    }
    fun enabled()=manager.isLocationEnabled
    fun stop() { if(running) { manager.removeUpdates(listener);running=false } }
}
