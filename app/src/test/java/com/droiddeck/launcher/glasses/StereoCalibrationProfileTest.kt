package com.droiddeck.launcher.glasses

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StereoCalibrationProfileTest {
    private fun base() = RuntimeEnvironment.getApplication().assets.open(StereoCalibrationProfile.BASE_ASSET)
        .bufferedReader().use { it.readText() }
    private fun device(verified: Boolean = true) = JSONObject(base()).put("identity", "my-glasses")
        .put("verified", verified).toString()
    private fun select(saved: String? = null, identity: String? = "my-glasses", width: Int = 640, height: Int = 480) =
        StereoCalibrationProfile.select(saved, ::base, identity, width, height)

    @Test fun newGlassesUseSharedGeometryWithoutClaimingIndividualValidation() {
        val profile = select()!!
        assertEquals("my-glasses", profile.identity)
        assertTrue(profile.bundledBase)
        assertFalse(profile.verified)
        assertEquals(1.1209395390660726, profile.error, 0.0)
        assertEquals(226.0499636040297, profile.matrices[0].values[0], 0.0)
        assertEquals(-0.05607918f, profile.cameraToHead.position.x, 0f)
    }

    @Test fun unknownSerialCanUseTheModelBase() {
        val profile = select(identity = null)!!
        assertEquals(StereoCalibrationProfile.BASE_IDENTITY, profile.identity)
        assertTrue(profile.bundledBase)
        assertFalse(profile.verified)
    }

    @Test fun savedDeviceProfileWinsWithoutReadingTheAsset() {
        val profile = StereoCalibrationProfile.select(device(), { error("Base must not be read") }, "my-glasses", 640, 480)!!
        assertFalse(profile.bundledBase)
        assertTrue(profile.verified)
    }

    @Test fun unfinishedPersonalCalibrationStillRequiresDistanceValidation() {
        val profile = select(device(verified = false))!!
        assertFalse(profile.bundledBase)
        assertFalse(profile.verified)
    }

    @Test fun foreignOrCorruptDeviceProfileFallsBackToBase() {
        assertTrue(select(JSONObject(device()).put("identity", "other-glasses").toString())!!.bundledBase)
        assertTrue(select("not json")!!.bundledBase)
    }

    @Test fun otherCameraGeometryDoesNotUseTheBase() {
        assertNull(select(width = 1280, height = 960))
        assertNull(select(width = 480, height = 640))
    }

    @Test fun baseRequiresTheCorrectModelAndCameraPair() {
        for ((key, value) in listOf("deviceModel" to "VITURE Luma Pro", "cameraPair" to "left1-right1", "identity" to "device-serial")) {
            val text = JSONObject(base()).put(key, value).toString()
            assertNull(StereoCalibrationProfile.select(null, { text }, "my-glasses", 640, 480))
        }
    }

    @Test fun badLensDimensionsAndInvalidFitsAreRejectedBeforeNativeAllocation() {
        for (name in listOf("k1", "d1", "k2", "d2", "r", "t")) {
            val data = JSONObject(base())
            data.getJSONObject(name).put("rows", 2)
            assertNull(StereoCalibrationProfile.parse(data.toString(), StereoCalibrationProfile.BASE_IDENTITY, 640, 480, true))
        }
        for (error in listOf(-1.0, 2.01)) {
            val data = JSONObject(base()).put("error", error)
            assertNull(StereoCalibrationProfile.parse(data.toString(), StereoCalibrationProfile.BASE_IDENTITY, 640, 480, true))
        }
    }

    @Test fun missingAssetAndNoDeviceProfileRequiresCalibration() {
        assertNull(StereoCalibrationProfile.select(null, { null }, "my-glasses", 640, 480))
    }

    @Test fun shippedBaseContainsNoSourceSerialOrFalseValidationClaim() {
        val data = JSONObject(base())
        assertEquals(StereoCalibrationProfile.BASE_IDENTITY, data.getString("identity"))
        assertFalse(data.getBoolean("verified"))
        assertTrue(data.getJSONObject("sourceValidation").getBoolean("sourceDeviceVerified"))
    }
}
