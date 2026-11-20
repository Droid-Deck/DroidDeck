package com.droiddeck.launcher.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StorageSummaryTest {
    @Test fun theSavedOverviewReadsBackAsWritten() {
        val buckets = Bucket.entries.mapIndexed { i, b -> b to (i + 1) * 1_000_000_000L }.toMap()
        val s = StorageSummary(42, 103L shl 30, 49L shl 30, buckets, 234L shl 20, 3, 1, 7L shl 30, 9L shl 20, 350L shl 20,
            12, 226L shl 20, 8, 73L shl 20, 0, 0, 105L shl 20, 9L shl 20, 8L shl 30)
        val back = StorageSummary.fromJson(s.toJson())!!
        assertEquals(s.toJson(), back.toJson())
        assertEquals(buckets, back.buckets)
        assertEquals(s.droiddeck, back.droiddeck)
    }

    @Test fun anOtherVersionOrDamagedFileReadsAsNone() {
        assertNull(StorageSummary.fromJson("{\"v\":0}"))
        assertNull(StorageSummary.fromJson("not json"))
    }
}
