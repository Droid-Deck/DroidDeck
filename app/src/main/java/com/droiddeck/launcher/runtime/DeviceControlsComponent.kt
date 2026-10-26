package com.droiddeck.launcher.runtime

import android.content.Context
import android.content.Intent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.R
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject
import kotlin.math.roundToInt

class DeviceControlsComponent(context: Context, root: File, private val activityVisible: () -> Boolean = { false }) : SessionPart() {
    private val context = context.applicationContext
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val directory = directory(root)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var pendingBrightness: Float? = null
    private var permissionRequestAt = -5_000L
    private var previous = ""
    private var revision = 0L
    init { prepareBacklight(context, root) }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { if (running) publish() }
    }
    private val requests = object : FileObserver(directory.path, CLOSE_WRITE or MOVED_TO) {
        override fun onEvent(event: Int, path: String?) {
            if (path in REQUESTS) handler.post { if (running) consume() }
        }
    }
    private val refresh = object : Runnable {
        override fun run() {
            if (!running) return
            consume()
            pendingBrightness?.let { value ->
                if (Settings.System.canWrite(context)) {
                    pendingBrightness = null
                    context.getSystemService(NotificationManager::class.java).cancel(PERMISSION_NOTIFICATION)
                    brightness(value)
                }
            }
            publish()
            handler.postDelayed(this, 250)
        }
    }

    fun publish(force: Boolean = false) {
        try {
            directory.mkdirs()
            val level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
            write("volume.state", "$level $maximum ${if (muted) 1 else 0}\n")
            val value = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            write("backlight/droiddeck/brightness", "$value\n")
            write("backlight/droiddeck/actual_brightness", "$value\n")
            val state = JSONObject().put("brightness", (value.coerceIn(1, 255) - 1) / 254.0)
                .put("brightnessWritable", Settings.System.canWrite(context)).toString()
            if (force || state != previous) {
                write("state.json", JSONObject(state).put("revision", ++revision).toString())
                previous = state
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not publish Android controls", e)
        }
    }

    override fun start() {
        handler.post {
            REQUESTS.forEach {
                File(directory, it).delete()
                File(directory, "$it.processing").delete()
            }
            running = true
            context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
            requests.startWatching()
            refresh.run()
        }
    }

    override fun stop() {
        handler.post {
            running = false
            handler.removeCallbacks(refresh)
            requests.stopWatching()
            context.contentResolver.unregisterContentObserver(observer)
            pendingBrightness = null
            context.getSystemService(NotificationManager::class.java).cancel(PERMISSION_NOTIFICATION)
        }
    }

    private fun consume() {
        for (name in REQUESTS) {
            val file = File(directory, name)
            if (!file.isFile) continue
            val claimed = File(directory, "$name.processing")
            try {
                Files.move(file.toPath(), claimed.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                if (claimed.length() > 64) continue
                val text = claimed.readText().trim()
                when (name) {
                    "brightness.request" -> text.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }?.let(::brightness)
                    "volume.request" -> text.toIntOrNull()?.let {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                            it.coerceIn(0, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
                    }
                    "mute.request" -> if (text == "0" || text == "1") {
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            if (text == "1") AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not apply $name", e)
            } finally {
                publish(force = true)
                claimed.delete()
            }
        }
    }

    private fun brightness(value: Float) {
        if (!Settings.System.canWrite(context)) {
            pendingBrightness = value
            val now = SystemClock.elapsedRealtime()
            if (now - permissionRequestAt >= 5_000) {
                permissionRequestAt = now
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val manager = context.getSystemService(NotificationManager::class.java)
                    manager.createNotificationChannel(NotificationChannel(PERMISSION_CHANNEL,
                        context.getString(R.string.brightness_permission_title), NotificationManager.IMPORTANCE_DEFAULT))
                    val action = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                    manager.notify(PERMISSION_NOTIFICATION, Notification.Builder(context, PERMISSION_CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat_session)
                        .setContentTitle(context.getString(R.string.brightness_permission_title))
                        .setContentText(context.getString(R.string.brightness_permission_text))
                        .setContentIntent(action).setOnlyAlertOnce(true).setAutoCancel(true).build())
                    if (activityVisible()) context.startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not open brightness permission", e)
                }
            }
            return
        }
        val level = 1 + (value * 254).roundToInt()
        try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
        } catch (e: SecurityException) {
            Log.w(TAG, "Brightness permission was revoked", e)
        }
    }

    private fun write(name: String, text: String) {
        val file = File(directory, name)
        if (file.isFile && file.readText() == text) return
        val staged = File(directory, "$name.tmp")
        staged.writeText(text)
        Files.move(staged.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        private const val TAG = "DeviceControls"
        private const val PERMISSION_CHANNEL = "brightness_permission"
        private const val PERMISSION_NOTIFICATION = 0x4442
        private val REQUESTS = setOf("brightness.request", "volume.request", "mute.request")
        @JvmStatic fun directory(root: File) = File(root, "etc/droiddeck-device-controls")
        @JvmStatic fun prepareBacklight(context: Context, root: File): File {
            val base = File(directory(root), "backlight")
            val panel = File(base, "droiddeck").apply { mkdirs() }
            val value = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            for ((name, text) in mapOf("type" to "raw", "max_brightness" to "255", "brightness" to "$value",
                "actual_brightness" to "$value", "bl_power" to "0", "scale" to "linear")) {
                File(panel, name).writeText("$text\n")
            }
            return base
        }
    }
}
