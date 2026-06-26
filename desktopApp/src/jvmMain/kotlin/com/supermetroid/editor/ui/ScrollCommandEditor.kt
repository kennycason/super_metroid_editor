package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.ScrollCommand

private val SCROLL_RED = Color(0xFFCC3333)
private val SCROLL_BLUE = Color(0xFF3366CC)
private val SCROLL_GREEN = Color(0xFF33AA44)
private val SCROLL_NONE = Color(0xFF444444)

/**
 * Visual grid editor for scroll trigger commands.
 * Shows the room's screen grid; user clicks to cycle through:
 * (none) → Blue → Green → Red → (none)
 *
 * "none" means this screen is not affected by the trigger.
 * Blue/Green/Red set the screen's scroll value when the trigger fires.
 */
@Composable
fun ScrollCommandEditor(
    roomWidthScreens: Int,
    roomHeightScreens: Int,
    initialCommands: List<ScrollCommand>,
    onSave: (List<ScrollCommand>) -> Unit,
    onCancel: () -> Unit,
) {
    // Map of screenIndex → scrollValue (only screens that are part of the command)
    val entries = remember {
        mutableStateMapOf<Int, Int>().also { map ->
            for (cmd in initialCommands) map[cmd.screenIndex] = cmd.scrollValue
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .padding(6.dp)
    ) {
        Text("Scroll Trigger Command", fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text("When Samus crosses this tile, these screens change:",
            fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))

        // Screen grid
        val cellSize = 24.dp
        for (row in 0 until roomHeightScreens) {
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                for (col in 0 until roomWidthScreens) {
                    val idx = row * roomWidthScreens + col
                    val value = entries[idx] // null = not in command
                    val bgColor = when (value) {
                        0 -> SCROLL_RED
                        1 -> SCROLL_BLUE
                        2 -> SCROLL_GREEN
                        else -> SCROLL_NONE
                    }
                    val label = when (value) {
                        0 -> "R"
                        1 -> "B"
                        2 -> "G"
                        else -> ""
                    }
                    Box(
                        modifier = Modifier
                            .size(cellSize)
                            .background(bgColor, RoundedCornerShape(2.dp))
                            .border(1.dp,
                                if (value != null) Color.White.copy(alpha = 0.5f)
                                else Color.White.copy(alpha = 0.1f),
                                RoundedCornerShape(2.dp))
                            .clickable {
                                // Cycle: none → Blue(1) → Green(2) → Red(0) → none
                                when (value) {
                                    null -> entries[idx] = 1  // → Blue
                                    1 -> entries[idx] = 2     // → Green
                                    2 -> entries[idx] = 0     // → Red
                                    0 -> entries.remove(idx)  // → none
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            color = Color.White)
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        // Legend — wrap to avoid clipping
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LegendDot(SCROLL_BLUE, "Blue")
            LegendDot(SCROLL_GREEN, "Green")
            LegendDot(SCROLL_RED, "Red")
            LegendDot(SCROLL_NONE, "None")
        }
        Text("Blue=normal camera, Green=normal + lower rows, Red=blocked",
            fontSize = 7.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(2.dp))
        val cmdCount = entries.size
        Text("$cmdCount screen${if (cmdCount != 1) "s" else ""} affected",
            fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(6.dp))
        // Save / Cancel
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val hasChanges = entries.isNotEmpty()
            Surface(
                modifier = Modifier.weight(1f).height(26.dp)
                    .clickable(enabled = hasChanges) {
                        onSave(entries.map { (idx, v) -> ScrollCommand(idx, v) })
                    },
                shape = RoundedCornerShape(4.dp),
                color = if (hasChanges) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                    Text("Save", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = if (hasChanges) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(
                modifier = Modifier.weight(1f).height(26.dp).clickable { onCancel() },
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                    Text("Cancel", fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
        Text(label, fontSize = 7.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
