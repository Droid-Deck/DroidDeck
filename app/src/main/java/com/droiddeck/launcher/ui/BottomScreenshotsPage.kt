package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.BottomScreenshots
import com.droiddeck.launcher.session.BottomShot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val SHOTS_POLL_MS = 5_000L

@Composable
fun BottomScreenshotsPage(takeScreenshot: ((onDone: (Boolean) -> Unit) -> Unit)?) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var shots by remember { mutableStateOf<List<BottomShot>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<BottomShot?>(null) }
    var status by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) {
        while (true) {
            shots = withContext(Dispatchers.IO) { BottomScreenshots.list(context, LinuxRuntime.rootDir(context)) }
            delay(SHOTS_POLL_MS)
        }
    }
    LaunchedEffect(status) {
        if (status != null) {
            delay(3_000L)
            status = null
        }
    }
    open?.let { shot ->
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(androidx.compose.ui.graphics.Color.Black)
            .clickable(role = Role.Button) { open = null }.testTag("bottom-shot-full")) {
            AsyncImage(ImageRequest.Builder(context).data(shot.model).build(), contentDescription = null,
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        return
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(status?.let { stringResource(it) } ?: stringResource(R.string.bottom_shots_where), fontSize = 13.sp,
                color = colors.onSurfaceVariant, maxLines = 2, modifier = Modifier.weight(1f).padding(end = 8.dp))
            if (takeScreenshot != null) BottomPillButton(stringResource(R.string.bottom_shots_take), "bottom-shots-take") {
                if (!busy) {
                    busy = true
                    takeScreenshot { ok ->
                        busy = false
                        status = if (ok) R.string.bottom_shots_saved else R.string.bottom_shots_failed
                        refresh++
                    }
                }
            }
        }
        if (shots.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.bottom_shots_none), fontSize = 15.sp, color = colors.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shots, key = { it.model.toString() }) { shot ->
                    AsyncImage(
                        ImageRequest.Builder(context).data(shot.model).size(480, 270).build(), contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
                            .background(LocalPalette.current.surface).clickable(role = Role.Button) { open = shot },
                    )
                }
            }
        }
    }
}
