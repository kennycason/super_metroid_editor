package com.supermetroid.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.asm.formatSnesAddress
import com.supermetroid.editor.asm.snesLoRomToPc

internal fun semanticAsmAddress(bankBase: Int, pointer: Int): Int? =
    (bankBase or (pointer and 0xFFFF)).takeIf {
        pointer in 0x8000..0xFFFE && snesLoRomToPc(it) != null
    }

@Composable
internal fun AsmNavigationLink(
    snesAddress: Int,
    onNavigateToAsm: (Int) -> Unit,
    label: String = "ASM ${formatSnesAddress(snesAddress)}",
    modifier: Modifier = Modifier,
) {
    Text(
        label,
        fontSize = LocalEditorTheme.current.fontSize.value.detail,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .clickable { onNavigateToAsm(snesAddress) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}
