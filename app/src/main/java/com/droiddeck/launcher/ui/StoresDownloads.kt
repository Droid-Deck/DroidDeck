package com.droiddeck.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Pause
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.download.StoreDownloadTier
import com.droiddeck.launcher.stores.epic.EpicClient
import com.droiddeck.launcher.stores.formatBytes
import com.droiddeck.launcher.stores.formatSpeed


@Composable
internal fun StoresDownloadsPane(s: FrontEndState, a: FrontEndActions) {
    DownloadList(s, a)
}

@Composable
private fun DownloadList(s: FrontEndState, a: FrontEndActions) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    val entries = StoresState.downloads
    var clearing by remember { mutableStateOf(false) }
    val (active, finished) = entries.partition { it.isActive }.let { (on, off) ->
        on.sortedWith(compareBy<DownloadEntry>({ when (it.state) { DownloadState.RUNNING -> 0; DownloadState.PAUSED -> 1; else -> 2 } }, { it.queuePosition })) to off
    }
    val finishedShown = if (clearing) emptyList() else finished
    val queued = entries.mapTo(HashSet()) { it.key }
    val ctx = LocalContext.current
    val missing = StoresState.unfinished.map { it.store }.filter { StoresState.library[it] == null }.toSet()
    LaunchedEffect(missing) { missing.forEach { StoresState.backend(it)?.syncLibrary(ctx, false) } }
    val interrupted = StoresState.unfinished.mapNotNull { u ->
        StoresState.library[u.store]?.firstOrNull {
            u.id == it.id || (u.id == null && u.folder.name == com.droiddeck.launcher.stores.StoreInstallRoot.folderName(it.title, it.id))
        }
    }.filter { "${it.store.id}:${it.id}" !in queued }.distinctBy { "${it.store.id}:${it.id}" }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AnimatedVisibility(entries.isEmpty() && interrupted.isEmpty(), enter = fadeIn(Motion.tw(300, 300)), exit = fadeOut(Motion.tw(120))) {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.stores_downloads_empty), fontSize = 13.sp, color = colors.onSurfaceVariant)
            }
        }
        AnimatedRows(
            active, key = { it.key },
            enter = { i, later ->
                val wait = if (later) 0 else 90 * i.coerceAtMost(4)
                expandHorizontally(Motion.tw(360, wait), expandFrom = Alignment.End) + expandVertically(Motion.tw(320, wait), expandFrom = Alignment.Top) +
                    fadeIn(Motion.tw(300, wait + 60))
            },
            exit = { row, _, _ ->
                if (StoresState.download(row.key)?.state == DownloadState.INSTALLED)
                    fadeOut(Motion.tw(220, GATHER_HOLD_MS)) + shrinkVertically(Motion.tw(300, GATHER_HOLD_MS))
                else shrinkHorizontally(Motion.tw(260), shrinkTowards = Alignment.End) + shrinkVertically(Motion.tw(300, 120)) + fadeOut(Motion.tw(200, 80))
            },
        ) { row, _ -> DownloadCard(StoresState.download(row.key) ?: row, s, a) }
        AnimatedRows(
            interrupted, key = { "${it.store.id}:${it.id}" },
            enter = { i, _ -> fadeIn(Motion.tw(300, 60 * i.coerceAtMost(5))) + expandVertically(Motion.tw(260)) },
            exit = { _, _, _ -> fadeOut(Motion.tw(160)) + shrinkVertically(Motion.tw(220, 60)) },
        ) { item, _ -> InterruptedRow(item) }
        AnimatedVisibility(
            finishedShown.isNotEmpty(),
            enter = expandVertically(Motion.tw(260)) + fadeIn(Motion.tw(220)),
            exit = fadeOut(Motion.tw(200, 60 * finished.size.coerceAtMost(8) + 160)) + shrinkVertically(Motion.tw(220, 60 * finished.size.coerceAtMost(8) + 200)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = if (active.isEmpty()) 0.dp else 4.dp)) {
                Box(Modifier.weight(1f).height(1.dp).background(pal.line))
                RoundAction(Icons.Outlined.Close, stringResource(R.string.stores_dl_clear_all), size = 30.dp) {
                    if (!clearing) {
                        val n = finished.size.coerceAtMost(8)
                        clearing = true
                        scope.launch {
                            delay(Motion.ms(60 * n + 420).toLong())
                            DownloadQueue.dismissFinished()
                            clearing = false
                        }
                    }
                }
            }
        }
        AnimatedRows(
            finishedShown, key = { it.key },
            enter = { i, later -> if (later) expandVertically(Motion.tw(300, GATHER_HOLD_MS)) + fadeIn(Motion.tw(220, GATHER_HOLD_MS + 50)) else fadeIn(Motion.tw(300, 60 * i.coerceAtMost(5))) },
            exit = { _, i, n ->
                val wait = 60 * (n - 1 - i).coerceIn(0, 8)
                scaleOut(Motion.tw(220, wait), targetScale = 0.2f, transformOrigin = TransformOrigin(1f, 0f)) +
                    fadeOut(Motion.tw(200, wait + 40)) + shrinkVertically(Motion.tw(240, wait + 80))
            },
        ) { row, later ->
            val d = StoresState.download(row.key) ?: row
            if (d.state == DownloadState.FAILED) DownloadCard(d, s, a) else FinishedRow(d, s, a, bloom = later)
        }
    }
}

@Composable
private fun InterruptedRow(item: com.droiddeck.launcher.stores.CatalogItem) {
    val ctx = LocalContext.current
    ArtStrip(item.imageUrl ?: item.tallImageUrl, item.title) {
        StripLabel(item.title, item.store.id, stringResource(R.string.stores_dl_interrupted), Color(0xFFFFC24D))
        RoundAction(Icons.Filled.PlayArrow, stringResource(R.string.stores_dl_resume), primary = true) { StoresState.requestInstall(ctx, item) }
        CancelAction { StoresState.clearUnfinished(ctx, item) }
    }
}

private const val GATHER_HOLD_MS = 900

@Composable
private fun ArtStrip(cover: String?, name: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val pal = LocalPalette.current
    Box(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(Shape12).background(Color(0xFF101318)).border(1.dp, pal.line, Shape12)) {
        if (!cover.isNullOrEmpty()) AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        else Spacer(Modifier.matchParentSize().background(artBrush(hueOf(name))))
        Spacer(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.5f to Color.Black.copy(alpha = 0.72f), 1f to Color.Black.copy(alpha = 0.2f))))
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), content = content,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StripLabel(title: String, store: String, status: String, statusColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        SourceChip(store, small = true)
        Text(status, fontSize = 12.sp, color = statusColor, maxLines = 1)
    }
}

@Composable
private fun FinishedRow(d: DownloadEntry, s: FrontEndState, a: FrontEndActions, bloom: Boolean = false) {
    val ctx = LocalContext.current
    ArtStrip(d.cover, d.name) {
        StripLabel(d.name, d.store.id, stringResource(if (d.state == DownloadState.INSTALLED) R.string.stores_installed_chip else R.string.stores_dl_cancelled), Color.White.copy(alpha = 0.72f))
        if (d.state == DownloadState.INSTALLED) Box(Modifier.bloomIn(bloom, GATHER_HOLD_MS + 150)) {
            RoundAction(Icons.Filled.PlayArrow, stringResource(R.string.stores_play), primary = true) { if (d.key == EpicClient.DOWNLOAD_KEY) a.onEpicLauncher() else launchStoreGame(ctx, d.store, d.id, s, a) }
        }
    }
}

private fun Modifier.bloomIn(on: Boolean, waitMs: Int): Modifier = composed {
    val k = remember { Animatable(if (on && Motion.scale > 0f) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (k.value < 1f) {
            delay(Motion.ms(waitMs).toLong())
            k.animateTo(1f, Motion.sp(0.5f, 300f))
        }
    }
    graphicsLayer { scaleX = k.value; scaleY = k.value; alpha = k.value.coerceIn(0f, 1f) }
}

@Composable
internal fun RoundAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, primary: Boolean = false, danger: Boolean = false,
    enabled: Boolean = true, size: androidx.compose.ui.unit.Dp = 36.dp, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hot = rememberHot(src)
    val fill = when { danger -> Color(0xFFD9443B); primary -> pal.signal; else -> colors.surface.copy(alpha = 0.85f) }
    val tint = if (primary || danger) Color.White else if (hot) pal.signal else colors.onBackground
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(fill)
            .glideBorder(hot, androidx.compose.foundation.shape.CircleShape, if (primary || danger) colors.onBackground else pal.signal, pal.line)
            .alpha(if (enabled) 1f else 0.4f)
            .hoverable(src).clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick),
    ) { androidx.compose.material3.Icon(icon, description, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

@Composable
private fun CancelAction(onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val keep = stringResource(R.string.stores_notification_keep)
    val delete = stringResource(R.string.stores_dl_cancel_confirm)
    Box(Modifier.onGloballyPositioned { placed[0] = it }) {
        RoundAction(Icons.Outlined.Close, stringResource(R.string.stores_dl_cancel)) {
            val at = placed[0]?.takeIf { it.isAttached }?.boundsInRoot() ?: return@RoundAction
            StoresMotion.ask(StepAsk(
                anchor = at, side = StepSide.Left, accent = colors.error, items = 2,
                handle = { androidx.compose.material3.Icon(Icons.Outlined.Close, null, tint = colors.error, modifier = Modifier.size(18.dp)) },
            ) {
                val left = remember { Animatable(1f) }
                LaunchedEffect(Unit) {
                    if (Motion.scale > 0f) launch { left.animateTo(0f, androidx.compose.animation.core.tween((CANCEL_HOLD_MS / Motion.scale).toInt(), easing = LinearEasing)) }
                    delay(CANCEL_HOLD_MS.toLong())
                    StoresMotion.fold()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    StepChoice(keep, enabled = open, modifier = Modifier.stepItem(0).focusRequester(first).timeRim({ left.value }, colors.onBackground)) { StoresMotion.fold() }
                    StepChoice(delete, danger = true, enabled = open, modifier = Modifier.stepItem(1)) { StoresMotion.fold(); onDelete() }
                }
            })
        }
    }
}

private const val CANCEL_HOLD_MS = 4000

private fun Modifier.timeRim(left: () -> Float, color: Color): Modifier = drawWithContent {
    drawContent()
    val v = left()
    if (v <= 0f) return@drawWithContent
    val w = 1.5.dp.toPx()
    val r = size.height / 2f
    val outline = androidx.compose.ui.graphics.Path().apply {
        moveTo(size.width / 2f, w / 2f)
        lineTo(size.width - r, w / 2f)
        arcTo(Rect(size.width - 2 * r + w / 2f, w / 2f, size.width - w / 2f, size.height - w / 2f), -90f, 180f, false)
        lineTo(r, size.height - w / 2f)
        arcTo(Rect(w / 2f, w / 2f, 2 * r - w / 2f, size.height - w / 2f), 90f, 180f, false)
        lineTo(size.width / 2f, w / 2f)
    }
    val measure = androidx.compose.ui.graphics.PathMeasure().apply { setPath(outline, false) }
    val part = androidx.compose.ui.graphics.Path()
    measure.getSegment(0f, measure.length * v, part, true)
    drawPath(part, color.copy(alpha = 0.8f), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
}

@Composable
private fun DownloadCard(d: DownloadEntry, s: FrontEndState, a: FrontEndActions) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val narrow = LocalNarrowPane.current
    val ink = Color.White
    val dim = Color.White.copy(alpha = 0.72f)
    Box(Modifier.fillMaxWidth().heightIn(min = 88.dp).clip(Shape12).background(Color(0xFF101318)).border(1.dp, pal.line, Shape12)) {
        if (!d.cover.isNullOrEmpty()) AsyncImage(model = d.cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        else Spacer(Modifier.matchParentSize().background(artBrush(hueOf(d.name))))
        Spacer(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.5f to Color.Black.copy(alpha = 0.7f), 1f to Color.Black.copy(alpha = 0.15f))))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(d.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    SourceChip(d.store.id, small = true)
                    val where = listOfNotNull(d.location.takeIf { it.isNotBlank() }, d.diskBytes.takeIf { it > 0 }?.let { formatBytes(it) }).joinToString(" · ")
                    if (where.isNotEmpty()) Text(where, fontSize = 11.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StageBar(d)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val meta = when (d.state) {
                        DownloadState.INSTALLED -> stringResource(R.string.stores_installed_chip)
                        DownloadState.CANCELLED -> stringResource(R.string.stores_dl_cancelled)
                        DownloadState.FAILED -> {
                            if (!d.error.isNullOrBlank()) stringResource(R.string.stores_dl_failed_reason, d.error)
                            else if (d.bytesDone > 0) stringResource(R.string.stores_dl_failed_kept, formatBytes(d.bytesDone))
                            else stringResource(R.string.stores_dl_failed)
                        }
                        DownloadState.PAUSED -> stringResource(R.string.stores_dl_paused)
                        DownloadState.QUEUED -> if (d.queuePosition > 0) stringResource(R.string.stores_dl_queued_at, d.queuePosition) else stringResource(R.string.stores_dl_queued)
                        DownloadState.RUNNING -> {
                            val head = downloadLabel(d)
                            when {
                                d.stage == DownloadStage.DOWNLOAD && d.bytesTotal > 0 -> stringResource(R.string.stores_dl_with, head, stringResource(R.string.stores_dl_amount, formatBytes(d.bytesDone), formatBytes(d.bytesTotal)))
                                d.stageItemsTotal > 0 -> stringResource(R.string.stores_dl_with, head, stringResource(R.string.stores_dl_items, d.stageItems, d.stageItemsTotal))
                                else -> head
                            }
                        }
                    }
                    val metaColor = if (d.state == DownloadState.FAILED) colors.error else dim
                    Text(meta, fontSize = 12.sp, color = metaColor, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (d.state == DownloadState.RUNNING && (d.stage == DownloadStage.DOWNLOAD || d.stage == DownloadStage.INSTALL) && d.speedBps > 0) {
                        Text(formatSpeed(d.speedBps), fontSize = 12.sp, color = dim, maxLines = 1)
                        if (d.etaSeconds >= 0) Text(eta(d.etaSeconds), fontSize = 12.sp, color = dim, maxLines = 1)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (d.state) {
                    DownloadState.RUNNING, DownloadState.QUEUED -> {
                        RoundAction(Icons.Filled.Pause, stringResource(R.string.stores_dl_pause)) { DownloadQueue.pause(d.key) }
                        CancelAction { DownloadQueue.cancel(ctx, d.key) }
                    }
                    DownloadState.PAUSED -> {
                        RoundAction(Icons.Filled.PlayArrow, stringResource(R.string.stores_dl_resume), primary = true) { DownloadQueue.resume(ctx, d.key) }
                        CancelAction { DownloadQueue.cancel(ctx, d.key) }
                    }
                    DownloadState.FAILED -> {
                        RoundAction(Icons.Filled.Refresh, stringResource(R.string.stores_dl_resume), primary = true) { DownloadQueue.retry(ctx, d.key) }
                        CancelAction { DownloadQueue.clear(ctx, d.key) }
                    }
                    DownloadState.INSTALLED -> Box(Modifier.bloomIn(true, 420)) {
                        RoundAction(Icons.Filled.PlayArrow, stringResource(R.string.stores_play), primary = true) { if (d.key == EpicClient.DOWNLOAD_KEY) a.onEpicLauncher() else launchStoreGame(ctx, d.store, d.id, s, a) }
                    }
                    DownloadState.CANCELLED -> {}
                }
            }
        }
    }
}

@Composable
private fun StageBar(d: DownloadEntry) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val stages = listOf(DownloadStage.MANIFEST, DownloadStage.DOWNLOAD, DownloadStage.VERIFY, DownloadStage.INSTALL)
    val installed = d.state == DownloadState.INSTALLED
    val actives = stages.map { st -> !installed && d.stage == st }
    val fills = stages.mapIndexed { i, st ->
        when {
            actives[i] -> d.stageFraction.let { if (it < 0f) 0.04f else it }
            d.passed(st) || st.ordinal < d.stage.ordinal || d.stage == DownloadStage.DONE -> 1f
            else -> 0f
        }
    }
    val done = fills.mapIndexed { i, f -> !actives[i] && f >= 1f }
    val shown = fills.mapIndexed { i, f -> animateFloatAsState(f, Motion.sp(0.8f, 300f), label = "stage$i") }
    val bloops = remember { List(4) { Animatable(1f) } }
    val wasDone = remember { done.toBooleanArray() }
    done.forEachIndexed { i, dn ->
        LaunchedEffect(i, dn) {
            if (dn && !wasDone[i] && Motion.scale > 0f) {
                bloops[i].animateTo(1.6f, Motion.tw(90))
                bloops[i].animateTo(1f, Motion.sp(0.45f, 600f))
            }
            wasDone[i] = dn
        }
    }
    val gather = remember { Animatable(0f) }
    LaunchedEffect(installed) { if (installed) gather.animateTo(1f, Motion.tw(520, easing = androidx.compose.animation.core.CubicBezierEasing(0.6f, 0f, 0.15f, 1f))) }
    val paused = d.state == DownloadState.PAUSED
    val breath = breathing(paused)
    val verifyColor = Color(0xFF9B6DFF)
    val pausedColor = Color(0xFFFFC24D)
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val h = 6.dp.toPx()
        val cy = size.height / 2f
        val g = gather.value
        if (g < 0.5f) {
            val join = (g * 2f).coerceIn(0f, 1f)
            val gap = 3.dp.toPx() * (1f - join)
            val segW = (size.width - gap * 3f) / 4f
            for (i in 0 until 4) {
                val x = i * (segW + gap)
                drawRoundRect(colors.surfaceVariant, Offset(x, cy - h / 2f), Size(segW, h), CornerRadius(h / 2f))
                val base = when {
                    d.state == DownloadState.FAILED -> colors.error
                    done[i] -> pal.good
                    actives[i] && paused -> pausedColor.copy(alpha = breath)
                    actives[i] && stages[i] == DownloadStage.VERIFY -> verifyColor
                    else -> pal.signal
                }
                val fill = androidx.compose.ui.util.lerp(shown[i].value.coerceIn(0f, 1f), 1f, join)
                val hh = h * bloops[i].value
                if (fill > 0f) drawRoundRect(
                    androidx.compose.ui.graphics.lerp(base, pal.good, join), Offset(x, cy - hh / 2f), Size(segW * fill, hh), CornerRadius(hh / 2f),
                )
            }
        } else {
            val p = ((g - 0.5f) * 2f).coerceIn(0f, 1f)
            val dot = 10.dp.toPx()
            val left = androidx.compose.ui.util.lerp(0f, size.width - dot, p)
            val hh = androidx.compose.ui.util.lerp(h, dot, p)
            drawRoundRect(pal.good, Offset(left, cy - hh / 2f), Size(size.width - left, hh), CornerRadius(hh / 2f), alpha = 1f - p * 0.4f)
        }
    }
}

@Composable
private fun breathing(on: Boolean): Float {
    if (!on || Motion.scale == 0f) return 1f
    val t = rememberInfiniteTransition(label = "breath")
    val v by t.animateFloat(1f, 0.5f, infiniteRepeatable(androidx.compose.animation.core.tween(800), RepeatMode.Reverse), label = "breathe")
    return v
}

private fun eta(seconds: Long): String = when {
    seconds < 60 -> "<1 min"
    seconds < 3600 -> "~${seconds / 60} min"
    else -> "~${seconds / 3600} h ${(seconds % 3600) / 60} m"
}

@Composable
internal fun DownloadControls(s: FrontEndState, a: FrontEndActions) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var parallel by remember { mutableStateOf(DownloadQueue.parallel(ctx)) }
    val tier: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.setup_stores_speed), fontSize = 12.sp, color = colors.onSurfaceVariant)
            SegmentedTabs(StoreDownloadTier.ALL.map { it.id to stringResource(it.label) }, s.gameStoresSpeedTier) { a.onGameStoresSpeedTier(it) }
        }
    }
    val count: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.stores_parallel), fontSize = 12.sp, color = colors.onSurfaceVariant)
            SegmentedTabs(listOf(1 to "1", 2 to "2", 3 to "3"), parallel) { parallel = it; DownloadQueue.setParallel(ctx, it) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 460.dp) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) { tier(); count() }
        else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { tier(); count() }
    }
}

internal fun storesSettingsStep(at: Rect, s: androidx.compose.runtime.State<FrontEndState>, a: FrontEndActions, accent: androidx.compose.ui.graphics.Color): StepAsk =
    StepAsk(
        anchor = at, side = StepSide.Left, accent = accent, items = 2, maxWidth = 440.dp,
        handle = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Settings, null, tint = accent, modifier = Modifier.size(20.dp)) },
    ) {
        StepTitle(stringResource(R.string.stores_settings_eyebrow))
        Column(
            Modifier.stepItem(1).fillMaxWidth().heightIn(max = minOf(340.dp, (maxHeight - 64.dp).coerceAtLeast(44.dp))).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val st = s.value
            SettingsRow(stringResource(R.string.setup_stores_open_on), null, stacked = true) {
                Box(Modifier.focusRequester(first).focusGroup()) { SegmentedTabs(
                    listOf(SessionPrefs.STORES_OPEN_LIBRARY to stringResource(R.string.stores_tab_library), SessionPrefs.STORES_OPEN_STORE to stringResource(R.string.stores_tab_store)),
                    st.storesOpenTab,
                ) { a.onStoresOpenTab(it) } }
            }
            SettingsRow(stringResource(R.string.stores_show_mature), null) {
                ToggleSwitch(st.storesShowMature, label = stringResource(R.string.stores_show_mature)) { a.onStoresShowMature(it) }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { DownloadControls(st, a) }
            val context = androidx.compose.ui.platform.LocalContext.current
            var gogAchievements by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.droiddeck.launcher.stores.gog.GogComet.enabled(context)) }
            SettingsRow(stringResource(R.string.gog_achievements), stringResource(R.string.gog_achievements_hint)) {
                ToggleSwitch(gogAchievements, label = stringResource(R.string.gog_achievements)) {
                    gogAchievements = it
                    com.droiddeck.launcher.stores.gog.GogComet.setEnabled(context, it)
                }
            }
            StoresEngineRow()
        }
    }
