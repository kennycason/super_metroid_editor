package com.supermetroid.editor.ai

import com.supermetroid.editor.ui.maskedAiCredentialPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AiCredentialPreviewTest {
    @Test
    fun `long credentials show only their first and last ten characters`() {
        val credential = "sk-project-1234567890-abcdefghijklmnopqrstuvwxyz"
        val preview = maskedAiCredentialPreview(credential)

        assertEquals("${credential.take(10)}……${credential.takeLast(10)}", preview)
        assertFalse(preview.contains("1234567890"))
        assertFalse(preview.contains(credential))
    }

    @Test
    fun `short credentials are never rendered in full`() {
        val credential = "short-secret"
        assertEquals("Configured key (12 characters)", maskedAiCredentialPreview(credential))
    }
}
