package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.asm.AsmDocumentFindState
import com.supermetroid.editor.asm.AsmDocumentMatch
import com.supermetroid.editor.asm.findAsmDocumentText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun AsmDocumentFindButton(onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(32.dp)) {
        Icon(Icons.Default.Search, "Find in this file", Modifier.size(18.dp))
    }
}

@Composable
internal fun AsmDocumentFindBar(
    find: AsmDocumentFindState,
    text: String,
    onNavigate: (AsmDocumentMatch) -> Unit,
    onClose: () -> Unit,
    findMatches: (String, String, Boolean) -> List<AsmDocumentMatch> = ::findAsmDocumentText,
) {
    if (!find.open) return
    val fs = LocalEditorTheme.current.fontSize.value
    val focus = remember { FocusRequester() }
    val navigate by rememberUpdatedState(onNavigate)
    var previousQuery by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var pending by remember { mutableStateOf(false) }
    LaunchedEffect(find.query, find.matchCase, text, findMatches) {
        pending = true
        val key = find.query to find.matchCase
        val restart = previousQuery != key
        delay(100)
        val result = withContext(Dispatchers.Default) { findMatches(text, key.first, key.second) }
        find.updateMatches(result, restart)
        previousQuery = key
        pending = false
        if (restart) find.selected?.let(navigate)
    }
    LaunchedEffect(find.focusSerial) { focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicTextField(
            value = find.query,
            onValueChange = { find.query = it },
            singleLine = true,
            textStyle = TextStyle(fontSize = fs.body, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f).focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        if (!pending) find.step(if (event.isShiftPressed) -1 else 1)?.let(navigate)
                        true
                    } else false
                }
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant).padding(6.dp),
            decorationBox = { field ->
                Box {
                    if (find.query.isEmpty()) Text("Find in this file", fontSize = fs.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    field()
                }
            },
        )
        Text(if (pending) "…" else if (find.matches.isEmpty()) "0" else "${find.selectedIndex + 1}/${find.matches.size}",
            fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { find.matchCase = !find.matchCase }, contentPadding = PaddingValues(4.dp),
            modifier = Modifier.width(36.dp).semantics {
                contentDescription = "Match case"
                stateDescription = if (find.matchCase) "On" else "Off"
            }) {
            Text("Aa", fontSize = fs.body, color = if (find.matchCase) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton({ find.step(-1)?.let(navigate) }, Modifier.size(28.dp), enabled = !pending && find.matches.isNotEmpty()) {
            Icon(Icons.Default.KeyboardArrowUp, "Previous match (Shift+Enter)")
        }
        IconButton({ find.step(1)?.let(navigate) }, Modifier.size(28.dp), enabled = !pending && find.matches.isNotEmpty()) {
            Icon(Icons.Default.KeyboardArrowDown, "Next match (Enter)")
        }
        IconButton(onClose, Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Close find (Escape)") }
    }
}

/** Decoration only: preserves syntax colors, link annotations, offsets and the original text. */
internal fun asmHighlightDocumentMatches(
    text: AnnotatedString,
    startOffset: Int,
    find: AsmDocumentFindState,
    color: Color,
): AnnotatedString {
    if (!find.open || find.matches.isEmpty()) return text
    return buildAnnotatedString {
        append(text)
        find.matchesInRange(startOffset, startOffset + text.length).forEach { match ->
            val start = (match.offset - startOffset).coerceAtLeast(0)
            val end = (match.offset + match.length - startOffset).coerceAtMost(text.length)
            if (start < end) addStyle(SpanStyle(background = color.copy(alpha = if (match == find.selected) 0.55f else 0.25f)), start, end)
        }
    }
}
