package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import com.droiddeck.launcher.gpu.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/** One matched driver pair in the list (DriverPairs), and what this device makes of it. */
class PairRow(
    val key: String,
    val name: String,
    val version: String,
    val detail: String,
    val recommended: Boolean,
    /** Built for this GPU's family; the others only show when asked. */
    val suits: Boolean,
    val complete: Boolean,
    val installed: Boolean,
    val active: Boolean,
)

class GpuDriversState(
    val gpuName: String = "",
    val gpuFamily: String = "",
    val soc: String = "",
    val supportText: String = "",
    val supported: Boolean = true,
    val unsupported: Boolean = false,
    val auto: Boolean = true,
    val pairs: List<PairRow> = emptyList(),
    /** The pair key being installed, and how far along (-1 = unknown). */
    val busy: String? = null,
    val percent: Int = -1,
    val autoStatus: String = "",
    val releaseStatus: String = "",
    val checking: Boolean = false,
    // Advanced: each driver on its own.
    val linuxRows: List<DriverRow> = emptyList(),
    val linuxSelected: String = "",
    val androidRows: List<DriverRow> = emptyList(),
    val androidSelected: String = "",
    val linuxDownloads: List<DownloadRow> = emptyList(),
    val androidDownloads: List<DownloadRow> = emptyList(),
    val canRestoreBundled: Boolean = false,
    /** The Android + Linux bundle both drivers are set to ("DD-Turnip 0.1.0"), or null. */
    val activeBundle: String? = null,
) {
    /** The pair both drivers are set to, if they make one. */
    val activePair: PairRow? get() = pairs.firstOrNull { it.active }
}

class GpuDriversActions(
    val onAuto: (Boolean) -> Unit = {},
    val onPair: (String) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSelectLinux: (String) -> Unit = {},
    val onSelectAndroid: (String) -> Unit = {},
    val onRemoveLinux: (String) -> Unit = {},
    val onRemoveAndroid: (String) -> Unit = {},
    val onDownloadDriver: (String) -> Unit = {},
    val onImportLinux: () -> Unit = {},
    val onImportAndroid: () -> Unit = {},
    /** The tab's own import: a bundle, or a single driver of either kind. */
    val onImportZip: () -> Unit = {},
    val onRestoreBundled: () -> Unit = {},
)

/**
 * Everything about the chip - SoC, GPU, CPU, memory, system, the phone's own Vulkan driver, the
 * drivers in use - gathered once, off the main thread, with a button that copies it for a bug
 * report. Stepped out of the Components page's device chip; [first] is where a pad starts.
 */
@Composable
internal fun DeviceDetails(first: FocusRequester) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var details by remember { mutableStateOf<List<DeviceInfo.Section>?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { details = withContext(Dispatchers.IO) { DeviceInfo.collect(context) } }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
    ) {
        val sections = details
        if (sections == null) {
            Text(stringResource(R.string.device_info_loading), fontSize = 13.sp, color = colors.onSurfaceVariant)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (copied) R.string.device_info_copied else R.string.device_info_copy_hint),
                    fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                SecondaryButton(stringResource(R.string.device_info_copy), compact = true, modifier = Modifier.focusRequester(first)) {
                    clipboard.setText(AnnotatedString(DeviceInfo.text(sections)))
                    copied = true
                }
            }
            for (section in sections) DeviceInfoSection(section)
        }
    }
}

/** One part of the device details: its title, then label/value rows. Focusable, so the d-pad walks the list. */
@Composable
private fun DeviceInfoSection(section: DeviceInfo.Section) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.fillMaxWidth().clip(Shape12)
            .glideBorder(hot, Shape12, pal.signal)
            .focusable(interactionSource = src).padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(section.title.uppercase(), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = colors.onSurfaceVariant)
        for ((label, value) in section.rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.width(150.dp))
                Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.onBackground, modifier = Modifier.weight(1f))
            }
        }
    }
}
