package com.example.gilkok

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

data class VehicleSnapshot(
    val speedMps: Float? = null,
    val brakePressed: Boolean? = null,
    val odometerKm: Float? = null,
    val drivingMode: String? = null,
    val evKmPerKwh: Float? = null,
    val fuelLPer100Km: Float? = null,
    val speedAtMs: Long = 0L,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

data class TripSample(val timeMs: Long, val value: VehicleSnapshot)

data class TripRecord(
    val id: String = UUID.randomUUID().toString(),
    val startMs: Long,
    var endMs: Long? = null,
    var destination: String? = null,
    val samples: MutableList<TripSample> = mutableListOf(),
    var destinationPoint: GeoPoint? = null,
    var crossing: String? = null,
    var interrupted: Boolean = false,
)

data class TripReport(
    val durationMinutes: Int,
    val distanceKm: Double?,
    val hardAccelerations: Int,
    val hardBrakes: Int,
    val speedVariabilityPercent: Int?,
    val evKmPerKwh: Float?,
    val fuelLPer100Km: Float?,
    val brakingLossUpperBoundKwh: Double?,
)

object TripAnalyzer {
    fun analyze(trip: TripRecord): TripReport {
        val samples = trip.samples
        var accelerated = false
        var braked = false
        var hardAccelerations = 0
        var hardBrakes = 0
        var integratedMeters = 0.0
        var brakingLossJoules = 0.0
        var speedPairs = 0
        val movingSpeeds = mutableListOf<Double>()

        for (sample in samples) {
            val speed = sample.value.speedMps?.toDouble()
            if (speed != null && speed.isFinite() && speed > 5.0) movingSpeeds += speed
        }
        for (i in 1 until samples.size) {
            val previous = samples[i - 1]
            val current = samples[i]
            val seconds = (current.timeMs - previous.timeMs) / 1000.0
            if (seconds <= 0.0 || seconds > 5.0) {
                accelerated = false
                braked = false
                continue
            }
            val before = previous.value.speedMps?.toDouble()
            val after = current.value.speedMps?.toDouble()
            if (before == null || after == null || !before.isFinite() || !after.isFinite()) {
                accelerated=false; braked=false; continue
            }
            speedPairs++
            val v0 = abs(before)
            val v1 = abs(after)
            integratedMeters += (v0 + v1) * 0.5 * seconds
            val acceleration = (v1 - v0) / seconds
            val isHardAcceleration = acceleration >= 2.5
            val isHardBrake = acceleration <= -3.0
            if (isHardAcceleration && !accelerated) hardAccelerations++
            if (isHardBrake && !braked) hardBrakes++
            if (isHardBrake) brakingLossJoules += 0.5 * 1700.0 * (v0 * v0 - v1 * v1)
            accelerated = isHardAcceleration
            braked = isHardBrake
        }

        val firstOdometer = samples.firstOrNull { it.value.odometerKm != null }?.value?.odometerKm
        val lastOdometer = samples.lastOrNull { it.value.odometerKm != null }?.value?.odometerKm
        val odometerDistance = if (firstOdometer != null && lastOdometer != null && lastOdometer >= firstOdometer) {
            (lastOdometer - firstOdometer).toDouble()
        } else null
        // A simulator can expose a constant odometer while speed still changes.
        val distance = odometerDistance?.takeIf { it > 0.0 }
            ?: integratedMeters.takeIf { it > 0.0 }?.div(1000.0)
            ?: odometerDistance

        val mean = movingSpeeds.average().takeIf { it.isFinite() && it > 0.0 }
        val variation = mean?.let { avg ->
            val variance = movingSpeeds.sumOf { (it - avg) * (it - avg) } / movingSpeeds.size
            (sqrt(variance) / avg * 100.0).toInt()
        }
        val lastEv = samples.lastOrNull { it.value.evKmPerKwh != null }?.value?.evKmPerKwh
        val lastFuel = samples.lastOrNull { it.value.fuelLPer100Km != null }?.value?.fuelLPer100Km
        val end = trip.endMs ?: samples.lastOrNull()?.timeMs ?: trip.startMs
        return TripReport(
            durationMinutes = ((end - trip.startMs).coerceAtLeast(0L) / 60_000L).toInt(),
            distanceKm = distance,
            hardAccelerations = hardAccelerations,
            hardBrakes = hardBrakes,
            speedVariabilityPercent = variation,
            evKmPerKwh = lastEv,
            fuelLPer100Km = lastFuel,
            // Kinetic energy at hard decelerations. It is an upper bound, not measured savings.
            brakingLossUpperBoundKwh = if (speedPairs > 0) brakingLossJoules / 3_600_000.0 else null,
        )
    }
}

class TripStore internal constructor(context: Context) {
    @Volatile var lastSaveError:String? = null
        private set
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val file = AtomicFile(File(context.filesDir, "trips.json"))
    private val directory=File(context.filesDir,"trip-records").apply { mkdirs() }
    private val activeFile=AtomicFile(File(directory,"active.json"))
    private val indexFile=AtomicFile(File(directory,"index.json"))
    private val writtenIds=directory.listFiles().orEmpty().map { it.nameWithoutExtension }.filter { safeId(it) }.toMutableSet()
    private val trips = mutableListOf<TripRecord>()
    var active: TripRecord? = null
        private set

    init { load() }

    fun recent(): List<TripRecord> = trips.toList()

    fun start(destination: String?, point: GeoPoint? = null, crossing: String? = null): TripRecord {
        active?.let { return it }
        val trip = TripRecord(startMs = System.currentTimeMillis(), destination = destination, destinationPoint=point, crossing=crossing)
        active = trip
        save()
        return trip
    }

    fun append(value: VehicleSnapshot) {
        val trip = active ?: return
        trip.samples += TripSample(System.currentTimeMillis(), value.copy(
            speedMps=value.speedMps?.takeIf { it.isFinite() && abs(it)<=70f },
            odometerKm=value.odometerKm?.takeIf { it.isFinite() && it>=0f },
            evKmPerKwh=value.evKmPerKwh?.takeIf { it.isFinite() && it>0f },
            fuelLPer100Km=value.fuelLPer100Km?.takeIf { it.isFinite() && it>0f }))
        if (trip.samples.size % 30 == 0) save()
    }

    fun finish(): TripRecord? {
        val trip = active ?: return null
        trip.endMs = System.currentTimeMillis()
        active = null
        trips.add(0, trip)
        while (trips.size > 20) trips.removeAt(trips.lastIndex)
        while(trips.sumOf { it.samples.size } > 120_000 && trips.size>1) trips.removeAt(trips.lastIndex)
        save()
        return trip
    }

    fun save() {
        val current = active?.copy(samples=active!!.samples.toMutableList())
        val history = trips.toList()
        writer.execute {
            try {
                history.forEach { trip ->
                    if(trip.id !in writtenIds) { writeAtomic(AtomicFile(File(directory,"${trip.id}.json")),encode(trip).toString()); writtenIds+=trip.id }
                }
                writeAtomic(indexFile,JSONArray(history.map { it.id }).toString())
                if(current==null) activeFile.delete() else writeAtomic(activeFile,encode(current).toString())
                val keep=history.map { it.id }.toSet()
                (writtenIds-keep).forEach { id -> AtomicFile(File(directory,"$id.json")).delete(); writtenIds.remove(id) }
                file.delete() // Legacy data is removed only after the new snapshot is durable.
                lastSaveError=null
            } catch (_: Exception) { lastSaveError="저장에 실패했습니다. 기기 저장 공간을 확인하세요" }
        }
    }

    private fun writeAtomic(target:AtomicFile,value:String) {
        val stream=target.startWrite()
        try { stream.write(value.toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }
        catch(e:Exception) { target.failWrite(stream); throw e }
    }
    private fun safeId(value:String)=value.matches(Regex("[a-fA-F0-9-]{36}"))

    private fun load() {
        try {
            if(indexFile.baseFile.exists() || File(directory,"index.json.bak").exists()) {
                active=runCatching { JSONObject(activeFile.openRead().bufferedReader().use { it.readText() }).let(::decode) }.getOrNull()
                val ids=JSONArray(indexFile.openRead().bufferedReader().use { it.readText() })
                for(i in 0 until minOf(ids.length(),20)) {
                    val id=ids.optString(i)
                    if(safeId(id)) runCatching { JSONObject(AtomicFile(File(directory,"$id.json")).openRead().bufferedReader().use { it.readText() }).let(::decode) }.getOrNull()?.let { trips+=it }
                }
                if(trips.any { it.id==active?.id }) active=null
                return
            }
            val root = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            active = root.optJSONObject("active")?.let(::decode)
            val array = root.optJSONArray("recent") ?: JSONArray()
            for (i in 0 until array.length()) array.optJSONObject(i)?.let { trips += decode(it) }
        } catch (_: Exception) {
            // A missing or damaged local file starts a fresh session.
        }
    }

    private fun encode(trip: TripRecord) = JSONObject().apply {
        require(safeId(trip.id))
        put("id", trip.id)
        put("start", trip.startMs)
        put("end", trip.endMs)
        put("destination", trip.destination)
        put("destinationLat", trip.destinationPoint?.latitude)
        put("destinationLon", trip.destinationPoint?.longitude)
        put("crossing", trip.crossing)
        put("interrupted", trip.interrupted)
        put("samples", JSONArray().also { array ->
            trip.samples.forEach { sample -> array.put(JSONObject().apply {
                put("time", sample.timeMs)
                put("speed", sample.value.speedMps)
                put("brake", sample.value.brakePressed)
                put("odometer", sample.value.odometerKm)
                put("mode", sample.value.drivingMode)
                put("ev", sample.value.evKmPerKwh)
                put("fuel", sample.value.fuelLPer100Km)
                put("lat", sample.value.latitude)
                put("lon", sample.value.longitude)
            }) }
        })
    }

    private fun decode(obj: JSONObject): TripRecord {
        val trip = TripRecord(
            id = obj.optString("id", UUID.randomUUID().toString()),
            startMs = obj.optLong("start"),
            endMs = obj.optLongOrNull("end"),
            destination = obj.optString("destination").ifBlank { null },
            destinationPoint = if(obj.isNull("destinationLat") || obj.isNull("destinationLon")) null else GeoPoint(obj.optDouble("destinationLat"),obj.optDouble("destinationLon")),
            crossing = obj.optString("crossing").ifBlank { null },
            interrupted = obj.optBoolean("interrupted"),
        )
        val array = obj.optJSONArray("samples") ?: JSONArray()
        for (i in 0 until array.length()) {
            val sample = array.optJSONObject(i) ?: continue
            trip.samples += TripSample(sample.optLong("time"), VehicleSnapshot(
                speedMps = sample.optFloatOrNull("speed"),
                brakePressed = if (sample.isNull("brake")) null else sample.optBoolean("brake"),
                odometerKm = sample.optFloatOrNull("odometer"),
                drivingMode = sample.optString("mode").ifBlank { null },
                evKmPerKwh = sample.optFloatOrNull("ev"),
                fuelLPer100Km = sample.optFloatOrNull("fuel"),
                latitude = if(sample.isNull("lat")) null else sample.optDouble("lat"),
                longitude = if(sample.isNull("lon")) null else sample.optDouble("lon"),
            ))
        }
        return trip
    }

    private fun JSONObject.optFloatOrNull(key: String): Float? =
        if (isNull(key)) null else optDouble(key).takeIf { it.isFinite() }?.toFloat()

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key)) null else optLong(key)

    fun close() { save() }
    fun clearHistory() { trips.clear(); save() }
    internal fun awaitPendingWrites():Boolean {
        val latch=java.util.concurrent.CountDownLatch(1)
        writer.execute { latch.countDown() }
        return latch.await(10,java.util.concurrent.TimeUnit.SECONDS)
    }
    companion object {
        @Volatile private var instance:TripStore?=null
        fun get(context:Context):TripStore = instance ?: synchronized(this) {
            instance ?: TripStore(context.applicationContext).also { instance=it }
        }
    }
}
