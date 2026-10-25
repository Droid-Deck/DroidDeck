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
                is Screen.Games -> GameGrid(current) { game -> play(game) }
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
                items(games, key = { it.id }) { game ->
                    Column(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onPlay(game) }) {
                        AsyncImage(model = game.imageUrl, contentDescription = game.title, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(460f / 215f))
                        Text(game.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 5.dp, end = 8.dp))
                        Text(game.store + if (game.inLibrary) " · in library" else "", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp, end = 8.dp))
                    }
                }
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
                store = variant?.optString("store").orEmpty(),
                imageUrl = game.optString("imageUrl"),
                inLibrary = game.optBoolean("isInLibrary") || variant?.optBoolean("inLibrary") == true,
                search = game.optString("searchText").ifEmpty { game.optString("title").lowercase() })
        }.filter { it.id.isNotEmpty() && it.variantId.isNotEmpty() }
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
