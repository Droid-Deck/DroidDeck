package com.droiddeck.launcher.core

import com.droiddeck.launcher.R

object FexPreset {
    class Preset(val id: String, val label: Int, val detail: Int, val env: List<String>)

    private fun tso(main: Int, vector: Int, memcpy: Int, halfBarrier: Int) = listOf(
        "FEX_TSOENABLED=$main", "FEX_VECTORTSOENABLED=$vector", "FEX_MEMCPYSETTSOENABLED=$memcpy", "FEX_HALFBARRIERTSOENABLED=$halfBarrier",
    )

    val all: List<Preset> = listOf(
        Preset("", R.string.fex_default_label, R.string.fex_default_detail, emptyList()),
        Preset("STABILITY", R.string.fex_stability_label, R.string.fex_stability_detail, tso(1, 1, 1, 1) + listOf("FEX_X87REDUCEDPRECISION=0", "FEX_MULTIBLOCK=0")),
        Preset("COMPATIBILITY", R.string.fex_compatibility_label, R.string.fex_compatibility_detail, tso(1, 1, 1, 1) + listOf("FEX_X87REDUCEDPRECISION=0", "FEX_MULTIBLOCK=1")),
        Preset("INTERMEDIATE", R.string.fex_intermediate_label, R.string.fex_intermediate_detail, tso(1, 0, 0, 1) + listOf("FEX_X87REDUCEDPRECISION=1", "FEX_MULTIBLOCK=1")),
        Preset("PERFORMANCE", R.string.fex_performance_label, R.string.fex_performance_detail, tso(0, 0, 0, 0) + listOf("FEX_X87REDUCEDPRECISION=1", "FEX_MULTIBLOCK=1")),
        Preset("PERFORMANCE_TSO", R.string.fex_performance_tso_label, R.string.fex_performance_tso_detail, tso(1, 0, 0, 0) + listOf("FEX_X87REDUCEDPRECISION=1", "FEX_MULTIBLOCK=1")),
        Preset("EXTREME", R.string.fex_extreme_label, R.string.fex_extreme_detail,
            tso(0, 0, 0, 0) + listOf("FEX_X87REDUCEDPRECISION=1", "FEX_MULTIBLOCK=1", "FEX_SMCCHECKS=none", "FEX_DISABLEL2CACHE=1", "FEX_DYNAMICL1CACHE=1", "FEX_DYNAMICL1CACHEINCREASECOUNTHEURISTIC=250", "FEX_DYNAMICL1CACHEDECREASECOUNTHEURISTIC=50")),
        Preset("EXTREME_TSO", R.string.fex_extreme_tso_label, R.string.fex_extreme_tso_detail,
            tso(1, 0, 0, 0) + listOf("FEX_X87REDUCEDPRECISION=1", "FEX_MULTIBLOCK=1", "FEX_SMCCHECKS=none", "FEX_DISABLEL2CACHE=1", "FEX_DYNAMICL1CACHE=1", "FEX_DYNAMICL1CACHEINCREASECOUNTHEURISTIC=250", "FEX_DYNAMICL1CACHEDECREASECOUNTHEURISTIC=50")),
        Preset("EXTREME_GN", R.string.fex_extreme_gn_label, R.string.fex_extreme_gn_detail,
            tso(0, 0, 0, 0) + listOf("FEX_X87REDUCEDPRECISION=1", "FEX_MULTIBLOCK=1", "FEX_SMALLTSCSCALE=1", "FEX_VOLATILEMETADATA=1")),
        Preset("DENUVO", R.string.fex_denuvo_label, R.string.fex_denuvo_detail,
            tso(0, 0, 0, 0) + listOf("FEX_X87REDUCEDPRECISION=1", "FEX_MULTIBLOCK=1", "FEX_SMCCHECKS=full", "FEX_HIDEHYPERVISORBIT=1")),
    )

    fun byId(id: String): Preset = all.firstOrNull { it.id == id } ?: all[0]

    /** Game-launch variables in KEY=VALUE form. */
    fun env(id: String): List<String> = byId(id).env
}
