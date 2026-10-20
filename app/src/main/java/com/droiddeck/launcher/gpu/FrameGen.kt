package com.droiddeck.launcher.gpu

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.wayland.WaylandCompositor

object FrameGen {
    const val ENGINE_OFF = "off"
    const val ENGINE_DDFG = "ddfg"
    private const val LEGACY_ENGINE_WINFG = "winfg"
    const val ENGINE_LSFG = "lsfg"

    private const val DDFG_PERF_PRESET = 2
    private const val DDFG_MODEL = 4
    private const val FLOW_SCALE_DDFG = 0.60f
    private const val FLOW_SCALE_LSFG = 0.80f

    val ADAPTIVE_TARGETS = listOf(120, 90, 60)

    private const val PREFS = "frame_gen"

    data class Mode(val engine: String, val multiplier: Int = 2, val target: Int = 0) {
        companion object {
            val OFF = Mode(ENGINE_OFF)
        }
    }

    fun engine(context: Context): String = mode(context).engine

    fun mode(context: Context): Mode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val engine = prefs.getString("engine", ENGINE_OFF).let { if (it == LEGACY_ENGINE_WINFG) ENGINE_DDFG else it ?: ENGINE_OFF }
        val target = prefs.getInt("target", 0)
        if (engine == ENGINE_LSFG && target in ADAPTIVE_TARGETS) return Mode(engine, target = target)
        return Mode(engine, prefs.getInt("multiplier", 2).coerceIn(2, 4))
    }

    fun set(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("engine", mode.engine).putInt("multiplier", mode.multiplier).putInt("target", mode.target).apply()
    }

    fun label(context: Context): String = label(context, mode(context))

    fun label(context: Context, mode: Mode): String = when {
        mode.engine == ENGINE_DDFG -> context.getString(R.string.frame_gen_ddfg, mode.multiplier)
        mode.engine == ENGINE_LSFG && mode.target > 0 -> context.getString(R.string.frame_gen_lsfg_adaptive, mode.target)
        mode.engine == ENGINE_LSFG -> context.getString(R.string.frame_gen_lsfg, mode.multiplier)
        else -> context.getString(R.string.frame_gen_off)
    }

    fun apply(context: Context, refreshHz: Float): String? {
        val mode = mode(context)
        when (mode.engine) {
            ENGINE_LSFG -> {
                val status = Lossless.sync(context)
                val cache = Lossless.cacheFile(context)
                if (status != LsfgNative.STATUS_OK || cache == null) {
                    WaylandCompositor.nativeSetFrameGenArmed(false, 0, 0)
                    val problem = context.getString(
                        if (status == LsfgNative.STATUS_NOT_INSTALLED) R.string.lsfg_problem_missing else R.string.lsfg_problem_failed,
                    )
                    Log.w(TAG, "$problem (status $status)")
                    return problem
                }
                WaylandCompositor.nativeSetFrameGenEngine(WaylandCompositor.FG_ENGINE_LSFG)
                WaylandCompositor.nativeSetLsfgCachePath(cache.absolutePath)
                WaylandCompositor.nativeSetFrameGenTuning(FLOW_SCALE_LSFG, refreshHz)
                WaylandCompositor.nativeSetFrameGenArmed(true, mode.multiplier, mode.target)
            }
            ENGINE_DDFG -> {
                WaylandCompositor.nativeSetFrameGenEngine(WaylandCompositor.FG_ENGINE_DDFG)
                WaylandCompositor.nativeSetDdFgTuning(DDFG_MODEL, DDFG_PERF_PRESET)
                WaylandCompositor.nativeSetFrameGenTuning(FLOW_SCALE_DDFG, refreshHz)
                WaylandCompositor.nativeSetFrameGenArmed(true, mode.multiplier, 0)
            }
            else -> WaylandCompositor.nativeSetFrameGenArmed(false, 0, 0)
        }
        Log.i(TAG, "frame generation: $mode at ${refreshHz}Hz")
        return null
    }

    private const val TAG = "FrameGen"
}
