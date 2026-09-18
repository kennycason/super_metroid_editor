package com.supermetroid.editor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.procgen.LearnedRoomProposal
import com.supermetroid.editor.procgen.LearnedRoomProposalCodec
import com.supermetroid.editor.procgen.PreparedLearnedRoomCandidate
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.roundToInt

internal fun chooseLearnedRoomProposalFile(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Import learned room candidates"
        fileFilter = FileNameExtensionFilter("SMEDIT learned room JSON", "json")
        isAcceptAllFileFilterUsed = true
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

internal fun loadLearnedRoomProposalFile(file: File): List<LearnedRoomProposal> {
    require(file.isFile) { "Proposal file does not exist" }
    require(file.length() <= MAX_PROPOSAL_FILE_BYTES) { "Proposal file is larger than 64 MiB" }
    return LearnedRoomProposalCodec.decode(file.readText())
}

@Composable
internal fun LearnedRoomCandidateGallery(
    candidates: List<PreparedLearnedRoomCandidate>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((rowIndex, rowCandidates) in candidates.chunked(2).withIndex()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for ((columnIndex, candidate) in rowCandidates.withIndex()) {
                    val index = rowIndex * 2 + columnIndex
                    LearnedRoomCandidateCard(
                        candidate = candidate,
                        editorRank = index + 1,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowCandidates.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LearnedRoomCandidateCard(
    candidate: PreparedLearnedRoomCandidate,
    editorRank: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val modelRank = candidate.proposal.rank.takeIf { it > 0 }?.let { " · model #$it" }.orEmpty()
    val score = (candidate.score * 10.0).roundToInt() / 10.0
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(6.dp),
        ) {
            Text("Editor pick #$editorRank$modelRank", fontSize = 10.sp)
            Text(
                "Score $score · ${candidate.changedCellCount} changed",
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LearnedRoomCollisionPreview(candidate)
            Text(
                "${candidate.metrics.passableComponents} region(s) · ${candidate.metrics.doorGroupCount} door group(s) · " +
                    "${(candidate.metrics.detailAdjacencyFraction * 100).roundToInt()}% source-pattern edges",
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            candidate.warnings.firstOrNull()?.let { warning ->
                Text(
                    warning,
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun LearnedRoomCollisionPreview(candidate: PreparedLearnedRoomCandidate) {
    val previewBackground = Color(0xFF11151B)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(previewBackground),
    ) {
        val width = candidate.proposal.widthBlocks
        val height = candidate.proposal.heightBlocks
        if (width <= 0 || height <= 0) return@Canvas
        val cellWidth = size.width / width
        val cellHeight = size.height / height
        for (index in candidate.resolvedBlockTypes.indices) {
            val type = candidate.resolvedBlockTypes[index]
            val color = collisionColor(type) ?: continue
            val x = index % width
            val y = index / width
            drawRect(
                color = color,
                topLeft = Offset(x * cellWidth, y * cellHeight),
                size = Size(cellWidth.coerceAtLeast(1f), cellHeight.coerceAtLeast(1f)),
            )
        }
    }
}

private fun collisionColor(type: Int): Color? = when (type) {
    0x0 -> null
    0x1, 0x2 -> Color(0xFF8A6BBE) // slopes and spike-air
    0x3 -> Color(0xFFE05A47) // spikes/hazards
    0x4, 0x7 -> Color(0xFF55AFC4) // shootable/open special tiles
    0x6 -> Color(0xFF7AC6D8) // platform
    0x9 -> Color(0xFFFFA726) // doors
    0xA -> Color(0xFFC94C4C)
    0xB, 0xC, 0xF -> Color(0xFFD3A83E) // destructibles
    else -> Color(0xFF7E8793) // solid and resolved extensions
}

private const val MAX_PROPOSAL_FILE_BYTES = 64L * 1024L * 1024L
