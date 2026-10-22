package com.droiddeck.launcher.glasses

import android.hardware.display.DeviceProductInfo
import android.hardware.display.DisplayManager
import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDisplay
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], manifest = Config.NONE, shadows = [GlassesDisplayTest.ProductMetadataDisplay::class])
@LooperMode(LooperMode.Mode.PAUSED)
class GlassesDisplayTest {
    @Test fun connectedGlassesUseTheOnlyExternalPanelWhenProductMetadataIsMissing() {
        val panel = display("External Display")
        assertNull(panel.deviceProductInfo)

        assertEquals(panel.displayId, Glasses.selectGlassesDisplay(listOf(panel), usbPresent = true)?.displayId)
    }

    @Test fun missingProductMetadataDoesNotIdentifyGlassesWithoutUsb() {
        val panel = display("External Display")

        assertNull(Glasses.selectGlassesDisplay(listOf(panel), usbPresent = false))
    }

    @Test fun connectedGlassesDoNotChooseBetweenMultipleUnidentifiedPanels() {
        val first = display("External Display 1")
        val second = display("External Display 2")

        assertNull(Glasses.selectGlassesDisplay(listOf(first, second), usbPresent = true))
    }

    @Test fun primaryAndNonPresentationDisplaysCannotBecomeTheGlassesOutput() {
        val primary = displayManager().getDisplay(Display.DEFAULT_DISPLAY)
        shadowOf(primary).setName("RayNeo Air 4 Pro")
        shadowOf(primary).setFlags(Display.FLAG_PRESENTATION)
        val nonPresentation = display("RayNeo Air 4 Pro", flags = 0)

        assertNull(Glasses.selectGlassesDisplay(listOf(primary, nonPresentation), usbPresent = true))
    }

    @Test fun rayNeoDisplayNameIsRecognizedEvenWhenProductMetadataHasAGenericName() {
        val panel = display("RayNeo Air 4 Pro", productName = "External Monitor")

        assertEquals(panel.displayId, Glasses.selectGlassesDisplay(listOf(panel), usbPresent = false)?.displayId)
    }

    @Test fun rayNeoProductMetadataIsRecognizedWhenAndroidUsesAGenericDisplayName() {
        val panel = display("External Display", productName = "RayNeo Air 3")

        assertEquals(panel.displayId, Glasses.selectGlassesDisplay(listOf(panel), usbPresent = false)?.displayId)
    }

    @Test fun anUnrelatedNamedMonitorIsNotClaimedByConnectedGlasses() {
        val monitor = display("External Display", productName = "TCL TV")

        assertNull(Glasses.selectGlassesDisplay(listOf(monitor), usbPresent = true))
    }

    @Test fun namedRayNeoDisplayWorksWithoutUsbControlPermission() {
        val panel = display("RayNeo GT")

        assertEquals(panel.displayId, Glasses.selectGlassesDisplay(listOf(panel), usbPresent = false)?.displayId)
    }

    @Test fun blankProductNameRetainsTheSinglePanelUsbFallback() {
        val panel = display("External Display", productName = "   ")

        assertEquals(panel.displayId, Glasses.selectGlassesDisplay(listOf(panel), usbPresent = true)?.displayId)
    }

    @Test fun brandedGlassesWinOverAnUnidentifiedSecondDisplay() {
        val monitor = display("External Display")
        val glasses = display("RayNeo Air 2s")

        assertEquals(glasses.displayId, Glasses.selectGlassesDisplay(listOf(monitor, glasses), usbPresent = true)?.displayId)
    }

    @Test fun gtMaxSmartGlassesEdidUsesTheSameOutputSelectionAsViture() {
        val panel = display("SmartGlasses", productName = "SmartGlasses", productId = "980")
        assertEquals(panel.displayId, Glasses.selectGlassesDisplay(listOf(panel), usbPresent = true)?.displayId)
        // The display remains usable while USB control reconnects during a mode switch.
        assertEquals(panel.displayId, Glasses.selectGlassesDisplay(listOf(panel), usbPresent = false)?.displayId)
    }

    @Test fun gtMaxMetadataIsRecognizedBehindGenericAndroidNameAndOtherPanels() {
        val monitor = display("External Display", productName = "TCL TV")
        val panel = display("External Display", productName = "SmartGlasses", productId = "980")
        assertEquals(panel.displayId, Glasses.selectGlassesDisplay(listOf(monitor, panel), usbPresent = true)?.displayId)
    }

    @Test fun genericSmartGlassesNameDoesNotClaimUnverifiedProducts() {
        val unknown = display("SmartGlasses", productName = "SmartGlasses")
        assertNull(Glasses.selectGlassesDisplay(listOf(unknown), usbPresent = true))
        assertFalse(RayNeoModel.isDisplayProduct("SmartGlasses", "OTHER", "980"))
        assertFalse(RayNeoModel.isDisplayProduct("TCL TV", "TCL", "980"))
    }

    private fun displayManager(): DisplayManager =
        RuntimeEnvironment.getApplication().getSystemService(DisplayManager::class.java)

    private fun display(name: String, productName: String? = null, flags: Int = Display.FLAG_PRESENTATION,
                        productId: String = "test-panel"): Display {
        val external = ReflectionHelpers.getStaticField<Int>(Display::class.java, "TYPE_EXTERNAL")
        val id = ShadowDisplayManager.addDisplay("w1920dp-h1080dp", external)
        return displayManager().getDisplay(id).also { panel ->
            shadowOf(panel).setName(name)
            shadowOf(panel).setFlags(flags)
            Shadow.extract<ProductMetadataDisplay>(panel).productMetadata = productName?.let {
                DeviceProductInfo(it, "TCL", productId, 2024, DeviceProductInfo.CONNECTION_TO_SINK_DIRECT)
            }
        }
    }

    // Robolectric exposes display names and flags, but has no product-metadata setter.
    @Implements(Display::class)
    class ProductMetadataDisplay : ShadowDisplay() {
        var productMetadata: DeviceProductInfo? = null

        @Implementation(minSdk = 31)
        protected fun getDeviceProductInfo(): DeviceProductInfo? = productMetadata
    }
}
