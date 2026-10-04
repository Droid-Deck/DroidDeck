package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.wayland.WaylandCompositor
import org.json.JSONObject
import kotlin.math.abs

/**
 * The compositor's post chain (waylandcomp/src/effects_chain.c) as the session menu drives it:
 * the colour grade, the sharpening, the shader toggles and terminal debanding. Units are the
 * menu's own, so a value goes straight from a row into the setter it drives:
 *
 *  - [brightness] / [contrast]   -100..100, 0 = neutral
 *  - [gamma]                     0.5..3.0, 1.0 = neutral
 *  - [saturation]                0..200 percent, 100 = neutral
 *  - [casLevel]                  0..100; the pass runs only while [cas] is on
 *  - [debandStrength]            0..200, 100 = one LSB
 *
 * Applies live: the compositor picks the change up on its next frame. While any pass is on, a
 * zero-copy game goes through the compositor pass instead, which costs a little GPU time.
 */
data class ScreenEffects(
    val brightness: Int = 0,
    val contrast: Int = 0,
    val gamma: Float = 1f,
    val saturation: Int = 100,
    val cas: Boolean = false,
    val casLevel: Int = 50,
    val hdr: Boolean = false,
    val deband: Boolean = false,
    val debandStrength: Int = 100,
    val fxaa: Boolean = false,
    val toon: Boolean = false,
    val crt: Boolean = false,
    val ntsc: Boolean = false,
) {
    /** The sharpening level the picture gets: 0 while the pass is off, whatever its level is parked at. */
    val effectiveCas: Int get() = if (cas) casLevel else 0

    /** Whether any pass runs, so the menu can say when the picture is untouched. */
    val any: Boolean get() = brightness != 0 || contrast != 0 || abs(gamma - 1f) > 0.005f || saturation != 100 ||
        effectiveCas > 0 || hdr || deband || fxaa || toon || crt || ntsc

    /** Hands every pass to the compositor, with the Look's name for its log line. */
    fun push(upscaler: Int) {
        WaylandCompositor.nativeSetCas(cas, casLevel)
        WaylandCompositor.nativeSetHdr(hdr)
        WaylandCompositor.nativeSetDeband(deband, debandStrength)
        WaylandCompositor.nativeSetScreenEffects(brightness.toFloat(), contrast.toFloat(), gamma, saturation.toFloat(), fxaa, toon, crt, ntsc)
        WaylandCompositor.nativeSetLookName(ScreenEffectLooks.match(this, upscaler)?.name)
    }

    fun encode(): JSONObject = JSONObject()
        .put("brightness", brightness).put("contrast", contrast).put("gamma", gamma.toDouble()).put("saturation", saturation)
        .put("cas", cas).put("casLevel", casLevel).put("hdr", hdr).put("deband", deband).put("debandStrength", debandStrength)
        .put("fxaa", fxaa).put("toon", toon).put("crt", crt).put("ntsc", ntsc)

    companion object {
        val OFF = ScreenEffects()

        fun decode(json: JSONObject): ScreenEffects = ScreenEffects(
            brightness = json.optInt("brightness", 0).coerceIn(-100, 100),
            contrast = json.optInt("contrast", 0).coerceIn(-100, 100),
            gamma = json.optDouble("gamma", 1.0).toFloat().coerceIn(0.5f, 3f),
            saturation = json.optInt("saturation", 100).coerceIn(0, 200),
            cas = json.optBoolean("cas", false),
            casLevel = json.optInt("casLevel", 50).coerceIn(0, 100),
            hdr = json.optBoolean("hdr", false),
            deband = json.optBoolean("deband", false),
            debandStrength = json.optInt("debandStrength", 100).coerceIn(0, 200),
            fxaa = json.optBoolean("fxaa", false),
            toon = json.optBoolean("toon", false),
            crt = json.optBoolean("crt", false),
            ntsc = json.optBoolean("ntsc", false),
        )
    }
}

/**
 * One Look: a named set of values for the rows that already exist on the Display page. Picking
 * a Look moves those rows; each stays adjustable afterwards, and the moment one moves the Look
 * row reads Custom. [scalingMode] null leaves the scaling mode alone; otherwise it is a
 * SessionPrefs.upscalerChoices mode the Look switches to.
 */
data class Look(
    val name: String,
    val effects: ScreenEffects,
    val scalingMode: Int? = null,
    /** One line under the row while this Look is picked. */
    val desc: String,
)

object ScreenEffectLooks {
    // Scaling modes as the compositor numbers them (WaylandCompositor.nativeSetUpscaler).
    private const val SCALING_NEAREST = 2
    private const val SCALING_FSR = 4

    /** Index 0 is the neutral state a fresh session matches, so the row always names something. */
    val LOOKS: List<Look> = listOf(
        Look("Off", ScreenEffects.OFF, desc = "Nothing applied."),
        Look("Game Clarity", ScreenEffects(brightness = 2, contrast = 12, saturation = 108, cas = true, casLevel = 55),
            desc = "Sharpening with a touch of contrast - the everyday one."),
        Look("Vivid", ScreenEffects(brightness = 3, contrast = 10, gamma = 0.98f, saturation = 145, cas = true, casLevel = 30, deband = true),
            desc = "Colour pushed hard. Deband on so skies don't stripe."),
        Look("Cinematic", ScreenEffects(brightness = -4, contrast = 22, gamma = 1.10f, saturation = 88, cas = true, casLevel = 20, fxaa = true, deband = true),
            desc = "Deeper contrast, cooler colour, edges smoothed."),
        Look("Competitive", ScreenEffects(brightness = 16, contrast = 18, gamma = 0.92f, saturation = 82, cas = true, casLevel = 75),
            desc = "Shadows lifted and heavy sharpening - spot people first."),
        Look("Adaptive Sharpen", ScreenEffects(cas = true, casLevel = 85),
            desc = "Sharpening only, colour untouched."),
        Look("Filmic", ScreenEffects(brightness = -2, contrast = 16, gamma = 1.14f, saturation = 92, cas = true, casLevel = 15, fxaa = true, deband = true),
            desc = "Film-like curve, slightly muted."),
        Look("Arcade", ScreenEffects(brightness = 6, contrast = 20, gamma = 0.95f, saturation = 170, cas = true, casLevel = 45, deband = true),
            desc = "Loud and punchy."),
        Look("Retro CRT", ScreenEffects(brightness = 6, contrast = 14, gamma = 1.05f, saturation = 115, crt = true, ntsc = true),
            desc = "The CRT tube and analogue signal passes together."),
        Look("Upscale Sharp", ScreenEffects(contrast = 6, saturation = 104, cas = true, casLevel = 70), scalingMode = SCALING_FSR,
            desc = "For running below panel resolution."),
        Look("Pixel Clean", ScreenEffects(contrast = 8, deband = true), scalingMode = SCALING_NEAREST,
            desc = "Crisp pixels for 2D and old titles."),
        Look("Anime Edge", ScreenEffects(brightness = 2, contrast = 10, saturation = 125, cas = true, casLevel = 50, toon = true, deband = true),
            desc = "Toon outlines plus sharpening."),
    )

    /**
     * The Look the live values are, or null for Custom. A Look's sharpening is compared as the
     * effective level, so a pass switched off matches a Look without one whatever its level is
     * parked at; a Look with no scaling mode of its own matches under any.
     */
    fun match(e: ScreenEffects, upscaler: Int): Look? = LOOKS.firstOrNull { l ->
        val t = l.effects
        t.brightness == e.brightness && t.contrast == e.contrast && abs(t.gamma - e.gamma) < 0.005f &&
            t.saturation == e.saturation && t.effectiveCas == e.effectiveCas &&
            t.fxaa == e.fxaa && t.crt == e.crt && t.toon == e.toon && t.ntsc == e.ntsc && t.deband == e.deband &&
            (l.scalingMode == null || l.scalingMode == upscaler)
    }
}
