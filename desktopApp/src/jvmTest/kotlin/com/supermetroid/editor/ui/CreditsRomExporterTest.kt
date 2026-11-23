package com.supermetroid.editor.ui

import com.supermetroid.editor.data.ProjectCreditsGroup
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.CreditsData
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CreditsRomExporterTest {
    @Test
    fun `desktop export relocates credits transactionally and reparses them`() {
        val rom = TestRomHelper.requireRomBytes()
        val project = SmEditProject("fixture.sfc").apply {
            creditsGroups += ProjectCreditsGroup("SMEDIT TEAM", mutableListOf("SAMUS ARAN", "BABY METROID"))
        }
        val built = assertNotNull(RomExporter(project, RomParser(rom)).build())
        val parsed = assertNotNull(CreditsData.parseCredits(RomParser(built.resultRom)))

        assertEquals("SMEDIT TEAM", parsed.sections.last().header)
        assertEquals(listOf("SAMUS ARAN", "BABY METROID"), parsed.sections.last().names)
        assertTrue(parsed.instListSnesAddr ushr 16 == CreditsData.INST_LIST_BANK)
        assertTrue(built.writeReport.writes.any { it.label == "Credits tilemap (recompressed)" })
        assertTrue(built.writeReport.writes.any { it.label == "Credits instruction list pointer" })
    }

    @Test
    fun `invalid persisted credits and uncompiled source ownership fail closed`() {
        val rom = TestRomHelper.requireRomBytes()
        val statuses = mutableListOf<String>()
        val invalid = SmEditProject("fixture.sfc").apply {
            creditsGroups += ProjectCreditsGroup("INVALID 123", mutableListOf("SAMUS"))
        }
        assertNull(RomExporter(invalid, RomParser(rom), onStatus = statuses::add).build())
        assertTrue(statuses.any { it.contains("Credits export failed") })

        val sourceOwned = SmEditProject("fixture.sfc").apply {
            creditsGroups += ProjectCreditsGroup("TEAM", mutableListOf("SAMUS"))
            asmWorkspace.creditsSourceVersion = 1
        }
        statuses.clear()
        assertNull(RomExporter(sourceOwned, RomParser(rom), onStatus = statuses::add).build())
        assertTrue(statuses.any { it.contains("validated ASM build") })
    }
}
