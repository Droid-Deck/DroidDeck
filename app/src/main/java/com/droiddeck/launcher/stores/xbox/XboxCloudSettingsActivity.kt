package com.droiddeck.launcher.stores.xbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import com.droiddeck.launcher.ui.DroidDeckTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class XboxCloudSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DroidDeckTheme {
                var settings by remember { mutableStateOf(XboxCloudSettings.load(this)) }
                var regions by remember { mutableStateOf(emptyList<String>()) }
                LaunchedEffect(Unit) {
                    regions = withContext(Dispatchers.IO) {
                        runCatching {
                            val account = StoreAccounts.signedInAs(applicationContext, Store.XBOX) ?: return@runCatching emptyList()
                            XboxCloudSession.get(applicationContext, account).regionList.map { it.name }
                        }.getOrDefault(emptyList())
                    }
                }
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { finish() }) { Text("Back") }
                        Text("Xbox Cloud Gaming", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    XboxCloudSettingsPanel(settings, regions, live = false) { changed ->
                        settings = changed
                        XboxCloudSettings.save(this@XboxCloudSettingsActivity, changed)
                    }
                }
            }
        }
    }
}
