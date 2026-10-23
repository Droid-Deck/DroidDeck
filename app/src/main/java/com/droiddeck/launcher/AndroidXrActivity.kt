package com.droiddeck.launcher

import android.os.Bundle

class AndroidXrActivity : GuestXrActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        intent.putExtra("xr_input", "hands")
        super.onCreate(savedInstanceState)
    }
}
