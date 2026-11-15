package com.droiddeck.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.SizeF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.roundToInt

object HomeWidgets {
    data class Placed(val id: Int, val widthDp: Int, val heightDp: Int)

    data class Provider(
        val info: AppWidgetProviderInfo,
        val label: String,
        val appLabel: String,
        val preview: Bitmap?,
        val widthDp: Int,
        val heightDp: Int,
    )

    private const val HOST_ID = 0x0DDE
    private const val PREFS = "home_launcher"
    private const val KEY = "widgets"
    private const val REQUEST_BIND = 0x0DD1
    private const val REQUEST_CONFIGURE = 0x0DD2
    private const val REQUEST_RECONFIGURE = 0x0DD3
    const val MIN_WIDTH_DP = 96
    const val MIN_HEIGHT_DP = 56

    var widgets by mutableStateOf<List<Placed>>(emptyList())
        private set
    var providers by mutableStateOf<List<Provider>?>(null)
        private set
    private var loaded = false
    private var host: Host? = null
    private var pending: AppWidgetProviderInfo? = null
    private var pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var pinRequest: LauncherApps.PinItemRequest? = null
    private val main = Handler(Looper.getMainLooper())

    private class Host(context: Context) : AppWidgetHost(context, HOST_ID) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
            HomeWidgetView(context)
    }

    class HomeWidgetView(context: Context) : AppWidgetHostView(context) {
        var onLongPress: (() -> Unit)? = null
        private var fired = false
        private var downX = 0f
        private var downY = 0f
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val longPress = Runnable {
            fired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress?.invoke()
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fired = false
                    downX = ev.x
                    downY = ev.y
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> if (hypot(ev.x - downX, ev.y - downY) > slop) removeCallbacks(longPress)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> removeCallbacks(longPress)
            }
            return fired
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (fired) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) fired = false
                return true
            }
            return super.onTouchEvent(event)
        }
    }

    private fun host(context: Context): Host = host ?: Host(context.applicationContext).also { host = it }

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        val manager = AppWidgetManager.getInstance(context)
        val parsed = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Placed(o.getInt("id"), o.getInt("w"), o.getInt("h"))
            }
        }.getOrDefault(emptyList())
        val live = parsed.filter { manager.getAppWidgetInfo(it.id) != null }
        parsed.filterNot { it in live }.forEach { host(context).deleteAppWidgetId(it.id) }
        widgets = live
        if (live.size != parsed.size) save(context)
    }

    fun startListening(context: Context) {
        load(context)
        runCatching { host(context).startListening() }
    }

    fun stopListening(context: Context) {
        runCatching { host(context).stopListening() }
    }

    fun info(context: Context, id: Int): AppWidgetProviderInfo? = AppWidgetManager.getInstance(context).getAppWidgetInfo(id)

    fun createView(context: Context, id: Int): HomeWidgetView? {
        val info = info(context, id) ?: return null
        return host(context).createView(context, id, info) as? HomeWidgetView
    }

    fun applySize(view: AppWidgetHostView, widthDp: Int, heightDp: Int) {
        if (Build.VERSION.SDK_INT >= 31) {
            view.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp.toFloat(), heightDp.toFloat())))
        } else {
            @Suppress("DEPRECATION")
            view.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp)
        }
    }

    fun loadProviders(context: Context) {
        if (providers != null) return
        val app = context.applicationContext
        Thread({
            val pm = app.packageManager
            val manager = AppWidgetManager.getInstance(app)
            val dpi = app.resources.displayMetrics.densityDpi
            val list = manager.installedProviders.mapNotNull { info ->
                runCatching {
                    val appLabel = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString() }
                        .getOrDefault(info.provider.packageName)
                    val drawable = info.loadPreviewImage(app, dpi) ?: info.loadIcon(app, dpi)
                    val preview = drawable?.let { d ->
                        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 192
                        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 192
                        val scale = minOf(1f, 360f / maxOf(w, h))
                        d.toBitmap((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
                    }
                    val (w, h) = defaultSize(app, info)
                    Provider(info, info.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { appLabel }, appLabel, preview, w, h)
                }.getOrNull()
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Provider::appLabel).thenBy(String.CASE_INSENSITIVE_ORDER, Provider::label))
            main.post { providers = list }
        }, "widget-providers").start()
    }

    fun refreshProviders(context: Context) {
        providers = null
        loadProviders(context)
    }

    private fun defaultSize(context: Context, info: AppWidgetProviderInfo): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        var w = (info.minWidth / density).roundToInt()
        var h = (info.minHeight / density).roundToInt()
        if (Build.VERSION.SDK_INT >= 31) {
            if (info.targetCellWidth > 0) w = maxOf(w, info.targetCellWidth * 80)
            if (info.targetCellHeight > 0) h = maxOf(h, info.targetCellHeight * 80)
        }
        return w.coerceIn(160, 560) to h.coerceIn(MIN_HEIGHT_DP, 360)
    }

    fun minSize(context: Context, id: Int): Pair<Int, Int> {
        val info = info(context, id) ?: return MIN_WIDTH_DP to MIN_HEIGHT_DP
        val density = context.resources.displayMetrics.density
        val w = (if (info.minResizeWidth > 0) info.minResizeWidth else info.minWidth) / density
        val h = (if (info.minResizeHeight > 0) info.minResizeHeight else info.minHeight) / density
        return w.roundToInt().coerceAtLeast(MIN_WIDTH_DP) to h.roundToInt().coerceAtLeast(MIN_HEIGHT_DP)
    }

    fun add(activity: Activity, provider: Provider) {
        if (pendingId == AppWidgetManager.INVALID_APPWIDGET_ID) add(activity, provider.info, null)
    }

    /** Places a widget another app asked for; its own app has already configured it. */
    fun addPinned(activity: Activity, request: LauncherApps.PinItemRequest): Boolean {
        if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) return false
        val info = request.getAppWidgetProviderInfo(activity) ?: return false
        load(activity)
        add(activity, info, request)
        return pending != null
    }

    private fun add(activity: Activity, info: AppWidgetProviderInfo, request: LauncherApps.PinItemRequest?) {
        val h = host(activity)
        val id = h.allocateAppWidgetId()
        pending = info
        pendingId = id
        pinRequest = request
        val manager = AppWidgetManager.getInstance(activity)
        val bound = runCatching { manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null) }.getOrDefault(false)
        if (bound) {
            bound(activity, id, info)
            return
        }
        val bind = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
        @Suppress("DEPRECATION")
        runCatching { activity.startActivityForResult(bind, REQUEST_BIND) }.onFailure { abandon(activity) }
    }

    private fun bound(activity: Activity, id: Int, info: AppWidgetProviderInfo) {
        val request = pinRequest
        if (request == null) {
            configure(activity, id, info)
            return
        }
        val accepted = runCatching { request.isValid && request.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id) }) }
            .getOrDefault(false)
        if (accepted) place(activity, id, info) else abandon(activity)
    }

    private fun configure(activity: Activity, id: Int, info: AppWidgetProviderInfo) {
        val optional = Build.VERSION.SDK_INT >= 31 &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (info.configure == null || optional) {
            place(activity, id, info)
            return
        }
        runCatching { host(activity).startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_CONFIGURE, null) }
            .onFailure { place(activity, id, info) }
    }

    fun canReconfigure(context: Context, id: Int): Boolean {
        val info = info(context, id) ?: return false
        if (info.configure == null) return false
        return Build.VERSION.SDK_INT < 28 || info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0
    }

    fun reconfigure(activity: Activity, id: Int) {
        runCatching { host(activity).startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_RECONFIGURE, null) }
    }

    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int): Boolean {
        when (requestCode) {
            REQUEST_BIND -> {
                val info = pending
                if (resultCode == Activity.RESULT_OK && info != null) bound(activity, pendingId, info) else abandon(activity)
            }
            REQUEST_CONFIGURE -> {
                val info = pending
                if (resultCode == Activity.RESULT_OK && info != null) place(activity, pendingId, info) else abandon(activity)
            }
            REQUEST_RECONFIGURE -> Unit
            else -> return false
        }
        return true
    }

    private fun abandon(context: Context) {
        if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) host(context).deleteAppWidgetId(pendingId)
        pending = null
        pinRequest = null
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
    }

    private fun place(context: Context, id: Int, info: AppWidgetProviderInfo) {
        val (w, h) = defaultSize(context, info)
        pending = null
        pinRequest = null
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
        widgets = widgets + Placed(id, w, h)
        save(context)
    }

    fun resize(context: Context, id: Int, widthDp: Int, heightDp: Int) {
        widgets = widgets.map { if (it.id == id) it.copy(widthDp = widthDp, heightDp = heightDp) else it }
        save(context)
    }

    fun move(context: Context, id: Int, by: Int) {
        val i = widgets.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in widgets.indices) return
        widgets = widgets.toMutableList().apply { add(j, removeAt(i)) }
        save(context)
    }

    fun remove(context: Context, id: Int) {
        host(context).deleteAppWidgetId(id)
        widgets = widgets.filterNot { it.id == id }
        save(context)
    }

    private fun save(context: Context) {
        val array = JSONArray()
        for (w in widgets) array.put(JSONObject().put("id", w.id).put("w", w.widthDp).put("h", w.heightDp))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}
