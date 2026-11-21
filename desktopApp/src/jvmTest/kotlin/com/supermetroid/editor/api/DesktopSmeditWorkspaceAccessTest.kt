package com.supermetroid.editor.api

import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.ui.EditorState
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSmeditWorkspaceAccessTest {
    @Test
    fun `documentation retrieval is useful and cannot escape the docs root`() {
        val access = DesktopSmeditWorkspaceAccess(
            editorState = EditorState(),
            asmWorkspaceState = AsmWorkspaceState(),
            parser = { null },
            roomInfos = { emptyList() },
            selectedRoom = { null },
        )

        val matches = access.searchDocs("state-sensitive room music", limit = 10)
        assertTrue(matches.any { it.path == "ai/semantic_safety.md" })

        val excerpt = access.readDoc("ai/semantic_safety.md", startLine = 1, maxLines = 40)
        assertTrue(excerpt?.text?.contains("State-sensitive room music") == true)
        assertNull(access.readDoc("../README.md", startLine = 1, maxLines = 20))
        assertNull(access.readDoc("/etc/passwd", startLine = 1, maxLines = 20))
    }
}
