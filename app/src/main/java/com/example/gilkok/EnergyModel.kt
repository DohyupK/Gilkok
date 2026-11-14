package com.example.gilkok

import kotlin.math.*

data class VehicleProfile(val massKg: Double=1700.0, val dragArea: Double=.65, val rollingResistance: Double=.01,
    val driveEfficiency: Double=.9, val regenerationEfficiency: Double=.6, val auxiliaryWatts: Double=500.0) {
    init {
        require(massKg in 700.0..4000.0 && dragArea in .3..1.5 && rollingResistance in .005.. .03)
        require(driveEfficiency in .5..1.0 && regenerationEfficiency in 0.0.. .9 && auxiliaryWatts in 0.0..5000.0)
    }
}
data class EnergyEstimate(val observedScenarioKwh: Double, val smoothScenarioKwh: Double, val coveredMeters: Double,
    val coveredSeconds: Double, val coveragePercent: Int) {
    val changePercent get() = if(observedScenarioKwh>0) (observedScenarioKwh-smoothScenarioKwh)/observedScenarioKwh*100 else 0.0
}

object EnergyModel {
    /** Flat-road EV model, no weather/HVAC/gradient measurements. Not measured energy or a driving instruction. */
    fun estimate(trip: TripRecord, profile: VehicleProfile=VehicleProfile()): EnergyEstimate? {
        val samples=trip.samples
        if(samples.size<31) return null
        val speeds=samples.map { it.value.speedMps?.toDouble()?.let(::abs)?.takeIf { v->v.isFinite() && v<=70 } }
        var validSeconds=0.0; var distance=0.0
        val intervals=mutableListOf<Int>()
        for(i in 1 until samples.size) {
            val dt=(samples[i].timeMs-samples[i-1].timeMs)/1000.0
            if(dt in .25..2.5 && speeds[i]!=null && speeds[i-1]!=null) {
                intervals+=i; validSeconds+=dt; distance+=(speeds[i]!!+speeds[i-1]!!)*.5*dt
            }
        }
        val elapsed=((trip.endMs ?: samples.last().timeMs)-trip.startMs)/1000.0
        val coverage=(validSeconds/elapsed.coerceAtLeast(1.0)*100).toInt().coerceIn(0,100)
        if(validSeconds<30 || distance<100 || coverage<80) return null
        // Smooth only within continuous, moving segments. Keep stops and total sampled distance fixed.
        val smooth=speeds.toMutableList()
        for(i in speeds.indices) {
            val speed=speeds[i] ?: continue
            if(speed<.5) continue
            val nearby=(max(0,i-2)..min(speeds.lastIndex,i+2)).filter { j ->
                abs(samples[j].timeMs-samples[i].timeMs)<=2500 &&
                (min(i,j)..max(i,j)).all { k -> speeds[k]!=null && speeds[k]!!>=.5 } }
            smooth[i]=nearby.map { speeds[it]!! }.average()
        }
        val smoothDistance=intervals.sumOf { i -> (smooth[i]!!+smooth[i-1]!!)*.5*(samples[i].timeMs-samples[i-1].timeMs)/1000.0 }
        val scale=distance/smoothDistance.coerceAtLeast(1.0)
        if(scale !in .8..1.2) return null
        for(i in smooth.indices) smooth[i]=smooth[i]?.times(scale)
        fun energy(values: List<Double?>): Double = intervals.sumOf { i ->
            val dt=(samples[i].timeMs-samples[i-1].timeMs)/1000.0
            val a=values[i-1]!!; val b=values[i]!!; val v=(a+b)*.5
            val kinetic=.5*profile.massKg*(b*b-a*a)/dt
            val road=profile.massKg*9.80665*profile.rollingResistance*v + .5*1.225*profile.dragArea*v*v*v
            val wheel=kinetic+road
            val battery=if(wheel>=0) wheel/profile.driveEfficiency else max(wheel,-50_000.0)*profile.regenerationEfficiency
            (battery+profile.auxiliaryWatts)*dt/3_600_000.0
        }.coerceAtLeast(0.0)
        return EnergyEstimate(energy(speeds),energy(smooth),distance,validSeconds,coverage)
    }
}

object TripComparison {
    fun similar(a: TripRecord,b: TripRecord): Boolean {
        val da=a.destinationPoint ?: return false; val db=b.destinationPoint ?: return false
        if(da.distanceTo(db)>500 || a.crossing!=b.crossing) return false
        fun start(t: TripRecord)=t.samples.firstNotNullOfOrNull { s ->
            val lat=s.value.latitude; val lon=s.value.longitude
            if(lat==null || lon==null) null else GeoPoint(lat,lon).takeIf { it.valid }
        }
        val sa=start(a) ?: return false; val sb=start(b) ?: return false
        if(sa.distanceTo(sb)>500) return false
        fun trace(t:TripRecord):List<GeoPoint> {
            val result=mutableListOf<GeoPoint>()
            t.samples.forEach { sample ->
                val lat=sample.value.latitude; val lon=sample.value.longitude
                if(lat!=null && lon!=null) {
                    val p=GeoPoint(lat,lon)
                    if(p.valid && (result.isEmpty() || result.last().distanceTo(p)>=80)) result+=p
                }
            }
            return result.filterIndexed { i,_ -> i % max(1,result.size/40)==0 }.take(50)
        }
        val ta=trace(a); val tb=trace(b)
        if(ta.size<5 || tb.size<5) return false
        if(ta.count { p -> tb.any { p.distanceTo(it)<300 } }.toDouble()/ta.size<.8 ||
            tb.count { p -> ta.any { p.distanceTo(it)<300 } }.toDouble()/tb.size<.8) return false
        val ea=EnergyModel.estimate(a) ?: return false; val eb=EnergyModel.estimate(b) ?: return false
        return ea.coveredMeters/eb.coveredMeters in .8..1.2 && ea.coveredSeconds/eb.coveredSeconds in .7..1.3
    }
}
