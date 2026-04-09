package com.supermetroid.editor.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.supermetroid.editor.rom.RomValidator

@Composable
fun ValidationPopup(
    issues: List<RomValidator.Issue>,
    scanTimeMs: Long,
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 400.dp, max = 600.dp).padding(top = 4.dp),
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                val errors = issues.count { it.severity == RomValidator.Severity.ERROR }
                val warnings = issues.count { it.severity == RomValidator.Severity.WARNING }
                val infos = issues.count { it.severity == RomValidator.Severity.INFO }
                Text(
                    buildString {
                        append("${issues.size} issues")
                        if (errors > 0) append(" ($errors errors)")
                        if (warnings > 0) append(" ($warnings warnings)")
                        if (infos > 0) append(" ($infos info)")
                        append(" — ${scanTimeMs}ms")
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (errors > 0) Color(0xFFFF5252) else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))

                if (issues.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        color = Color(0xFF1B5E20),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text("No issues found!", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = Color.White, modifier = Modifier.padding(12.dp))
                    }
                } else {
                    val grouped = issues.groupBy { it.category }
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .height(400.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        for ((category, categoryIssues) in grouped) {
                            val catErrors = categoryIssues.count { it.severity == RomValidator.Severity.ERROR }
                            Text(
                                "$category (${categoryIssues.size})",
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = if (catErrors > 0) Color(0xFFFF5252) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                            Divider()
                            for (issue in categoryIssues) {
                                IssueRow(issue)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IssueRow(issue: RomValidator.Issue) {
    val dotColor = when (issue.severity) {
        RomValidator.Severity.ERROR -> Color(0xFFFF5252)
        RomValidator.Severity.WARNING -> Color(0xFFFFB74D)
        RomValidator.Severity.INFO -> Color(0xFF64B5F6)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.size(8.dp).padding(top = 4.dp)
                .background(dotColor, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            val rid = issue.roomId
            if (rid != null) {
                Text(
                    "${issue.roomName} (0x${rid.toString(16).uppercase()})",
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                issue.message,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Default,
            )
        }
    }
}
