package com.droiddeck.xrsmoke

import android.app.NativeActivity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

class RuntimeProbeActivity : NativeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (!intent.hasExtra("xr_input")) intent.putExtra("xr_input", "hands")
        intent.putExtra("xr_smoke", true)
        super.onCreate(savedInstanceState)
    }

    fun onXrActive(active: Boolean) = Unit
    fun onXrFrame(monotonicNs: Long) = Unit
    fun onXrStatus(): Int = -1
    fun onXrError(reason: String) {
        runOnUiThread {
            setResult(RESULT_CANCELED, Intent().putExtra("error", reason))
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
