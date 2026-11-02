package com.droiddeck.launcher.core

import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

object SystemInsets {
    fun fit(view: View) {
        if (Build.VERSION.SDK_INT < 35) return
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            target.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
