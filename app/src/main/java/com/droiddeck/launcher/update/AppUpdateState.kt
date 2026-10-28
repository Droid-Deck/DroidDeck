package com.droiddeck.launcher.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.R
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal object AppUpdateState {
    enum class Phase { IDLE, DOWNLOADING, VERIFYING, READY, INSTALLING, CONFIRMING, FAILED }
    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val apk: AppUpdates.Apk? = null,
        val percent: Int = -1,
        val file: File? = null,
        val error: String? = null,
        val automatic: Boolean = false,
        val targetLabel: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<(Snapshot) -> Unit>()
    var snapshot = Snapshot()
        private set
    private var initialized = false
    @Volatile private var session = -1
    private var confirmation: Intent? = null
    private const val PREFS = "app_update_task"

    private fun publish(value: Snapshot) {
        check(Looper.myLooper() == Looper.getMainLooper())
        snapshot = value
        listeners.toList().forEach { if (snapshot === value) it(value) }
    }

    fun subscribe(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
        listener(snapshot)
    }

    fun unsubscribe(listener: (Snapshot) -> Unit) { listeners.remove(listener) }

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        session = prefs(app).getInt("session", -1)
        val filter = IntentFilter(SelfInstaller.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        val raw = prefs(app).getString("ready", null) ?: run { session = -1; return }
        val record = runCatching { JSONObject(raw) }.getOrElse { fail(app, it.message); return }
        val apk = runCatching {
            val o = record
            AppUpdates.Apk(o.getString("name"), o.getString("url"), o.getLong("size"),
                o.getString("sha256"), o.getString("packageName"), o.getInt("versionCode"), o.getString("signerSha256"))
        }.getOrElse { fail(app, it.message); return }
        val file = File(app.cacheDir, "updates/${apk.name}")
        val restoring = Snapshot(Phase.VERIFYING, apk, targetLabel = record.optString("targetLabel").ifBlank { null })
        val savedSession = session
        publish(restoring)
        Thread({
            val result = runCatching {
                when {
                    savedSession >= 0 && SelfInstaller.isInstalled(app, apk) -> Phase.IDLE
                    savedSession >= 0 && app.packageManager.packageInstaller.getSessionInfo(savedSession)
                        ?.appPackageName == app.packageName -> Phase.INSTALLING
                    else -> {
                        SelfInstaller.validate(app, file, apk)
                        Phase.READY
                    }
                }
            }
            main.post {
                // A live installer callback can arrive while recovery is checking the APK.
                if (snapshot !== restoring) return@post
                result.onSuccess { phase ->
                    when (phase) {
                        Phase.IDLE -> complete(app)
                        Phase.INSTALLING -> {
                            publish(restoring.copy(phase = Phase.INSTALLING, file = file))
                            resume(app, savedSession)
                        }
                        else -> {
                            session = -1
                            prefs(app).edit().remove("session").apply()
                            publish(restoring.copy(phase = Phase.READY, file = file))
                        }
                    }
                }
                    .onFailure { fail(app, it.message) }
            }
        }, "app-update-restore").start()
    }

    fun begin(apk: AppUpdates.Apk, targetLabel: String? = null): Boolean {
        if (snapshot.phase !in setOf(Phase.IDLE, Phase.FAILED)) return false
        confirmation = null
        session = -1
        publish(Snapshot(Phase.DOWNLOADING, apk, percent = 0, targetLabel = targetLabel))
        return true
    }

    fun progress(percent: Int) {
        if (snapshot.phase == Phase.DOWNLOADING) publish(snapshot.copy(percent = percent))
    }

    fun ready(context: Context, file: File) {
        val apk = snapshot.apk ?: return
        val o = JSONObject().put("name", apk.name).put("url", apk.url).put("size", apk.size)
            .put("sha256", apk.sha256).put("packageName", apk.packageName)
            .put("versionCode", apk.versionCode).put("signerSha256", apk.signerSha256)
            .put("targetLabel", snapshot.targetLabel)
        prefs(context).edit().putString("ready", o.toString()).apply()
        publish(snapshot.copy(phase = Phase.READY, file = file, percent = -1, automatic = true))
    }

    fun fail(context: Context, message: String?) {
        val abandoned = session
        session = -1
        if (abandoned >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(abandoned) }
        prefs(context).edit().clear().apply()
        confirmation = null
        publish(snapshot.copy(phase = Phase.FAILED, error = message ?: context.getString(R.string.upd_failed), automatic = false))
    }

    fun discardReady(context: Context) {
        if (snapshot.phase != Phase.READY || session >= 0) return
        snapshot.file?.let {
            it.delete()
            File(it.path + ".resume.properties").delete()
        }
        complete(context)
    }

    private fun complete(context: Context) {
        prefs(context).edit().clear().apply()
        session = -1
        confirmation = null
        publish(Snapshot())
    }

    private fun resume(context: Context, id: Int) {
        Thread({
            runCatching { SelfInstaller.resume(context, id) }.onFailure { e ->
                main.post { if (session == id) fail(context, e.message) }
            }
        }, "app-update-resume").start()
    }

    internal fun claimInstallation(): Snapshot? {
        // Resuming the UI and completing a download can both trigger installation; claim it before starting the worker.
        val current = snapshot
        if (current.phase != Phase.READY || current.file == null) return null
        publish(current.copy(phase = Phase.INSTALLING, automatic = false))
        return current
    }

    fun install(context: Context) {
        val current = claimInstallation() ?: return
        val installing = snapshot
        val file = current.file!!
        val app = context.applicationContext
        Thread({
            runCatching {
                SelfInstaller.validate(app, file, current.apk!!)
                SelfInstaller.install(app, file) {
                    session = it
                    // Persist the session before commit so process death cannot orphan the pending confirmation.
                    if (!prefs(app).edit().putInt("session", it).commit()) {
                        throw IOException(app.getString(R.string.upd_failed))
                    }
                }
            }.onFailure { e -> main.post { if (snapshot === installing) fail(app, e.message) } }
        }, "app-update-install").start()
    }

    fun takeConfirmation(): Intent? = confirmation.also { confirmation = null }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (session < 0 || intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != session) return
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    confirmation = confirm?.let { SelfInstaller.safeConfirmIntent(context, it) }
                    if (confirmation == null) fail(context, context.getString(R.string.upd_not_offered))
                    else publish(snapshot.copy(phase = Phase.CONFIRMING))
                }
                PackageInstaller.STATUS_SUCCESS -> complete(context)
                else -> fail(context, SelfInstaller.describeFailure(context, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
            }
        }
    }
}
