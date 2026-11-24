package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.min
import kotlin.math.roundToInt

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
    clearFocus: Boolean = false,
    onNext: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    focusOnShow: Boolean = false,
    showLabel: Boolean = true,
    focusAtTap: Int? = null,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val onBackLatest = rememberUpdatedState(onBack)
    val focusAsked = remember { booleanArrayOf(false) }
    val focusTapAsked = remember { arrayOfNulls<Int>(1) }
    val palette = LocalPalette.current
    val onValueChangeLatest = rememberUpdatedState(onValueChange)
    val onNextLatest = rememberUpdatedState(onNext)
    val onDoneLatest = rememberUpdatedState(onDone)
    val onFocusChangeLatest = rememberUpdatedState(onFocusChange)
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val inputHeight = if (compact) 44.dp else 52.dp
    val colors = MaterialTheme.colorScheme
    val bottomTyping = LocalBottomTyping.current
    val bottomTypingLatest = rememberUpdatedState(bottomTyping)
    val typingRequest = remember { arrayOfNulls<TypingRequest>(1) }

    fun typeOnBottom(editText: EditText): Boolean {
        if (!bottomTypingLatest.value || !DualScreenPanel.attached) return false
        val text = editText.text.toString()
        lateinit var request: TypingRequest
        request = TypingRequest(
            TextFieldValue(text, TextRange(editText.selectionStart.coerceIn(0, text.length), editText.selectionEnd.coerceIn(0, text.length))),
            ImeOptions(singleLine = true, keyboardType = keyboardType, imeAction = imeAction),
            { next ->
                if (editText.text.toString() != next.text) editText.setText(next.text)
                editText.setSelection(next.selection.start.coerceIn(0, next.text.length), next.selection.end.coerceIn(0, next.text.length))
            },
            { action -> editText.onEditorAction(imeActionFlag(action)) },
            { editText.post { if (typingRequest[0] === request) onBackLatest.value?.invoke() ?: editText.clearFocus() } },
        )
        typingRequest[0] = request
        DualScreenPanel.startTyping(request)
        return true
    }

    DisposableEffect(Unit) {
        onDispose { typingRequest[0]?.let { DualScreenPanel.stopTyping(it) } }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (showLabel) Text(
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
                                // Search and Go are terminal: leaving them unhandled
                                // keeps focus in the field, so the keyboard would stay
                                // up over the results it was used to find.
                                imeAction in setOf(ImeAction.Search, ImeAction.Go) &&
                                    (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO || isEnter) -> {
                                    onDoneLatest.value?.invoke()
                                    post {
                                        clearFocus()
                                        (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                                            as? android.view.inputmethod.InputMethodManager)
                                            ?.hideSoftInputFromWindow(windowToken, 0)
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                        showSoftInputOnFocus = !bottomTyping
                        // B on a controller puts the keyboard away first; with no keyboard up it goes back as usual.
                        var hidingKeyboard = false
                        setOnKeyListener { view, keyCode, event ->
                            if (keyCode != android.view.KeyEvent.KEYCODE_BUTTON_B) return@setOnKeyListener false
                            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                hidingKeyboard = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                                if (hidingKeyboard) (view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                                    ?.hideSoftInputFromWindow(view.windowToken, 0)
                                onBackLatest.value?.let { hidingKeyboard = true; it() }
                            }
                            hidingKeyboard
                        }
                        setOnClickListener { if (hasFocus() && !typeOnBottom(this) && bottomTypingLatest.value) showKeyboardHere(this) }
                        setOnFocusChangeListener { _, hasFocus ->
                            focused = hasFocus
                            if (hasFocus) { if (!typeOnBottom(this) && bottomTypingLatest.value) showKeyboardHere(this) }
                            else typingRequest[0]?.let { DualScreenPanel.stopTyping(it) }
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
                    editText.showSoftInputOnFocus = !bottomTyping
                    // A new tap may ask again: a field still on screen while it closes can be opened once more.
                    val fresh = if (focusAtTap != null) focusTapAsked[0] != focusAtTap else !focusAsked[0]
                    if (focusOnShow && fresh) {
                        if (focusAtTap != null) focusTapAsked[0] = focusAtTap else focusAsked[0] = true
                        editText.post {
                            if (focusAtTap != null && focusAtTap != ScreenTaps.count) return@post
                            editText.requestFocus()
                            if (!bottomTypingLatest.value) (editText.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                                ?.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                    if (clearFocus && editText.hasFocus()) {
                        editText.clearFocus()
                        (editText.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                            ?.hideSoftInputFromWindow(editText.windowToken, 0)
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


private fun showKeyboardHere(editText: EditText) {
    (editText.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
        ?.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
}

private fun imeActionFlag(action: ImeAction): Int = when (action) {
    ImeAction.Next -> EditorInfo.IME_ACTION_NEXT
    ImeAction.Done -> EditorInfo.IME_ACTION_DONE
    ImeAction.Go -> EditorInfo.IME_ACTION_GO
    ImeAction.Search -> EditorInfo.IME_ACTION_SEARCH
    else -> EditorInfo.IME_ACTION_UNSPECIFIED
}
