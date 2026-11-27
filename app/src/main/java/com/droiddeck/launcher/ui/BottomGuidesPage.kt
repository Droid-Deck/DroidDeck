package com.droiddeck.launcher.ui

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.SteamAchievements
import androidx.compose.foundation.verticalScroll
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GameLinks
import com.droiddeck.launcher.session.GuideLibrary
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SteamGuides
import com.droiddeck.launcher.session.SteamWebLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

private sealed class GuideList {
    object Loading : GuideList()
    object Failed : GuideList()
    class Found(val guides: List<SteamGuides.Guide>) : GuideList()
}

internal enum class GuideSection(val label: Int) {
    GUIDES(R.string.bottom_page_guides),
    ACHIEVEMENTS(R.string.achievements_title),
    WIKI(R.string.bottom_guides_wiki),
    MAPS(R.string.bottom_guides_maps),
    SAVED(R.string.bottom_guides_saved),
}

private val TAG_LABELS = listOf(
    null to R.string.bottom_guides_tag_all,
    "Walkthroughs" to R.string.bottom_guides_tag_walkthroughs,
    "Achievements" to R.string.achievements_title,
    "Secrets" to R.string.bottom_guides_tag_secrets,
    "Maps or Levels" to R.string.bottom_guides_maps,
)

@Composable
internal fun GuidesPanel(runningGame: () -> String?) {
    val context = LocalContext.current
    val id = rememberShownGame(runningGame)
    if (id == null) {
        PanelNote(stringResource(R.string.bottom_guides_no_game))
        return
    }
    val game = id.toString()
    val scope = rememberCoroutineScope()
    val host = rememberMenuHost()
    val firstGame = remember { id }
    var reading by remember(id) { mutableStateOf(if (id == firstGame) SteamGuides.place(context, game)?.url else null) }
    var section by remember(id) { mutableStateOf(GuideSection.GUIDES) }
    var tag by remember(id) { mutableStateOf<String?>(null) }
    var list by remember(id) { mutableStateOf<GuideList>(GuideList.Loading) }
    var cookieHeader by remember(id) { mutableStateOf<String?>(null) }
    var name by remember(id) { mutableStateOf<String?>(null) }
    var openMap by remember(id) { mutableStateOf<File?>(null) }
    if (reading != null || openMap != null) FullScreenChrome()
    var libraryRevision by remember(id) { mutableIntStateOf(0) }
    var note by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        val cookies = withContext(Dispatchers.IO) {
            runCatching {
                SteamWebLogin.read(LinuxRuntime.rootDir(context), SessionState.steamClientArch, File(context.cacheDir, "steam-web-login"))
            }.getOrDefault(emptyList())
        }
        val manager = CookieManager.getInstance()
        for (c in cookies) suspendCancellableCoroutine { cont -> manager.setCookie("https://${c.host}", c.header) { cont.resume(Unit) } }
        cookieHeader = cookies.filter { steamChatAllows(it.host) }.joinToString("; ") { it.header.substringBefore(';') }
        name = withContext(Dispatchers.IO) { IdleHome.overview(context, id)?.name }
    }
    LaunchedEffect(id, tag, cookieHeader) {
        val header = cookieHeader ?: return@LaunchedEffect
        list = GuideList.Loading
        list = withContext(Dispatchers.IO) { SteamGuides.list(id, header, tag) }?.let { GuideList.Found(it) } ?: GuideList.Failed
    }
    val pinnedMsg = stringResource(R.string.bottom_guides_pinned)
    val pinFailedMsg = stringResource(R.string.bottom_guides_pin_failed)
    val savedMsg = stringResource(R.string.bottom_guides_saved_note)
    openMap?.let { map ->
        MapViewer(map, onBack = { openMap = null; libraryRevision++ }, onDelete = {
            GuideLibrary.delete(map)
            openMap = null
            libraryRevision++
        })
        return
    }
    reading?.let { open ->
        // A new game or page needs a new WebView: its factory runs once and keeps the first page's callbacks.
        key(game, open) {
            GuideReaderPanel(
                url = open,
                scroll = SteamGuides.place(context, game)?.takeIf { it.url == open }?.scroll ?: 0f,
                note = note,
                onBack = { reading = null; note = null },
                onPlace = { url, s -> SteamGuides.savePlace(context, game, url, s) },
                onSave = { view ->
                    val file = GuideLibrary.savedFile(context, game)
                    val title = view.title.orEmpty()
                    val url = view.url.orEmpty()
                    view.saveWebArchive(file.path, false) { path ->
                        if (path != null) {
                            GuideLibrary.recordSaved(file, title, url)
                            note = savedMsg
                            libraryRevision++
                        }
                    }
                },
                onPin = { image ->
                    scope.launch {
                        val cookies = CookieManager.getInstance().getCookie(image)
                        val file = withContext(Dispatchers.IO) { GuideLibrary.pin(context, game, image, cookies) }
                        note = if (file != null) pinnedMsg else pinFailedMsg
                        libraryRevision++
                    }
                },
                onStopped = { reading = null },
            )
        }
        return
    }
    PanelColumn {
        TabStrip(GuideSection.entries.map { stringResource(it.label) }, section.ordinal, { section = GuideSection.entries[it] },
            modifier = Modifier.fillMaxWidth(), compact = true, centered = true)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            when (section) {
                GuideSection.GUIDES -> GuidesSection(host, list, tag, SteamGuides.place(context, game), onTag = { tag = it }, onOpen = { reading = it })
                GuideSection.ACHIEVEMENTS -> AchievementsSection(id)
                GuideSection.WIKI -> WikiSection(id, name, onOpen = { reading = it })
                GuideSection.MAPS -> MapsSection(id, game, name, libraryRevision, onOpenMap = { openMap = it }, onOpen = { reading = it })
                GuideSection.SAVED -> SavedSection(game, libraryRevision, onOpen = { reading = Uri.fromFile(it).toString() }, onDelete = {
                    GuideLibrary.delete(it)
                    libraryRevision++
                })
            }
        }
    }
}

@Composable
private fun GuidesSection(
    host: MenuHost, list: GuideList, tag: String?, last: SteamGuides.Place?, onTag: (String?) -> Unit, onOpen: (String) -> Unit,
) {
    SettingsGroup(stringResource(R.string.bottom_guides_steam)) {
        if (last != null) PanelRow(stringResource(R.string.bottom_guides_continue), onClick = { onOpen(last.url) }, tag = "bottom-guides-continue",
            trailing = { SecondaryButton(stringResource(R.string.store_open), compact = true) { onOpen(last.url) } })
        ChoiceRow(host, "guides-tag", stringResource(R.string.bottom_guides_category), null,
            TAG_LABELS.map { (value, label) -> value to stringResource(label) }, tag) { onTag(it) }
        when (list) {
            GuideList.Loading -> PanelRow(stringResource(R.string.achievements_reading))
            GuideList.Failed -> PanelRow(stringResource(R.string.bottom_guides_failed))
            is GuideList.Found ->
                if (list.guides.isEmpty()) PanelRow(stringResource(R.string.bottom_guides_empty))
                else for (guide in list.guides) GuideRow(guide) { onOpen(guide.url) }
        }
    }
}

@Composable
private fun AchievementsSection(appId: Long) {
    val game = remember(appId) { SteamAchievements.game(appId) }
    val state = rememberAchievements(game) ?: return
    Box(Modifier.padding(top = 8.dp)) { AchievementsTab(SteamAchievements.label, state) }
}

@Composable
private fun WikiSection(appId: Long, name: String?, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var links by remember(appId) { mutableStateOf<List<GameLinks.Link>?>(null) }
    var tier by remember(appId) { mutableStateOf<String?>(null) }
    LaunchedEffect(appId, name) {
        val (found, t) = withContext(Dispatchers.IO) {
            val pending = async { GameLinks.protonTier(appId) }
            GameLinks.links(context, appId, name) to pending.await()
        }
        tier = t
        links = found
    }
    val open = stringResource(R.string.store_open)
    val found = links
    if (found == null) {
        SettingsGroup(stringResource(R.string.bottom_guides_wiki)) { PanelRow(stringResource(R.string.achievements_reading)) }
        return
    }
    tier?.let { t ->
        SettingsGroup(stringResource(R.string.fex_compatibility_label)) {
            PanelRow(stringResource(R.string.bottom_guides_protondb), tag = "guides-protondb", onClick = { onOpen("https://www.protondb.com/app/$appId") },
                trailing = { PanelValue(stringResource(tierLabel(t)), tierColor(t)) })
        }
    }
    SettingsGroup(stringResource(R.string.bottom_guides_wiki)) {
        for (link in found.filter { it.kind != GameLinks.Kind.MAPGENIE }) PanelRow(stringResource(linkLabel(link.kind)), Uri.parse(link.url).host.orEmpty().removePrefix("www."),
            onClick = { onOpen(link.url) },
            trailing = { SecondaryButton(open, compact = true, modifier = Modifier.testTag("guides-link-${link.kind.name.lowercase()}")) { onOpen(link.url) } })
    }
    name?.let { n ->
        SettingsGroup(stringResource(R.string.bottom_guides_search)) {
            for (s in GameLinks.searches(n, found)) PanelRow(stringResource(searchLabel(s.id)), n, onClick = { onOpen(s.url) },
                trailing = { SecondaryButton(open, compact = true, modifier = Modifier.testTag("guides-search-${s.id}")) { onOpen(s.url) } })
        }
    }
}

@Composable
private fun MapsSection(appId: Long, game: String, name: String?, revision: Int, onOpenMap: (File) -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var maps by remember(game) { mutableStateOf<List<GuideLibrary.PinnedMap>>(emptyList()) }
    LaunchedEffect(game, revision) { maps = withContext(Dispatchers.IO) { GuideLibrary.maps(context, game) } }
    SettingsGroup(stringResource(R.string.bottom_guides_maps)) {
        if (maps.isEmpty()) PanelRow(stringResource(R.string.bottom_guides_maps_empty), stringResource(R.string.bottom_guides_pin_hint))
        maps.forEachIndexed { i, map ->
            PanelRow(
                stringResource(R.string.bottom_guides_map_name, i + 1), stringResource(R.string.bottom_guides_markers, map.markers.size),
                tag = "guides-map", onClick = { onOpenMap(map.file) },
                leading = {
                    AsyncImage(model = map.file, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp, 40.dp).clip(RoundedCornerShape(6.dp)))
                },
            )
        }
    }
    var mapSite by remember(name) { mutableStateOf<GameLinks.Link?>(null) }
    LaunchedEffect(name) {
        if (name != null) mapSite = withContext(Dispatchers.IO) { GameLinks.links(context, appId, name) }.firstOrNull { it.kind == GameLinks.Kind.MAPGENIE }
    }
    mapSite?.let { link ->
        val open = stringResource(R.string.store_open)
        SettingsGroup(stringResource(R.string.bottom_guides_find_maps)) {
            PanelRow(stringResource(R.string.bottom_guides_search_mapgenie), Uri.parse(link.url).run { host.orEmpty() + path.orEmpty() },
                onClick = { onOpen(link.url) },
                trailing = { SecondaryButton(open, compact = true, modifier = Modifier.testTag("guides-maps-mapgenie")) { onOpen(link.url) } })
        }
    }
}

@Composable
private fun SavedSection(game: String, revision: Int, onOpen: (File) -> Unit, onDelete: (File) -> Unit) {
    val context = LocalContext.current
    var saved by remember(game) { mutableStateOf<List<GuideLibrary.Saved>>(emptyList()) }
    LaunchedEffect(game, revision) { saved = withContext(Dispatchers.IO) { GuideLibrary.saved(context, game) } }
    val delete = stringResource(R.string.common_delete)
    SettingsGroup(stringResource(R.string.bottom_guides_saved)) {
        if (saved.isEmpty()) PanelRow(stringResource(R.string.bottom_guides_saved_empty), stringResource(R.string.bottom_guides_saved_hint))
        for (page in saved) PanelRow(page.title, Uri.parse(page.url).host.orEmpty().removePrefix("www."), tag = "guides-saved",
            onClick = { onOpen(page.file) },
            trailing = { SecondaryButton(delete, compact = true, modifier = Modifier.testTag("guides-saved-delete")) { onDelete(page.file) } })
    }
}

@Composable
private fun GuideReaderPanel(
    url: String,
    scroll: Float,
    note: String?,
    onBack: () -> Unit,
    onPlace: (String, Float) -> Unit,
    onSave: (WebView) -> Unit,
    onPin: (String) -> Unit,
    onStopped: () -> Unit,
) {
    val pal = LocalPalette.current
    var view by remember { mutableStateOf<WebView?>(null) }
    var image by remember(url) { mutableStateOf<String?>(null) }
    val local = url.startsWith("file:")
    PanelColumn {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackLink(stringResource(R.string.mode_back), compact = true, onClick = onBack)
            Text(note ?: stringResource(R.string.bottom_guides_pin_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            image?.let { img -> SecondaryButton(stringResource(R.string.bottom_guides_pin), compact = true, modifier = Modifier.testTag("bottom-guides-pin")) { onPin(img); image = null } }
            if (!local) SecondaryButton(stringResource(R.string.game_env_save), compact = true, modifier = Modifier.testTag("bottom-guides-save")) { view?.let(onSave) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, pal.line, RoundedCornerShape(14.dp))) {
            GuideReader(url, scroll, onPlace, onStopped, onView = { view = it }, onImage = { image = it })
        }
    }
}

@Composable
internal fun GuideRow(guide: SteamGuides.Guide, onOpen: () -> Unit) {
    val pal = LocalPalette.current
    PanelRow(
        guide.title,
        listOfNotNull(guide.author.takeIf { it.isNotEmpty() }, "★".repeat(guide.stars).takeIf { it.isNotEmpty() }).joinToString(" · "),
        tag = "guide-${guide.id}", onClick = onOpen,
        leading = {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(pal.line)) {
                if (guide.preview != null) AsyncImage(model = guide.preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        },
    )
}

internal object ReaderAds {
    private val HOSTS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "google-analytics.com",
        "googletagservices.com", "googletagmanager.com", "amazon-adsystem.com", "adnxs.com", "criteo.com", "criteo.net",
        "taboola.com", "outbrain.com", "pubmatic.com", "rubiconproject.com", "casalemedia.com", "openx.net", "moatads.com",
        "scorecardresearch.com", "quantserve.com", "adsafeprotected.com", "3lift.com", "sharethrough.com", "teads.tv",
        "yieldmo.com", "media.net", "smartadserver.com", "adform.net", "bidswitch.net", "lijit.com", "sonobi.com",
        "indexww.com", "gumgum.com", "nativo.com", "zemanta.com", "primis.tech", "connatix.com", "playwire.com",
        "fandomadtags.com", "kargo.com", "33across.com", "onetag-sys.com", "adsrvr.org", "id5-sync.com", "ezoic.net",
        "ezojs.com", "mediavine.com", "intergient.com", "nitropay.com", "freestar.io", "pbxai.com",
    )

    fun blocks(host: String?): Boolean {
        var h = host?.lowercase() ?: return false
        while (true) {
            if (h in HOSTS) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }
}

private const val REDIRECT_GRACE_MS = 10_000L

internal fun readerAllows(start: String, target: Uri, userInitiated: Boolean): Boolean {
    val host = target.host ?: return false
    if (target.scheme != "https" && target.scheme != "http") return false
    if (steamChatAllows(host) || userInitiated) return true
    val home = Uri.parse(start).host?.let(::siteOf) ?: return false
    return siteOf(host) == home
}

private fun siteOf(host: String): String = host.split('.').takeLast(2).joinToString(".")

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GuideReader(
    url: String, scroll: Float, onPlace: (String, Float) -> Unit, onStopped: () -> Unit,
    onView: (WebView?) -> Unit, onImage: (String) -> Unit,
) {
    val context = LocalContext.current
    AndroidView(factory = { ctx ->
        WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFocusable = false
            isFocusableInTouchMode = false
            setBackgroundColor(0xFF1B2838.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowContentAccess = false
            settings.allowFileAccess = url.startsWith("file:") && GuideLibrary.isSaved(context, File(Uri.parse(url).path.orEmpty()))
            var restore = scroll
            // Opening the page and each tapped link may redirect off the site for a moment; a page's own navigation may not.
            var approvedUntil = android.os.SystemClock.uptimeMillis() + REDIRECT_GRACE_MS
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (request.hasGesture()) approvedUntil = now + REDIRECT_GRACE_MS
                    return !readerAllows(url, request.url, request.hasGesture() || (request.isRedirect && now < approvedUntil))
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? =
                    if (ReaderAds.blocks(request.url.host)) android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    else null

                override fun onPageFinished(view: WebView, url: String) {
                    if (restore > 0f) {
                        val ratio = restore
                        restore = 0f
                        view.postDelayed({ view.scrollTo(0, (view.contentHeight * view.scale * ratio).toInt()) }, 400)
                    }
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    onStopped()
                    return true
                }
            }
            setOnLongClickListener { v ->
                val hit = (v as WebView).hitTestResult
                val src = hit.extra
                if ((hit.type == WebView.HitTestResult.IMAGE_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) &&
                    src != null && src.startsWith("http")) {
                    onImage(src)
                    true
                } else false
            }
            setOnScrollChangeListener { v, _, y, _, _ ->
                val web = v as WebView
                val height = web.contentHeight * web.scale
                val current = web.url ?: return@setOnScrollChangeListener
                if (height > 0) onPlace(current, y / height)
            }
            onView(this)
            loadUrl(url)
        }
    }, modifier = Modifier.fillMaxSize(), onRelease = { view ->
        view.url?.let { current -> onPlace(current, view.scrollY / (view.contentHeight * view.scale).coerceAtLeast(1f)) }
        onView(null)
        view.destroy()
    })
}


@Composable
private fun MapViewer(file: File, onBack: () -> Unit, onDelete: () -> Unit) {
    var image by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    var markers by remember(file) { mutableStateOf(GuideLibrary.markers(file)) }
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(file) { image = withContext(Dispatchers.IO) { decodeMap(file) } }
    val pal = LocalPalette.current
    PanelColumn {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackLink(stringResource(R.string.mode_back), compact = true, onClick = onBack)
            Text(stringResource(R.string.bottom_guides_marker_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            SecondaryButton(stringResource(R.string.common_delete), compact = true, modifier = Modifier.testTag("guides-map-delete"), onClick = onDelete)
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black)
                .border(1.dp, pal.line, RoundedCornerShape(14.dp))
                .pointerInput(file) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val img = image ?: return@Box
            Box(
                Modifier.aspectRatio(img.width.toFloat() / img.height)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                    .pointerInput(file) {
                        detectTapGestures(
                            onDoubleTap = { p ->
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = DOUBLE_TAP_ZOOM
                                    offset = Offset((size.width / 2f - p.x) * DOUBLE_TAP_ZOOM, (size.height / 2f - p.y) * DOUBLE_TAP_ZOOM)
                                }
                            },
                            onLongPress = { p ->
                                markers = markers + (p.x / size.width to p.y / size.height)
                                GuideLibrary.saveMarkers(file, markers)
                            },
                            onTap = { p ->
                                val hit = markers.minByOrNull { (x, y) -> MapMarkers.distance(x * size.width, y * size.height, p.x, p.y) }
                                if (hit != null && MapMarkers.distance(hit.first * size.width, hit.second * size.height, p.x, p.y) < MARKER_HIT_PX * density / scale) {
                                    markers = markers - hit
                                    GuideLibrary.saveMarkers(file, markers)
                                }
                            },
                        )
                    },
            ) {
                Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                Canvas(Modifier.fillMaxSize()) {
                    for ((x, y) in markers) {
                        val c = Offset(x * size.width, y * size.height)
                        drawCircle(Color.Black.copy(alpha = 0.6f), radius = 9.dp.toPx() / scale, center = c)
                        drawCircle(pal.signal, radius = 6.dp.toPx() / scale, center = c)
                    }
                }
            }
        }
    }
}

internal object MapMarkers {
    fun distance(x0: Float, y0: Float, x1: Float, y1: Float): Float = kotlin.math.hypot(x0 - x1, y0 - y1)

    fun sampleSize(width: Int, height: Int, longest: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > longest) sample *= 2
        return sample
    }
}

private fun decodeMap(file: File): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val opts = BitmapFactory.Options().apply { inSampleSize = MapMarkers.sampleSize(bounds.outWidth, bounds.outHeight, MAP_LONGEST) }
    BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
}.getOrNull()

private const val MAX_ZOOM = 8f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MARKER_HIT_PX = 20f
private const val MAP_LONGEST = 4096
private fun linkLabel(kind: GameLinks.Kind): Int = when (kind) {
    GameLinks.Kind.WIKI -> R.string.bottom_guides_link_wiki
    GameLinks.Kind.STRATEGYWIKI -> R.string.bottom_guides_link_strategywiki
    GameLinks.Kind.PCGAMINGWIKI -> R.string.bottom_guides_link_pcgw
    GameLinks.Kind.GAMEFAQS -> R.string.bottom_guides_link_gamefaqs
    GameLinks.Kind.IGN -> R.string.bottom_guides_link_ign
    GameLinks.Kind.HOWLONGTOBEAT -> R.string.bottom_guides_link_hltb
    GameLinks.Kind.MAPGENIE -> R.string.bottom_guides_search_mapgenie
}

private fun searchLabel(id: String): Int = when (id) {
    "youtube" -> R.string.bottom_guides_search_youtube
    else -> R.string.bottom_guides_link_gamefaqs
}

private fun tierLabel(tier: String): Int = when (tier) {
    "platinum" -> R.string.bottom_guides_tier_platinum
    "gold" -> R.string.bottom_guides_tier_gold
    "silver" -> R.string.bottom_guides_tier_silver
    "bronze" -> R.string.bottom_guides_tier_bronze
    else -> R.string.bottom_guides_tier_borked
}

private fun tierColor(tier: String): Color = when (tier) {
    "platinum" -> Color(0xFFB4C7DC)
    "gold" -> Color(0xFFCFB53B)
    "silver" -> Color(0xFFA6A6A6)
    "bronze" -> Color(0xFFCD7F32)
    else -> Color(0xFFFF6B6B)
}

