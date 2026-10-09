package com.supermetroid.editor.ui

import com.supermetroid.editor.data.AppSettings
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AppLayoutSettingsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `legacy settings receive current layout defaults`() {
        val settings = json.decodeFromString<AppSettings>("{}")

        assertEquals(340f, settings.mainSidebarWidthDp)
        assertEquals(0f, settings.sidebarBottomPaneHeightDp)
        assertEquals(180f, settings.asmProblemsPaneHeightDp)
    }

    @Test
    fun `sidebar layout dimensions survive settings round trip`() {
        val settings = AppSettings(
            mainSidebarWidthDp = 412f,
            sidebarBottomPaneHeightDp = 368f,
            asmProblemsPaneHeightDp = 246f,
        )

        val restored = json.decodeFromString<AppSettings>(json.encodeToString(settings))

        assertEquals(412f, restored.mainSidebarWidthDp)
        assertEquals(368f, restored.sidebarBottomPaneHeightDp)
        assertEquals(246f, restored.asmProblemsPaneHeightDp)
    }

    @Test
    fun `streaming preset is full hd`() {
        assertEquals(1920, STREAMING_WINDOW_WIDTH)
        assertEquals(1080, STREAMING_WINDOW_HEIGHT)
    }
}
