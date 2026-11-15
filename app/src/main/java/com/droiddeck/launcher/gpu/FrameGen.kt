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

    fun mode(context: Context): Mode = mode(context, null)

    fun mode(context: Context, appId: String?): Mode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val gamePrefix = appId?.takeIf { it.isNotBlank() }?.let { "game.$it." }
            ?.takeIf { prefs.contains(it + "engine") } ?: ""
        val engine = prefs.getString(gamePrefix + "engine", ENGINE_OFF)
            .let { if (it == LEGACY_ENGINE_WINFG) ENGINE_DDFG else it ?: ENGINE_OFF }
        val target = prefs.getInt(gamePrefix + "target", 0)
        if (engine == ENGINE_LSFG && target in ADAPTIVE_TARGETS) return Mode(engine, target = target)
        return Mode(engine, prefs.getInt(gamePrefix + "multiplier", 2).coerceIn(2, 4))
    }

    fun hasGameOverride(context: Context, appId: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("game.$appId.engine")

    fun setForGame(context: Context, appId: String, mode: Mode) {
        val prefix = "game.$appId."
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(prefix + "engine", mode.engine)
            .putInt(prefix + "multiplier", mode.multiplier)
            .putInt(prefix + "target", mode.target)
            .apply()
    }

    fun clearGame(context: Context, appId: String) {
        val prefix = "game.$appId."
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(prefix + "engine")
            .remove(prefix + "multiplier")
            .remove(prefix + "target")
            .apply()
    }

    fun set(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("engine", mode.engine).putInt("multiplier", mode.multiplier).putInt("target", mode.target).apply()
    }

    fun clearGlobal(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("engine").remove("multiplier").remove("target").apply()
    }

    fun label(context: Context): String = label(context, mode(context))

    fun label(context: Context, mode: Mode): String = when {
        mode.engine == ENGINE_DDFG -> context.getString(R.string.frame_gen_ddfg, mode.multiplier)
        mode.engine == ENGINE_LSFG && mode.target > 0 -> context.getString(R.string.frame_gen_lsfg_adaptive, mode.target)
        mode.engine == ENGINE_LSFG -> context.getString(R.string.frame_gen_lsfg, mode.multiplier)
        else -> context.getString(R.string.frame_gen_off)
    }

    data class PreparedApply internal constructor(
        val mode: Mode,
        internal val lsfgStatus: Int? = null,
        internal val lsfgCachePath: String? = null,
    )

    fun prepareApply(context: Context, mode: Mode): PreparedApply {
        if (mode.engine != ENGINE_LSFG) return PreparedApply(mode)
        val status = Lossless.sync(context)
        val cachePath = Lossless.cacheFile(context)?.absolutePath
        return PreparedApply(mode, status, cachePath)
    }

    fun apply(context: Context, refreshHz: Float): String? =
        apply(context, mode(context), refreshHz)

    fun apply(context: Context, mode: Mode, refreshHz: Float): String? =
        applyPrepared(context, prepareApply(context, mode), refreshHz)

    fun applyPrepared(context: Context, prepared: PreparedApply, refreshHz: Float): String? {
        val mode = prepared.mode
        when (mode.engine) {
            ENGINE_LSFG -> {
                val status = prepared.lsfgStatus ?: LsfgNative.STATUS_NOT_INSTALLED
                val cachePath = prepared.lsfgCachePath
                if (status != LsfgNative.STATUS_OK || cachePath == null) {
                    WaylandCompositor.nativeSetFrameGenArmed(false, 0, 0)
                    val problem = context.getString(
                        if (status == LsfgNative.STATUS_NOT_INSTALLED) R.string.lsfg_problem_missing else R.string.lsfg_problem_failed,
                    )
                    Log.w(TAG, "$problem (status $status)")
                    return problem
                }
                WaylandCompositor.nativeSetFrameGenEngine(WaylandCompositor.FG_ENGINE_LSFG)
                WaylandCompositor.nativeSetLsfgCachePath(cachePath)
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
