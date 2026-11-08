package com.supermetroid.editor.asm

import java.io.File
import com.supermetroid.editor.rom.RomConstants
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class AsmProjectModuleAuditorTest {
    @TempDir
    lateinit var temporary: File

    @Test
    fun `identical module writes still collide because ownership is derived independently`() {
        val baseline = ByteArray(0x8000)
        val first = baseline.copyOf().also { it[8] = 0x42 }
        val second = baseline.copyOf().also { it[8] = 0x42 }
        val claims = AsmProjectModuleAuditor.claims("project/first.asm", baseline, first) +
            AsmProjectModuleAuditor.claims("project/second.asm", baseline, second)

        assertFailsWith<AsmCompilationException> {
            AsmProjectModuleAuditor.requireNoCollisions(claims)
        }
    }

    @Test
    fun `nonoverlapping module writes retain exact owners`() {
        val baseline = ByteArray(0x8000)
        val first = baseline.copyOf().also { it[4] = 1 }
        val second = baseline.copyOf().also { it[20] = 2 }
        val claims = AsmProjectModuleAuditor.claims("project/first.asm", baseline, first) +
            AsmProjectModuleAuditor.claims("project/second.asm", baseline, second)

        AsmProjectModuleAuditor.requireNoCollisions(claims)
        assertEquals(listOf("project/first.asm", "project/second.asm"), claims.map { it.sourceFileId })
    }

    @Test
    fun `module writes reject generated and project source ownership`() {
        val claim = AsmSourceOwnedRange(10, 4, "project/hook.asm")
        assertFailsWith<AsmCompilationException> {
            AsmProjectModuleAuditor.requireNoGeneratedCollisions(listOf(claim), listOf((12..15) to "room data"))
        }

        val immutable = ByteArray(32)
        val moduleFree = immutable.copyOf().also { it[11] = 1 }
        assertFailsWith<AsmCompilationException> {
            AsmProjectModuleAuditor.requireNoProjectSourceCollisions(listOf(claim), immutable, moduleFree)
        }
    }

    @Test
    fun `combined build must reproduce each isolated module and claim every new write`() {
        val baseline = ByteArray(0x8000)
        val isolated = baseline.copyOf().also { it[10] = 1 }
        val claims = AsmProjectModuleAuditor.claims("project/first.asm", baseline, isolated)
        val module = AsmProjectModuleAuditor.capture("project/first.asm", claims, isolated)

        AsmProjectModuleAuditor.requireDeterministicComposition(baseline, isolated, listOf(module))

        val overwritten = isolated.copyOf().also { it[10] = 2 }
        assertFailsWith<AsmCompilationException> {
            AsmProjectModuleAuditor.requireDeterministicComposition(baseline, overwritten, listOf(module))
        }
        val interaction = isolated.copyOf().also { it[20] = 3 }
        assertFailsWith<AsmCompilationException> {
            AsmProjectModuleAuditor.requireDeterministicComposition(baseline, interaction, listOf(module))
        }
    }

    @Test
    fun `compiler rejects two modules that emit the same replacement byte`() {
        AsmProjectCompiler.clearBuildCacheForTests()
        val fixture = AsmProjectTestFixture.create(temporary, AsmSourceAugmentation.NONE)
        val repository = AsmProjectWorkspaceRepository(starterModules = emptyList(), augmentation = AsmSourceAugmentation.NONE)
        val source = "org ${'$'}808000\ndb ${'$'}42\n"
        repository.createModule(fixture.projectFile.path, "first_writer", initialSource = source)
        repository.createModule(fixture.projectFile.path, "second_writer", initialSource = source)

        val failure = assertFailsWith<AsmCompilationException> {
            AsmProjectCompiler(repository).compile(fixture.projectFile.path, fixture.rom)
        }
        assertContains(failure.message.orEmpty(), "both write")
        AsmProjectCompiler.clearBuildCacheForTests()
    }

    @Test
    fun `module audit composes with generated source inputs`() {
        AsmProjectCompiler.clearBuildCacheForTests()
        val fixture = AsmProjectTestFixture.create(temporary, AsmSourceAugmentation.NONE)
        val repository = AsmProjectWorkspaceRepository(starterModules = emptyList(), augmentation = AsmSourceAugmentation.NONE)
        repository.createModule(
            fixture.projectFile.path,
            "expanded_module",
            initialSource = "org ${'$'}E08010\ndb ${'$'}42\n",
        )
        val patchPc = checkNotNull(snesLoRomToPc(0xE08100))
        val patch = AsmGeneratedPatchClaim(
            owner = "patch:audit-test",
            label = "Generated audit byte",
            pcOffset = patchPc,
            bytes = byteArrayOf(0x24),
        )

        val compiled = AsmProjectCompiler(repository).compile(
            fixture.projectFile.path,
            fixture.rom,
            patchMaterialization = AsmPatchMaterialization(
                source = "org ${'$'}E08100\ndb ${'$'}24\n",
                claims = listOf(patch),
                eligibleWrites = 1,
                skippedWrites = 0,
            ),
            outputRomSize = RomConstants.EXPANDED_ROM_SIZE,
        )

        assertEquals(0x42, compiled.romBytes[checkNotNull(snesLoRomToPc(0xE08010))].toInt() and 0xFF)
        assertEquals(0x24, compiled.romBytes[patchPc].toInt() and 0xFF)
        AsmProjectCompiler.clearBuildCacheForTests()
    }
}
