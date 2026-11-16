package com.supermetroid.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The same filled selection row for Samus, enemies, and native-asset workspaces. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SpriteMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) {
        if (selected) bringIntoViewRequester.bringIntoView()
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .clickable(onClick = onClick),
        color = if (selected) colors.primaryContainer else colors.surface,
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = LocalEditorTheme.current.fontSize.value.body,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                modifier = Modifier.weight(1f))
            trailing()
        }
    }
}

/** Compact chips used by the existing Samus suit and boss palette selectors. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpriteOptionSelector(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            FilterChip(selected = selected == index, onClick = { onSelect(index) },
                label = { Text(label, fontSize = LocalEditorTheme.current.fontSize.value.detail) }, modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
internal fun SpriteWorkspaceAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Surface(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(label, fontSize = LocalEditorTheme.current.fontSize.value.detail,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}
