package com.example.gilkok

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener
import ai.pleos.playground.vehicle.listener.EvBatteryEfficiencyListener
import ai.pleos.playground.vehicle.listener.FuelEconomyListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

/** Reads vehicle signals only while the app is visible. Every field is optional by design. */
class VehicleTelemetry(
    private val context: Context,
    private val onValue: (VehicleSnapshot) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onRestricted: (Boolean) -> Unit,
    private val onRestrictionAvailability:(Boolean)->Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var running = false
    private var generation = 0
    private var snapshot = VehicleSnapshot()
    private val subscribed = mutableSetOf<String>()

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) = update { copy(brakePressed = status) }
        override fun onFailed(e: Exception) = status("브레이크", e)
    }
    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) = update { copy(odometerKm = value) }
        override fun onFailed(e: Exception) = status("주행거리", e)
    }
    private val modeListener = object : DrivingModeStateListener {
        override fun onDrivingModeStateUpdated(state: ai.pleos.playground.vehicle.constant.control.DrivingModeType?) =
            update { copy(drivingMode = state?.name) }
        override fun onFailed(e: Exception) = status("주행 모드", e)
    }
    private val evListener = object : EvBatteryEfficiencyListener {
        override fun onEvBatteryEfficiencyUpdated(efficiency: Float?) = update { copy(evKmPerKwh = efficiency) }
        override fun onFailed(e: Exception) = status("전비", e)
    }
    private val fuelListener = object : FuelEconomyListener {
        override fun onFuelEconomyUpdated(efficiency: Float?) = update { copy(fuelLPer100Km = efficiency) }
        override fun onFailed(e: Exception) = status("연비", e)
    }
    private val speedListener = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED &&
                value.status == CarPropertyValue.STATUS_AVAILABLE) {
                update { copy(speedMps = (value.value as? Number)?.toFloat(), speedAtMs=System.currentTimeMillis()) }
            }
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            statusText("속도 신호를 읽을 수 없습니다")
        }
    }
    private val restrictionListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { restrictions ->
        main.post { if (running) { onRestrictionAvailability(true);onRestricted(isRestricted(restrictions)) } }
    }

    fun start() {
        if (running) return
        running = true
        onRestrictionAvailability(false)
        val currentGeneration = ++generation
        onValue(snapshot)
        try {
            if (vehicle == null) vehicle = Vehicle(context.applicationContext).also { it.initialize() }
            subscribeVehicle(currentGeneration)
        } catch (e: Exception) {
            status("Vehicle SDK 연결", e)
        }
        try {
            if (car == null) car = Car.createCar(context.applicationContext)
            val connectedCar = car ?: error("Car Service가 준비되지 않았습니다")
            properties = connectedCar.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            uxManager = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            uxManager?.let {
                onRestrictionAvailability(it.currentCarUxRestrictions!=null)
                onRestricted(isRestricted(it.currentCarUxRestrictions))
                it.registerListener(restrictionListener)
            } ?: onRestricted(true)
            refreshSpeed()
        } catch (e: Exception) {
            onRestrictionAvailability(false)
            onRestricted(true)
            status("AAOS 차량 서비스", e)
        }
    }

    fun refreshSpeed() {
        if (!running) return
        val manager = properties ?: return
        if (context.checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            statusText("속도 권한이 필요합니다")
            return
        }
        try {
            val speed = manager.getFloatProperty(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)
            update { copy(speedMps = speed, speedAtMs=System.currentTimeMillis()) }
            @Suppress("DEPRECATION")
            manager.registerCallback(speedListener, VehiclePropertyIds.PERF_VEHICLE_SPEED, 1.0f)
        } catch (e: Exception) {
            status("속도", e)
        }
    }

    private fun subscribeVehicle(token: Int) {
        val v = vehicle ?: return
        fun readable(config: CapabilityConfig) =
            config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE

        v.getBrake().checkBrakePedalStatusCapabilities({ config ->
            if (running && token == generation && readable(config)) {
                v.getBrake().getCurrentBrakePedalStatus({ value -> update { copy(brakePressed = value) } }, { status("브레이크", it) })
                v.getBrake().registerBrakePedalStatus(brakeListener)
                subscribed += "brake"
            } else if (!readable(config)) statusText("브레이크 신호 미지원")
        }, { status("브레이크 지원 확인", it) })

        v.getOdometer().checkCurrentDistanceDrivenCapabilities({ config ->
            if (running && token == generation && readable(config)) {
                v.getOdometer().getCurrentDistanceDriven({ value -> update { copy(odometerKm = value) } }, { status("주행거리", it) })
                v.getOdometer().registerDistanceDriven(odometerListener)
                subscribed += "odometer"
            }
        }, { status("주행거리 지원 확인", it) })

        v.getDrivingMode().checkDrivingModeStateCapabilities({ config ->
            if (running && token == generation && readable(config)) {
                v.getDrivingMode().getCurrentDrivingModeState({ value -> update { copy(drivingMode = value?.name) } }, { status("주행 모드", it) })
                v.getDrivingMode().registerDrivingModeState(modeListener)
                subscribed += "mode"
            }
        }, { status("주행 모드 지원 확인", it) })

        v.getEvBattery().checkCurrentEvEfficiencyCapabilities({ config ->
            if (running && token == generation && readable(config)) {
                v.getEvBattery().getCurrentEvEfficiency({ value -> update { copy(evKmPerKwh = value) } }, { status("전비", it) })
                v.getEvBattery().registerCurrentEvEfficiency(evListener)
                subscribed += "ev"
            }
        }, { status("전비 지원 확인", it) })

        v.getFuel().checkCurrentFuelEconomyCapabilities({ config ->
            if (running && token == generation && readable(config)) {
                v.getFuel().getCurrentFuelEconomy({ value -> update { copy(fuelLPer100Km = value) } }, { status("연비", it) })
                v.getFuel().registerCurrentFuelEconomy(fuelListener)
                subscribed += "fuel"
            }
        }, { status("연비 지원 확인", it) })
    }

    fun pollSpeed() {
        if(!running || context.checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) return
        try {
            val speed = properties?.getFloatProperty(VehiclePropertyIds.PERF_VEHICLE_SPEED,0)
            update { copy(speedMps=speed, speedAtMs=System.currentTimeMillis()) }
        } catch (_: Exception) { update { copy(speedMps=null) } }
    }

    fun stop() {
        if (!running) return
        running = false
        ++generation
        vehicle?.let { v ->
            try {
                if ("brake" in subscribed) v.getBrake().unregisterBrakePedalStatus(brakeListener)
                if ("odometer" in subscribed) v.getOdometer().unregisterDistanceDriven(odometerListener)
                if ("mode" in subscribed) v.getDrivingMode().unregisterDrivingModeState(modeListener)
                if ("ev" in subscribed) v.getEvBattery().unregisterCurrentEvEfficiency(evListener)
                if ("fuel" in subscribed) v.getFuel().unregisterCurrentFuelEconomy(fuelListener)
            } catch (_: Exception) { /* Service may have disconnected. */ }
        }
        subscribed.clear()
        @Suppress("DEPRECATION")
        try { properties?.unregisterCallback(speedListener) } catch (_: Exception) {}
        try { uxManager?.unregisterListener() } catch (_: Exception) {}
    }

    fun release() {
        stop()
        try { vehicle?.release() } catch (_: Exception) {}
        vehicle = null
        try { car?.disconnect() } catch (_: Exception) {}
        car = null
        properties = null
        uxManager = null
    }

    private fun update(change: VehicleSnapshot.() -> VehicleSnapshot) {
        main.post {
            if (!running) return@post
            snapshot = snapshot.change()
            onValue(snapshot)
        }
    }

    private fun status(feature: String, error: Exception) {
        android.util.Log.w("GilkokVehicle", "$feature: ${error.message ?: error.javaClass.simpleName}")
        statusText("일부 차량 항목은 이 환경에서 제공되지 않습니다")
    }
    private fun statusText(message: String) { main.post { if (running) onStatus(message) } }
    private fun isRestricted(value: CarUxRestrictions?): Boolean =
        value == null || value.isRequiresDistractionOptimization || value.activeRestrictions != 0
}
