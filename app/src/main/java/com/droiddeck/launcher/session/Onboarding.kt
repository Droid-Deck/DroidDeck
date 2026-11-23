package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import kotlinx.coroutines.flow.MutableStateFlow

/** What first run installs by itself, in the order it is needed, and how far each part has got. */
object Onboarding {
    enum class Step { RUNTIME, PROTON, STEAM }

    data class Progress(val label: String, val percent: Int)

    data class Facts(val runtime: Boolean = true, val proton: Boolean = true, val steam: Boolean = true)

    enum class State { DONE, ACTIVE, WAITING, FAILED }

    data class Row(val step: Step, val state: State, val label: String?, val percent: Int)

    val active = MutableStateFlow<Map<Step, Progress>>(emptyMap())
    val failed = MutableStateFlow<Map<Step, String>>(emptyMap())
    val facts = MutableStateFlow(Facts())

    fun recheck(context: Context) {
        val runtime = LinuxRuntime.isInstalled(context)
        val own = ProtonExtras.tools.first { it.id == ProtonExtras.OWN }
        facts.value = Facts(
            runtime = runtime,
            proton = ProtonExtras.declined(context) || ProtonExtras.installedAll(context, own).isNotEmpty(),
            steam = runtime && !SteamClientPrefetch.pending(context),
        )
    }

    fun report(step: Step, label: String, percent: Int) {
        failed.value = failed.value - step
        active.value = active.value + (step to Progress(label, percent))
    }

    fun finish(step: Step) {
        active.value = active.value - step
        failed.value = failed.value - step
    }

    fun fail(step: Step, message: String) {
        active.value = active.value - step
        failed.value = failed.value + (step to message)
    }

    fun rows(active: Map<Step, Progress>, failed: Map<Step, String>, facts: Facts): List<Row> = Step.entries.map { step ->
        val ready = when (step) { Step.RUNTIME -> facts.runtime; Step.PROTON -> facts.proton; Step.STEAM -> facts.steam }
        val progress = active[step]
        when {
            progress != null -> Row(step, State.ACTIVE, progress.label, progress.percent)
            failed[step] != null -> Row(step, State.FAILED, failed[step], -1)
            ready -> Row(step, State.DONE, null, 100)
            else -> Row(step, State.WAITING, null, -1)
        }
    }

    fun pending(rows: List<Row>): Boolean = rows.any { it.state != State.DONE }

    /** The aggregate fraction of the whole first-run work, weighting each step by what it downloads. */
    fun overall(rows: List<Row>): Int {
        val weights = mapOf(Step.RUNTIME to 60, Step.PROTON to 15, Step.STEAM to 25)
        val total = weights.values.sum()
        val got = rows.sumOf { row ->
            val w = weights.getValue(row.step)
            when (row.state) {
                State.DONE -> w
                State.ACTIVE -> if (row.percent >= 0) w * row.percent / 100 else 0
                else -> 0
            }
        }
        return got * 100 / total
    }
}
