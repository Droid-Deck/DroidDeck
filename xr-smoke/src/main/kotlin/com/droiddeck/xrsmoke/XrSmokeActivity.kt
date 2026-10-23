package com.droiddeck.xrsmoke

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.droiddeck.xr.XrPlatform

class XrSmokeActivity : Activity() {
    private lateinit var report: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            text = "DroidDeck XR runtime test\nPhysical OpenXR headset required (Android XR or Quest).\nEmulators validate the 2D launcher only."
            textSize = 20f
        })
        val emulator = Build.HARDWARE in setOf("ranchu", "goldfish", "cutf_cvm") ||
            Build.PRODUCT.startsWith("sdk_") || Build.FINGERPRINT.startsWith("generic")
        content.addView(Button(this).apply {
            text = "Start OpenXR hand interaction test"
            isEnabled = !emulator && (packageManager.hasSystemFeature(XrPlatform.ANDROID_OPENXR_FEATURE) || packageManager.hasSystemFeature("oculus.software.handtracking"))
            setOnClickListener { startActivity(Intent(this@XrSmokeActivity, RuntimeProbeActivity::class.java)) }
        })
        content.addView(Button(this).apply {
            text = "Read last runtime report"
            setOnClickListener { readReport() }
        })
        report = TextView(this).apply { textSize = 14f }
        content.addView(report)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        if (::report.isInitialized) readReport()
    }

    private fun readReport() {
        val file = (getExternalFilesDir(null) ?: filesDir).resolve("xr-health.json")
        report.text = runCatching { file.readText().take(65536) }.getOrDefault("No runtime report yet.")
    }
}
