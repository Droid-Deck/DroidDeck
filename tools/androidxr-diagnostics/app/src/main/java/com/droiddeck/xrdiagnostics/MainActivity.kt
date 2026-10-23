package com.droiddeck.xrdiagnostics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.SpatialApiVersionHelper
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.InteractableComponent
import androidx.xr.scenecore.PanelEntity
import androidx.xr.scenecore.scene
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val bridge = NativeBridge()
    private var handle = 0L
    private var generation = 1L
    private var epoch = 1L
    private var fixtureMode = false
    private var focused = false
    private var resumed = false
    private var provider: JetpackProvider? = null
    private var starting = false
    private var panel: PanelEntity? = null
    private var loop: Job? = null
    private lateinit var status: TextView
    private lateinit var grid: Grid
    private var last = JSONObject()
    private var injected = JSONObject().put("status", "not run")
    private var roundTrip = false
    private var runtimeStatus = "Session not created"
    private val observations = JSONObject().put("headPose", false).put("leftPalm", false).put("rightPalm", false)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        provider?.configure() ?: startSession()
    }
    private val originChanged = Runnable {
        if (!fixtureMode) {
            generation++; provider?.release()
            bridge.begin(handle, 2, epoch, generation)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle = bridge.create()
        bridge.begin(handle, 2, epoch, generation)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 20, 20, 20) }
        status = TextView(this).apply { textSize = 15f }
        grid = Grid(this)
        layout.addView(Button(this).apply { text = "Live Jetpack / request head pose"; setOnClickListener {
            selectLive(); permission.launch(arrayOf(JetpackProvider.HEAD))
        } })
        layout.addView(Button(this).apply { text = "Request raw hand joints (optional)"; setOnClickListener {
            permission.launch(arrayOf(JetpackProvider.HAND))
        } })
        if (BuildConfig.DEBUG) layout.addView(Button(this).apply { text = "SYNTHETIC fixtures + shared tests"; setOnClickListener { selectFixtures() } })
        layout.addView(Button(this).apply { text = "New origin / release inputs"; setOnClickListener { originChanged.run() } })
        layout.addView(Button(this).apply { text = "Export JSON report"; setOnClickListener { export() } })
        layout.addView(grid, LinearLayout.LayoutParams(-1, 300))
        layout.addView(status)
        setContentView(ScrollView(this).apply { addView(layout) })
        if (BuildConfig.DEBUG && intent.getStringExtra("provider") == "fixture") selectFixtures() else startSession()
        loop = lifecycleScope.launch {
            var step = 0
            while (true) {
                if (fixtureMode) {
                    last = JSONObject(bridge.fixture(handle, step % 13, System.nanoTime()))
                    step++
                } else {
                    step = 0
                    liveSample()
                }
                roundTrip = bridge.roundTrip(handle, filesDir.path)
                render(); export()
                delay(if (fixtureMode) 500 else 100)
            }
        }
    }

    private fun startSession() {
        if (starting || provider != null) return
        starting = true
        lifecycleScope.launch {
            try {
                when (val result = Session.create(this@MainActivity)) {
                    is SessionCreateSuccess -> {
                        val session = result.session
                        provider = JetpackProvider(this@MainActivity, session).also { it.configure() }
                        runtimeStatus = "Jetpack XR 1.0.0 SessionCreateSuccess"
                        session.scene.requestFullSpace()
                        session.scene.activitySpace.addOriginChangedListener(originChanged)
                        val target = TextView(this@MainActivity).apply {
                            text = "PINCH HERE\nSceneCore HANDS input target"; textSize = 22f
                            setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(25, 70, 100))
                        }
                        panel = PanelEntity.create(session, target, FloatSize2d(.6f, .3f), "Pinch target",
                            Pose(Vector3(.7f, 0f, -1.5f)))
                        panel?.addComponent(InteractableComponent.create(session, mainExecutor) {
                            if (!fixtureMode) { provider?.event(it); liveSample(); render() }
                        })
                    }
                    else -> runtimeStatus = "Session unavailable: $result"
                }
            } catch (e: Exception) { runtimeStatus = "Session unavailable: ${e.javaClass.simpleName}: ${e.message}" }
            starting = false
        }
    }

    private fun selectLive() {
        fixtureMode = false; epoch++; generation = 1
        provider?.release(); bridge.begin(handle, 2, epoch, generation)
        startSession()
    }

    private fun selectFixtures() {
        if (!BuildConfig.DEBUG) return
        fixtureMode = true; provider?.release()
        injected = JSONObject(bridge.tests(filesDir.path))
    }

    private fun liveSample() {
        val now = System.nanoTime()
        val p = provider
        val values = try { p?.values(now) ?: FloatArray(30) } catch (e: Exception) {
            p?.release(); runtimeStatus = "Tracking sample unavailable: ${e.javaClass.simpleName}: ${e.message}"
            FloatArray(30)
        }
        val caps = (if (p?.headEnabled == true) 1 else 0) or (if (p?.handsEnabled == true) 34 else 0) or (if (p != null) 4 else 0)
        last = JSONObject(bridge.update(handle, epoch, generation, now, now, p != null && resumed, focused, values, caps))
        if (last.optJSONObject("head")?.optInt("flags") == 15) observations.put("headPose", true)
        last.optJSONArray("hands")?.let { hands ->
            for (side in 0..1) if (hands.getJSONObject(side).getJSONObject("pose").optInt("flags") == 15)
                observations.put(if (side == 0) "leftPalm" else "rightPalm", true)
        }
    }

    private fun render() {
        val label = if (fixtureMode) "SYNTHETIC FIXTURES — no live tracking claim" else "LIVE JETPACK — ArDevice / Hand.PALM / SceneCore InputEvent"
        status.text = "$label\n$runtimeStatus\n${provider?.status ?: "Tracking unavailable"}\n" +
            "API ${Build.VERSION.SDK_INT}, ABI ${Build.SUPPORTED_ABIS.joinToString()}, image ${Build.FINGERPRINT}\n" +
            "Right-hand pinch events: ${provider?.rightPinchEvents ?: 0}; grasp unavailable in live adapter\n" +
            "Receipt age and validity (no predicted display time):\n${last.toString(1)}\nRound trip=$roundTrip\nInjected=$injected"
        grid.sample = last; grid.invalidate()
    }

    private fun export() {
        val report = JSONObject().put("source", if (fixtureMode) "synthetic" else "jetpack")
            .put("runtime", runtimeStatus).put("sdk", Build.VERSION.SDK_INT).put("abi", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("systemImage", Build.FINGERPRINT).put("liveJetpackObservations", observations)
            .put("spatialApiVersion", SpatialApiVersionHelper.spatialApiVersion)
            .put("runtimeFrameAgeNs", provider?.session?.state?.value?.timeMark?.elapsedNow()?.inWholeNanoseconds ?: -1)
            .put("rightHandPinchEvents", provider?.rightPinchEvents ?: 0)
            .put("capabilityStatus", provider?.status ?: "runtime unavailable")
            .put("headPermission", checkSelfPermission(JetpackProvider.HEAD))
            .put("handPermission", checkSelfPermission(JetpackProvider.HAND))
            .put("advertisedFeatures", JSONArray(packageManager.systemAvailableFeatures.mapNotNull { it.name }.filter { it.contains("xr") }))
            .put("unavailable", JSONArray(listOf("live grasp", "OpenXR emulator startup", "guest graphics", "SteamVR gameplay", "continuous pinch strength")))
            .put("sampleClock", "device CLOCK_MONOTONIC receipt time; runtime CoreState age checked separately")
            .put("sample", last).put("packetRoundTrip", roundTrip).put("injectedTests", injected)
            .put("failures", JSONArray(buildList {
                if (!roundTrip) add("packet roundtrip failed")
                if (fixtureMode && !injected.optBoolean("passed")) add("shared fixture tests failed")
            }))
        File(filesDir, "report.json").writeText(report.toString(2))
        Log.i("DroidDeckXRDiag", report.toString())
    }

    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { resumed = false; provider?.release(); if (!fixtureMode) liveSample(); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus); focused = hasFocus
        if (!hasFocus) provider?.release()
        if (handle != 0L && !fixtureMode) liveSample()
    }
    override fun onDestroy() {
        loop?.cancel(); panel?.removeAllComponents(); panel?.setEnabled(false)
        provider?.session?.scene?.activitySpace?.removeOriginChangedListener(originChanged)
        bridge.destroy(handle); handle = 0
        super.onDestroy()
    }

    private class Grid(context: Context) : View(context) {
        var sample = JSONObject()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = Color.DKGRAY; paint.strokeWidth = 1f
            for (i in -4..4) {
                canvas.drawLine(width/2f + i*40f, 0f, width/2f + i*40f, height.toFloat(), paint)
                canvas.drawLine(0f, height/2f + i*40f, width.toFloat(), height/2f + i*40f, paint)
            }
            paint.color = Color.WHITE; paint.textSize = 24f
            canvas.drawText("Top-down X/Z reference: grid = 0.25 m; markers only for valid positions", 5f, 25f, paint)
            fun marker(pose: JSONObject?, color: Int, name: String) {
                if (pose == null || pose.optInt("flags") and 2 == 0) return
                val p = pose.getJSONArray("meters")
                val x = width/2f + p.getDouble(0).toFloat()*160f
                val z = height/2f + p.getDouble(2).toFloat()*160f
                paint.color = color; canvas.drawCircle(x, z, 8f, paint); canvas.drawText(name, x + 10, z, paint)
            }
            marker(sample.optJSONObject("head"), Color.WHITE, "H")
            sample.optJSONArray("hands")?.let {
                marker(it.getJSONObject(0).optJSONObject("pose"), Color.CYAN, "L")
                marker(it.getJSONObject(1).optJSONObject("pose"), Color.YELLOW, "R")
            }
        }
    }
}
