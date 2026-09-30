package com.droiddeck.launcher.gpu

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.wayland.WaylandCompositor

/**
 * The frame-generation setting and how it reaches the compositor.
 *
 * Both engines generate on gamescope's final output inside our compositor, so they work for any
 * game with nothing changed in the runtime. Win-FG is our own and needs nothing; LSFG needs the
 * shader chain from the user's copy of Lossless Scaling (see [Lossless]). Every compositor
 * setter is a value store on the native side, so this can be applied before the compositor is
 * up and changed live from the main screen while a session runs.
 */
object FrameGen {
    const val ENGINE_OFF = "off"
    const val ENGINE_WINFG = "winfg"
    const val ENGINE_LSFG = "lsfg"

    /** Win-FG's Performance preset, pinned: changing it live rebuilds the whole chain. */
    private const val WINFG_PERF_PRESET = 2
    /** Bidirectional flow with the occlusion gate - the chain's own default. */
    private const val WINFG_MODEL = 4
    /** Optical-flow resolution as a fraction of the output; Bannerlator's per-engine defaults. */
    private const val FLOW_SCALE_WINFG = 0.60f
    private const val FLOW_SCALE_LSFG = 0.80f

    private const val PREFS = "frame_gen"

    fun engine(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("engine", ENGINE_OFF) ?: ENGINE_OFF

    fun multiplier(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("multiplier", 2).coerceIn(2, 4)

    fun set(context: Context, engine: String, multiplier: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("engine", engine).putInt("multiplier", multiplier).apply()
    }

    /** "Off", "Win-FG 2×", "LSFG 3×". */
    fun label(context: Context): String = label(context, engine(context), multiplier(context))

    fun label(context: Context, engine: String, multiplier: Int): String = when (engine) {
        ENGINE_WINFG -> context.getString(R.string.frame_gen_winfg, multiplier)
        ENGINE_LSFG -> context.getString(R.string.frame_gen_lsfg, multiplier)
        else -> context.getString(R.string.frame_gen_off)
    }

    /**
     * Pushes the saved setting into the compositor. Returns a message for the user when the
     * engine cannot run (LSFG without Lossless Scaling), or null. Blocks while a new or updated
     * Lossless.dll is prepared.
     */
    fun apply(context: Context, refreshHz: Float): String? {
        val engine = engine(context)
        val multiplier = multiplier(context)
        when (engine) {
            ENGINE_LSFG -> {
                val status = Lossless.sync(context)
                val cache = Lossless.cacheFile(context)
                if (status != LsfgNative.STATUS_OK || cache == null) {
                    WaylandCompositor.nativeSetFrameGenArmed(false, 0)
                    val problem = context.getString(
                        if (status == LsfgNative.STATUS_NOT_INSTALLED) R.string.lsfg_problem_missing else R.string.lsfg_problem_failed,
                    )
                    Log.w(TAG, "$problem (status $status)")
                    return problem
                }
                WaylandCompositor.nativeSetFrameGenEngine(WaylandCompositor.FG_ENGINE_LSFG)
                WaylandCompositor.nativeSetLsfgCachePath(cache.absolutePath)
                WaylandCompositor.nativeSetFrameGenTuning(FLOW_SCALE_LSFG, refreshHz)
                WaylandCompositor.nativeSetFrameGenArmed(true, multiplier)
            }
            ENGINE_WINFG -> {
                WaylandCompositor.nativeSetFrameGenEngine(WaylandCompositor.FG_ENGINE_WINFG)
                WaylandCompositor.nativeSetWinFgTuning(WINFG_MODEL, WINFG_PERF_PRESET)
                WaylandCompositor.nativeSetFrameGenTuning(FLOW_SCALE_WINFG, refreshHz)
                WaylandCompositor.nativeSetFrameGenArmed(true, multiplier)
            }
            else -> WaylandCompositor.nativeSetFrameGenArmed(false, 0)
        }
        Log.i(TAG, "frame generation: $engine x$multiplier at ${refreshHz}Hz")
        return null
    }

    private const val TAG = "FrameGen"
}
