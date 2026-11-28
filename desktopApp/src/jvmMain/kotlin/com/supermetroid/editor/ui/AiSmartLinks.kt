package com.supermetroid.editor.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import java.awt.event.MouseEvent

sealed interface AiSmartLinkTarget {
    data class Room(val roomId: Int) : AiSmartLinkTarget
    data class AsmAddress(val snesAddress: Int) : AiSmartLinkTarget
}

internal data class AiSmartLinkSpan(
    val start: Int,
    val endExclusive: Int,
    val target: AiSmartLinkTarget,
)

private val AI_SMART_TOKEN = Regex(
    "(?i)\\$[0-9A-F]{2}:[0-9A-F]{4}|(?<![0-9A-F:])(?:0x|\\$)?[8-9A-F][0-9A-F]{3}(?![0-9A-F])",
)
private const val AI_ROOM_LINK_TAG = "smedit-room"
private const val AI_ASM_LINK_TAG = "smedit-asm-address"

/** Only link values that resolve to a real room, or an unambiguous banked SNES address. */
internal fun aiSmartLinkSpans(
    text: String,
    roomIds: Set<Int>,
    asmLinksEnabled: Boolean,
): List<AiSmartLinkSpan> = AI_SMART_TOKEN.findAll(text).mapNotNull { match ->
    val token = match.value
    val target = if (':' in token) {
        val bank = token.substring(1, 3).toIntOrNull(16) ?: return@mapNotNull null
        val offset = token.substring(4).toIntOrNull(16) ?: return@mapNotNull null
        when {
            bank == 0x8F && offset in roomIds -> AiSmartLinkTarget.Room(offset)
            asmLinksEnabled && bank in 0x80..0xFF && offset in 0x8000..0xFFFF ->
                AiSmartLinkTarget.AsmAddress((bank shl 16) or offset)
            else -> null
        }
    } else {
        val digits = when {
            token.startsWith("0x", ignoreCase = true) -> token.substring(2)
            token.startsWith('$') -> token.substring(1)
            else -> token
        }
        val roomId = digits.toIntOrNull(16) ?: return@mapNotNull null
        AiSmartLinkTarget.Room(roomId).takeIf { roomId in roomIds }
    } ?: return@mapNotNull null
    AiSmartLinkSpan(match.range.first, match.range.last + 1, target)
}.toList()

internal fun aiSmartAnnotatedString(
    text: String,
    links: List<AiSmartLinkSpan>,
    linkColor: Color,
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    links.sortedBy(AiSmartLinkSpan::start).forEach { link ->
        if (link.start < cursor) return@forEach
        append(text.substring(cursor, link.start))
        val (tag, value) = when (val target = link.target) {
            is AiSmartLinkTarget.Room -> AI_ROOM_LINK_TAG to target.roomId.toString()
            is AiSmartLinkTarget.AsmAddress -> AI_ASM_LINK_TAG to target.snesAddress.toString()
        }
        pushStringAnnotation(tag, value)
        pushStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline))
        append(text.substring(link.start, link.endExclusive))
        pop()
        pop()
        cursor = link.endExclusive
    }
    append(text.substring(cursor))
}

@Composable
internal fun AiSmartText(
    text: String,
    roomIds: Set<Int>,
    asmLinksEnabled: Boolean,
    onSmartLink: (AiSmartLinkTarget) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val links = remember(text, roomIds, asmLinksEnabled) { aiSmartLinkSpans(text, roomIds, asmLinksEnabled) }
    if (links.isEmpty()) {
        Text(text, modifier, color, fontSize, fontWeight = fontWeight, maxLines = maxLines, overflow = overflow)
        return
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, links, linkColor) { aiSmartAnnotatedString(text, links, linkColor) }
    val resolvedColor = if (color == Color.Unspecified) LocalContentColor.current else color
    val style = LocalTextStyle.current.merge(
        color = resolvedColor,
        fontSize = fontSize,
        fontWeight = fontWeight,
    )
    AiSelectableLinkedText(
        text = annotated,
        style = style,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
    ) { annotation ->
        val value = annotation.item.toIntOrNull() ?: return@AiSelectableLinkedText
        when (annotation.tag) {
            AI_ROOM_LINK_TAG -> onSmartLink(AiSmartLinkTarget.Room(value))
            AI_ASM_LINK_TAG -> onSmartLink(AiSmartLinkTarget.AsmAddress(value))
        }
    }
}

/** Preserve SelectionContainer gestures while activating an unmoved primary-button click. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun AiSelectableLinkedText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier,
    maxLines: Int,
    overflow: TextOverflow,
    onAnnotationClick: (AnnotatedString.Range<String>) -> Unit,
) {
    var layoutResult by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    var hoveredLink by remember(text) { mutableStateOf(false) }
    BasicText(
        text = text,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { layoutResult = it },
        modifier = modifier
            .pointerHoverIcon(if (hoveredLink) PointerIcon.Hand else PointerIcon.Text, overrideDescendants = true)
            .onPointerEvent(PointerEventType.Move, PointerEventPass.Initial) { event ->
                val offset = event.changes.firstOrNull()?.position?.let { layoutResult?.getOffsetForPosition(it) }
                hoveredLink = offset != null && aiAnnotationAt(text, offset) != null
            }
            .onPointerEvent(PointerEventType.Exit) { hoveredLink = false }
            .pointerInput(text) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val pressMouseEvent = currentEvent.nativeEvent as? MouseEvent
                    val secondaryPress = currentEvent.buttons.isSecondaryPressed || pressMouseEvent?.button == MouseEvent.BUTTON3
                    var releasePosition = down.position
                    var releaseMouseEvent: MouseEvent? = null
                    var released = false
                    var movedBeyondClick = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        releasePosition = change.position
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) movedBeyondClick = true
                        if (!change.pressed) {
                            releaseMouseEvent = event.nativeEvent as? MouseEvent
                            released = true
                            break
                        }
                    }
                    val releasedButton = releaseMouseEvent?.button?.takeUnless { it == MouseEvent.NOBUTTON }
                        ?: pressMouseEvent?.button
                    val primaryRelease = !secondaryPress && (releasedButton == null || releasedButton == MouseEvent.BUTTON1)
                    if (released && !movedBeyondClick && primaryRelease) {
                        val offset = layoutResult?.getOffsetForPosition(releasePosition) ?: return@awaitEachGesture
                        aiAnnotationAt(text, offset)?.let(onAnnotationClick)
                    }
                }
            },
    )
}

private fun aiAnnotationAt(text: AnnotatedString, requestedOffset: Int): AnnotatedString.Range<String>? {
    val offset = requestedOffset.coerceIn(0, text.length)
    return text.getStringAnnotations(start = offset, end = offset).firstOrNull()
        ?: offset.takeIf { it > 0 }
            ?.let { text.getStringAnnotations(start = it - 1, end = it - 1).firstOrNull() }
}
