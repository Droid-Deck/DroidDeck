package com.droiddeck.launcher.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.ui.GlideDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.droiddeck.launcher.R

@Composable
fun ScreenLayoutPanel(
    layouts: List<String>,
    onSave: (String) -> Unit,
    onLoad: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: (String) -> Unit,
    exportText: () -> String,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (text != null) onImport(text)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(exportText().toByteArray()) } }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(
                Modifier.widthIn(max = 520.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                color = p.background, contentColor = p.onBackground, border = BorderStroke(1.dp, p.line2),
            ) {
                Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.screen_key_layout), Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.focusGlide(CircleShape)) { Icon(Icons.Default.Close, null) }
                    }
                    Row(Modifier.fillMaxWidth().padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { naming = true }, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.screen_key_layout_save)) }
                        TextButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.focusGlide(CircleShape)) {
                            Text(stringResource(R.string.boot_movie_import))
                        }
                        TextButton(onClick = { exporter.launch("droiddeck-layout.icp") }, modifier = Modifier.focusGlide(CircleShape)) {
                            Text(stringResource(R.string.screen_key_layout_export))
                        }
                    }
                    Column(Modifier.padding(end = 12.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        layouts.forEach { layout ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    layout, Modifier.weight(1f).clickable { onLoad(layout) }.padding(vertical = 14.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                TextButton(onClick = { onDelete(layout) }, modifier = Modifier.focusGlide(CircleShape)) {
                                    Text(stringResource(R.string.common_delete), color = p.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (naming) {
        Dialog(onDismissRequest = { naming = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), contentAlignment = Alignment.Center) {
                Surface(
                    Modifier.widthIn(max = 520.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                    color = p.background, contentColor = p.onBackground, border = BorderStroke(1.dp, p.line2),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            name, { name = it.take(24) }, Modifier.fillMaxWidth().focusGlide(), singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { naming = false }, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.common_cancel)) }
                            Button(onClick = { onSave(name.trim()); naming = false }, enabled = name.isNotBlank(), modifier = Modifier.focusGlide(CircleShape)) {
                                Text(stringResource(R.string.screen_key_layout_save))
                            }
                        }
                    }
                }
            }
        }
    }
}
