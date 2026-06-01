package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object EmulatorReplayUi {
    fun canRecord(state: EmulatorWorkspaceState): Boolean =
        state.session.active && !state.isBusy && !state.session.replaying

    fun canWatchLast(state: EmulatorWorkspaceState): Boolean =
        state.session.active &&
            !state.isBusy &&
            !state.session.replaying &&
            (state.replayBundlePath != null || state.recordingPath != null)

    fun canOpenReplay(state: EmulatorWorkspaceState): Boolean =
        state.session.active && !state.isBusy && !state.session.replaying

    fun canStopReplay(state: EmulatorWorkspaceState): Boolean =
        state.session.replaying && !state.isBusy

    fun canExportReplay(state: EmulatorWorkspaceState): Boolean =
        !state.session.replaying && state.recordingPath != null && !state.isBusy
}

@Composable
fun EmulatorReplayOutlinedButtons(
    workspaceState: EmulatorWorkspaceState,
    onAction: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { onAction(if (workspaceState.session.recording) "record_off" else "record_on") },
            enabled = EmulatorReplayUi.canRecord(workspaceState),
        ) {
            Text(if (workspaceState.session.recording) "Stop Rec" else "Record", fontSize = 12.sp)
        }
        OutlinedButton(
            onClick = { onAction("replay_watch") },
            enabled = EmulatorReplayUi.canWatchLast(workspaceState),
        ) {
            Text("Watch Replay", fontSize = 12.sp)
        }
        OutlinedButton(
            onClick = { onAction("replay_open") },
            enabled = EmulatorReplayUi.canOpenReplay(workspaceState),
        ) {
            Text("Open Replay", fontSize = 12.sp)
        }
        if (workspaceState.session.replaying) {
            OutlinedButton(
                onClick = { onAction("replay_stop") },
                enabled = EmulatorReplayUi.canStopReplay(workspaceState),
            ) {
                Text("Stop Replay", fontSize = 12.sp)
            }
        } else if (workspaceState.recordingPath != null) {
            OutlinedButton(
                onClick = { onAction("replay_export") },
                enabled = EmulatorReplayUi.canExportReplay(workspaceState),
            ) {
                Text("Export .smreplay", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun EmulatorReplayFloatingControls(
    workspaceState: EmulatorWorkspaceState,
    scope: CoroutineScope,
    btnShape: RoundedCornerShape,
    onOpenReplay: () -> Unit,
    onExportReplay: () -> Unit,
) {
    IconButton(
        onClick = {
            scope.launch { workspaceState.setRecording(!workspaceState.session.recording) }
        },
        enabled = EmulatorReplayUi.canRecord(workspaceState),
        modifier = Modifier
            .size(30.dp)
            .clip(btnShape)
            .background(
                if (workspaceState.session.recording) Color(0xFFE53935).copy(alpha = 0.18f)
                else Color.Transparent,
            ),
    ) {
        Icon(
            Icons.Default.RadioButtonChecked,
            contentDescription = if (workspaceState.session.recording) "Stop recording" else "Start recording",
            modifier = Modifier.size(18.dp),
            tint = if (workspaceState.session.recording) Color(0xFFE53935)
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.width(4.dp))

    if (workspaceState.session.replaying) {
        Surface(
            onClick = { scope.launch { workspaceState.stopReplay() } },
            enabled = EmulatorReplayUi.canStopReplay(workspaceState),
            color = Color.Transparent,
            shape = btnShape,
        ) {
            Text(
                "STOP",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    } else {
        Surface(
            onClick = { scope.launch { workspaceState.watchLastRecording() } },
            enabled = EmulatorReplayUi.canWatchLast(workspaceState),
            color = Color.Transparent,
            shape = btnShape,
        ) {
            Text(
                "WATCH",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }

        Spacer(Modifier.width(2.dp))

        Surface(
            onClick = onOpenReplay,
            enabled = EmulatorReplayUi.canOpenReplay(workspaceState),
            color = Color.Transparent,
            shape = btnShape,
        ) {
            Text(
                "OPEN",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }

        if (workspaceState.recordingPath != null) {
            Spacer(Modifier.width(2.dp))
            Surface(
                onClick = onExportReplay,
                enabled = EmulatorReplayUi.canExportReplay(workspaceState),
                color = Color.Transparent,
                shape = btnShape,
            ) {
                Text(
                    "SHARE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}
