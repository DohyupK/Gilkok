package com.example.gilkok

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TripStoreInstrumentedTest {
    private fun isolatedContext():Context {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(target.cacheDir,"trip-test-${UUID.randomUUID()}").apply { mkdirs() }
        return object:ContextWrapper(target) { override fun getFilesDir():File=root }
    }
    @Test fun activeTripSurvivesReloadAndDeletionRemovesCompletedLocations() {
        val context=isolatedContext()
        val first=TripStore(context)
        first.start("시범 장소",GeoPoint(37.5,127.0),"영동대교:NORTH")
        first.append(VehicleSnapshot(speedMps=10f,latitude=37.51,longitude=127.01))
        first.save(); assertTrue(first.awaitPendingWrites())
        val restored=TripStore(context)
        assertEquals(1,restored.active!!.samples.size)
        assertEquals("영동대교:NORTH",restored.active!!.crossing)
        restored.finish(); assertTrue(restored.awaitPendingWrites())
        val completed=TripStore(context)
        assertNull(completed.active)
        assertEquals(1,completed.recent().size)
        completed.clearHistory(); assertTrue(completed.awaitPendingWrites())
        assertTrue(TripStore(context).recent().isEmpty())
        assertEquals(listOf("index.json"),File(context.filesDir,"trip-records").list()!!.toList())
    }
    @Test fun legacyRecordsAreMigratedWithoutKeepingOldLocationCopies() {
        val context=isolatedContext()
        File(context.filesDir,"trips.json").writeText("""{"recent":[{"id":"11111111-1111-1111-1111-111111111111","start":1,"end":2,"destination":"옛 기록","samples":[{"time":1,"speed":0}]}]}""")
        val migrated=TripStore(context)
        assertEquals("옛 기록",migrated.recent().single().destination)
        migrated.save(); assertTrue(migrated.awaitPendingWrites())
        assertFalse(File(context.filesDir,"trips.json").exists())
        assertEquals(1,TripStore(context).recent().size)
    }
}
