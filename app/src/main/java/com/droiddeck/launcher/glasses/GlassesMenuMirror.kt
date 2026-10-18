package com.droiddeck.launcher.glasses

import android.app.Activity
import android.app.Application
import android.app.Presentation
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.droiddeck.launcher.SessionActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

object GlassesMenuMirror {
    private var controller: GlassesMenuController? = null

    fun initialize(app: Application) {
        if (controller != null) return
        val pose = GlassesMenuPose()
        val menus = GlassesMenuController(pose,
            display = Glasses::glassesDisplay,
            observe = { active -> if (active) Glasses.addPoseObserver(pose) else Glasses.removePoseObserver(pose) },
            create = { activity, display, failure -> MenuPresentation(activity, display, pose, failure) })
        controller = menus
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = menus.resume(activity)
            override fun onActivityPaused(activity: Activity) = menus.pause(activity)
            override fun onActivityDestroyed(activity: Activity) = menus.pause(activity)
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            combine(Glasses.state, Glasses.settings) { state, settings -> state to settings }
                .collect { (state, settings) -> menus.update(state, settings) }
        }
    }

    fun setSessionMenuVisible(activity: Activity, visible: Boolean) {
        controller?.setSessionMenuVisible(activity, visible)
    }

    fun recenter() { controller?.recenter() }
}

internal class GlassesMenuController(
    private val pose: GlassesMenuPose,
    private val display: () -> Display?,
    private val observe: (Boolean) -> Unit,
    private val create: (Activity, Display, (Throwable) -> Unit) -> Output,
    private val schedule: (() -> Unit) -> Unit = { android.os.Handler(android.os.Looper.getMainLooper()).post(it) },
) {
    interface Output {
        fun configure(state: GlassesState, settings: GlassesSettings)
        fun show()
        fun close()
    }

    private var activity: Activity? = null
    private val sessionMenus = java.util.WeakHashMap<Activity, Boolean>()
    private var state = GlassesState()
    private var settings = GlassesSettings()
    private var output: Output? = null
    private var outputDisplay = Display.INVALID_DISPLAY
    private var failed = false
    private var dismissalRetried = false
    private var generation = 0

    fun resume(owner: Activity) {
        if (activity !== owner) close()
        activity = owner
        failed = false
        dismissalRetried = false
        reconcile()
    }

    fun pause(owner: Activity) {
        if (activity !== owner) return
        close()
        activity = null
    }

    fun setSessionMenuVisible(owner: Activity, visible: Boolean) {
        if (visible) sessionMenus[owner] = true else sessionMenus.remove(owner)
        if (activity === owner) reconcile()
    }

    fun update(next: GlassesState, preferences: GlassesSettings) {
        val deviceChanged = state.canControl != next.canControl || state.model != next.model
        if (deviceChanged) pose.reset()
        if (deviceChanged || state.displayId != next.displayId || state.width != next.width ||
            state.height != next.height || state.sbs != next.sbs || settings != preferences) {
            failed = false
            dismissalRetried = false
        }
        state = next
        settings = preferences
        pose.configure(preferences)
        reconcile()
    }

    fun recenter() = pose.recenter()

    private fun reconcile() {
        val owner = activity
        val eligible = owner != null && !owner.isFinishing && !owner.isDestroyed && !owner.isInPictureInPictureMode &&
            owner.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0 &&
            owner !is com.droiddeck.launcher.QuestXrActivity &&
            (owner !is SessionActivity || sessionMenus[owner] == true) &&
            settings.gameOnGlasses && settings.screen3dof && state.usable && state.canControl && state.supportsScreen3dof
        val target = if (eligible && !failed) display() else null
        if (target == null || owner == null || owner.windowManager.defaultDisplay.displayId == target.displayId) {
            close()
            return
        }
        if (outputDisplay != target.displayId) close()
        try {
            output?.let { it.configure(state, settings); return }
            val token = ++generation
            val next = create(owner, target) { error ->
                if (generation == token) {
                    close()
                    if (error is GlassesMenuDisplayStopped && !dismissalRetried) {
                        dismissalRetried = true
                        val retry = generation
                        schedule { if (generation == retry && activity === owner) reconcile() }
                    } else {
                        Log.w("GlassesMenu", "Tracked menus are unavailable for this display", error)
                        failed = true
                    }
                }
            }
            if (generation != token) {
                next.close()
                return
            }
            output = next
            outputDisplay = target.displayId
            next.configure(state, settings)
            if (generation != token) return
            observe(true)
            next.show()
        } catch (error: RuntimeException) {
            Log.w("GlassesMenu", "Could not show tracked menus", error)
            failed = true
            close()
        }
    }

    private fun close() {
        ++generation
        val previous = output
        output = null
        outputDisplay = Display.INVALID_DISPLAY
        if (previous != null) {
            observe(false)
            try { previous.close() }
            catch (error: RuntimeException) { Log.w("GlassesMenu", "Could not close tracked menus", error) }
        }
    }
}

internal class GlassesMenuDisplayStopped : IllegalStateException("The glasses menu display stopped")

private class MenuPresentation(
    private val owner: Activity,
    display: Display,
    pose: GlassesMenuPose,
    private val onFailure: (Throwable) -> Unit,
) : Presentation(owner, display), GlassesMenuController.Output {
    private var closed = false
    private val capture: GlassesMenuCapture = GlassesMenuCapture(owner,
        onSize = { width, height -> surface.setSourceSize(width, height) }, onFailure = onFailure)
    private val surface: GlassesMenuSurface = GlassesMenuSurface(context, pose, onFailure = onFailure) { texture, input ->
        if (!closed) {
            capture.attach(texture, input)
            capture.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(surface)
    }

    override fun configure(state: GlassesState, settings: GlassesSettings) {
        surface.configure(settings, state)
        val mode = display.mode
        val hz = if (state.sbs) settings.sbsHz else settings.refreshHz
        val best = display.supportedModes.filter {
            it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight
        }.minByOrNull { kotlin.math.abs(it.refreshRate - hz) }
        if (best != null) window?.let { it.attributes = it.attributes.apply { preferredDisplayModeId = best.modeId } }
    }

    override fun close() {
        if (closed) return
        closed = true
        capture.stop()
        surface.close()
        dismiss()
    }

    override fun onStop() {
        capture.stop()
        surface.close()
        super.onStop()
        if (!closed) {
            closed = true
            onFailure(GlassesMenuDisplayStopped())
        }
    }
}
