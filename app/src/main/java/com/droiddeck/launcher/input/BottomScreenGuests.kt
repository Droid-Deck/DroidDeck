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
import java.util.concurrent.ConcurrentHashMap

object BottomScreenGuests {
    enum class Top { OWN, HOME, GUEST, NONE }

    internal const val MIN_COVER = 0.5f

    internal fun classify(pkg: String?, own: String, homes: Set<String>): Top = when (pkg) {
        null -> Top.NONE
        own -> Top.OWN
        in homes -> Top.HOME
        else -> Top.GUEST
    }

    internal fun taken(top: Top, hiddenArrival: Boolean): Boolean = top == Top.GUEST || (top == Top.OWN && hiddenArrival)

    internal fun ignorable(type: Int): Boolean = type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
        type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
        type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER ||
        type == AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY

    fun supported(model: String = Build.MODEL, sdk: Int = Build.VERSION.SDK_INT): Boolean =
        sdk >= 33 && DualScreen.yieldsBottom(model)
}

class BottomScreenWatch(private val service: AccessibilityService) {
    private class Arrival(val windowId: Int, val pkg: String)

    private val thread = HandlerThread("bottom-screen").apply { start() }
    private val handler = Handler(thread.looper)
    private val arrivals = ConcurrentHashMap<Int, Arrival>()
    private val own = service.packageName
    private var homes: Set<String> = emptySet()
    private var holders: Map<Int, String?> = emptyMap()
    private val check = Runnable { evaluate() }

    fun start() {
        service.serviceInfo = service.serviceInfo?.apply {
            eventTypes = eventTypes or AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        handler.post {
            homes = homePackages()
            evaluate()
        }
    }

    fun onEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (event.displayId == Display.DEFAULT_DISPLAY || pkg == own) return
                arrivals[event.displayId] = Arrival(event.windowId, pkg)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> Unit
            else -> return
        }
        handler.removeCallbacks(check)
        handler.postDelayed(check, 150)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        DualScreen.setTaken(emptySet())
    }

    private fun evaluate() {
        val all = runCatching { service.windowsOnAllDisplays }.getOrNull() ?: return
        val displays = service.getSystemService(DisplayManager::class.java) ?: return
        val taken = mutableMapOf<Int, String?>()
        for (i in 0 until all.size()) {
            val id = all.keyAt(i)
            if (id == Display.DEFAULT_DISPLAY) continue
            val display = displays.getDisplay(id) ?: continue
            val windows = all.valueAt(i)
            val topPkg = windows.asSequence()
                .filter { !BottomScreenGuests.ignorable(it.type) && cover(it, display) >= BottomScreenGuests.MIN_COVER }
                .sortedByDescending { it.layer }
                .firstNotNullOfOrNull { packageOf(it) }
            val top = BottomScreenGuests.classify(topPkg, own, homes)
            val arrival = arrivals[id]?.takeUnless { a -> a.pkg in homes || windows.any { it.id == a.windowId } }
            if (top != BottomScreenGuests.Top.OWN || arrival == null) arrivals.remove(id)
            if (BottomScreenGuests.taken(top, arrival != null)) taken[id] = if (top == BottomScreenGuests.Top.GUEST) topPkg else arrival?.pkg
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
                    .mapNotNull { it.activityInfo?.packageName }
            }.getOrDefault(emptyList())
        }.toSet()
    }

    private companion object {
        const val TAG = "BottomScreen"
    }
}
