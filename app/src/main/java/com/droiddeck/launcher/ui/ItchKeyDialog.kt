package com.droiddeck.launcher.ui

import androidx.compose.foundation.shape.CircleShape
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.droiddeck.launcher.ui.GlideAlertDialog as AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.itch.ItchApi
import com.droiddeck.launcher.stores.itch.ItchAuth
import com.droiddeck.launcher.stores.itch.ItchLibrary

private fun copiedKey(ctx: Context): String? {
    val clipboard = ctx.getSystemService(ClipboardManager::class.java)
    val text = runCatching { clipboard?.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull() ?: return null
    return ItchAuth.candidates(text).firstOrNull()
}

@Composable
internal fun ItchKeyDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(copiedKey(ctx).orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var rejected by remember { mutableStateOf(false) }

    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && key.isBlank()) {
                copiedKey(ctx)?.let { key = it }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun submit() {
        val raw = key.trim()
        if (raw.isEmpty() || busy) return
        busy = true
        rejected = false
        Thread({
            val user = ItchAuth.verify(raw)
            if (user == null) {
                StoresState.post { busy = false; rejected = true }
                return@Thread
            }
            ItchAuth.save(ctx, raw, user)
            StoresState.post { busy = false; onDismiss() }
            StoresState.refresh(ctx)
            ItchLibrary.sync(ctx, true)
        }, "itch-key").start()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(Store.ITCH.label) },
        text = {
            Column {
                AdbTextField(
                    value = key,
                    onValueChange = { key = it; rejected = false },
                    label = stringResource(R.string.stores_itch_key),
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                    focusOnShow = true,
                    onDone = { submit() },
                )
                Row(horizontalArrangement = Arrangement.Start) {
                    TextButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ItchApi.KEYS_PAGE)))
                    }, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.stores_itch_get_key)) }
                }
                if (rejected) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.stores_itch_key_rejected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = key.isNotBlank() && !busy, onClick = { submit() }, modifier = Modifier.focusGlide(CircleShape)) {
                Text(stringResource(R.string.game_env_save))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
