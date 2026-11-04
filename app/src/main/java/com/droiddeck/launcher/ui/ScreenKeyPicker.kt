package com.droiddeck.launcher.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.ui.GlideDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.PC_KEYS
import com.droiddeck.launcher.input.PC_KEY_ROWS
import com.droiddeck.launcher.input.PC_MODIFIERS
import com.droiddeck.launcher.input.PcKey
import com.droiddeck.launcher.input.pcKeyLabel

@Composable
fun ScreenKeyPicker(
    onDismiss: () -> Unit,
    onAdd: (List<Int>) -> Unit,
    initial: List<Int> = emptyList(),
    sequence: Boolean = false,
    sequenceLimit: Int = 4,
    slots: List<String> = emptyList(),
) {
    var picked by remember { mutableStateOf(initial) }
    val p = LocalPalette.current
    val toggle: (Int) -> Unit = { code ->
        picked = if (sequence) {
            if (code in picked) picked - code else (picked + code).take(sequenceLimit)
        } else if (code in PC_MODIFIERS) {
            if (code in picked) picked - code else picked + code
        } else {
            picked.filter { it in PC_MODIFIERS } + code
        }
    }
    val rows = remember {
        val functionRow = PC_KEY_ROWS[0]
        val nav = listOfNotNull(PC_KEYS[110], PC_KEYS[111], PC_KEYS[102], PC_KEYS[107], PC_KEYS[104], PC_KEYS[109]) +
            listOfNotNull(PC_KEYS[103], PC_KEYS[105], PC_KEYS[108], PC_KEYS[106])
        listOf(functionRow.take(13), nav) + PC_KEY_ROWS.drop(1)
    }
    val unit = remember { rows.maxOf { row -> row.fold(0f) { sum, key -> sum + key.weight } } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().systemBarsPadding().padding(10.dp), contentAlignment = Alignment.Center) {
            Surface(
                Modifier.widthIn(max = 820.dp).fillMaxWidth().fillMaxHeight(), shape = RoundedCornerShape(20.dp),
                color = p.background, contentColor = p.onBackground, border = BorderStroke(1.dp, p.line2),
            ) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.screen_keys_add), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = onDismiss, modifier = Modifier.focusGlide(CircleShape)) { Icon(Icons.Default.Close, null) }
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 26.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (sequence && slots.size >= sequenceLimit) {
                            slots.take(sequenceLimit).forEachIndexed { index, arrow ->
                                val code = picked.getOrNull(index)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (code == null) p.line2.copy(alpha = 0.2f) else p.primary.copy(alpha = 0.16f),
                                    modifier = if (code == null) Modifier else Modifier.clickable { picked = picked - code },
                                ) {
                                    Text(
                                        if (code == null) arrow else "$arrow ${pcKeyLabel(LocalContext.current, code)}",
                                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        } else {
                            picked.forEach { code ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp), color = p.primary.copy(alpha = 0.16f),
                                    modifier = Modifier.clickable { picked = picked - code },
                                ) {
                                    Text(pcKeyLabel(LocalContext.current, code), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(rows.size) { index ->
                            val row = rows[index]
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                row.forEach { key -> KeyCell(key, key.code in picked, Modifier.weight(key.weight)) { toggle(key.code) } }
                                if (row.sumOf { it.weight.toDouble() } < unit) {
                                    Spacer(Modifier.weight(unit - row.fold(0f) { sum, key -> sum + key.weight }))
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onAdd(picked) },
                            enabled = picked.isNotEmpty(),
                            modifier = Modifier.weight(1f).focusGlide(CircleShape),
                        ) {
                            Text(stringResource(R.string.screen_keys_add))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCell(key: PcKey, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val label = pcKeyLabel(LocalContext.current, key.code)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) p.primary.copy(alpha = 0.2f) else p.surface,
        border = BorderStroke(1.dp, if (selected) p.primary else p.line),
        modifier = modifier.height(36.dp).focusGlide(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
            Text(
                label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = if (label.length > 4) 10.sp else 12.sp,
            )
        }
    }
}
