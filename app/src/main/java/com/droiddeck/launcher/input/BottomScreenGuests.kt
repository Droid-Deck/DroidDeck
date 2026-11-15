package com.droiddeck.launcher.input

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import com.droiddeck.shortcuts.Relay
import java.util.concurrent.ConcurrentHashMap

object BottomScreenGuests {
    enum class Top { HOME, GUEST, NONE }

    internal const val MIN_COVER = 0.5f

    internal fun classify(pkg: String?, homes: Set<String>): Top = when (pkg) {
        null -> Top.NONE
        in homes -> Top.HOME
        else -> Top.GUEST
    }

    internal fun holder(topPkg: String?, arrival: String?, homes: Set<String>): String? = when {
        topPkg != null -> topPkg.takeIf { classify(it, homes) == Top.GUEST }
        arrival != null -> arrival.takeIf { classify(it, homes) == Top.GUEST }
        else -> null
    }

    internal fun current(arrivedAt: Long, ownShownAt: Long?): Boolean = ownShownAt == null || arrivedAt >= ownShownAt

    internal fun ignorable(type: Int): Boolean = type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
        type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
        type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER ||
        type == AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY

    fun supported(model: String = Build.MODEL, sdk: Int = Build.VERSION.SDK_INT): Boolean =
        sdk >= 33 && DualScreen.yieldsBottom(model)
}

class BottomScreenWatch(private val service: Context) {
    private class Arrival(val pkg: String, val at: Long)

    private val thread = HandlerThread("bottom-screen").apply { start() }
    private val handler = Handler(thread.looper)
    private val own = service.packageName
    private val arrivals = ConcurrentHashMap<Int, Arrival>()
    private var homes: Set<String> = emptySet()
    private var keyboards: Set<String> = emptySet()
    private var holders: Map<Int, String?> = emptyMap()
    private var ownWindows: Map<Int, Set<Int>> = emptyMap()
    private val ownShownAt = mutableMapOf<Int, Long>()

    fun start() {
        current = this
        handler.post {
            homes = homePackages()
            keyboards = keyboardPackages()
        }
    }

    fun onArrival(displayId: Int, pkg: String, at: Long) {
        if (displayId == Display.DEFAULT_DISPLAY || pkg == own || pkg == SYSTEM_UI) return
        arrivals[displayId] = Arrival(pkg, at)
    }

    fun onWindows(all: Map<Int, List<Relay.Window>>) {
        handler.post { evaluate(all) }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        handler.post {
            if (current === this) {
                current = null
                DualScreen.setTaken(emptySet())
            }
        }
        thread.quitSafely()
    }

    private fun evaluate(all: Map<Int, List<Relay.Window>>) {
        val seen = HashMap(arrivals)
        val displays = service.getSystemService(DisplayManager::class.java) ?: return
        val taken = mutableMapOf<Int, String?>()
        val shown = mutableMapOf<Int, Set<Int>>()
        val now = SystemClock.uptimeMillis()
        for ((id, list) in all) {
            if (id == Display.DEFAULT_DISPLAY) continue
            val display = displays.getDisplay(id)?.takeIf(DualScreen::builtIn) ?: continue
            val windows = list
                .filter { !BottomScreenGuests.ignorable(it.type) && cover(it, display) >= BottomScreenGuests.MIN_COVER }
                .sortedByDescending { it.layer }
            val ours = windows.filter { it.pkg == own }.map { it.id }.toSet()
            if (!ownWindows[id].orEmpty().containsAll(ours)) ownShownAt[id] = now
            shown[id] = ours
            val topPkg = windows.firstNotNullOfOrNull { it.pkg?.takeUnless { pkg -> pkg == own } }
            val arrival = seen[id]?.takeIf { BottomScreenGuests.current(it.at, ownShownAt[id]) }
            seen[id]?.takeIf { topPkg != null || arrival == null }?.let { arrivals.remove(id, it) }
            BottomScreenGuests.holder(topPkg, arrival?.pkg?.takeUnless { it in keyboards }, homes)?.let { taken[id] = it }
        }
        ownWindows = shown
        if (current !== this) return
        if (taken != holders) Log.i(TAG, "bottom screen holders: $taken")
        holders = taken
        DualScreen.setTaken(taken.keys)
    }

    private fun cover(window: Relay.Window, display: Display): Float {
        val area = display.mode.physicalWidth.toFloat() * display.mode.physicalHeight
        return if (area <= 0f) 0f else window.bounds.width().toFloat() * window.bounds.height() / area
    }

    private fun homePackages(): Set<String> {
        val pm = service.packageManager
        return listOf(Intent.CATEGORY_HOME, Intent.CATEGORY_SECONDARY_HOME).flatMap { category ->
            runCatching {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), PackageManager.MATCH_ALL)
                    .filter { it.priority >= 0 }
                    .mapNotNull { it.activityInfo?.packageName }
            }.getOrDefault(emptyList())
        }.toSet()
    }

    private fun keyboardPackages(): Set<String> =
        runCatching {
            service.getSystemService(InputMethodManager::class.java)?.inputMethodList.orEmpty().map { it.packageName }.toSet()
        }.getOrDefault(emptySet())

    private companion object {
        @Volatile var current: BottomScreenWatch? = null

        const val TAG = "BottomScreen"
        const val SYSTEM_UI = "com.android.systemui"
    }
}
