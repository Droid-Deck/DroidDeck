package com.droiddeck.launcher.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BluetoothConnected
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.BtControllerActivity
import com.droiddeck.launcher.R

@Composable
internal fun TrinketsPage(modifier: Modifier) {
    val context = LocalContext.current
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.trinkets_intro), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Trinket(Icons.Outlined.BluetoothConnected, stringResource(R.string.btpad_title), stringResource(R.string.btpad_summary)) {
            context.startActivity(Intent(context, BtControllerActivity::class.java))
        }
    }
}

@Composable
private fun Trinket(icon: ImageVector, title: String, summary: String, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line2, Shape14).padding(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(Shape12).background(pal.signal.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = pal.signal)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
            Text(summary, fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
        PrimaryButton(stringResource(R.string.trinkets_open), compact = true, onClick = onOpen)
    }
}
