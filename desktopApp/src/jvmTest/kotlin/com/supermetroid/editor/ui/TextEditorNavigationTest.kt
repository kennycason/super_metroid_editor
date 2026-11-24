package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.TextCategory
import com.supermetroid.editor.rom.TextEntry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextEditorNavigationTest {
    @Test
    fun `text editor typography follows the global font size preset`() {
        val root = File(checkNotNull(System.getProperty("smedit.repositoryRoot")))
        val source = File(
            root,
            "desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/TextEditor.kt",
        ).readText()

        assertTrue("LocalEditorTheme.current.fontSize.value" in source)
        assertFalse(
            Regex("""fontSize\s*=\s*\d+(?:\.\d+)?\.sp""").containsMatchIn(source),
            "Text Editor font sizes must use SMEDIT's semantic global typography values",
        )
    }

    @Test
    fun `section navigation follows preview order and always exposes credits`() {
        val entries = listOf(
            entry("intro_0", TextCategory.INTRO_STORY),
            entry("area_0", TextCategory.AREA_NAME),
            entry("item_0", TextCategory.ITEM_NAME),
        )

        val targets = textEditorSectionTargets(entries, managedIntro = false, managedIntroSceneCount = 0)

        assertEquals(
            listOf(
                textCategorySectionId(TextCategory.INTRO_STORY),
                textCategorySectionId(TextCategory.AREA_NAME),
                textCategorySectionId(TextCategory.ITEM_NAME),
                TEXT_CREDITS_SECTION_ID,
            ),
            targets.map(TextEditorSectionTarget::id),
        )
        assertEquals(emptyList(), targets.last().entryIds)
    }

    @Test
    fun `first section is selected by default and stale selections are repaired`() {
        val entries = listOf(
            entry("intro_0", TextCategory.INTRO_STORY),
            entry("area_0", TextCategory.AREA_NAME),
        )
        val state = TextEditorState()
        val nativeTargets = textEditorSectionTargets(entries, managedIntro = false, managedIntroSceneCount = 0)

        state.ensureSelection(nativeTargets)
        assertEquals(textCategorySectionId(TextCategory.INTRO_STORY), state.selectedEntryId)

        state.select("area_0")
        state.ensureSelection(nativeTargets)
        assertEquals("area_0", state.selectedEntryId)

        val managedTargets = textEditorSectionTargets(entries, managedIntro = true, managedIntroSceneCount = 2)
        state.select("intro_0")
        state.ensureSelection(managedTargets)
        assertEquals(TEXT_MANAGED_INTRO_SECTION_ID, state.selectedEntryId)
        assertEquals(listOf("managed_intro_0", "managed_intro_1"), managedTargets.first().entryIds)
    }

    private fun entry(id: String, category: TextCategory) = TextEntry(
        id = id,
        label = id,
        category = category,
        pcOffset = 0,
        snesAddress = 0x808000,
        maxLength = 8,
        text = id,
        rawBytes = byteArrayOf(0, 0),
    )
}
