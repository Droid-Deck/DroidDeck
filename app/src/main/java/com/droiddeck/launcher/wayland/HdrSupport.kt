package com.droiddeck.launcher.wayland

import android.content.Context
import android.os.Build
import android.view.Display
import android.view.WindowManager

/**
 * What the panel says about HDR, read from android.view.Display: the gate the compositor's HDR10
 * output sits behind (droiddeck_color.h). A property of the display, not the device - and the
 * settings screen greys the HDR switch out with the reason when it is not there, rather than
 * offering a switch that a session would then silently ignore.
 */
class HdrProbe(
    val hdr10: Boolean,
    val formats: String,
    val maxLuminance: Float,
    val maxAverageLuminance: Float,
    val minLuminance: Float,
    val ratioAvailable: Boolean,
    val ratio: Float,
    val displayId: Int,
    val name: String,
) {
    /** Why HDR cannot be offered, or null when it can. */
    val reason: String? = when {
        Build.VERSION.SDK_INT < 29 -> "needs Android 10 or newer (display layers with a color space)"
        !hdr10 -> if (formats.isEmpty()) "this display reports no HDR support" else "this display supports $formats, not HDR10"
        else -> null
    }
}

object HdrSupport {
    fun probe(context: Context): HdrProbe {
        val display: Display? = if (Build.VERSION.SDK_INT >= 30) {
            try { context.display } catch (e: Exception) { null }
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        }
        if (display == null) return HdrProbe(false, "", 0f, 0f, 0f, false, -1f, -1, "none")
        val caps = try { display.hdrCapabilities } catch (e: Exception) { null }
        val types = caps?.supportedHdrTypes ?: IntArray(0)
        val formats = types.joinToString(", ") { typeName(it) }
        val ratioAvailable = Build.VERSION.SDK_INT >= 34 && try { display.isHdrSdrRatioAvailable } catch (e: Exception) { false }
        val ratio = if (ratioAvailable) try { display.hdrSdrRatio } catch (e: Exception) { -1f } else -1f
        return HdrProbe(
            hdr10 = types.contains(Display.HdrCapabilities.HDR_TYPE_HDR10),
            formats = formats,
            maxLuminance = caps?.desiredMaxLuminance ?: 0f,
            maxAverageLuminance = caps?.desiredMaxAverageLuminance ?: 0f,
            minLuminance = caps?.desiredMinLuminance ?: 0f,
            ratioAvailable = ratioAvailable,
            ratio = ratio,
            displayId = display.displayId,
            name = display.name ?: "display",
        )
    }

    private fun typeName(t: Int): String = when (t) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        else -> "HDR"
    }
}
