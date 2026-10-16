package com.droiddeck.launcher.gpu

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs

object AutoDriverTrial {
    private const val TAG = "AutoDriverTrial"
    private const val PREFS = "gpu_auto_trial"
    const val UNREADY_STARTS_BEFORE_ROLLBACK = 2

    class Trial(
        val release: String, val name: String,
        val display: String, val linux: String,
        val previousDisplay: String, val previousLinux: String,
        val starts: Int,
    )

    @Volatile private var app: Context? = null
    @Volatile private var watching: Trial? = null

    fun release(pair: DriverPairs.DriverPair) = "${pair.key}@${pair.version}"

    fun current(context: Context): Trial? {
        val p = prefs(context)
        val release = p.getString("release", null) ?: return null
        return Trial(
            release, p.getString("name", "").orEmpty(),
            p.getString("display", "").orEmpty(), p.getString("linux", "").orEmpty(),
            p.getString("previousDisplay", "").orEmpty(), p.getString("previousLinux", "").orEmpty(),
            p.getInt("starts", 0),
        )
    }

    fun begin(context: Context, release: String, name: String, display: String, linux: String, previousDisplay: String, previousLinux: String) {
        prefs(context).edit()
            .putString("release", release).putString("name", name)
            .putString("display", display).putString("linux", linux)
            .putString("previousDisplay", previousDisplay).putString("previousLinux", previousLinux)
            .putInt("starts", 0)
            .commit()
    }

    fun clear(context: Context) {
        val bad = bad(context)
        prefs(context).edit().clear().putStringSet("bad", bad).commit()
    }

    fun bad(context: Context): Set<String> = prefs(context).getStringSet("bad", emptySet()).orEmpty()

    fun sessionStarting(context: Context, display: String, linux: String) {
        val trial = current(context)
        watching = null
        if (trial == null || trial.display != display || trial.linux != linux) return
        app = context.applicationContext
        watching = trial
        prefs(context).edit().putInt("starts", trial.starts + 1).commit()
    }

    fun sessionReady() {
        val trial = watching ?: return
        val context = app ?: return
        watching = null
        if (current(context)?.release != trial.release) return
        Log.i(TAG, "${trial.release} reached a ready session; keeping it")
        clear(context)
        prune(context, setOf(trial.display, trial.linux))
    }

    fun rollbackDue(context: Context): Trial? = current(context)?.takeIf { it.starts >= UNREADY_STARTS_BEFORE_ROLLBACK }

    fun rollBack(context: Context, trial: Trial) {
        Log.w(TAG, "${trial.release} never reached a ready session in ${trial.starts} starts; restoring the previous drivers")
        if (SessionPrefs.androidDriver(context) == trial.display) SessionPrefs.setAndroidDriver(context, trial.previousDisplay)
        if (SessionPrefs.linuxDriver(context) == trial.linux) SessionPrefs.setLinuxDriver(context, trial.previousLinux)
        val bad = bad(context) + trial.release
        prefs(context).edit().clear().putStringSet("bad", bad).commit()
        prune(context, setOf(trial.previousDisplay, trial.previousLinux, SessionPrefs.androidDriver(context), SessionPrefs.linuxDriver(context)))
    }

    fun prune(context: Context, keep: Set<String>) {
        val lm = LinuxVulkanDriverManager(context)
        val td = TurnipDriver(context)
        val owned = SessionPrefs.gpuAutoInstalled(context)
        for (id in owned - keep) {
            val bundle = DriverBundle.containing(context, id, lm.isInstalled(id))
            when {
                bundle != null -> {
                    if (bundle.androidId in keep || bundle.linuxId in keep) continue
                    DriverBundle.remove(context, bundle); TurnipReleases.forget(context, bundle.id)
                }
                lm.isInstalled(id) -> lm.removeDriver(id)
                else -> td.remove(id)
            }
            TurnipReleases.forget(context, id)
        }
        SessionPrefs.setGpuAutoInstalled(context, owned.intersect(keep))
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
