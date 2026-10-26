package com.droiddeck.launcher.stores.gfn

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.ui.DroidDeckTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** GeForce NOW: sign in with an NVIDIA account, browse the games GeForce NOW offers, and play one. */
class GfnActivity : ComponentActivity() {
    data class Game(val id: String, val variantId: String, val title: String, val store: String,
                    val imageUrl: String, val inLibrary: Boolean, val search: String)

    private sealed interface Screen {
        data object Loading : Screen
        data class SignedOut(val error: String? = null) : Screen
        data class Code(val userCode: String, val url: String, val attemptId: String, val intervalMs: Long) : Screen
        data class Games(val user: String) : Screen
        data class Failed(val message: String) : Screen
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DroidDeckTheme { Content() } }
    }

    @Composable
    private fun Content() {
        var screen by remember { mutableStateOf<Screen>(Screen.Loading) }
        val scope = rememberCoroutineScope()
        val load: () -> Unit = {
            screen = Screen.Loading
            scope.launch { screen = withContext(Dispatchers.IO) { loadSignedIn() } }
        }
        LaunchedEffect(Unit) { load() }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { finish() }) { Text("Back") }
                Text("GeForce NOW", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(horizontal = 16.dp).weight(1f))
                val current = screen
                if (current is Screen.Games) {
                    Text(current.user, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(end = 12.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { GfnCore.request(this@GfnActivity, "auth.logout") } }
                            screen = Screen.SignedOut()
                        }
                    }) { Text("Sign out") }
                }
            }
            Text("Unofficial client built on OpenNOW. NVIDIA does not support third-party GeForce NOW apps.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp))
            when (val current = screen) {
                Screen.Loading -> Message("Loading…")
                is Screen.Failed -> Column(Modifier.padding(24.dp)) {
                    Text(current.message, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = load) { Text("Try again") }
                }
                is Screen.SignedOut -> Column(Modifier.padding(24.dp)) {
                    Text("Sign in with your NVIDIA account to play your GeForce NOW games.",
                        color = MaterialTheme.colorScheme.onBackground)
                    current.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        scope.launch {
                            screen = withContext(Dispatchers.IO) {
                                runCatching { startSignIn() }.getOrElse { Screen.SignedOut(it.message) }
                            }
                        }
                    }) { Text("Sign in") }
                }
                is Screen.Code -> {
                    SignInCode(current) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current.url)))
                    }
                    LaunchedEffect(current.attemptId) {
                        screen = withContext(Dispatchers.IO) { waitForSignIn(current) }
                    }
                }
                is Screen.Games -> GamesHome(current)
            }
        }
    }

    @Composable
    private fun Message(text: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, color = MaterialTheme.colorScheme.onBackground)
        }
    }

    @Composable
    private fun SignInCode(code: Screen.Code, openBrowser: () -> Unit) {
        Column(Modifier.padding(24.dp)) {
            Text("On this phone or another device, open", color = MaterialTheme.colorScheme.onBackground)
            Text(code.url, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
            Text("and enter this code:", color = MaterialTheme.colorScheme.onBackground)
            Text(code.userCode, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(vertical = 12.dp))
            Button(onClick = openBrowser) { Text("Open the sign-in page") }
            Text("Waiting for you to sign in…", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 12.dp))
        }
    }

    @Composable
    private fun GamesHome(catalog: Screen.Games) {
        var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Store", "My library", "Connected stores").forEachIndexed { index, label ->
                androidx.compose.material3.FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(label) })
            }
        }
        when (tab) {
            0 -> GameGrid(catalog) { game -> play(game) }
            1 -> LibraryGrid { game -> play(game) }
            else -> ConnectedStores()
        }
    }

    @Composable
    private fun GameCard(game: Game, onPlay: (Game) -> Unit, markLibrary: Boolean = true) {
        Column(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onPlay(game) }) {
            AsyncImage(model = game.imageUrl, contentDescription = game.title, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(460f / 215f))
            Text(game.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 5.dp, end = 8.dp))
            Text(game.store + if (markLibrary && game.inLibrary) " · in library" else "", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp, end = 8.dp))
        }
    }

    /** The account's GeForce NOW library: games from connected stores and ones added on GeForce NOW. */
    @Composable
    private fun LibraryGrid(onPlay: (Game) -> Unit) {
        var games by remember { mutableStateOf<List<Game>?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var query by remember { mutableStateOf("") }
        var reload by remember { mutableStateOf(0) }
        LaunchedEffect(reload) {
            error = null
            withContext(Dispatchers.IO) { runCatching { libraryGames() } }
                .onSuccess { games = it.sortedBy { game -> game.title.lowercase() } }
                .onFailure { error = it.message ?: "Your GeForce NOW library could not be loaded." }
        }
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        val shown = games.orEmpty().filter { game -> words.all { game.search.contains(it) } }
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Search your ${games?.size ?: 0} games") }, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { games = null; reload += 1 }, modifier = Modifier.padding(start = 12.dp)) { Text("Refresh") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
            when {
                games == null && error == null -> Message("Loading your library…")
                games?.isEmpty() == true -> Message("Your GeForce NOW library is empty. Connect your stores in Connected stores, then sync.")
                else -> LazyVerticalGrid(columns = GridCells.Fixed(5), modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    items(shown, key = { it.id }) { game -> GameCard(game, onPlay, markLibrary = false) }
                }
            }
        }
    }

    private fun libraryGames(): List<Game> {
        val games = LinkedHashMap<String, Game>()
        val traversal = System.currentTimeMillis().toString()
        var cursor = ""
        var revision: Any? = null
        var context = ""
        repeat(50) {
            val params = JSONObject().put("limit", 100).put("cursor", cursor).put("traversalId", traversal)
                .put("catalogRevision", revision ?: JSONObject.NULL).put("catalogContext", context)
            val page = GfnCore.request(this, "catalog.library.list", params, timeoutMs = 60_000)
            gamesOf(page).forEach { games.putIfAbsent(it.id, it.copy(inLibrary = true)) }
            revision = page.opt("catalogRevision")
            context = page.optString("catalogContext")
            cursor = page.optString("nextCursor")
            if (!page.optBoolean("hasNextPage") || cursor.isEmpty()) return games.values.toList()
        }
        return games.values.toList()
    }

    private data class StoreAccount(val provider: String, val label: String, val displayName: String, val connected: Boolean,
                                    val status: String, val syncState: String, val canLink: Boolean, val canSync: Boolean)

    /** Store accounts linked to GeForce NOW, which fill the GeForce NOW library. */
    @Composable
    private fun ConnectedStores() {
        var accounts by remember { mutableStateOf<List<StoreAccount>?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var reload by remember { mutableStateOf(0) }
        val scope = rememberCoroutineScope()
        val scroll = androidx.compose.foundation.rememberScrollState()
        LaunchedEffect(reload) {
            withContext(Dispatchers.IO) { runCatching { storeAccounts() } }
                .onSuccess { accounts = it }.onFailure { message = it.message ?: "Connected stores could not be loaded." }
        }
        fun perform(action: suspend () -> String) {
            if (busy) return
            busy = true
            scope.launch {
                message = runCatching { action() }.getOrElse { it.message ?: "That did not work. Try again." }
                busy = false
                reload += 1
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(scroll)) {
            Text("Games in stores you connect here show up in My library and can be streamed. Amazon and itch.io games are not on GeForce NOW.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp)) }
            if (accounts == null) Text("Loading…", color = MaterialTheme.colorScheme.onBackground)
            accounts.orEmpty().forEach { account ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(account.label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(statusText(account), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
                    }
                    when (actionFor(account)) {
                        "link" -> Button(enabled = !busy, onClick = { perform { link(account) } }) {
                            Text(if (account.connected) "Reconnect" else "Connect")
                        }
                        "sync" -> Button(enabled = !busy, onClick = { perform { sync(account) } }) { Text("Sync library") }
                        "unlink" -> OutlinedButton(enabled = !busy, onClick = { perform { unlink(account) } }) { Text("Disconnect") }
                        else -> if (account.provider == "STEAM" && !account.connected)
                            Text("Link Steam on NVIDIA's site", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    private fun statusText(account: StoreAccount): String = when {
        account.status == "expired" -> "Connection expired: reconnect"
        account.status == "sync_error" -> "Last sync failed"
        account.connected -> "Connected" + if (account.displayName.isNotEmpty()) " as ${account.displayName}" else ""
        else -> "Not connected"
    }

    /** OpenNOW's own rule for which action a store row offers. */
    private fun actionFor(account: StoreAccount): String = when {
        account.canLink && (!account.connected || account.status == "expired" ||
            (account.syncState == "SYNC_DENIED" && account.provider != "STEAM")) -> "link"
        account.canSync -> "sync"
        account.connected && account.canLink -> "unlink"
        else -> "none"
    }

    private fun storeAccounts(): List<StoreAccount> {
        val list = GfnCore.request(this, "account.connections.list").optJSONArray("accounts") ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            val account = list.optJSONObject(index) ?: return@mapNotNull null
            if (account.optString("provider") in setOf("", "UNKNOWN", "NONE", "NVIDIA")) return@mapNotNull null
            StoreAccount(provider = account.optString("provider"), label = account.optString("label").ifEmpty { account.optString("provider") },
                displayName = account.optString("displayName"),
                connected = account.optBoolean("isConnected") || account.optString("status") == "connected",
                status = account.optString("status"), syncState = account.optString("syncState"),
                canLink = account.optBoolean("supportsLinking"), canSync = account.optBoolean("supportsSync"))
        }.filter { it.canLink || it.canSync || it.connected }
    }

    private suspend fun link(account: StoreAccount): String {
        val attempt = withContext(Dispatchers.IO) {
            GfnCore.request(this@GfnActivity, "account.connections.link.start", JSONObject().put("provider", account.provider))
        }
        val url = attempt.optString("loginUrl")
        if (url.isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        repeat(150) {
            delay(2_000)
            val poll = withContext(Dispatchers.IO) {
                GfnCore.request(this@GfnActivity, "account.connections.link.poll",
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

    private suspend fun sync(account: StoreAccount): String = withContext(Dispatchers.IO) {
        var state = GfnCore.request(this@GfnActivity, "account.connections.sync", JSONObject().put("provider", account.provider))
        val operation = state.optString("operationId")
        if (operation.isEmpty()) return@withContext state.optString("message").ifEmpty { "${account.label} synced." }
        repeat(90) {
            when (state.optString("phase")) {
                "waiting_remote", "starting" -> {
                    delay(state.optLong("retryAfterMs", 2_000).coerceAtLeast(2_000))
                    state = GfnCore.request(this@GfnActivity, "account.connections.sync.status", JSONObject().put("operationId", operation))
                }
                "refreshing_library" -> {
                    runCatching { libraryGames() }
                    runCatching {
                        GfnCore.request(this@GfnActivity, "account.connections.sync.status",
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

    private suspend fun unlink(account: StoreAccount): String = withContext(Dispatchers.IO) {
        GfnCore.request(this@GfnActivity, "account.connections.unlink", JSONObject().put("provider", account.provider))
            .optString("message").ifEmpty { "${account.label} disconnected." }
    }

    private class Page(val games: List<Game>, val next: String, val more: Boolean, val total: Int)

    @Composable
    private fun GameGrid(catalog: Screen.Games, onPlay: (Game) -> Unit) {
        var query by remember { mutableStateOf("") }
        var games by remember { mutableStateOf(emptyList<Game>()) }
        var next by remember { mutableStateOf("") }
        var more by remember { mutableStateOf(true) }
        var total by remember { mutableStateOf(0) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var generation by remember { mutableStateOf(0) }
        val scope = rememberCoroutineScope()
        val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        fun loadMore(reset: Boolean) {
            if (loading || (!reset && !more)) return
            loading = true
            val asked = generation
            val cursor = if (reset) "" else next
            val text = query.trim()
            scope.launch {
                val page = withContext(Dispatchers.IO) { runCatching { storePage(cursor, text) } }
                if (asked != generation) return@launch
                loading = false
                page.onSuccess {
                    error = null
                    val seen = if (reset) HashSet() else games.map { game -> game.id }.toHashSet()
                    games = (if (reset) emptyList() else games) + it.games.filter { game -> seen.add(game.id) }
                    next = it.next
                    more = it.more
                    total = maxOf(it.total, games.size)
                }.onFailure { error = it.message ?: "The GeForce NOW store did not answer." }
            }
        }
        LaunchedEffect(query) {
            delay(if (query.isEmpty()) 0 else 350)
            generation += 1
            loading = false
            more = true
            loadMore(reset = true)
        }
        val nearEnd by remember {
            androidx.compose.runtime.derivedStateOf {
                val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= grid.layoutInfo.totalItemsCount - 10
            }
        }
        LaunchedEffect(nearEnd, games.size) { if (nearEnd && games.isNotEmpty()) loadMore(reset = false) }
        Column {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text(if (total > 0) "Search $total games" else "Search games") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (games.isEmpty() && loading) Message("Loading the GeForce NOW store…")
            else if (games.isEmpty() && !loading && error == null) Message("No games found.")
            LazyVerticalGrid(state = grid, columns = GridCells.Fixed(5), modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                items(games, key = { it.id }) { game -> GameCard(game, onPlay) }
            }
        }
    }

    private fun play(game: Game) {
        startActivity(Intent(this, GfnPlayerActivity::class.java)
            .putExtra(GfnPlayerActivity.EXTRA_APP_ID, game.id)
            .putExtra(GfnPlayerActivity.EXTRA_VARIANT_ID, game.variantId)
            .putExtra(GfnPlayerActivity.EXTRA_TITLE, game.title))
    }

    private fun loadSignedIn(): Screen = runCatching {
        val auth = GfnCore.request(this, "auth.session.get")
        val user = auth.optJSONObject("session")?.optJSONObject("user") ?: auth.optJSONObject("user")
        if (user == null || user.optString("userId").isEmpty()) return Screen.SignedOut()
        Screen.Games(user.optString("displayName").ifEmpty { "Signed in" })
    }.getOrElse { Screen.Failed(it.message ?: "GeForce NOW could not start.") }

    /**
     * NVIDIA's signed-in store catalog (its game and variant ids are the ones launching needs).
     * Browsing reads the core's local copy, which grows a page at a time; a search asks NVIDIA.
     */
    private fun storePage(cursor: String, query: String): Page {
        val params = JSONObject().put("limit", 40).put("cursor", cursor).put("searchQuery", query)
        if (query.isEmpty()) params.put("genre", "").put("store", "").put("categoryId", "").put("refresh", false)
        val result = GfnCore.request(this, if (query.isEmpty()) "catalog.store.local" else "catalog.store.list", params, timeoutMs = 60_000)
        val games = gamesOf(result)
        return Page(games, result.optString("nextCursor"), result.optBoolean("hasNextPage") && result.optString("nextCursor").isNotEmpty(),
            result.optInt("totalCount", games.size))
    }

    private fun gamesOf(result: JSONObject): List<Game> {
        val list = result.optJSONArray("games") ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            val game = list.optJSONObject(index) ?: return@mapNotNull null
            val variants = game.optJSONArray("variants")
            val variant = variants?.optJSONObject(game.optInt("selectedVariantIndex", 0).coerceIn(0, (variants.length() - 1).coerceAtLeast(0)))
            Game(id = game.optString("id"),
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
        "EPIC" -> "Epic Games"
        "XBOX" -> "Xbox"
        "BATTLENET" -> "Battle.net"
        "UPLAY", "UBISOFT" -> "Ubisoft"
        "GOG" -> "GOG"
        "EA_APP", "EA", "ORIGIN" -> "EA app"
        "GAIJIN" -> "Gaijin"
        else -> store.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun startSignIn(): Screen {
        val challenge = GfnCore.request(this, "auth.device.start")
        return Screen.Code(
            userCode = challenge.optString("userCode"),
            url = challenge.optString("verificationUriComplete").ifEmpty { challenge.optString("verificationUri") },
            attemptId = challenge.optString("attemptId"),
            intervalMs = (challenge.optDouble("intervalSeconds", 5.0) * 1000).toLong().coerceAtLeast(1000))
    }

    private suspend fun waitForSignIn(code: Screen.Code): Screen {
        var interval = code.intervalMs
        while (true) {
            delay(interval)
            val poll = runCatching { GfnCore.request(this, "auth.device.poll", JSONObject().put("attemptId", code.attemptId)) }
                .getOrElse { return Screen.SignedOut(it.message) }
            when (poll.optString("status")) {
                "authorized" -> {
                    runCatching {
                        GfnCore.request(this, "auth.device.complete",
                            JSONObject().put("attemptId", code.attemptId).put("staySignedIn", true))
                    }.onFailure { return Screen.SignedOut(it.message) }
                    return loadSignedIn()
                }
                "pending", "slow_down" -> interval = poll.optLong("retryAfterMs",
                    (poll.optDouble("intervalSeconds", interval / 1000.0) * 1000).toLong()).coerceAtLeast(1000)
                else -> return Screen.SignedOut(poll.optString("message").ifEmpty { "Sign-in did not finish. Try again." })
            }
        }
    }
}
