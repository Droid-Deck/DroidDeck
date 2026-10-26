package com.droiddeck.launcher.stores.gfn

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class GfnGame(val id: String, val variantId: String, val title: String, val store: String,
                   val imageUrl: String, val inLibrary: Boolean, val search: String)

data class GfnStoreAccount(val provider: String, val label: String, val displayName: String, val connected: Boolean,
                           val status: String, val syncState: String, val canLink: Boolean, val canSync: Boolean)

/**
 * The GeForce NOW tab on the Stores page: NVIDIA sign-in, the store catalog, the account's library
 * and its connected stores, all through OpenNOW's core. Kept here so it survives switching tabs.
 */
object GfnState {
    sealed interface Phase {
        data object Loading : Phase
        data class SignedOut(val error: String? = null) : Phase
        data class Code(val userCode: String, val url: String, val attemptId: String, val intervalMs: Long) : Phase
        data class SignedIn(val user: String) : Phase
        data class Failed(val message: String) : Phase
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var phase by mutableStateOf<Phase>(Phase.Loading)
        private set
    private var started = false
    private var signInJob: Job? = null

    var storeGames by mutableStateOf(emptyList<GfnGame>())
        private set
    var storeTotal by mutableStateOf(0)
        private set
    var storeLoading by mutableStateOf(false)
        private set
    var storeError by mutableStateOf<String?>(null)
        private set
    private var storeQuery = ""
    private var storeNext = ""
    private var storeMore = true
    private var storeGeneration = 0
    private var storeJob: Job? = null

    var library by mutableStateOf<List<GfnGame>?>(null)
        private set
    var libraryError by mutableStateOf<String?>(null)
        private set
    private var libraryJob: Job? = null

    var accounts by mutableStateOf<List<GfnStoreAccount>?>(null)
        private set
    var accountsMessage by mutableStateOf<String?>(null)
        private set
    var accountsBusy by mutableStateOf(false)
        private set

    /** Loads the sign-in state once; [force] checks again (Try again). */
    fun start(context: Context, force: Boolean = false) {
        if (started && !force) return
        started = true
        val app = context.applicationContext
        phase = Phase.Loading
        scope.launch { phase = withContext(Dispatchers.IO) { loadSignedIn(app) } }
    }

    fun signIn(context: Context) {
        val app = context.applicationContext
        signInJob?.cancel()
        signInJob = scope.launch {
            val code = withContext(Dispatchers.IO) { runCatching { startSignIn(app) } }
                .getOrElse { phase = Phase.SignedOut(it.message); return@launch }
            phase = code
            phase = waitForSignIn(app, code)
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        phase = Phase.SignedOut()
    }

    fun signOut(context: Context) {
        val app = context.applicationContext
        signInJob?.cancel()
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { GfnCore.request(app, "auth.logout") } }
            clearAccountData()
            phase = Phase.SignedOut()
        }
    }

    private fun clearAccountData() {
        storeJob?.cancel(); libraryJob?.cancel()
        storeGames = emptyList(); storeTotal = 0; storeNext = ""; storeMore = true; storeError = null; storeLoading = false
        library = null; libraryError = null
        accounts = null; accountsMessage = null
    }

    /** Shows the store for [query]: loads it when the query changed or nothing is loaded yet. */
    fun showStore(context: Context, query: String) {
        if (query.trim() != storeQuery || (storeGames.isEmpty() && !storeLoading && storeError == null)) loadStore(context, query, reset = true)
    }

    /** NVIDIA's signed-in store catalog, a page at a time; a new [query] starts over. */
    fun loadStore(context: Context, query: String, reset: Boolean) {
        val text = query.trim()
        if (!reset && (storeLoading || !storeMore || text != storeQuery)) return
        val app = context.applicationContext
        storeGeneration += 1
        val asked = storeGeneration
        storeQuery = text
        if (reset) { storeNext = ""; storeMore = true }
        val cursor = storeNext
        storeLoading = true
        storeJob?.cancel()
        storeJob = scope.launch {
            if (reset && text.isNotEmpty()) delay(350)
            val page = withContext(Dispatchers.IO) { runCatching { storePage(app, cursor, text) } }
            if (asked != storeGeneration) return@launch
            storeLoading = false
            page.onSuccess {
                storeError = null
                val seen = if (reset) HashSet() else storeGames.map { game -> game.id }.toHashSet()
                storeGames = (if (reset) emptyList() else storeGames) + it.games.filter { game -> seen.add(game.id) }
                storeNext = it.next
                storeMore = it.more
                storeTotal = maxOf(it.total, storeGames.size)
            }.onFailure { storeError = it.message ?: "The GeForce NOW store did not answer." }
        }
    }

    fun loadLibrary(context: Context, force: Boolean = false) {
        if (!force && (library != null || libraryJob?.isActive == true)) return
        val app = context.applicationContext
        if (force) library = null
        libraryError = null
        libraryJob?.cancel()
        libraryJob = scope.launch {
            withContext(Dispatchers.IO) { runCatching { libraryGames(app) } }
                .onSuccess { library = it.sortedBy { game -> game.title.lowercase() } }
                .onFailure { libraryError = it.message ?: "Your GeForce NOW library could not be loaded." }
        }
    }

    fun loadAccounts(context: Context) {
        val app = context.applicationContext
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { storeAccounts(app) } }
                .onSuccess { accounts = it }
                .onFailure { accountsMessage = it.message ?: "Connected stores could not be loaded." }
        }
    }

    fun link(context: Context, account: GfnStoreAccount) = perform(context) { app -> linkStore(app, account) }
    fun sync(context: Context, account: GfnStoreAccount) = perform(context) { app -> syncStore(app, account) }
    fun unlink(context: Context, account: GfnStoreAccount) = perform(context) { app ->
        withContext(Dispatchers.IO) {
            GfnCore.request(app, "account.connections.unlink", JSONObject().put("provider", account.provider))
                .optString("message").ifEmpty { "${account.label} disconnected." }
        }
    }

    private fun perform(context: Context, action: suspend (Context) -> String) {
        if (accountsBusy) return
        val app = context.applicationContext
        accountsBusy = true
        scope.launch {
            accountsMessage = runCatching { action(app) }.getOrElse { it.message ?: "That did not work. Try again." }
            accountsBusy = false
            loadAccounts(app)
            loadLibrary(app, force = true)
        }
    }

    fun play(context: Context, game: GfnGame) {
        context.startActivity(Intent(context, GfnPlayerActivity::class.java)
            .apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            .putExtra(GfnPlayerActivity.EXTRA_APP_ID, game.id)
            .putExtra(GfnPlayerActivity.EXTRA_VARIANT_ID, game.variantId)
            .putExtra(GfnPlayerActivity.EXTRA_TITLE, game.title))
    }

    /** OpenNOW's own rule for which action a connected-store row offers. */
    fun actionFor(account: GfnStoreAccount): String = when {
        account.canLink && (!account.connected || account.status == "expired" ||
            (account.syncState == "SYNC_DENIED" && account.provider != "STEAM")) -> "link"
        account.canSync -> "sync"
        account.connected && account.canLink -> "unlink"
        else -> "none"
    }

    private fun loadSignedIn(context: Context): Phase = runCatching {
        val auth = GfnCore.request(context, "auth.session.get")
        val user = auth.optJSONObject("session")?.optJSONObject("user") ?: auth.optJSONObject("user")
        if (user == null || user.optString("userId").isEmpty()) return Phase.SignedOut()
        Phase.SignedIn(user.optString("displayName").ifEmpty { "Signed in" })
    }.getOrElse { Phase.Failed(it.message ?: "GeForce NOW could not start.") }

    private fun startSignIn(context: Context): Phase.Code {
        val challenge = GfnCore.request(context, "auth.device.start")
        return Phase.Code(
            userCode = challenge.optString("userCode"),
            url = challenge.optString("verificationUriComplete").ifEmpty { challenge.optString("verificationUri") },
            attemptId = challenge.optString("attemptId"),
            intervalMs = (challenge.optDouble("intervalSeconds", 5.0) * 1000).toLong().coerceAtLeast(1000))
    }

    private suspend fun waitForSignIn(context: Context, code: Phase.Code): Phase {
        var interval = code.intervalMs
        while (true) {
            delay(interval)
            val poll = withContext(Dispatchers.IO) {
                runCatching { GfnCore.request(context, "auth.device.poll", JSONObject().put("attemptId", code.attemptId)) }
            }.getOrElse { return Phase.SignedOut(it.message) }
            when (poll.optString("status")) {
                "authorized" -> {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            GfnCore.request(context, "auth.device.complete",
                                JSONObject().put("attemptId", code.attemptId).put("staySignedIn", true))
                        }
                    }.onFailure { return Phase.SignedOut(it.message) }
                    clearAccountData()
                    return withContext(Dispatchers.IO) { loadSignedIn(context) }
                }
                "pending", "slow_down" -> interval = poll.optLong("retryAfterMs",
                    (poll.optDouble("intervalSeconds", interval / 1000.0) * 1000).toLong()).coerceAtLeast(1000)
                else -> return Phase.SignedOut(poll.optString("message").ifEmpty { "Sign-in did not finish. Try again." })
            }
        }
    }

    private class Page(val games: List<GfnGame>, val next: String, val more: Boolean, val total: Int)

    /** Browsing reads the core's local copy of the catalog, which grows a page at a time; a search asks NVIDIA. */
    private fun storePage(context: Context, cursor: String, query: String): Page {
        val params = JSONObject().put("limit", 40).put("cursor", cursor).put("searchQuery", query)
        if (query.isEmpty()) params.put("genre", "").put("store", "").put("categoryId", "").put("refresh", false)
        val result = GfnCore.request(context, if (query.isEmpty()) "catalog.store.local" else "catalog.store.list", params, timeoutMs = 60_000)
        val games = gamesOf(result)
        return Page(games, result.optString("nextCursor"), result.optBoolean("hasNextPage") && result.optString("nextCursor").isNotEmpty(),
            result.optInt("totalCount", games.size))
    }

    /** Pages of 50 keep each answer well under the binder transaction limit. */
    private fun libraryGames(context: Context): List<GfnGame> {
        val games = LinkedHashMap<String, GfnGame>()
        val traversal = System.currentTimeMillis().toString()
        var cursor = ""
        var revision: Any? = null
        var catalogContext = ""
        repeat(100) {
            val params = JSONObject().put("limit", 50).put("cursor", cursor).put("traversalId", traversal)
                .put("catalogRevision", revision ?: JSONObject.NULL).put("catalogContext", catalogContext)
            val page = GfnCore.request(context, "catalog.library.list", params, timeoutMs = 60_000)
            gamesOf(page).forEach { games.putIfAbsent(it.id, it.copy(inLibrary = true)) }
            revision = page.opt("catalogRevision")
            catalogContext = page.optString("catalogContext")
            cursor = page.optString("nextCursor")
            if (!page.optBoolean("hasNextPage") || cursor.isEmpty()) return games.values.toList()
        }
        return games.values.toList()
    }

    private fun gamesOf(result: JSONObject): List<GfnGame> {
        val list = result.optJSONArray("games") ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            val game = list.optJSONObject(index) ?: return@mapNotNull null
            val variants = game.optJSONArray("variants")
            val variant = variants?.optJSONObject(game.optInt("selectedVariantIndex", 0).coerceIn(0, (variants.length() - 1).coerceAtLeast(0)))
            GfnGame(id = game.optString("id"),
                variantId = variant?.optString("id").orEmpty().ifEmpty { game.optString("launchAppId") },
                title = game.optString("title"),
                store = storeLabel(variant?.optString("store").orEmpty()),
                imageUrl = game.optString("imageUrl"),
                inLibrary = game.optBoolean("isInLibrary") || variant?.optBoolean("inLibrary") == true,
                search = game.optString("searchText").ifEmpty { game.optString("title").lowercase() })
        }.filter { it.id.isNotEmpty() && it.variantId.isNotEmpty() }
    }

    private fun storeLabel(store: String): String = when (store.uppercase()) {
        "STEAM" -> "Steam"
        "EPIC" -> "Epic"
        "XBOX" -> "Xbox"
        "BATTLENET" -> "Battle.net"
        "UPLAY", "UBISOFT" -> "Ubisoft"
        "GOG" -> "GOG"
        "EA_APP", "EA", "ORIGIN" -> "EA"
        "GAIJIN" -> "Gaijin"
        else -> store.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun storeAccounts(context: Context): List<GfnStoreAccount> {
        val list = GfnCore.request(context, "account.connections.list").optJSONArray("accounts") ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            val account = list.optJSONObject(index) ?: return@mapNotNull null
            if (account.optString("provider") in setOf("", "UNKNOWN", "NONE", "NVIDIA")) return@mapNotNull null
            GfnStoreAccount(provider = account.optString("provider"), label = account.optString("label").ifEmpty { account.optString("provider") },
                displayName = account.optString("displayName"),
                connected = account.optBoolean("isConnected") || account.optString("status") == "connected",
                status = account.optString("status"), syncState = account.optString("syncState"),
                canLink = account.optBoolean("supportsLinking"), canSync = account.optBoolean("supportsSync"))
        }.filter { it.canLink || it.canSync || it.connected }
    }

    private suspend fun linkStore(context: Context, account: GfnStoreAccount): String {
        val attempt = withContext(Dispatchers.IO) {
            GfnCore.request(context, "account.connections.link.start", JSONObject().put("provider", account.provider))
        }
        val url = attempt.optString("loginUrl")
        if (url.isNotEmpty()) runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        repeat(150) {
            delay(2_000)
            val poll = withContext(Dispatchers.IO) {
                GfnCore.request(context, "account.connections.link.poll",
                    JSONObject().put("attemptId", attempt.optString("attemptId")), timeoutMs = 10_000)
            }
            when (poll.optString("status")) {
                "pending" -> {}
                "complete" -> return "${account.label} connected."
                else -> return poll.optString("message").ifEmpty { "${account.label} was not connected." }
            }
        }
        return "Stopped waiting for ${account.label}. Finish signing in, then refresh."
    }

    private suspend fun syncStore(context: Context, account: GfnStoreAccount): String = withContext(Dispatchers.IO) {
        var state = GfnCore.request(context, "account.connections.sync", JSONObject().put("provider", account.provider))
        val operation = state.optString("operationId")
        if (operation.isEmpty()) return@withContext state.optString("message").ifEmpty { "${account.label} synced." }
        repeat(90) {
            when (state.optString("phase")) {
                "waiting_remote", "starting" -> {
                    delay(state.optLong("retryAfterMs", 2_000).coerceAtLeast(2_000))
                    state = GfnCore.request(context, "account.connections.sync.status", JSONObject().put("operationId", operation))
                }
                "refreshing_library" -> {
                    runCatching { libraryGames(context) }
                    runCatching {
                        GfnCore.request(context, "account.connections.sync.status",
                            JSONObject().put("operationId", operation).put("libraryRefreshed", true), timeoutMs = 10_000)
                    }
                    return@withContext "${account.label} synced. Your library is up to date."
                }
                "timed_out" -> return@withContext "The sync could not be confirmed. ${account.label} may still finish; refresh later."
                else -> return@withContext "${account.label} could not finish the sync. Try again."
            }
        }
        "Still waiting for ${account.label}; refresh later."
    }
}
