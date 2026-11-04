package com.supermetroid.editor.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.supermetroid.editor.data.AppConfig

internal enum class RoomSidebarTab { TILESET, PATTERNS, ROOM_INFO, GENERATE }

/** Owned above view/tab composition: returning from ASM must not reset a sidebar viewport. */
internal class RoomsSidebarState(
    initialTab: String = AppConfig.load().roomSidebarTab,
    private val persistTab: (String) -> Unit = { tab -> AppConfig.update { copy(roomSidebarTab = tab) } },
) {
    var selectedTab by mutableStateOf(RoomSidebarTab.entries.firstOrNull { it.name == initialTab } ?: RoomSidebarTab.TILESET)
        private set
    private val vertical = RoomSidebarTab.entries.associateWith { ScrollState(0) }
    val tilesetHorizontalScroll = ScrollState(0)
    fun verticalScroll(tab: RoomSidebarTab): ScrollState = vertical.getValue(tab)

    fun select(tab: RoomSidebarTab) {
        if (selectedTab == tab) return
        selectedTab = tab
        persistTab(tab.name)
    }
}
