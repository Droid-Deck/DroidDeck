package com.droiddeck.launcher.lab

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import com.droiddeck.launcher.BuildConfig
import org.json.JSONObject
import java.io.File

/**
 * A Lab build: app code and resources an on-device agent built from this install's own commit,
 * loaded over the installed APK at startup so it can be tried without reinstalling or re-signing.
 *
 * `files/lab/next` holds a build waiting for the next start and `files/lab/active` the one in use,
 * each with `overlay.apk` (the build's dex files and linked resources) and `overlay.json`. A build
 * that is replaced, turned off or refused moves to `files/lab/builds`, so it can be used again.
 */
object LabOverlay {
    data class Info(val label: String, val baseCommit: String, val baseApk: String, val built: Long = 0)

    enum class Reason { REPLACED, TURNED_OFF, OTHER_INSTALL, DID_NOT_START, UNREADABLE }

    /** A build kept in `files/lab/builds`; [usable] when it was made for this install. */
    data class Saved(val dir: File, val info: Info?, val reason: Reason?, val usable: Boolean)

    private const val TAG = "LabOverlay"
    private const val MAX_FAILED_STARTS = 2
    private const val STABLE_AFTER_MS = 10_000L
    private const val KEEP_BUILDS = 12
    const val SKIPPED_APP_IMAGE = "app image present"

    /** The installed APK's commit; this class always comes from the APK, never from a Lab build. */
    val apkCommit: String = BuildConfig.SOURCE_COMMIT

    @Volatile var active: Info? = null
        private set
    @Volatile var skipped: String? = null
        private set
    /** This start moved a newly staged build into use. */
    @Volatile var promoted = false
        private set

    private var loader: Any? = null

    @VisibleForTesting
    internal var injectDex: (ClassLoader, File) -> Unit = ::prependDex
    @VisibleForTesting
    internal var openResources: (File) -> Any? = { if (Build.VERSION.SDK_INT >= 30) createLoader(it) else null }
    @VisibleForTesting
    internal var appImage: (Context) -> Boolean = ::hasAppImage

    fun root(context: Context) = File(context.filesDir, "lab")

    /** Called from [Application.attachBaseContext], before any class the build may change is loaded. */
    fun load(app: Application, mainProcess: Boolean) {
        active = null
        skipped = null
        promoted = false
        try {
            val root = root(app)
            if (mainProcess) promoted = promote(root)
            val dir = File(root, "active")
            val apk = File(dir, "overlay.apk")
            val meta = File(dir, "overlay.json")
            if (!apk.isFile || !meta.isFile) return
            val info = parse(meta.readText()) ?: return refuse(root, mainProcess, Reason.UNREADABLE)
            if (!madeFor(app, info)) return refuse(root, mainProcess, Reason.OTHER_INSTALL)
            if (Build.VERSION.SDK_INT < 30) return skip("needs Android 11")
            // Classes in an app image are loaded before attachBaseContext and would ignore the overlay;
            // dd-lab-build recompiles the app without one (cmd package compile -m verify).
            if (appImage(app)) return skip(SKIPPED_APP_IMAGE)
            if (mainProcess) {
                val starts = failedStarts(root) + 1
                if (starts > MAX_FAILED_STARTS) return refuse(root, true, Reason.DID_NOT_START)
                File(root, "starts").writeText(starts.toString())
            }
            apk.setReadOnly()
            loader = openResources(apk)
            injectDex(app.classLoader, apk)
            active = info
            addLoader(app.resources)
            app.registerActivityLifecycleCallbacks(Callbacks(root, mainProcess))
        } catch (e: Throwable) {
            Log.w(TAG, "Lab build not loaded", e)
            skipped = e.message ?: e.javaClass.simpleName
        }
    }

    /** The APK's path changes with every install and update of the app, so it pins a build to one install. */
    fun madeFor(context: Context, info: Info) = info.baseCommit == apkCommit && info.baseApk == context.applicationInfo.sourceDir

    /** The build in `active`, whether or not this start loaded it. */
    fun current(context: Context): Info? = read(File(root(context), "active"))

    /** A build staged for the next start. */
    fun pending(context: Context): Info? = read(File(root(context), "next"))

    /** Kept builds, newest first. */
    fun saved(context: Context): List<Saved> = File(root(context), "builds").listFiles().orEmpty()
        .filter { File(it, "overlay.apk").isFile }
        .sortedByDescending { it.name.toLongOrNull() ?: 0L }
        .map { dir ->
            val info = read(dir)
            val reason = File(dir, "reason").takeIf { it.isFile }?.readText()?.trim()?.let { r -> Reason.entries.firstOrNull { it.name == r } }
            Saved(dir, info, reason, info != null && madeFor(context, info))
        }

    /** Leaves the Lab build and restarts the app on the installed APK. */
    fun turnOff(context: Context) {
        val root = root(context)
        archive(root, File(root, "active"), Reason.TURNED_OFF)
        archive(root, File(root, "next"), Reason.TURNED_OFF)
        File(root, "next").deleteRecursively()
        File(root, "starts").delete()
        restart(context)
    }

    /** Stages [build] for the next start and keeps a build that was waiting; the caller restarts the app. */
    fun stage(context: Context, build: Saved) {
        val root = root(context)
        val next = File(root, "next")
        val waiting = File(root, "next.replaced").apply { deleteRecursively() }
        next.renameTo(waiting)
        next.deleteRecursively()
        File(build.dir, "reason").delete()
        if (!build.dir.renameTo(next)) {
            waiting.renameTo(next)
            error("could not stage ${build.dir.name}")
        }
        archive(root, waiting, Reason.REPLACED)
        waiting.deleteRecursively()
    }

    fun delete(build: Saved) {
        build.dir.deleteRecursively()
    }

    /**
     * Android's background optimization can give the APK an app image again, which keeps a Lab
     * build from loading. Recompiling without one needs a shell, which the caller provides.
     */
    fun needsRecompile(context: Context) = appImage(context)

    fun recompileCommand(context: Context) = "cmd package compile -m verify -f ${context.packageName}"

    fun restart(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) context.startActivity(launch)
        Runtime.getRuntime().exit(0)
    }

    @VisibleForTesting
    internal fun parse(json: String): Info? = runCatching {
        val o = JSONObject(json)
        Info(o.getString("label"), o.getString("baseCommit"), o.getString("baseApk"), o.optLong("built"))
    }.getOrNull()

    private fun read(dir: File): Info? = File(dir, "overlay.json").takeIf { it.isFile && File(dir, "overlay.apk").isFile }?.let { parse(it.readText()) }

    /** Moves `next` into use and keeps the build it replaces. Returns whether there was one to move. */
    @VisibleForTesting
    internal fun promote(root: File): Boolean {
        val next = File(root, "next")
        if (!File(next, "overlay.apk").isFile || !File(next, "overlay.json").isFile) return false
        val active = File(root, "active")
        archive(root, active, Reason.REPLACED)
        active.deleteRecursively()
        if (!next.renameTo(active)) return false
        File(root, "starts").delete()
        return true
    }

    @VisibleForTesting
    internal fun archive(root: File, dir: File, reason: Reason) {
        if (!File(dir, "overlay.apk").isFile) return
        val builds = File(root, "builds").apply { mkdirs() }
        var stamp = System.currentTimeMillis()
        while (File(builds, stamp.toString()).exists()) stamp++
        val target = File(builds, stamp.toString())
        if (!dir.renameTo(target)) return
        File(target, "reason").writeText(reason.name)
        builds.listFiles().orEmpty().sortedByDescending { it.name.toLongOrNull() ?: 0L }.drop(KEEP_BUILDS).forEach { it.deleteRecursively() }
    }

    @VisibleForTesting
    internal fun failedStarts(root: File) = File(root, "starts").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0

    private fun refuse(root: File, mainProcess: Boolean, reason: Reason) {
        skipped = reason.name
        Log.w(TAG, "Lab build turned off: $reason")
        if (!mainProcess) return
        archive(root, File(root, "active"), reason)
        File(root, "starts").delete()
        File(root, "last-off-reason").writeText(reason.name)
    }

    private fun skip(reason: String) {
        skipped = reason
        Log.w(TAG, "Lab build not loaded: $reason")
    }

    private fun hasAppImage(context: Context): Boolean {
        val apk = File(context.applicationInfo.sourceDir)
        val oat = File(apk.parentFile, "oat")
        return oat.listFiles().orEmpty().any { File(it, "${apk.nameWithoutExtension}.art").isFile }
    }

    @RequiresApi(30)
    private fun createLoader(apk: File): ResourcesLoader =
        ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            ResourcesLoader().apply { addProvider(ResourcesProvider.loadFromApk(fd)) }
        }

    /** Gives [resources] the Lab build's resources; contexts made from them inherit it. */
    fun addLoader(resources: Resources) {
        if (Build.VERSION.SDK_INT < 30) return
        val current = loader as? ResourcesLoader ?: return
        resources.addLoaders(current)
    }

    private fun prependDex(classLoader: ClassLoader, apk: File) {
        val pathList = field(Class.forName("dalvik.system.BaseDexClassLoader"), "pathList").get(classLoader)!!
        val elementsField = field(pathList.javaClass, "dexElements")
        @Suppress("UNCHECKED_CAST") val installed = elementsField.get(pathList) as Array<Any?>
        val errors = ArrayList<java.io.IOException>()
        val made = try {
            pathList.javaClass.getDeclaredMethod("makeDexElements", List::class.java, File::class.java, List::class.java, ClassLoader::class.java)
                .apply { isAccessible = true }.invoke(null, listOf(apk), null, errors, classLoader)
        } catch (_: NoSuchMethodException) {
            pathList.javaClass.getDeclaredMethod(
                "makeDexElements", List::class.java, File::class.java, List::class.java, ClassLoader::class.java, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(null, listOf(apk), null, errors, classLoader, false)
        }
        errors.firstOrNull()?.let { throw it }
        @Suppress("UNCHECKED_CAST") val added = made as Array<Any?>
        if (added.isEmpty()) error("no dex in ${apk.name}")
        val merged = java.lang.reflect.Array.newInstance(installed.javaClass.componentType!!, added.size + installed.size)
        System.arraycopy(added, 0, merged, 0, added.size)
        System.arraycopy(installed, 0, merged, added.size, installed.size)
        elementsField.set(pathList, merged)
    }

    private fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }

    private class Callbacks(private val root: File, private val mainProcess: Boolean) : Application.ActivityLifecycleCallbacks {
        private var settled = false

        override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) = addLoader(activity.resources)

        override fun onActivityResumed(activity: Activity) {
            if (settled || !mainProcess) return
            settled = true
            Handler(Looper.getMainLooper()).postDelayed({ File(root, "starts").delete() }, STABLE_AFTER_MS)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
