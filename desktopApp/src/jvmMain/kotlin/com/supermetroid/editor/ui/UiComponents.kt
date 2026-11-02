package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A styled text input that is always visible regardless of theme.
 * Uses BasicTextField with explicit onSurface color and a surfaceVariant background.
 * Consistent with stat/hex input fields throughout the editor.
 */
@Composable
fun AppTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    fontSize: TextUnit = 13.sp,
    height: Dp = 32.dp,
    monospace: Boolean = false,
    textAlign: TextAlign = TextAlign.Start
) {
    val textStyle = TextStyle(
        fontSize = fontSize,
        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = textAlign
    )
    Box(
        modifier = modifier
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(
                placeholder,
                style = textStyle.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
        )
    }
}

/**
 * A numeric stat input (compact, monospace, centered).
 * Used in boss stats, enemy stats, and beam damage editors.
 */
@Composable
fun StatNumberInput(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    maxDigits: Int = 5,
    maxValue: Int = 65535,
    fontSize: TextUnit = 13.sp,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() }.take(maxDigits)
            text = filtered
            filtered.toIntOrNull()?.let { onChange(it.coerceIn(0, maxValue)) }
        },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = fontSize,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height((fontSize.value * 1.3f + 8f).coerceAtLeast(28f).dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

/**
 * OutlinedTextField with explicit colors to ensure text is always visible.
 * Use instead of bare OutlinedTextField in dialogs and forms.
 */
@Composable
fun AppOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    singleLine: Boolean = false,
    fontSize: TextUnit = 13.sp
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = if (label.isNotEmpty()) {{ Text(label, fontSize = (fontSize.value - 2).sp) }} else null,
        singleLine = singleLine,
        textStyle = TextStyle(
            fontSize = fontSize,
            color = MaterialTheme.colorScheme.onSurface
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            cursorColor = MaterialTheme.colorScheme.primary
        )
    )
}
