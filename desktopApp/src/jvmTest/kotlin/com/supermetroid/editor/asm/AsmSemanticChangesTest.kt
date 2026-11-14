package com.supermetroid.editor.asm

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class AsmSemanticChangesTest {
    @Test
    fun `proven editor links classify resources without claiming source provenance`() {
        val room = AsmSemanticChanges.source(
            fileId = "bank_8F.asm",
            before = "Room_91F8: dw \$0001\n",
            after = "Room_91F8: dw \$0002\n",
            projectModule = false,
            editorLinks = listOf(AsmEditorLink(
                "Edit Waterway",
                "Room header / state fields",
                AsmEditorTarget.Room(0x91F8),
            )),
        )
        assertEquals(AsmSemanticChangeKind.ROOM_RESOURCE, room.kind)
        assertEquals(AsmSemanticChangeOrigin.AUTHORED_SOURCE, room.origin)
        assertContains(room.originDetail, "does not retain whether ASM typing")

        val palette = AsmSemanticChanges.source(
            fileId = "bank_B4.asm",
            before = "SpriteColors: dw \$0000\n",
            after = "SpriteColors: dw \$001F\n",
            projectModule = false,
            editorLinks = listOf(AsmEditorLink(
                "Open Ridley",
                "Sprite palette",
                AsmEditorTarget.Sprite(0xE1BF),
            )),
        )
        assertEquals(AsmSemanticChangeKind.PALETTE, palette.kind)
    }

    @Test
    fun `modules and baseline updates retain their exact known origins`() {
        val module = AsmSemanticChanges.source(
            "project/custom_hook.asm", "", "org \$808000\nJML Handler\n", projectModule = true,
        )
        assertEquals(AsmSemanticChangeKind.HOOK_OR_MODULE, module.kind)
        assertEquals(AsmSemanticChangeOrigin.PROJECT_MODULE, module.origin)

        val base = "Root:\n dw \$0001\n"
        val project = "Root:\n dw \$0002\n"
        val updated = "; documented\n$base"
        val file = AsmBaselineFileUpdate(
            fileId = "bank_80.asm",
            merge = AsmThreeWayMerge.merge(base, project, updated),
            changed = true,
            baselineChanged = true,
            projectEdited = true,
            baseText = base,
            projectText = project,
            updatedText = updated,
        )
        assertEquals(AsmSemanticChangeOrigin.MIXED, AsmSemanticChanges.baseline(file).origin)
    }
}
