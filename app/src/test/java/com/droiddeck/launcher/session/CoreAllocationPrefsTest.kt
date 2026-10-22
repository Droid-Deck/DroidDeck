package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.CpuAllocationDefaults
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CoreAllocationPrefsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val defaults = CpuAllocationDefaults.Allocation("7", "3,4,5,6")
    private val saved get() = app.getSharedPreferences("session", Context.MODE_PRIVATE)

    @Before fun clear() { saved.edit().clear().commit() }

    @Test fun absentPreferencesUseTheDeviceDefaultWithoutWritingIt() {
        assertEquals(SessionPrefs.CoreAllocation(true, "7", "3,4,5,6"), SessionPrefs.coreAllocation(app, defaults))
        assertTrue(saved.all.isEmpty())
        assertEquals(SessionPrefs.CoreAllocation(false, "", ""), SessionPrefs.coreAllocation(app, null))
    }

    @Test fun explicitDisableAndAllCoreChoicesSurviveAnUpdate() {
        saved.edit().putBoolean("clientCpusOverride", false).putString("clientCpus", "").putString("gameCpus", "").commit()
        assertEquals(SessionPrefs.CoreAllocation(false, "", ""), SessionPrefs.coreAllocation(app, defaults))
    }

    @Test fun existingCustomMasksArePreserved() {
        saved.edit().putBoolean("clientCpusOverride", true).putString("clientCpus", "0,1").putString("gameCpus", "2,7").commit()
        assertEquals(SessionPrefs.CoreAllocation(true, "0,1", "2,7"), SessionPrefs.coreAllocation(app, defaults))
    }

    @Test fun anOldClientMaskIsNotEnabledWithoutAnOverrideChoice() {
        saved.edit().putString("clientCpus", "0,1").commit()
        assertEquals(SessionPrefs.CoreAllocation(false, "0,1", "3,4,5,6"), SessionPrefs.coreAllocation(app, defaults))
    }

    @Test fun changingOneChoiceDoesNotReplaceTheOtherDefaults() {
        SessionPrefs.setGameCpus(app, "0,1,2,3,4,5,6,7")
        assertEquals(SessionPrefs.CoreAllocation(true, "7", "0,1,2,3,4,5,6,7"), SessionPrefs.coreAllocation(app, defaults))
        SessionPrefs.setClientCpusOverride(app, false)
        assertEquals(SessionPrefs.CoreAllocation(false, "7", "0,1,2,3,4,5,6,7"), SessionPrefs.coreAllocation(app, defaults))
    }
}
