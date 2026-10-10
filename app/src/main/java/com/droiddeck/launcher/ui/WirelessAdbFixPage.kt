package com.droiddeck.launcher.ui

import androidx.compose.ui.platform.LocalInspectionMode
import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.droiddeck.launcher.core.PhantomProcessLimit
import com.droiddeck.launcher.core.WirelessAdbFix
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun WirelessAdbFixPage(
    onBack: () -> Unit,
    desiredEnabled: Boolean,
    onOpenDeveloperOptions: () -> Unit,
    onPair: (String, Int, String, (String?) -> Unit) -> Unit,
    onFindConnectPort: (String, (Int?) -> Unit) -> Unit,
    onApply: (String, Int, Boolean, (String?) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val inspecting = LocalInspectionMode.current
    val savedAddress = remember { if (inspecting) null else WirelessAdbFix.savedConnectionAddress(context) }
    var step by rememberSaveable { mutableStateOf(if (savedAddress == null) 0 else 1) }
    var pairingAddress by rememberSaveable { mutableStateOf("") }
    var connectionAddress by rememberSaveable { mutableStateOf(savedAddress.orEmpty()) }
    var pairingCode by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val pairingEndpoint = parseAdbAddress(pairingAddress)

    val pairNow: () -> Unit = {
        parseAdbAddress(pairingAddress)?.let { endpoint ->
            keyboardController?.hide()
            focusManager.clearFocus()
            busy = true
            editing = false
            message = null
            messageIsError = false
            onPair(endpoint.host, endpoint.port, pairingCode) { error ->
                pairingCode = ""
                if (error != null) {
                    busy = false
                    message = error
                    messageIsError = true
                } else {
                    step = 1
                    busy = true
                    message = context.getString(R.string.wadb_finding_port)
                    onFindConnectPort(endpoint.host) { discoveredPort ->
                        if (discoveredPort == null) {
                            connectionAddress = formatAddressHost(endpoint.host) + ":"
                            busy = false
                            message = context.getString(R.string.wadb_enter_port)
                        } else {
                            connectionAddress = formatAdbAddress(endpoint.host, discoveredPort)
                            message = context.getString(R.string.wadb_applying)
                            onApply(endpoint.host, discoveredPort, desiredEnabled) { applyError ->
                                busy = false
                                if (applyError == null) {
                                    step = 2
                                    message = null
                                } else {
                                    message = context.getString(R.string.wadb_apply_failed, applyError)
                                    messageIsError = true
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    val applyAddress: () -> Unit = {
        parseAdbAddress(connectionAddress)?.let { endpoint ->
            keyboardController?.hide()
            focusManager.clearFocus()
            busy = true
            editing = false
            message = null
            messageIsError = false
            onApply(endpoint.host, endpoint.port, desiredEnabled) { error ->
                busy = false
                if (error == null) {
                    step = 2
                    message = null
                } else {
                    message = error
                    messageIsError = true
                }
            }
        }
    }
    val pairAgain: () -> Unit = {
        step = 0
        pairingAddress = ""
        pairingCode = ""
        message = null
        messageIsError = false
    }

    BackHandler { if (!busy) onBack() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compactSplit = maxWidth < 620.dp && maxHeight < 500.dp
        val form: @Composable () -> Unit = {
            WirelessStepForm(
                step = step,
                pairingAddress = pairingAddress,
                onPairingAddressChange = { pairingAddress = it },
                pairingCode = pairingCode,
                onPairingCodeChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                connectionAddress = connectionAddress,
                onConnectionAddressChange = { connectionAddress = it },
                busy = busy,
                message = message,
                messageIsError = messageIsError,
                compact = compactSplit,
                editing = editing,
                onEditingChanged = { editing = it },
                focusManager = focusManager,
                desiredEnabled = desiredEnabled,
                canPair = pairingEndpoint != null && pairingCode.length == 6,
                onPair = pairNow,
                onApply = applyAddress,
                onPairAgain = pairAgain,
            )
        }
        SettingsPage(
            host = rememberMenuHost(),
            title = when {
                compactSplit && step < 2 -> stringResource(R.string.wadb_title_wireless)
                step == 2 -> stringResource(R.string.wadb_title_done)
                else -> stringResource(R.string.wadb_title)
            },
            eyebrow = stringResource(R.string.setup_title),
            lede = when {
                compactSplit && step == 0 -> stringResource(R.string.wadb_lede_compact0)
                compactSplit && step == 1 -> stringResource(R.string.wadb_lede_compact1)
                compactSplit -> stringResource(R.string.wadb_lede_done)
                step == 0 -> stringResource(R.string.wadb_lede0)
                step == 1 -> stringResource(R.string.wadb_lede1)
                else -> stringResource(R.string.wadb_lede_done_long)
            },
            onBack = { if (!busy) onBack() },
            action = if (compactSplit && step < 2) {
                { SecondaryButton(stringResource(R.string.setup_dev_options), compact = true, enabled = !busy, onClick = onOpenDeveloperOptions) }
            } else null,
            scrollContent = !compactSplit,
            compactLayout = compactSplit,
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 620.dp
                if (step == 2) {
                    Column(
                        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SettingsGroup(stringResource(R.string.setup_limit), compact = compactSplit) {
                            Text(
                                if (desiredEnabled) stringResource(R.string.wadb_confirmed_on) else stringResource(R.string.wadb_confirmed_off),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                        PrimaryButton(stringResource(R.string.setup_check_done), enabled = !busy, onClick = onBack)
                    }
                } else if (compactSplit) {
                    Column(
                        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        form()
                        if (!editing) CompactComputerFallback(desiredEnabled)
                    }
                } else {
                    val routes: @Composable () -> Unit = {
                        FallbackOrder(desiredEnabled, busy, onOpenDeveloperOptions)
                    }
                    if (wide) {
                        Row(
                            modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.weight(1f)) { routes() }
                            Column(Modifier.weight(1f)) { form() }
                        }
                    } else {
                        Column(
                            modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            routes()
                            form()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WirelessStepForm(
    step: Int,
    pairingAddress: String,
    onPairingAddressChange: (String) -> Unit,
    pairingCode: String,
    onPairingCodeChange: (String) -> Unit,
    connectionAddress: String,
    onConnectionAddressChange: (String) -> Unit,
    busy: Boolean,
    message: String?,
    messageIsError: Boolean,
    compact: Boolean,
    editing: Boolean,
    onEditingChanged: (Boolean) -> Unit,
    focusManager: FocusManager,
    desiredEnabled: Boolean,
    canPair: Boolean,
    onPair: () -> Unit,
    onApply: () -> Unit,
    onPairAgain: () -> Unit,
) {
    if (step == 0) {
        SettingsGroup(stringResource(R.string.wadb_pair_group), compact = compact) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 6.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp),
            ) {
                AdbTextField(
                    value = pairingAddress,
                    onValueChange = onPairingAddressChange,
                    label = stringResource(R.string.wadb_pair_address),
                    placeholder = "192.168.1.42:37123",
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next,
                    compact = compact,
                    onFocusChange = onEditingChanged,
                    onNext = { focusManager.moveFocus(FocusDirection.Next) },
                )
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AdbTextField(
                        value = pairingCode,
                        onValueChange = onPairingCodeChange,
                        label = stringResource(R.string.wadb_pin),
                        placeholder = stringResource(R.string.wadb_pin_placeholder),
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                        compact = compact,
                        onFocusChange = onEditingChanged,
                        onDone = {
                            focusManager.clearFocus()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton(stringResource(R.string.wadb_pair), compact = compact, enabled = !busy && canPair, onClick = onPair)
                }
                StatusMessage(busy, message, isError = messageIsError)
            }
        }
    } else {
        SettingsGroup(stringResource(R.string.wadb_apply_group), compact = compact) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 6.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp),
            ) {
                AdbTextField(
                    value = connectionAddress,
                    onValueChange = onConnectionAddressChange,
                    label = stringResource(R.string.wadb_connect_address),
                    placeholder = "192.168.1.42:45678",
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                    compact = compact,
                    onFocusChange = onEditingChanged,
                    onDone = { focusManager.clearFocus() },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StatusMessage(busy, message, Modifier.weight(1f), isError = messageIsError)
                    SecondaryButton(stringResource(R.string.wadb_pair_again), compact = compact, enabled = !busy, onClick = onPairAgain)
                    PrimaryButton(
                        if (desiredEnabled) stringResource(R.string.setup_limit_turn_on) else stringResource(R.string.wadb_turn_off),
                        compact = compact,
                        enabled = !busy && parseAdbAddress(connectionAddress) != null,
                        onClick = onApply,
                    )
                }
            }
        }
    }
}

@Composable
internal fun AdbTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    compact: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {},
    onNext: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    /** Takes focus (and the keyboard) once, as soon as it is shown: a field that opened on request. */
    focusOnShow: Boolean = false,
) {
    val context = LocalContext.current
    val focusAsked = remember { booleanArrayOf(false) }
    val palette = LocalPalette.current
    val onValueChangeLatest = rememberUpdatedState(onValueChange)
    val onNextLatest = rememberUpdatedState(onNext)
    val onDoneLatest = rememberUpdatedState(onDone)
    val onFocusChangeLatest = rememberUpdatedState(onFocusChange)
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val inputHeight = if (compact) 44.dp else 52.dp
    val colors = MaterialTheme.colorScheme

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(inputHeight)
                .background(colors.surfaceVariant, shape)
                .glideBorder(focused, shape, palette.signal, palette.line2)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    EditText(viewContext).apply {
                        isSingleLine = true
                        setHorizontallyScrolling(true)
                        background = null
                        setPadding((2 * context.resources.displayMetrics.density).roundToInt(), 0, 0, 0)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 14f)
                        setTextColor(colors.onBackground.toArgb())
                        setHintTextColor(colors.onSurfaceVariant.toArgb())
                        contentDescription = label
                        inputType = if (keyboardType == KeyboardType.Number) {
                            InputType.TYPE_CLASS_NUMBER
                        } else {
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        }
                        imeOptions = imeActionFlag(imeAction) or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        hint = placeholder
                        setOnEditorActionListener { _, actionId, event ->
                            val isEnter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                            when {
                                imeAction == ImeAction.Next && (actionId == EditorInfo.IME_ACTION_NEXT || isEnter) -> {
                                    onNextLatest.value?.invoke()
                                    true
                                }
                                imeAction == ImeAction.Done && (actionId == EditorInfo.IME_ACTION_DONE || isEnter) -> {
                                    onDoneLatest.value?.invoke()
                                    true
                                }
                                else -> false
                            }
                        }
                        setOnFocusChangeListener { _, hasFocus ->
                            focused = hasFocus
                            onFocusChangeLatest.value(hasFocus)
                        }
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                            override fun afterTextChanged(s: Editable?) {
                                onValueChangeLatest.value(s?.toString().orEmpty())
                            }
                        })
                    }
                },
                update = { editText ->
                    if (focusOnShow && !focusAsked[0]) {
                        focusAsked[0] = true
                        editText.post {
                            editText.requestFocus()
                            (editText.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                                ?.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                    editText.setTextColor(colors.onBackground.toArgb())
                    editText.setHintTextColor(colors.onSurfaceVariant.toArgb())
                    editText.hint = placeholder
                    editText.contentDescription = label
                    if (editText.text.toString() != value) {
                        val cursor = if (editText.hasFocus()) editText.selectionStart.coerceAtLeast(0) else value.length
                        editText.setText(value)
                        editText.setSelection(min(cursor, value.length))
                    }
                },
            )
        }
    }
}

@Composable
private fun FallbackOrder(
    desiredEnabled: Boolean,
    busy: Boolean,
    onOpenDeveloperOptions: () -> Unit,
) {
    SettingsGroup(stringResource(R.string.wadb_other_ways)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.wadb_dev_try_first), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Text(if (desiredEnabled) stringResource(R.string.wadb_restrict_on) else stringResource(R.string.wadb_restrict_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SecondaryButton(stringResource(R.string.gate_open_dev), enabled = !busy, onClick = onOpenDeveloperOptions)
            Text(stringResource(R.string.wadb_computer_last), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Text(stringResource(R.string.wadb_only_if), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                PhantomProcessLimit.adbCommand(desiredEnabled),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CompactComputerFallback(desiredEnabled: Boolean) {
    SettingsGroup(stringResource(R.string.wadb_computer_group), compact = true) {
        Text(
            PhantomProcessLimit.adbCommand(desiredEnabled),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun StatusMessage(busy: Boolean, message: String?, modifier: Modifier = Modifier, isError: Boolean = false) {
    if (busy) {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(message ?: stringResource(R.string.common_working), style = MaterialTheme.typography.bodySmall)
        }
    } else {
        message?.let {
            Text(
                it,
                modifier = modifier,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class AdbEndpoint(val host: String, val port: Int)

private fun parseAdbAddress(value: String): AdbEndpoint? {
    val address = value.trim()
    val host: String
    val portText: String
    if (address.startsWith("[")) {
        val closingBracket = address.indexOf(']')
        if (closingBracket <= 1 || address.getOrNull(closingBracket + 1) != ':') return null
        host = address.substring(1, closingBracket)
        if (':' !in host) return null
        portText = address.substring(closingBracket + 2)
    } else {
        val colon = address.lastIndexOf(':')
        if (colon <= 0 || address.indexOf(':') != colon) return null
        host = address.substring(0, colon)
        portText = address.substring(colon + 1)
    }
    if (host.isBlank() || host.any(Char::isWhitespace)) return null
    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return AdbEndpoint(host, port)
}

private fun formatAddressHost(host: String): String = if (':' in host) "[$host]" else host
private fun formatAdbAddress(host: String, port: Int): String = "${formatAddressHost(host)}:$port"

private fun imeActionFlag(action: ImeAction): Int = when (action) {
    ImeAction.Next -> EditorInfo.IME_ACTION_NEXT
    ImeAction.Done -> EditorInfo.IME_ACTION_DONE
    ImeAction.Go -> EditorInfo.IME_ACTION_GO
    ImeAction.Search -> EditorInfo.IME_ACTION_SEARCH
    else -> EditorInfo.IME_ACTION_UNSPECIFIED
}
