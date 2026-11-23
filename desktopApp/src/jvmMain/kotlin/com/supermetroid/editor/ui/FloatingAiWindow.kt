package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.supermetroid.editor.data.AppConfig
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val AI_DEFAULT_WIDTH = 620f
private const val AI_DEFAULT_HEIGHT = 680f
private const val AI_MIN_WIDTH = 420f
private const val AI_MIN_HEIGHT = 360f
private const val AI_TITLE_HEIGHT = 34f
private val FLOATING_AI_CONTROL_SHAPE = RoundedCornerShape(4.dp)

/** Movable, resizable in-app assistant that remains usable while other editor tabs are active. */
@Composable
fun FloatingAiWindow(
    state: AiWorkspaceState,
    containerSizePx: IntSize,
    density: Float,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val safeDensity = density.coerceAtLeast(0.01f)
    val saved = remember { AppConfig.load() }
    val initialWidth = saved.aiWindowWidth.takeIf { it > 0f } ?: AI_DEFAULT_WIDTH
    val initialHeight = saved.aiWindowHeight.takeIf { it > 0f } ?: AI_DEFAULT_HEIGHT
    val initialX = saved.aiWindowX.takeIf { it >= 0f }
        ?: ((containerSizePx.width / safeDensity) - initialWidth - 12f).coerceAtLeast(8f)
    val initialY = saved.aiWindowY.takeIf { it >= 0f } ?: 8f
    var offsetX by remember { mutableStateOf(initialX * safeDensity) }
    var offsetY by remember { mutableStateOf(initialY * safeDensity) }
    var windowWidth by remember { mutableStateOf(initialWidth) }
    var windowHeight by remember { mutableStateOf(initialHeight) }

    fun fitToContainer() {
        val maxWidth = (containerSizePx.width / safeDensity).coerceAtLeast(1f)
        val maxHeight = (containerSizePx.height / safeDensity).coerceAtLeast(1f)
        windowWidth = windowWidth.coerceIn(minOf(AI_MIN_WIDTH, maxWidth), maxWidth)
        windowHeight = windowHeight.coerceIn(minOf(AI_MIN_HEIGHT, maxHeight), maxHeight)
        offsetX = offsetX.coerceIn(0f, (containerSizePx.width - windowWidth * safeDensity).coerceAtLeast(0f))
        offsetY = offsetY.coerceIn(0f, (containerSizePx.height - windowHeight * safeDensity).coerceAtLeast(0f))
    }

    LaunchedEffect(containerSizePx, safeDensity) { fitToContainer() }
    LaunchedEffect(offsetX, offsetY, windowWidth, windowHeight) {
        delay(400)
        AppConfig.update {
            copy(
                aiWindowX = offsetX / safeDensity,
                aiWindowY = offsetY / safeDensity,
                aiWindowWidth = windowWidth,
                aiWindowHeight = windowHeight,
            )
        }
    }

    Surface(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .width(windowWidth.dp)
            .height(windowHeight.dp)
            .testTag("floating-ai-window"),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 5.dp,
        shadowElevation = 14.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(AI_TITLE_HEIGHT.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(containerSizePx, windowWidth, windowHeight) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val maxX = (containerSizePx.width - windowWidth * safeDensity).coerceAtLeast(0f)
                                val maxY = (containerSizePx.height - windowHeight * safeDensity).coerceAtLeast(0f)
                                offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxX)
                                offsetY = (offsetY + dragAmount.y).coerceIn(0f, maxY)
                            }
                        }
                        .padding(start = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("SMEDIT AI", fontSize = fs.body, fontWeight = FontWeight.Bold)
                    AiCompactModelPicker(state)
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = state::newConversation,
                        enabled = !state.interactionInFlight,
                        contentPadding = PaddingValues(horizontal = 5.dp),
                        shape = FLOATING_AI_CONTROL_SHAPE,
                    ) {
                        Text("New", fontSize = fs.detail)
                    }
                    TextButton(
                        onClick = state::undoAiMutation,
                        enabled = state.canUndoAiMutation && state.pendingMutation == null && !state.interactionInFlight,
                        contentPadding = PaddingValues(horizontal = 5.dp),
                        shape = FLOATING_AI_CONTROL_SHAPE,
                    ) { Text("Undo", fontSize = fs.detail) }
                    TextButton(
                        onClick = state::redoAiMutation,
                        enabled = state.canRedoAiMutation && state.pendingMutation == null && !state.interactionInFlight,
                        contentPadding = PaddingValues(horizontal = 5.dp),
                        shape = FLOATING_AI_CONTROL_SHAPE,
                    ) { Text("Redo", fontSize = fs.detail) }
                    TextButton(
                        onClick = onClose,
                        contentPadding = PaddingValues(horizontal = 5.dp),
                        shape = FLOATING_AI_CONTROL_SHAPE,
                    ) {
                        Text("Close", fontSize = fs.detail)
                    }
                }
                AiWorkspaceCanvas(
                    state = state,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(10.dp),
                    showHeader = false,
                )
            }
            Box(
                modifier = Modifier.align(Alignment.BottomEnd).size(28.dp)
                    .pointerInput(containerSizePx, safeDensity) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val availableWidth = (containerSizePx.width - offsetX) / safeDensity
                            val availableHeight = (containerSizePx.height - offsetY) / safeDensity
                            windowWidth = (windowWidth + dragAmount.x / safeDensity)
                                .coerceIn(minOf(AI_MIN_WIDTH, availableWidth), availableWidth.coerceAtLeast(1f))
                            windowHeight = (windowHeight + dragAmount.y / safeDensity)
                                .coerceIn(minOf(AI_MIN_HEIGHT, availableHeight), availableHeight.coerceAtLeast(1f))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("\u2921", fontSize = fs.heading, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            }
        }
    }
}
