package com.droiddeck.launcher.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState

@Composable
internal fun StoreAccountsButton() {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var itchKey by remember { mutableStateOf(false) }
    if (itchKey) ItchKeyDialog { itchKey = false }
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val label = stringResource(R.string.library_accounts)
    val open = {
        placed[0]?.takeIf { it.isAttached }?.boundsInRoot()?.let { at ->
            Steps.ask(StepAsk(
                anchor = at, side = StepSide.Left, accent = pal.signal, items = storesWithSignIn().size + 1, pillCorner = 20.dp, maxWidth = 380.dp,
                handle = { Icon(Icons.Filled.Add, null, tint = pal.signal, modifier = Modifier.size(20.dp)) },
            ) {
                StoreAccountsStep(onSignIn = { store ->
                    Steps.fold()
                    if (store == Store.ITCH) itchKey = true else StoresState.signIn(ctx, store)
                })
            })
        }
        Unit
    }
    Box(Modifier.onGloballyPositioned { placed[0] = it }) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.paneItem("libchip:accounts").testTag("library-accounts").size(40.dp).clip(CircleShape)
                .background(colors.surface)
                .glideBorder(hot, CircleShape, pal.signal, pal.line)
                .hoverable(src)
                .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClickLabel = label, onClick = open)
                .controllerConfirm(onClick = open),
        ) { Icon(Icons.Filled.Add, label, tint = if (hot) colors.onBackground else colors.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
    }
}

private fun storesWithSignIn(): List<Store> = Store.entries.filter { StoresState.backend(it) != null }

@Composable
private fun StepOutScope.StoreAccountsStep(onSignIn: (Store) -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var leaving by remember { mutableStateOf<Store?>(null) }
    LaunchedEffect(leaving) { if (leaving != null) focusSoon { first } }
    val out = leaving
    if (out == null) {
        StepTitle(stringResource(R.string.library_accounts))
        Column(Modifier.fillMaxWidth().heightIn(max = (maxHeight - 72.dp).coerceAtLeast(120.dp)).verticalScroll(rememberScrollState())) {
            storesWithSignIn().forEachIndexed { i, store ->
                val name = StoresState.accounts[store]
                AccountRow(
                    store, name,
                    modifier = Modifier.stepItem(1 + i).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    enabled = open,
                ) { if (name != null) leaving = store else onSignIn(store) }
            }
        }
    } else {
        StepTitle(stringResource(R.string.stores_signout_title, out.label))
        Text(stringResource(R.string.stores_signout_note), fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.stepItem(1).padding(start = 4.dp, bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth().stepItem(2)) {
            StepChoice(stringResource(R.string.stores_signout_stay), enabled = open, modifier = Modifier.focusRequester(first)) { leaving = null }
            StepChoice(stringResource(R.string.stores_signout_go), danger = true, enabled = open) {
                Steps.fold()
                StoresState.signOut(ctx, out)
            }
        }
    }
}

@Composable
private fun AccountRow(store: Store, account: String?, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(20.dp)
    val signedIn = account != null
    val accent = if (signedIn) colors.error else pal.signal
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp).clip(shape)
            .background(if (hot) accent.copy(alpha = if (signedIn) 0.16f else 0.14f) else Color.Transparent)
            .glideBorder(hot, shape, accent, Color.Transparent)
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .testTag("account-${store.id}")
            .padding(horizontal = 16.dp),
    ) {
        Box(Modifier.size(10.dp).background(sourceColours(store.id).dot.copy(alpha = if (signedIn) 1f else 0.45f), CircleShape))
        Column(Modifier.weight(1f)) {
            Text(store.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (account != null) Text(account, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            stringResource(if (signedIn) R.string.library_account_sign_out else R.string.library_account_sign_in),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (hot || !signedIn) accent else colors.onSurfaceVariant, maxLines = 1,
        )
    }
}
