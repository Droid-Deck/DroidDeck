package com.droiddeck.launcher.files

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.ui.focusGlide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import kotlin.math.roundToInt

internal const val EDITOR_FONT_SIZE_SP = 13
internal const val EDITOR_LINE_HEIGHT_SP = 18
internal const val EDITOR_TAB_WIDTH = 4
internal const val EDITOR_MAX_OPEN_BYTES = 4L * 1024 * 1024
internal const val EDITOR_MAX_EDIT_BYTES = 512L * 1024

internal enum class EditorCharset(val label: String, private val charsetName: String) {
    UTF8("UTF-8", "UTF-8"),
    GB18030("GB18030", "GB18030"),
    ;

    val charset: Charset get() = Charset.forName(charsetName)
}

internal enum class EditorLock { NONE, BINARY, TOO_LARGE, PERMISSION, LOSSY }

internal enum class EditorLoadState { LOADING, READY, TOO_LARGE, UNREADABLE }

internal data class DecodedEditorText(val text: String, val charset: EditorCharset, val lossy: Boolean)

internal fun editorIsBinary(bytes: ByteArray, sample: Int = 8192): Boolean {
    val limit = minOf(bytes.size, sample)
    for (i in 0 until limit) if (bytes[i].toInt() == 0) return true
    return false
}

private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
    charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (e: CharacterCodingException) {
    null
}

internal fun decodeEditorText(bytes: ByteArray): DecodedEditorText {
    strictDecode(bytes, Charsets.UTF_8)?.let { return DecodedEditorText(it, EditorCharset.UTF8, false) }
    strictDecode(bytes, EditorCharset.GB18030.charset)?.let { return DecodedEditorText(it, EditorCharset.GB18030, false) }
    return DecodedEditorText(String(bytes, Charsets.UTF_8), EditorCharset.UTF8, true)
}

internal fun readCapped(input: InputStream, limit: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return out.toByteArray()
        total += n
        if (total > limit) return null
        out.write(buffer, 0, n)
    }
}

internal fun expandEditorTabs(text: String, width: Int = EDITOR_TAB_WIDTH): String {
    if (text.indexOf('\t') < 0) return text
    val out = StringBuilder(text.length)
    var column = 0
    for (c in text) {
        when (c) {
            '\n' -> {
                out.append(c)
                column = 0
            }
            '\t' -> {
                val pad = width - (column % width)
                repeat(pad) { out.append(' ') }
                column += pad
            }
            else -> {
                out.append(c)
                column++
            }
        }
    }
    return out.toString()
}

internal fun lineCountOf(text: String): Int {
    var lines = 1
    for (i in text.indices) if (text[i] == '\n') lines++
    return lines
}

internal fun lineIndexOfOffset(text: String, offset: Int): Int {
    val end = offset.coerceIn(0, text.length)
    var line = 0
    var i = 0
    while (i < end) {
        if (text[i] == '\n') line++
        i++
    }
    return line
}

internal fun columnOfOffset(text: String, offset: Int): Int {
    val end = offset.coerceIn(0, text.length)
    var start = end
    while (start > 0 && text[start - 1] != '\n') start--
    return end - start + 1
}

internal fun findEditorMatches(
    text: String,
    query: String,
    matchCase: Boolean = false,
    limit: Int = 10000,
): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<IntRange>()
    var at = 0
    while (out.size < limit) {
        val found = text.indexOf(query, at, ignoreCase = !matchCase)
        if (found < 0) break
        out.add(found until found + query.length)
        at = found + query.length
    }
    return out
}

internal fun replaceEditorMatches(text: String, matches: List<IntRange>, replacement: String): String {
    if (matches.isEmpty()) return text
    val out = StringBuilder(text.length)
    var cursor = 0
    for (m in matches) {
        if (m.first < cursor || m.last >= text.length) continue
        out.append(text, cursor, m.first).append(replacement)
        cursor = m.last + 1
    }
    out.append(text, cursor, text.length)
    return out.toString()
}

@Composable
internal fun TextEditorScreen(
    file: File,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var text by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(TextRange.Zero) }
    var composing by remember { mutableStateOf<TextRange?>(null) }
    var loadState by remember { mutableStateOf(EditorLoadState.LOADING) }
    var lock by remember { mutableStateOf(EditorLock.NONE) }
    var charset by remember { mutableStateOf(EditorCharset.UTF8) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var matchIndex by remember { mutableIntStateOf(0) }

    val scrollState = rememberScrollState()
    val textHorizontalState = rememberScrollState()
    var viewportHeight by remember { mutableIntStateOf(0) }
    var viewportWidth by remember { mutableIntStateOf(0) }

    val lineHeightDp = with(density) { EDITOR_LINE_HEIGHT_SP.sp.toDp() }
    val lineHeightPx = with(density) { lineHeightDp.toPx() }
    val totalLines = remember(text) { lineCountOf(text) }
    val cursorLine = lineIndexOfOffset(text, selection.end)
    val cursorColumn = columnOfOffset(text, selection.end)
    val matches = remember(text, query, matchCase) { findEditorMatches(text, query, matchCase) }
    val readOnly = lock != EditorLock.NONE
    val bodyStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = EDITOR_FONT_SIZE_SP.sp,
        lineHeight = EDITOR_LINE_HEIGHT_SP.sp,
        color = MaterialTheme.colorScheme.onBackground,
    )
    val textMeasurer = rememberTextMeasurer()
    val charWidthPx = remember(bodyStyle) { textMeasurer.measure(text = "0", style = bodyStyle).size.width.toFloat() }

    LaunchedEffect(file) {
        loadState = EditorLoadState.LOADING
        val size = withContext(Dispatchers.IO) { file.length() }
        if (size > EDITOR_MAX_OPEN_BYTES) {
            loadState = EditorLoadState.TOO_LARGE
            return@LaunchedEffect
        }
        val read = withContext(Dispatchers.IO) {
            runCatching { file.inputStream().use { readCapped(it, EDITOR_MAX_OPEN_BYTES) } }
        }
        val bytes = read.getOrNull()
        if (bytes == null) {
            loadState = if (read.isFailure) EditorLoadState.UNREADABLE else EditorLoadState.TOO_LARGE
            return@LaunchedEffect
        }
        val decoded = decodeEditorText(bytes)
        text = expandEditorTabs(decoded.text)
        charset = decoded.charset
        lock = when {
            editorIsBinary(bytes) -> EditorLock.BINARY
            decoded.lossy -> EditorLock.LOSSY
            bytes.size > EDITOR_MAX_EDIT_BYTES -> EditorLock.TOO_LARGE
            !file.canWrite() -> EditorLock.PERMISSION
            else -> EditorLock.NONE
        }
        selection = TextRange.Zero
        loadState = EditorLoadState.READY
    }

    suspend fun revealLine(line: Int) {
        val current = scrollState.value
        val top = line * lineHeightPx
        val target = when {
            top < current -> top
            top + lineHeightPx > current + viewportHeight -> top + lineHeightPx - viewportHeight
            else -> return
        }
        val clamped = target.roundToInt().coerceIn(0, scrollState.maxValue)
        if (clamped != current) scrollState.scrollTo(clamped)
    }

    LaunchedEffect(text, selection.end, viewportHeight, viewportWidth) {
        if (loadState != EditorLoadState.READY) return@LaunchedEffect
        revealLine(lineIndexOfOffset(text, selection.end))
        if (viewportWidth > 0 && charWidthPx > 0) {
            val x = (columnOfOffset(text, selection.end) - 1) * charWidthPx
            val current = textHorizontalState.value
            val target = when {
                x < current -> x
                x + charWidthPx > current + viewportWidth -> x + charWidthPx - viewportWidth
                else -> null
            }
            if (target != null) {
                val clamped = target.roundToInt().coerceIn(0, textHorizontalState.maxValue)
                if (clamped != current) textHorizontalState.scrollTo(clamped)
            }
        }
    }

    LaunchedEffect(query, matchCase) {
        if (query.isEmpty()) return@LaunchedEffect
        matchIndex = 0
        val first = matches.firstOrNull() ?: return@LaunchedEffect
        selection = TextRange(first.first, first.last + 1)
        composing = TextRange.Zero
    }

    LaunchedEffect(text) { matchIndex = 0 }

    fun gotoMatch(delta: Int) {
        if (matches.isEmpty()) return
        matchIndex = Math.floorMod(matchIndex + delta, matches.size)
        val m = matches[matchIndex]
        selection = TextRange(m.first, m.last + 1)
        composing = TextRange.Zero
    }

    fun replaceCurrent() {
        if (readOnly) return
        val m = matches.getOrNull(matchIndex) ?: return
        text = text.replaceRange(m.first, m.last + 1, replacement)
        dirty = true
        matchIndex = 0
        selection = TextRange(m.first + replacement.length)
        composing = TextRange.Zero
    }

    fun replaceAll() {
        if (readOnly || matches.isEmpty()) return
        val all = findEditorMatches(text, query, matchCase, limit = Int.MAX_VALUE)
        text = replaceEditorMatches(text, all, replacement)
        dirty = true
        matchIndex = 0
        selection = TextRange.Zero
        composing = TextRange.Zero
    }

    fun save() {
        if (readOnly || saving || loadState != EditorLoadState.READY) return
        val snapshot = text
        val bytes = snapshot.toByteArray(charset.charset)
        saving = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val parent = file.parentFile
                    if (parent == null) {
                        file.writeBytes(bytes)
                        true
                    } else {
                        val tmp = File.createTempFile(".${file.name}.", ".droiddeck-tmp", parent)
                        try {
                            tmp.writeBytes(bytes)
                            runCatching {
                                Files.setPosixFilePermissions(tmp.toPath(), Files.getPosixFilePermissions(file.toPath()))
                            }
                            tmp.renameTo(file)
                        } finally {
                            tmp.delete()
                        }
                    }
                }.isSuccess
            }
            saving = false
            if (ok) {
                if (text == snapshot) dirty = false
            } else {
                Toast.makeText(context, context.getString(R.string.fm_edit_save_failed, file.name), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun tryExit() {
        if (saving) return
        if (dirty && !readOnly) showDiscard = true else onExit()
    }

    BackHandler { tryExit() }

    if (showDiscard) {
        OutlinedAlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text(stringResource(R.string.fm_edit_discard_title)) },
            text = { Text(stringResource(R.string.fm_edit_discard_text, file.name)) },
            confirmButton = {
                TextButton(onClick = onExit, modifier = Modifier.focusGlide(CircleShape)) {
                    Text(stringResource(R.string.fm_edit_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscard = false }, modifier = Modifier.focusGlide(CircleShape)) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            IconButton(onClick = { tryExit() }, modifier = Modifier.focusGlide(CircleShape)) {
                Icon(
                    Icons.Filled.ArrowBack,
                    stringResource(R.string.fm_back),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    text = file.name,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (dirty) {
                    Text(
                        text = "\u2022 " + stringResource(R.string.fm_edit_modified),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
            if (loadState == EditorLoadState.READY && lock != EditorLock.NONE) {
                Text(
                    text = stringResource(
                        if (lock == EditorLock.BINARY) R.string.fm_edit_binary else R.string.fm_edit_read_only,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            IconButton(
                onClick = { showSearch = !showSearch },
                enabled = loadState == EditorLoadState.READY,
                modifier = Modifier.focusGlide(CircleShape),
            ) {
                Icon(
                    Icons.Filled.Search,
                    stringResource(R.string.fm_edit_find),
                    tint = if (showSearch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { save() },
                enabled = loadState == EditorLoadState.READY && !readOnly && dirty && !saving,
                modifier = Modifier.focusGlide(CircleShape),
            ) {
                Icon(
                    Icons.Filled.Save,
                    stringResource(R.string.fm_edit_save),
                    tint = if (dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (showSearch && loadState == EditorLoadState.READY) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it; matchIndex = 0 },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.fm_edit_find), fontSize = 13.sp) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { gotoMatch(1) }),
                        modifier = Modifier.weight(1f).focusGlide(),
                    )
                    Text(
                        text = if (matches.isEmpty()) "0/${matches.size}" else "${matchIndex.coerceAtMost(matches.size - 1) + 1}/${matches.size}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp).widthIn(min = 44.dp, max = 80.dp),
                    )
                    IconButton(
                        onClick = { gotoMatch(-1) },
                        enabled = matches.isNotEmpty(),
                        modifier = Modifier.focusGlide(CircleShape),
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            stringResource(R.string.fm_edit_find_previous),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = { gotoMatch(1) },
                        enabled = matches.isNotEmpty(),
                        modifier = Modifier.focusGlide(CircleShape),
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            stringResource(R.string.fm_edit_find_next),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    TextButton(onClick = { matchCase = !matchCase }, modifier = Modifier.focusGlide(CircleShape)) {
                        Text(
                            text = "Aa",
                            color = if (matchCase) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                    IconButton(onClick = { showSearch = false }, modifier = Modifier.focusGlide(CircleShape)) {
                        Icon(
                            Icons.Filled.Close,
                            stringResource(R.string.common_cancel),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp, end = 4.dp, bottom = 2.dp),
                ) {
                    OutlinedTextField(
                        value = replacement,
                        onValueChange = { replacement = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.fm_edit_replace), fontSize = 13.sp) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).focusGlide(),
                    )
                    TextButton(
                        onClick = { replaceCurrent() },
                        enabled = !readOnly && matches.isNotEmpty(),
                        modifier = Modifier.focusGlide(CircleShape),
                    ) {
                        Text(stringResource(R.string.fm_edit_replace), fontSize = 13.sp)
                    }
                    TextButton(
                        onClick = { replaceAll() },
                        enabled = !readOnly && matches.isNotEmpty(),
                        modifier = Modifier.focusGlide(CircleShape),
                    ) {
                        Text(stringResource(R.string.fm_edit_replace_all), fontSize = 13.sp)
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewportHeight = it.height },
        ) {
            when (loadState) {
                EditorLoadState.LOADING -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                EditorLoadState.TOO_LARGE, EditorLoadState.UNREADABLE -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (loadState == EditorLoadState.TOO_LARGE) R.string.fm_edit_too_large
                            else R.string.fm_edit_open_failed,
                            if (loadState == EditorLoadState.TOO_LARGE) FileOps.formatBytes(context, file.length())
                            else file.name,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                }

                EditorLoadState.READY -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                ) {
                    Row(modifier = Modifier.fillMaxWidth().height(lineHeightDp * totalLines.toFloat())) {
                        LineNumbers(
                            scrollState = scrollState,
                            viewportHeight = viewportHeight,
                            lineHeightDp = lineHeightDp,
                            lineHeightPx = lineHeightPx,
                            totalLines = totalLines,
                            cursorLine = cursorLine,
                            style = bodyStyle,
                            mutedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            cursorColor = MaterialTheme.colorScheme.primary,
                        )
                        Box(modifier = Modifier.weight(1f).onSizeChanged { viewportWidth = it.width }.horizontalScroll(textHorizontalState)) {
                            BasicTextField(
                                value = TextFieldValue(text = text, selection = selection, composition = composing),
                                onValueChange = { next ->
                                    val changed = next.text != text
                                    text = next.text
                                    selection = next.selection
                                    composing = next.composition
                                    if (changed) dirty = true
                                },
                                readOnly = readOnly,
                                textStyle = bodyStyle,
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    capitalization = KeyboardCapitalization.None,
                                ),
                                modifier = Modifier.fillMaxHeight().focusGlide(),
                            )
                        }
                    }
                }
            }
        }

        if (loadState == EditorLoadState.READY) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.fm_edit_position, cursorLine + 1, cursorColumn),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = charset.label + "  \u2022  " + FileOps.formatBytes(context, file.length()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun LineNumbers(
    scrollState: ScrollState,
    viewportHeight: Int,
    lineHeightDp: Dp,
    lineHeightPx: Float,
    totalLines: Int,
    cursorLine: Int,
    style: TextStyle,
    mutedColor: Color,
    cursorColor: Color,
    modifier: Modifier = Modifier,
) {
    val firstLine = (scrollState.value / lineHeightPx).toInt().coerceIn(0, totalLines - 1)
    val lastLine = ((scrollState.value + viewportHeight) / lineHeightPx)
        .toInt()
        .coerceIn(firstLine, totalLines - 1)
    Column(
        horizontalAlignment = Alignment.End,
        modifier = modifier
            .width(IntrinsicSize.Min)
            .padding(top = lineHeightDp * firstLine.toFloat()),
    ) {
        for (line in firstLine..lastLine) {
            Text(
                text = (line + 1).toString(),
                color = if (line == cursorLine) cursorColor else mutedColor,
                textAlign = TextAlign.End,
                maxLines = 1,
                style = style,
                modifier = Modifier.fillMaxWidth().height(lineHeightDp),
            )
        }
    }
}
