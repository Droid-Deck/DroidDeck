package com.droiddeck.launcher.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.droiddeck.launcher.ui.GlideAlertDialog as AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.xbox.XboxBackend
import com.droiddeck.launcher.stores.xbox.XboxContent

@Composable
internal fun XboxStoreDialogs(
    content: XboxContent.Plan? = XboxContent.plan,
    pending: XboxBackend.PackageChoice? = XboxBackend.packageChoice,
) {
    val ctx = LocalContext.current
    content?.let {
        AlertDialog(
            onDismissRequest = { XboxContent.dismiss() },
            title = { Text(stringResource(R.string.stores_xbox_content_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                if (content.error != null) Text(content.error)
                else if (content.offers.isEmpty()) Text(stringResource(R.string.stores_xbox_content_none))
                content.offers.forEach { offer ->
                    TextButton(onClick = { XboxContent.install(ctx, offer.item) }, modifier = Modifier.focusGlide(CircleShape)) {
                        Text(stringResource(if (offer.installed) R.string.stores_xbox_content_update else R.string.stores_xbox_content_install, offer.item.title))
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { XboxContent.dismiss() }, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(android.R.string.ok)) } },
        )
    }
    pending?.let {
        AlertDialog(
            onDismissRequest = { XboxBackend.dismissPackages() },
            title = { Text(stringResource(R.string.stores_xbox_choose_content)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { pending.choices.forEach { choice ->
                TextButton(onClick = { XboxBackend.selectPackage(ctx, choice) }, modifier = Modifier.focusGlide(CircleShape)) {
                    Text(stringResource(R.string.stores_xbox_choice, choice.getString("title"), choice.getString("kind")))
                }
            } } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { XboxBackend.dismissPackages() }, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
