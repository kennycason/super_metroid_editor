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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.supermetroid.editor.data.AppConfig
import com.supermetroid.editor.emulator.EmulatorRegistry

@Composable
fun SettingsPopup(
    onDismiss: () -> Unit,
    emulatorWorkspaceState: EmulatorWorkspaceState,
    editorState: EditorState? = null,
    showRoomItemNames: Boolean = true,
    showRoomMetaNames: Boolean = true,
    highlightRoomItems: Boolean = true,
    showRoomEnemyNames: Boolean = true,
    showRoomFlatSlopeSurfaces: Boolean = true,
    onShowRoomItemNamesChange: (Boolean) -> Unit = {},
    onShowRoomMetaNamesChange: (Boolean) -> Unit = {},
    onHighlightRoomItemsChange: (Boolean) -> Unit = {},
    onShowRoomEnemyNamesChange: (Boolean) -> Unit = {},
    onShowRoomFlatSlopeSurfacesChange: (Boolean) -> Unit = {},
) {
    Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val themeState = LocalEditorTheme.current
        var currentTheme by themeState.theme
        var currentFontSize by themeState.fontSize
        var selectedTab by remember { mutableStateOf(0) }
        val tabs = listOf("General", "Emulator")

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            tonalElevation = 2.dp,
            modifier = Modifier.width(380.dp).padding(top = 4.dp, end = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Settings",
                    fontSize = currentFontSize.heading,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // ── Tab bar ──
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tabs.forEachIndexed { index, label ->
                        val selected = index == selectedTab
                        Surface(
                            modifier = Modifier.clickable { selectedTab = index },
                            shape = RoundedCornerShape(6.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                label,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                fontSize = currentFontSize.body,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                when (selectedTab) {
                    0 -> GeneralSettingsTab(currentTheme, currentFontSize,
                        onThemeChange = { theme ->
                            currentTheme = theme
                            AppConfig.update { copy(theme = theme.name) }
                        },
                        onFontSizeChange = { size ->
                            currentFontSize = size
                            AppConfig.update { copy(fontSize = size.name) }
                        },
                        editorState = editorState,
                        showRoomItemNames = showRoomItemNames,
                        showRoomMetaNames = showRoomMetaNames,
                        highlightRoomItems = highlightRoomItems,
                        showRoomEnemyNames = showRoomEnemyNames,
                        showRoomFlatSlopeSurfaces = showRoomFlatSlopeSurfaces,
                        onShowRoomItemNamesChange = onShowRoomItemNamesChange,
                        onShowRoomMetaNamesChange = onShowRoomMetaNamesChange,
                        onHighlightRoomItemsChange = onHighlightRoomItemsChange,
                        onShowRoomEnemyNamesChange = onShowRoomEnemyNamesChange,
                        onShowRoomFlatSlopeSurfacesChange = onShowRoomFlatSlopeSurfacesChange,
                    )
                    1 -> EmulatorSettingsTab(emulatorWorkspaceState, currentFontSize)
                }
            }
        }
    }
}

@Composable
private fun GeneralSettingsTab(
    currentTheme: EditorTheme,
    currentFontSize: FontSize,
    onThemeChange: (EditorTheme) -> Unit,
    onFontSizeChange: (FontSize) -> Unit,
    editorState: EditorState? = null,
    showRoomItemNames: Boolean,
    showRoomMetaNames: Boolean,
    highlightRoomItems: Boolean,
    showRoomEnemyNames: Boolean,
    showRoomFlatSlopeSurfaces: Boolean,
    onShowRoomItemNamesChange: (Boolean) -> Unit,
    onShowRoomMetaNamesChange: (Boolean) -> Unit,
    onHighlightRoomItemsChange: (Boolean) -> Unit,
    onShowRoomEnemyNamesChange: (Boolean) -> Unit,
    onShowRoomFlatSlopeSurfacesChange: (Boolean) -> Unit,
) {
    // ── Theme Section ──
    Text(
        "Theme",
        fontSize = currentFontSize.body,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (theme in EditorTheme.entries) {
            val selected = theme == currentTheme
            val colors = theme.colorScheme
            Surface(
                modifier = Modifier.clickable { onThemeChange(theme) },
                shape = RoundedCornerShape(6.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier.size(14.dp).clip(CircleShape)
                            .background(colors.background)
                            .border(1.dp, colors.outline, CircleShape)
                    )
                    Box(
                        modifier = Modifier.size(14.dp).clip(CircleShape)
                            .background(colors.primary)
                            .border(1.dp, colors.outline, CircleShape)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        theme.displayName,
                        fontSize = currentFontSize.detail,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // ── Font Size Section ──
    Text(
        "Font Size",
        fontSize = currentFontSize.body,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (size in FontSize.entries) {
            val selected = size == currentFontSize
            Surface(
                modifier = Modifier.clickable { onFontSizeChange(size) },
                shape = RoundedCornerShape(6.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    size.displayName,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = size.body,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // ── Room Editor Section ──
    Text(
        "Room Editor",
        fontSize = currentFontSize.body,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
    SettingsCheckboxRow(
        label = "Show item names",
        description = "Draw item name badges under room item icons.",
        checked = showRoomItemNames,
        onCheckedChange = onShowRoomItemNamesChange,
        fontSize = currentFontSize,
    )
    SettingsCheckboxRow(
        label = "Show meta names",
        description = "Draw station, door, scroll trigger, and metadata object name badges.",
        checked = showRoomMetaNames,
        onCheckedChange = onShowRoomMetaNamesChange,
        fontSize = currentFontSize,
    )
    SettingsCheckboxRow(
        label = "Highlight items",
        description = "Draw a bordered background behind item icons.",
        checked = highlightRoomItems,
        onCheckedChange = onHighlightRoomItemsChange,
        fontSize = currentFontSize,
    )
    SettingsCheckboxRow(
        label = "Show enemy names",
        description = "Draw enemy name badges above room enemy sprites.",
        checked = showRoomEnemyNames,
        onCheckedChange = onShowRoomEnemyNamesChange,
        fontSize = currentFontSize,
    )
    SettingsCheckboxRow(
        label = "Show slopes for flat surfaces",
        description = "When the Slope overlay is enabled, draw exposed flat solid edges too.",
        checked = showRoomFlatSlopeSurfaces,
        onCheckedChange = onShowRoomFlatSlopeSurfacesChange,
        fontSize = currentFontSize,
    )

    // ── Export Section ──
    if (editorState != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            "Export",
            fontSize = currentFontSize.body,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Version", fontSize = currentFontSize.body, color = MaterialTheme.colorScheme.onSurface)
            VersionDropdown(
                value = editorState.project.versionMajor,
                onValueChange = { editorState.project.versionMajor = it },
                fontSize = currentFontSize,
            )
            Text(".", fontSize = currentFontSize.body, color = MaterialTheme.colorScheme.onSurface)
            VersionDropdown(
                value = editorState.project.versionMinor,
                onValueChange = { editorState.project.versionMinor = it },
                fontSize = currentFontSize,
            )
        }
        Text(
            "Build Name",
            fontSize = currentFontSize.body,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        AppTextInput(
            value = editorState.project.buildName,
            onValueChange = { editorState.project.buildName = it },
            placeholder = "Optional (e.g. kaizo, practice)",
            modifier = Modifier.fillMaxWidth(),
        )
        // ── Export filename preview ──
        val build = editorState.project.buildName.trim()
        val version = "v${editorState.project.versionMajor}.${editorState.project.versionMinor}"
        val suffix = if (build.isNotEmpty()) "$build-$version" else version
        Text(
            "Export: romname-$suffix.smc",
            fontSize = currentFontSize.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsCheckboxRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    fontSize: FontSize,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Column {
            Text(label, fontSize = fontSize.body, color = MaterialTheme.colorScheme.onSurface)
            Text(description, fontSize = fontSize.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VersionDropdown(
    value: Int,
    onValueChange: (Int) -> Unit,
    fontSize: FontSize,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            modifier = Modifier.clickable { expanded = !expanded },
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                value.toString(),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).widthIn(min = 32.dp),
                fontSize = fontSize.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            Popup(
                alignment = Alignment.TopStart,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp,
                    tonalElevation = 2.dp,
                ) {
                    Column(
                        modifier = Modifier.height(200.dp).width(60.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        for (i in 0..255) {
                            Text(
                                i.toString(),
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        onValueChange(i)
                                        expanded = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                fontSize = fontSize.body,
                                fontWeight = if (i == value) FontWeight.Bold else FontWeight.Normal,
                                color = if (i == value) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmulatorSettingsTab(
    workspaceState: EmulatorWorkspaceState,
    currentFontSize: FontSize,
) {
    // ── Backend selector ──
    Text(
        "Backend",
        fontSize = currentFontSize.body,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EmulatorRegistry.availableBackends().forEach { backend ->
            val selected = backend == workspaceState.selectedBackendName
            Surface(
                modifier = Modifier.clickable {
                    if (workspaceState.isConnected) {
                        workspaceState.disconnectBridge()
                    }
                    workspaceState.updateSelectedBackendName(backend)
                },
                shape = RoundedCornerShape(6.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    backend,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = currentFontSize.body,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (workspaceState.isConnected) {
        Text(
            "Disconnect to change backend",
            fontSize = currentFontSize.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // ── Backend-specific config ──
    when (workspaceState.selectedBackendName) {
        "retroarch" -> {
            Spacer(Modifier.height(2.dp))
            Text(
                "RetroArch Path",
                fontSize = currentFontSize.body,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            AppTextInput(
                value = workspaceState.retroArchPath,
                onValueChange = { workspaceState.updateRetroArchPath(it) },
                placeholder = "Path to RetroArch executable",
                modifier = Modifier.fillMaxWidth(),
                monospace = true,
            )
            Text(
                "SNES Core",
                fontSize = currentFontSize.body,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            AppTextInput(
                value = workspaceState.retroArchCorePath,
                onValueChange = { workspaceState.updateRetroArchCorePath(it) },
                placeholder = "Path to bsnes_mercury_accuracy core (auto-detected)",
                modifier = Modifier.fillMaxWidth(),
                monospace = true,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("NWA Port:", fontSize = currentFontSize.body, color = MaterialTheme.colorScheme.onSurface)
                AppTextInput(
                    value = workspaceState.retroArchNwaPort.toString(),
                    onValueChange = { workspaceState.updateRetroArchNwaPort(it.toIntOrNull() ?: 55355) },
                    placeholder = "55355",
                    modifier = Modifier.width(100.dp),
                    monospace = true,
                )
            }
            Text(
                "Enable in RetroArch: Settings > Network > Network Commands = ON",
                fontSize = currentFontSize.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        "libretro" -> {
            Text(
                "Embedded SNES emulator via libretro core. No external setup required.",
                fontSize = currentFontSize.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        "lsnes-b25" -> {
            Spacer(Modifier.height(2.dp))
            Text(
                "lsnes Worker",
                fontSize = currentFontSize.body,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            AppTextInput(
                value = workspaceState.lsnesPath,
                onValueChange = { workspaceState.updateLsnesPath(it) },
                placeholder = "Path to smedit-lsnes-worker",
                modifier = Modifier.fillMaxWidth(),
                monospace = true,
            )
            Text(
                "TAS Movie",
                fontSize = currentFontSize.body,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            AppTextInput(
                value = workspaceState.lsnesMoviePath,
                onValueChange = { workspaceState.updateLsnesMoviePath(it) },
                placeholder = "Path to TASVideos movie (*.lsmv)",
                modifier = Modifier.fillMaxWidth(),
                monospace = true,
            )
            Text(
                "Extra Lua Script",
                fontSize = currentFontSize.body,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            AppTextInput(
                value = workspaceState.lsnesLuaScriptPath,
                onValueChange = { workspaceState.updateLsnesLuaScriptPath(it) },
                placeholder = "Optional extra Lua script",
                modifier = Modifier.fillMaxWidth(),
                monospace = true,
            )
            Text(
                "Stock lsnes is rejected. Movies must be TASVideos .lsmv. Play boots ROM+movie together.",
                fontSize = currentFontSize.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // ── Sync toggle ──
    Spacer(Modifier.height(2.dp))
    Text(
        "Sync",
        fontSize = currentFontSize.body,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val syncEnabled = workspaceState.followLiveRoom
        Surface(
            modifier = Modifier.clickable { workspaceState.updateFollowLiveRoom(!syncEnabled) },
            shape = RoundedCornerShape(6.dp),
            color = if (syncEnabled) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                if (syncEnabled) "SYNC ON" else "SYNC OFF",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                fontSize = currentFontSize.body,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (syncEnabled) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "Follow emulator room in editor",
            fontSize = currentFontSize.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
