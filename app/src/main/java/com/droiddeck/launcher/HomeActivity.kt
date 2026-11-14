package com.droiddeck.launcher

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.droiddeck.launcher.core.AppUiPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.stores.download.StoreDownloadService

/**
 * The front end while DroidDeck is the Home app. Android starts it for the Home intent, so it lives in the Home task, which
 * the recents screen never shows as a card. The manifest makes it singleTask: later Home presses reach this one instance
 * through [onNewIntent], where the Home routing runs again (home page choice, Steam session guide).
 */
class HomeActivity : FrontEndActivity() {
    override val inHomeTask: Boolean get() = true

    override fun handOff(savedInstanceState: Bundle?): Boolean {
        if (!HomeRoute.isHomeApp(this)) {
            // Opened while DroidDeck is not the Home app (a "just once" choice, or a game hub that opens Home entries):
            // keep the old behaviour and open the front end in its own task.
            if (savedInstanceState == null) {
                val destination = HomeRoute.outsideHome(this)
                if (SessionState.pipActive && destination.component?.className == FrontEndActivity::class.java.name) {
                    HomeApp.showFrontEnd(this, destination)
                } else startActivityPlain(destination)
            }
            finish()
            return true
        }
        intent = route(intent, launchGuide = savedInstanceState == null)
        return false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(route(intent, launchGuide = true))
    }

    private fun route(incoming: Intent, launchGuide: Boolean): Intent {
        val route = HomeRoute.resolve(this, incoming)
        if (launchGuide) route.guide?.let(::startActivityPlain)
        return route.frontEnd
    }
}

internal object HomeRoute {
    const val EXTRA_FORWARDED = "com.droiddeck.launcher.extra.FORWARDED"
    /** The front end's intent while Home only brought up the Steam session guide: no page change, no startup session. */
    const val ACTION_SESSION_HOME = "com.droiddeck.launcher.action.SESSION_HOME"

    class Route(val frontEnd: Intent, val guide: Intent?)

    fun isHomeApp(context: Context): Boolean = HomeApp.isHomeScreenEnabled(context) && HomeApp.isDefault(context)

    fun isHomeIntent(intent: Intent): Boolean = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)

    /** What the Home front end reads for [incoming], and the session guide to open on top of it, if any. */
    fun resolve(context: Context, incoming: Intent): Route {
        if (!isHomeIntent(incoming)) return Route(incoming, null)
        forwarded(incoming)?.let { return Route(it, null) }
        val guide = guide(context)
        return if (guide != null) Route(Intent(context, HomeActivity::class.java).setAction(ACTION_SESSION_HOME), guide)
        else Route(startPage(context, HomeActivity::class.java), null)
    }

    /** The routing used when DroidDeck is not the Home app: a separate front end task, as before. */
    fun outsideHome(context: Context): Intent =
        guide(context) ?: startPage(context, FrontEndActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Whether a launch of the plain front end should be handed to the Home front end instead of opening a second one. */
    fun forwards(request: Intent): Boolean = request.action != Intent.ACTION_CREATE_SHORTCUT && !isHomeIntent(request)

    /**
     * The Home intent that carries [request] to the Home front end. It names no component, so Android treats it as a Home
     * launch and brings the Home task forward instead of opening the activity in a new standard task.
     */
    fun forward(context: Context, request: Intent): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_FORWARDED, Intent(request))

    private fun forwarded(intent: Intent): Intent? {
        val request = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_FORWARDED, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_FORWARDED)
        request ?: return null
        // The app icon only brings the front end forward; it is not a fresh launch that would start a session at startup.
        if (request.action == Intent.ACTION_MAIN) {
            request.action = null
            request.removeCategory(Intent.CATEGORY_LAUNCHER)
        }
        return request
    }

    private fun guide(context: Context): Intent? =
        if (HomeApp.isActiveSteamSession() && !SessionState.pipActive) {
            Intent(context, SessionActivity::class.java)
                .setAction(SessionService.ACTION_HOME_GUIDE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else null

    private fun startPage(context: Context, target: Class<*>): Intent =
        Intent(context, target).apply {
            val page = AppUiPrefs.homePage(context)
            if (page != AppUiPrefs.HOME_PAGE_STEAM) putExtra(StoreDownloadService.EXTRA_NAV, page)
        }
}
