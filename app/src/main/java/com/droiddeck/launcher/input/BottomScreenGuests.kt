package com.droiddeck.launcher.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
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

    internal fun ignorable(type: Int): Boolean = type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
        type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
        type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER ||
        type == AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY

    fun supported(model: String = Build.MODEL, sdk: Int = Build.VERSION.SDK_INT): Boolean =
        sdk >= 33 && DualScreen.yieldsBottom(model)
}

class BottomScreenWatch(private val service: AccessibilityService) {
    private class Arrival(val pkg: String)

    private val thread = HandlerThread("bottom-screen").apply { start() }
    private val handler = Handler(thread.looper)
    private val own = service.packageName
    private val arrivals = ConcurrentHashMap<Int, Arrival>()
    private var homes: Set<String> = emptySet()
    private var keyboards: Set<String> = emptySet()
    private var holders: Map<Int, String?> = emptyMap()
    private val check = Runnable { evaluate() }

    fun start() {
        service.serviceInfo = service.serviceInfo?.apply {
            eventTypes = eventTypes or AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        handler.post {
            homes = homePackages()
            keyboards = keyboardPackages()
            evaluate()
        }
    }

    fun onEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (event.displayId == Display.DEFAULT_DISPLAY || pkg == own || pkg == SYSTEM_UI) return
                arrivals[event.displayId] = Arrival(pkg)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> Unit
            else -> return
        }
        handler.removeCallbacks(check)
        handler.postDelayed(check, 150)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        handler.post { DualScreen.setTaken(emptySet()) }
        thread.quitSafely()
    }

    private fun evaluate() {
        val seen = HashMap(arrivals)
        val all = runCatching { service.windowsOnAllDisplays }.getOrNull() ?: return
        val displays = service.getSystemService(DisplayManager::class.java) ?: return
        val taken = mutableMapOf<Int, String?>()
        for (i in 0 until all.size()) {
            val id = all.keyAt(i)
            if (id == Display.DEFAULT_DISPLAY) continue
            val display = displays.getDisplay(id)?.takeIf(DualScreen::builtIn) ?: continue
            val windows = all.valueAt(i)
            val topPkg = windows.asSequence()
                .filter { !BottomScreenGuests.ignorable(it.type) && cover(it, display) >= BottomScreenGuests.MIN_COVER }
                .sortedByDescending { it.layer }
                .firstNotNullOfOrNull { packageOf(it)?.takeUnless { pkg -> pkg == own } }
            val arrival = seen[id]
            if (topPkg != null && arrival != null) arrivals.remove(id, arrival)
            BottomScreenGuests.holder(topPkg, arrival?.pkg?.takeUnless { it in keyboards }, homes)?.let { taken[id] = it }
        }
        if (taken != holders) Log.i(TAG, "bottom screen holders: $taken")
        holders = taken
        DualScreen.setTaken(taken.keys)
    }

    private fun cover(window: AccessibilityWindowInfo, display: Display): Float {
        val bounds = Rect().also(window::getBoundsInScreen)
        val area = display.mode.physicalWidth.toFloat() * display.mode.physicalHeight
        return if (area <= 0f) 0f else bounds.width().toFloat() * bounds.height() / area
    }

    private fun packageOf(window: AccessibilityWindowInfo): String? =
        runCatching { window.getRoot(0)?.packageName?.toString() }.getOrNull()

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
        const val TAG = "BottomScreen"
        const val SYSTEM_UI = "com.android.systemui"
    }
}
