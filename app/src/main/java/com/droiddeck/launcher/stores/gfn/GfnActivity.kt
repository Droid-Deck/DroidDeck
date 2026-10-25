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
import androidx.compose.foundation.layout.width
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
        data class Games(val user: String, val games: List<Game>, val total: Int, val owned: Set<String>) : Screen
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

    @Composable
    private fun GameGrid(catalog: Screen.Games, onPlay: (Game) -> Unit) {
        var query by remember { mutableStateOf("") }
        var found by remember { mutableStateOf<List<Game>?>(null) }
        LaunchedEffect(query) {
            val text = query.trim()
            if (text.isEmpty()) { found = null; return@LaunchedEffect }
            delay(350)
            // The core searches NVIDIA's whole catalog, not only the games shown.
            found = withContext(Dispatchers.IO) {
                runCatching { publicGames(JSONObject().put("limit", 1000).put("searchQuery", text), catalog.owned) }.getOrDefault(emptyList())
            }
        }
        val shown = found ?: catalog.games
        Column {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("Search ${catalog.total} games") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            if (found == null && catalog.total > catalog.games.size) {
                Text("Showing ${catalog.games.size} of ${catalog.total}; search to find the rest.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 16.dp))
            }
            LazyVerticalGrid(columns = GridCells.Adaptive(220.dp), modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                items(shown, key = { it.id }) { game ->
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onPlay(game) }) {
                        AsyncImage(model = game.imageUrl, contentDescription = game.title, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(460f / 215f))
                        Text(game.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp, top = 8.dp, end = 10.dp))
                        Text(game.store + if (game.inLibrary) " · in your library" else "", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 10.dp, bottom = 8.dp, end = 10.dp).width(200.dp))
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
        val owned = runCatching { libraryIds() }.getOrDefault(emptySet())
        val first = GfnCore.request(this, "catalog.public.list", JSONObject().put("limit", 1000), timeoutMs = 60_000)
        val games = mark(gamesOf(first), owned).sortedBy { it.title.lowercase() }
        Screen.Games(user.optString("displayName").ifEmpty { "Signed in" }, games,
            first.optInt("totalCount", games.size), owned)
    }.getOrElse { Screen.Failed(it.message ?: "GeForce NOW could not start.") }

    private fun publicGames(params: JSONObject, owned: Set<String>): List<Game> =
        mark(gamesOf(GfnCore.request(this, "catalog.public.list", params, timeoutMs = 60_000)), owned)

    private fun mark(games: List<Game>, owned: Set<String>) = games.map { if (it.id in owned) it.copy(inLibrary = true) else it }

    /** Every game in the account's GeForce NOW library, page by page. */
    private fun libraryIds(): Set<String> {
        val ids = HashSet<String>()
        val traversal = System.currentTimeMillis().toString()
        var cursor = ""
        var revision: Any? = null
        var context = ""
        repeat(50) {
            val params = JSONObject().put("limit", 100).put("cursor", cursor).put("traversalId", traversal)
                .put("catalogRevision", revision ?: JSONObject.NULL).put("catalogContext", context)
            val page = GfnCore.request(this, "catalog.library.list", params, timeoutMs = 60_000)
            gamesOf(page).forEach { ids += it.id }
            revision = page.opt("catalogRevision")
            context = page.optString("catalogContext")
            cursor = page.optString("nextCursor")
            if (!page.optBoolean("hasNextPage") || cursor.isEmpty()) return ids
        }
        return ids
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
                inLibrary = game.optBoolean("isInLibrary"),
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
