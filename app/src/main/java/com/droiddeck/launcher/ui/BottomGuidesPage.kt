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
internal fun BottomGuidesPage(runningGame: () -> String?) {
    val context = LocalContext.current
    val id = rememberShownGame(runningGame)
    if (id == null) {
        PageNote(stringResource(R.string.bottom_guides_no_game))
        return
    }
    val game = id.toString()
    val scope = rememberCoroutineScope()
    var reading by remember(id) { mutableStateOf(SteamGuides.place(context, game)?.url) }
    var section by remember(id) { mutableStateOf(GuideSection.GUIDES) }
    var tag by remember(id) { mutableStateOf<String?>(null) }
    var list by remember(id) { mutableStateOf<GuideList>(GuideList.Loading) }
    var cookieHeader by remember(id) { mutableStateOf<String?>(null) }
    var name by remember(id) { mutableStateOf<String?>(null) }
    var openMap by remember(id) { mutableStateOf<File?>(null) }
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
    val open = reading
    if (open != null) {
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
        return
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in GuideSection.entries) GuideChip(stringResource(s.label), s == section, "guides-section-${s.name.lowercase()}") { section = s }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                GuideSection.GUIDES -> GuidesSection(list, tag, SteamGuides.place(context, game), onTag = { tag = it }, onOpen = { reading = it })
                GuideSection.WIKI -> WikiSection(id, name, onOpen = { reading = it })
                GuideSection.MAPS -> MapsSection(game, name, libraryRevision, onOpenMap = { openMap = it }, onOpen = { reading = it })
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
    list: GuideList, tag: String?, last: SteamGuides.Place?, onTag: (String?) -> Unit, onOpen: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "tags") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((value, label) in TAG_LABELS) GuideChip(stringResource(label), value == tag, "guides-tag-${value ?: "all"}", small = true) { onTag(value) }
            }
        }
        if (last != null) item(key = "continue") {
            BottomPillButton(stringResource(R.string.bottom_guides_continue), "bottom-guides-continue") { onOpen(last.url) }
        }
        when (list) {
            GuideList.Loading -> item(key = "loading") { InlineNote(stringResource(R.string.achievements_reading)) }
            GuideList.Failed -> item(key = "failed") { InlineNote(stringResource(R.string.bottom_guides_failed)) }
            is GuideList.Found ->
                if (list.guides.isEmpty()) item(key = "empty") { InlineNote(stringResource(R.string.bottom_guides_empty)) }
                else items(list.guides, key = { it.id }) { guide -> GuideRow(guide) { onOpen(guide.url) } }
        }
    }
}

@Composable
private fun WikiSection(appId: Long, name: String?, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var links by remember(appId) { mutableStateOf<List<GameLinks.Link>?>(null) }
    var tier by remember(appId) { mutableStateOf<String?>(null) }
    LaunchedEffect(appId) {
        val (found, t) = withContext(Dispatchers.IO) {
            val pending = async { GameLinks.protonTier(appId) }
            GameLinks.links(context, appId) to pending.await()
        }
        tier = t
        links = found
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tier?.let { t ->
            item(key = "tier") {
                GuideLinkRow(stringResource(R.string.bottom_guides_protondb), stringResource(tierLabel(t)), tierColor(t), "guides-protondb") {
                    onOpen("https://www.protondb.com/app/$appId")
                }
            }
        }
        val found = links
        if (found == null) item(key = "loading") { InlineNote(stringResource(R.string.achievements_reading)) }
        else items(found, key = { it.kind.name }) { link ->
            GuideLinkRow(stringResource(linkLabel(link.kind)), Uri.parse(link.url).host.orEmpty().removePrefix("www."), null, "guides-link-${link.kind.name.lowercase()}") {
                onOpen(link.url)
            }
        }
        name?.let { n ->
            item(key = "search-head") { GuideSectionLabel(stringResource(R.string.bottom_guides_search)) }
            items(GameLinks.searches(n), key = { "search-" + it.id }) { s ->
                GuideLinkRow(stringResource(searchLabel(s.id)), n, null, "guides-search-${s.id}") { onOpen(s.url) }
            }
        }
    }
}

@Composable
private fun MapsSection(game: String, name: String?, revision: Int, onOpenMap: (File) -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var maps by remember(game) { mutableStateOf<List<GuideLibrary.PinnedMap>>(emptyList()) }
    LaunchedEffect(game, revision) { maps = withContext(Dispatchers.IO) { GuideLibrary.maps(context, game) } }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (maps.isEmpty()) item(key = "hint") { InlineNote(stringResource(R.string.bottom_guides_maps_hint)) }
        items(maps, key = { it.file.path }) { map ->
            val pal = LocalPalette.current
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pal.surface)
                    .clickable(role = Role.Button) { onOpenMap(map.file) }.testTag("guides-map").padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(model = map.file, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(96.dp, 64.dp).clip(RoundedCornerShape(8.dp)))
                Text(stringResource(R.string.bottom_guides_markers, map.markers.size), fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
            }
        }
        name?.let { n ->
            item(key = "find") { GuideSectionLabel(stringResource(R.string.bottom_guides_find_maps)) }
            items(GameLinks.mapSearches(n), key = { it.id }) { s ->
                GuideLinkRow(stringResource(searchLabel(s.id)), n, null, "guides-maps-${s.id}") { onOpen(s.url) }
            }
        }
    }
}

@Composable
private fun SavedSection(game: String, revision: Int, onOpen: (File) -> Unit, onDelete: (File) -> Unit) {
    val context = LocalContext.current
    var saved by remember(game) { mutableStateOf<List<GuideLibrary.Saved>>(emptyList()) }
    LaunchedEffect(game, revision) { saved = withContext(Dispatchers.IO) { GuideLibrary.saved(context, game) } }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (saved.isEmpty()) item(key = "hint") { InlineNote(stringResource(R.string.bottom_guides_saved_hint)) }
        items(saved, key = { it.file.path }) { page ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    GuideLinkRow(page.title, Uri.parse(page.url).host.orEmpty().removePrefix("www."), null, "guides-saved") { onOpen(page.file) }
                }
                BottomPillButton(stringResource(R.string.common_delete), "guides-saved-delete") { onDelete(page.file) }
            }
        }
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
    var view by remember { mutableStateOf<WebView?>(null) }
    var image by remember(url) { mutableStateOf<String?>(null) }
    val local = url.startsWith("file:")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BottomPillButton(stringResource(R.string.mode_back), "bottom-guides-back", onBack)
            if (!local) BottomPillButton(stringResource(R.string.game_env_save), "bottom-guides-save") { view?.let(onSave) }
            image?.let { img -> BottomPillButton(stringResource(R.string.bottom_guides_pin), "bottom-guides-pin") { onPin(img); image = null } }
            Text(note ?: stringResource(R.string.bottom_guides_pin_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp))) {
            GuideReader(url, scroll, onPlace, onStopped, onView = { view = it }, onImage = { image = it })
        }
    }
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
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !readerAllows(url, request.url, request.hasGesture() || request.isRedirect)

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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BottomPillButton(stringResource(R.string.mode_back), "guides-map-back", onBack)
            BottomPillButton(stringResource(R.string.common_delete), "guides-map-delete", onDelete)
            Text(stringResource(R.string.bottom_guides_marker_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f), maxLines = 2)
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black)
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
        while (maxOf(width, height) / (sample * 2) >= longest) sample *= 2
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

@Composable
private fun GuideChip(label: String, selected: Boolean, tag: String, small: Boolean = false, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.height(if (small) 32.dp else 38.dp).clip(shape)
            .background(if (selected) pal.signal else Color.Transparent)
            .border(1.dp, if (selected) pal.signal else pal.line, shape)
            .clickable(role = Role.Tab, onClick = onClick).testTag(tag).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = if (small) 12.sp else 14.sp, fontWeight = FontWeight.SemiBold,
            color = if (selected) pal.onSignal else MaterialTheme.colorScheme.onBackground, maxLines = 1)
    }
}

@Composable
private fun GuideLinkRow(title: String, detail: String, badge: Color?, tag: String, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pal.surface)
            .clickable(role = Role.Button, onClick = onClick).testTag(tag).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(detail, fontSize = 13.sp, fontWeight = if (badge != null) FontWeight.Bold else FontWeight.Normal,
            color = badge ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GuideSectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LocalPalette.current.signal, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
}

@Composable
private fun InlineNote(text: String) {
    Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(12.dp))
}

private fun linkLabel(kind: GameLinks.Kind): Int = when (kind) {
    GameLinks.Kind.WIKI -> R.string.bottom_guides_link_wiki
    GameLinks.Kind.STRATEGYWIKI -> R.string.bottom_guides_link_strategywiki
    GameLinks.Kind.PCGAMINGWIKI -> R.string.bottom_guides_link_pcgw
    GameLinks.Kind.GAMEFAQS -> R.string.bottom_guides_link_gamefaqs
    GameLinks.Kind.IGN -> R.string.bottom_guides_link_ign
    GameLinks.Kind.HOWLONGTOBEAT -> R.string.bottom_guides_link_hltb
}

private fun searchLabel(id: String): Int = when (id) {
    "youtube" -> R.string.bottom_guides_search_youtube
    "gamefaqs" -> R.string.bottom_guides_link_gamefaqs
    "ign" -> R.string.bottom_guides_link_ign
    "mapgenie" -> R.string.bottom_guides_search_mapgenie
    else -> R.string.bottom_guides_search_vgmaps
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

@Composable
private fun PageNote(text: String) {
    Box(Modifier.fillMaxSize()) {
        Text(text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(24.dp))
    }
}

@Composable
internal fun GuideRow(guide: SteamGuides.Guide, onOpen: () -> Unit) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pal.surface)
            .clickable(role = Role.Button, onClick = onOpen).testTag("guide-${guide.id}").padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(pal.line)) {
            if (guide.preview != null) AsyncImage(model = guide.preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(guide.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(guide.author.takeIf { it.isNotEmpty() }, "★".repeat(guide.stars).takeIf { it.isNotEmpty() }).joinToString(" · "),
                fontSize = 12.sp, color = pal.signal, maxLines = 1)
            Text(guide.summary, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
