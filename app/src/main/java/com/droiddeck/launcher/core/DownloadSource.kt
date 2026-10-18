package com.droiddeck.launcher.core

import android.content.Context
import android.content.SharedPreferences
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object DownloadSource {
    const val MODE_AUTO = "auto"
    const val MODE_DIRECT = "direct"
    const val MODE_MIRROR = "mirror"
    const val MODE_CUSTOM = "custom"

    private const val PREFS = "download-source"
    private const val KEY_MODE = "mode"
    private const val KEY_MIRROR = "mirror"
    private const val KEY_CUSTOM = "custom"
    private const val KEY_FASTEST = "fastest"

    private const val PROBE = "https://raw.githubusercontent.com/Droid-Deck/DroidDeck/main/README.md"
    private const val PROBE_TIMEOUT_MS = 4_000
    private const val PROBE_COOLDOWN_MS = 30_000L
    private const val UA = "DroidDeck-Android"

    private val MODES = listOf(MODE_AUTO, MODE_DIRECT, MODE_MIRROR, MODE_CUSTOM)

    private val GITHUB_HOSTS = listOf(
        "github.com",
        "raw.githubusercontent.com",
        "api.github.com",
    )

    private val CATALOGS = listOf(
        "https://raw.githubusercontent.com/Droid-Deck/DroidDeck-CI/",
        "https://raw.githubusercontent.com/Droid-Deck/DroidDeck-Components/",
        "https://raw.githubusercontent.com/Droid-Deck/Drivers-CI/",
    )

    data class Mirror(val host: String, val api: Boolean)

    val MIRRORS = listOf(
        Mirror("gh-proxy.com", true),
        Mirror("gh-proxy.org", true),
        Mirror("ghproxy.net", false),
        Mirror("ghfast.top", false),
    )

    @Volatile
    private var app: Context? = null

    @Volatile
    private var lastGood: String? = null

    private val probing = AtomicBoolean(false)
    private val probedAt = AtomicLong(0L)

    fun init(context: Context) {
        app = context.applicationContext
        lastGood = prefs()?.getString(KEY_FASTEST, null)
    }

    fun probe() {
        if (app == null || prefs()?.getString(KEY_MODE, MODE_AUTO) == MODE_DIRECT) return
        if (!probing.compareAndSet(false, true)) return
        val now = System.currentTimeMillis()
        if (now - probedAt.get() < PROBE_COOLDOWN_MS) {
            probing.set(false)
            return
        }
        probedAt.set(now)
        Thread {
            try {
                val times = ConcurrentHashMap<String, Long>()
                val sources = listOf<String?>(null) + MIRRORS.map { it.host }
                val latch = CountDownLatch(sources.size)
                for (mirror in sources) {
                    Thread {
                        try {
                            val ms = latency(mirror?.let { "https://$it/$PROBE" } ?: PROBE)
                            if (ms > 0) times[mirror ?: ""] = ms
                        } finally {
                            latch.countDown()
                        }
                    }.apply { isDaemon = true }.start()
                }
                latch.await(PROBE_TIMEOUT_MS * 3L, TimeUnit.MILLISECONDS)
                val best = times.minByOrNull { it.value }?.key ?: return@Thread
                lastGood = best
                prefs()?.edit()?.putString(KEY_FASTEST, best)?.apply()
            } finally {
                probing.set(false)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun latency(url: String): Long {
        val started = System.nanoTime()
        var connection: HttpURLConnection? = null
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=0-1023")
                setRequestProperty("User-Agent", UA)
            }
            connection = c
            if (c.responseCode / 100 != 2) 0L else {
                c.inputStream.use { it.read() }
                (System.nanoTime() - started) / 1_000_000
            }
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    fun isGithub(url: String): Boolean = GITHUB_HOSTS.any { url.startsWith("https://$it/") }

    /** Catalogs carry the digests everything else is checked against, so a mirror must never supply one. */
    fun isCatalog(url: String): Boolean = CATALOGS.any { url.startsWith(it) }

    fun candidates(target: String): List<String> {
        // GitHub API metadata carries executable URLs and digests; never proxy it.
        if (isCatalog(target) || target.startsWith("https://api.github.com/")) return listOf(target)
        val prefs = prefs() ?: return listOf(target)
        if (!isGithub(target)) return listOf(target)
        val api = target.startsWith("https://api.github.com/")
        val proxies = MIRRORS.filter { !api || it.api }.map { it.proxy(target) }
        val ordered = when (prefs.getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO) {
            MODE_DIRECT -> listOf(target)
            MODE_MIRROR -> listOfNotNull(picked(prefs, api)?.proxy(target)) + listOf(target) + quickest(proxies)
            MODE_CUSTOM -> listOfNotNull(customProxy(prefs, target)) + listOf(target) + quickest(proxies)
            else -> if (api) listOf(target) else listOf(target) + quickest(proxies)
        }
        return ordered.distinct()
    }

    private fun quickest(sources: List<String>): List<String> =
        sources.sortedBy { if (host(it) == lastGood) 0 else 1 }

    fun succeeded(source: String) {
        lastGood = host(source)
    }

    fun failed() {
        lastGood = null
        prefs()?.edit()?.remove(KEY_FASTEST)?.apply()
    }

    fun mode(context: Context): String = prefs(context)?.getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO

    fun setMode(context: Context, mode: String) {
        prefs(context)?.edit()?.putString(KEY_MODE, mode.takeIf { it in MODES } ?: MODE_AUTO)?.apply()
    }

    fun mirror(context: Context): String {
        val host = prefs(context)?.getString(KEY_MIRROR, "") ?: ""
        return MIRRORS.firstOrNull { it.host == host }?.host ?: MIRRORS.first().host
    }

    fun setMirror(context: Context, host: String) {
        prefs(context)?.edit()?.putString(KEY_MIRROR, host.takeIf { h -> MIRRORS.any { it.host == h } } ?: "")?.apply()
    }

    fun custom(context: Context): String = prefs(context)?.getString(KEY_CUSTOM, "") ?: ""

    fun setCustom(context: Context, prefix: String) {
        prefs(context)?.edit()?.putString(KEY_CUSTOM, normalizePrefix(prefix))?.apply()
    }

    fun reset(context: Context) {
        lastGood = null
        prefs(context)?.edit()?.clear()?.apply()
    }

    private fun prefs(): SharedPreferences? = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun prefs(context: Context): SharedPreferences? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun Mirror.proxy(target: String): String = "https://$host/$target"

    private fun host(url: String): String =
        MIRRORS.firstOrNull { url.startsWith("https://${it.host}/") }?.host ?: ""

    private fun picked(prefs: SharedPreferences, api: Boolean): Mirror? {
        val chosen = MIRRORS.firstOrNull { it.host == (prefs.getString(KEY_MIRROR, "") ?: "") } ?: MIRRORS.firstOrNull()
        return if (api && chosen != null && !chosen.api) MIRRORS.firstOrNull { it.api } else chosen
    }

    private fun custom(prefs: SharedPreferences): String = prefs.getString(KEY_CUSTOM, "") ?: ""

    private fun customProxy(prefs: SharedPreferences, target: String): String? =
        normalizePrefix(custom(prefs)).takeIf { it.isNotEmpty() }?.let { "$it/$target" }

    private fun normalizePrefix(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isEmpty() || trimmed.startsWith("http://")) return ""
        return if (trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }
}
