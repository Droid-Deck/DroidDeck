package com.droiddeck.launcher.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.runtime.AppImageManager
import java.io.File

/** The imported AppImages and the import in progress, for the Desktop page. */
object AppImageState {
    private val main = Handler(Looper.getMainLooper())

    var items by mutableStateOf<List<AppImageManager.Item>>(emptyList())
        private set
    /** The file being imported and what the import is doing; null when idle. */
    var importing by mutableStateOf<String?>(null)
        private set
    var stage by mutableStateOf<String?>(null)
        private set
    /** Why the last import was refused, shown on the page until the next one. */
    var lastError by mutableStateOf<String?>(null)
        private set

    fun refresh(context: Context) {
        val app = context.applicationContext
        Thread({
            val list = AppImageManager.list(app)
            main.post { items = list }
        }, "appimage-refresh").start()
    }

    fun import(context: Context, file: File) {
        if (importing != null) return
        val app = context.applicationContext
        importing = file.name; stage = "Starting…"; lastError = null
        Thread({
            val problem = try {
                AppImageManager.import(app, file) { s -> main.post { stage = s } }
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            val list = AppImageManager.list(app)
            main.post {
                importing = null; stage = null; items = list
                lastError = problem?.let { "${file.name}: $it" }
                if (problem == null) Toast.makeText(app, "${file.name} imported", Toast.LENGTH_SHORT).show()
            }
        }, "appimage-import").start()
    }

    fun remove(context: Context, id: String) {
        val app = context.applicationContext
        Thread({
            AppImageManager.remove(app, id)
            val list = AppImageManager.list(app)
            main.post { items = list }
        }, "appimage-remove").start()
    }
}
