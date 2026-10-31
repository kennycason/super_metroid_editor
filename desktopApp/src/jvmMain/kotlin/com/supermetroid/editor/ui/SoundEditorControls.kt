package com.supermetroid.editor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Match the editor's main navigation controls, instead of Material's pill-shaped buttons. */
internal val SoundControlShape = RoundedCornerShape(6.dp)
internal val SoundToolbarHeight = 36.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SoundToolbar(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
internal fun SoundToolbarButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, primary: Boolean = false, icon: ImageVector? = null, colors: ButtonColors? = null) {
    val control = modifier.height(SoundToolbarHeight).widthIn(min = 80.dp)
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) { Icon(icon, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
        Text(label, fontSize = LocalEditorTheme.current.fontSize.value.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (primary) Button(onClick, control, enabled, shape = SoundControlShape,
        colors = colors ?: ButtonDefaults.buttonColors(), contentPadding = PaddingValues(horizontal = 10.dp), content = content)
    else OutlinedButton(onClick, control, enabled, shape = SoundControlShape,
        colors = colors ?: ButtonDefaults.outlinedButtonColors(), contentPadding = PaddingValues(horizontal = 10.dp), content = content)
}

@Composable
internal fun SoundToolbarToggle(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: ImageVector? = null, minWidth: Dp = 80.dp) {
    OutlinedButton(onClick, modifier.height(SoundToolbarHeight).widthIn(min = minWidth).semantics { this.selected = selected },
        shape = SoundControlShape, contentPadding = PaddingValues(horizontal = 10.dp),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)) {
        if (icon != null) { Icon(icon, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
        Text(label, fontSize = LocalEditorTheme.current.fontSize.value.body, maxLines = 1)
    }
}

@Composable
internal fun SynthModule(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = SoundControlShape, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
