package com.supermetroid.editor.asm

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AsmBaselineUpdateTest {
    @TempDir lateinit var temporary: File
    private val rom = ByteArray(0x8000)
    private val source = "Root:\n dw \$0001\n RTL\n"
    private val oldRecipe = AsmSourceAugmentation("one")
    private val nextRecipe = AsmSourceAugmentation("two", documentation = listOf(
        AsmSourceDocumentation("root", "bank_80.asm", "Root", "dw \$0001", listOf("A new explanation."))))
    private fun repo(recipe: AsmSourceAugmentation = oldRecipe) = AsmProjectWorkspaceRepository(emptyList(), emptyList(), recipe)
    private fun updater(recipe: AsmSourceAugmentation = nextRecipe, validate: (String, ByteArray) -> Unit = { _, _ -> }) =
        AsmBaselineUpdateRepository(repo(), recipe, emptyList(), emptyList(), validate)
    private fun reference(): AsmReferenceWorkspace {
        val directory = File(temporary, "reference")
        val src = File(directory, "src").apply { mkdirs() }
        val data = File(directory, "data").apply { mkdirs() }
        File(src, "main.asm").writeText("incsrc bank_80.asm\n")
        File(src, "bank_80.asm").writeText(source)
        return AsmReferenceWorkspace(directory, AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL,
            AsmReferenceContract.COMMIT, "fixture", "fixture.sfc", AsmVerifiedCache.hash(rom), rom.size, 0),
            AsmSourceParser().parse(src, data, emptyList()))
    }
    private fun project(reference: AsmReferenceWorkspace): Pair<File, AsmProjectWorkspace> {
        val file = File(temporary, "hack/Test.smedit").apply { parentFile.mkdirs(); writeText("{}") }
        return file to repo().initialize(file.path, reference)
    }

    @Test fun `preview is isolated and updating reapplies edits preserves modules and retains a portable backup`() {
        val reference = reference()
        val (file, initial) = project(reference)
        val edited = source.replace("\$0001", "\$0009")
        repo().persistSource(file.path, "bank_80.asm", edited)
        repo().createModule(file.path, "objectives", initialSource = "Objectives:\n RTS\n; my notes\n")
        repo().createModule(file.path, "custom", initialSource = "Custom:\n RTL\n")
        repo().setModuleEnabled(file.path, "project/objectives.asm", false)
        val before = AsmBaselineUpdateRepository.inventory(initial.root)
        val plan = updater().prepare(file.path, reference)
        assertEquals(0, plan.conflictCount)
        assertEquals(1, plan.baselineChanges.size)
        assertTrue(plan.baselineChanges.single().documentationOnly)
        assertEquals(1, plan.editedBankCount)
        assertEquals(2, plan.moduleCount)
        assertEquals(before, AsmBaselineUpdateRepository.inventory(initial.root))
        val result = updater().apply(plan, emptyMap(), rom)
        assertEquals("two", result.workspace.baselineRevision)
        assertEquals("; A new explanation.\n$edited", File(result.workspace.workingDirectory, "src/bank_80.asm").readText())
        assertEquals(listOf("project/objectives.asm", "project/custom.asm"), result.workspace.projectModuleFileIds)
        assertEquals(setOf("project/objectives.asm"), result.workspace.disabledProjectModuleFileIds)
        assertEquals("Objectives:\n RTS\n; my notes\n", File(initial.root, "modules/objectives.asm").readText())
        assertEquals(before, AsmBaselineUpdateRepository.inventory(File(result.backupDirectory, "asm")))
        assertEquals("one", AsmSourceBaseline.load(File(result.backupDirectory, "asm"))?.augmentationRevision)
        assertEquals(setOf("bank_80.asm", "project/custom.asm"), result.workspace.modifiedFileIds)
    }

    @Test fun `update summaries preserve string significance and distinguish executable compatibility lines and renames`() {
        assertTrue(asmBaselineDocumentationOnly("Root:\n LDA #1 ; old\n RTL\n", "; Heading\nRoot:\n  LDA  #1 ; new\n RTL ; end\n"))
        assertFalse(asmBaselineDocumentationOnly("Root:\n LDA #1\n", "Root:\n LDA #2\n"))
        assertFalse(asmBaselineDocumentationOnly("Old:\n RTL\n", "New:\n RTL\n"))
        assertFalse(asmBaselineDocumentationOnly(" db \"A B\"\n", " db \"AB\"\n"))
        assertFalse(asmBaselineDocumentationOnly(";@ LDA #1\n", ";@ LDA #2\n"))
        assertTrue(asmBaselineDocumentationOnly(" LDA #1 ;A08000;\r\n", " LDA #1 ; explanation\n"))
    }

    @Test fun `overlapping comments require explicit resolution and choosing project keeps other new changes`() {
        val reference = reference()
        val first = oldRecipe.copy(documentation = nextRecipe.documentation.map { it.copy(paragraphs = listOf("Original explanation.")) })
        val file = File(temporary, "hack/Test.smedit").apply { parentFile.mkdirs(); writeText("{}") }
        val initial = repo(first).initialize(file.path, reference)
        repo().persistSource(file.path, "bank_80.asm", "; My explanation.\n$source")
        val plan = updater().prepare(file.path, reference)
        assertEquals(1, plan.conflictCount)
        val before = AsmBaselineUpdateRepository.inventory(initial.root)
        assertFailsWith<IllegalArgumentException> { updater().apply(plan, emptyMap(), rom) }
        assertEquals(before, AsmBaselineUpdateRepository.inventory(initial.root))
        updater().apply(plan, mapOf("bank_80.asm" to mapOf(0 to "; My reviewed explanation.\n")), rom)
        assertEquals("; My reviewed explanation.\n$source", File(initial.workingDirectory, "src/bank_80.asm").readText())
    }

    @Test fun `stale authored files or module settings invalidate a preview without overwriting anything`() {
        for (change in listOf("source", "module")) {
            val reference = reference()
            val (file, initial) = project(reference)
            repo().createModule(file.path, "objectives", initialSource = "Objectives:\n RTL\n")
            val plan = updater().prepare(file.path, reference)
            if (change == "source") repo().persistSource(file.path, "bank_80.asm", source + "; later change\n")
            else repo().setModuleEnabled(file.path, "project/objectives.asm", false)
            val latest = AsmBaselineUpdateRepository.inventory(initial.root)
            assertFailsWith<IllegalArgumentException> { updater().apply(plan, emptyMap(), rom) }
            assertEquals(latest, AsmBaselineUpdateRepository.inventory(initial.root))
            updater().discard(plan)
            // Separate temporary project for the next scenario.
            assertTrue(File(temporary, "hack").renameTo(File(temporary, "finished-$change")))
        }
    }

    @Test fun `failed validation leaves the old baseline active and a resolved draft can be retried`() {
        val reference = reference()
        val (file, initial) = project(reference)
        val plan = updater().prepare(file.path, reference)
        val before = AsmBaselineUpdateRepository.inventory(initial.root)
        assertFailsWith<IllegalStateException> { updater(validate = { _, _ -> error("assembler rejected candidate") }).apply(plan, emptyMap(), rom) }
        assertEquals(before, AsmBaselineUpdateRepository.inventory(initial.root))
        assertFalse(plan.consumed)
        updater().apply(plan, emptyMap(), rom)
        assertEquals("two", AsmSourceBaseline.load(initial.root)?.augmentationRevision)
        assertFailsWith<IllegalArgumentException> { updater().apply(plan, emptyMap(), rom) }
    }

    @Test fun `changes during compiler validation invalidate the transaction`() {
        val reference = reference()
        val (file, initial) = project(reference)
        val plan = updater().prepare(file.path, reference)
        assertFailsWith<IllegalArgumentException> { updater(validate = { _, _ ->
            repo().persistSource(file.path, "bank_80.asm", source + "; external edit\n")
        }).apply(plan, emptyMap(), rom) }
        assertEquals("one", AsmSourceBaseline.load(initial.root)?.augmentationRevision)
        assertContains(File(initial.workingDirectory, "src/bank_80.asm").readText(), "; external edit")
    }

    @Test fun `symbol updates use checked bindings in both banks and authored modules`() {
        val reference = reference()
        val first = oldRecipe.copy(renames = listOf(AsmSourceRename("Root", "OldRoot")))
        val file = File(temporary, "hack/Test.smedit").apply { parentFile.mkdirs(); writeText("{}") }
        val initial = repo(first).initialize(file.path, reference)
        repo().persistSource(file.path, "bank_80.asm", source.replace("Root", "OldRoot").replace("\$0001", "\$0009"))
        repo().createModule(file.path, "objectives", initialSource = "Objectives:\n JSL OldRoot\n RTL\n; OldRoot stays in comments\n db \"OldRoot\"\n")
        val next = nextRecipe.copy(renames = listOf(AsmSourceRename("Root", "NewRoot")))
        val plan = updater(next).prepare(file.path, reference)
        assertEquals(0, plan.conflictCount)
        val result = updater(next).apply(plan, emptyMap(), rom)
        assertContains(File(result.workspace.workingDirectory, "src/bank_80.asm").readText(), "NewRoot:\n dw \$0009")
        assertEquals("Objectives:\n JSL NewRoot\n RTL\n; OldRoot stays in comments\n db \"OldRoot\"\n", File(initial.root, "modules/objectives.asm").readText())
    }

    @Test fun `unsupported symbol shadowing fails closed and cancelling never changes live files`() {
        val reference = reference()
        val (file, initial) = project(reference)
        repo().createModule(file.path, "objectives", initialSource = "macro emit(Root)\n dw <Root>\nendmacro\n")
        val before = AsmBaselineUpdateRepository.inventory(initial.root)
        assertFailsWith<IllegalArgumentException> {
            updater(nextRecipe.copy(renames = listOf(AsmSourceRename("Root", "NewRoot")))).prepare(file.path, reference)
        }
        assertEquals(before, AsmBaselineUpdateRepository.inventory(initial.root))
        val plan = updater().prepare(file.path, reference)
        updater().discard(plan)
        assertFalse(plan.staging.exists())
        assertEquals(before, AsmBaselineUpdateRepository.inventory(initial.root))
    }

    @Test fun `already current wrong ROM unknown choices and inventory changes cannot be installed`() {
        val reference = reference()
        val (file, initial) = project(reference)
        assertFailsWith<IllegalArgumentException> { updater(oldRecipe).prepare(file.path, reference) }
        val plan = updater().prepare(file.path, reference)
        assertFailsWith<IllegalArgumentException> { updater().apply(plan, emptyMap(), ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { updater().apply(plan, mapOf("evil.asm" to mapOf(0 to "RTS")), rom) }
        assertEquals("one", AsmSourceBaseline.load(initial.root)?.augmentationRevision)
        updater().discard(plan)
        File(reference.root, "src/added.asm").writeText("Added:\n RTL\n")
        assertFailsWith<IllegalArgumentException> { updater().prepare(file.path, reference) }
    }

    @Test fun `an actual format one project upgrades explicitly while its saved manifest bytes remain recoverable`() {
        val reference = reference()
        val file = File(temporary, "format1/Test.smedit").apply { parentFile.mkdirs(); writeText("{}") }
        val root = File(file.parentFile, "asm")
        AsmSourceBaseline.create(root, reference, oldRecipe, emptyList(), emptyList())
        val manifest = File(root, "base/manifest.json")
        val fields = Json.parseToJsonElement(manifest.readText()).jsonObject.toMutableMap().apply {
            this["formatVersion"] = JsonPrimitive(1)
            remove("nativeSymbols")
        }
        manifest.writeText(JsonObject(fields).toString())
        val originalBytes = manifest.readBytes()
        repo().initialize(file.path, reference)
        assertEquals(1, AsmSourceBaseline.load(root)?.formatVersion)
        val result = updater().apply(updater().prepare(file.path, reference), emptyMap(), rom)
        assertEquals(2, AsmSourceBaseline.load(root)?.formatVersion)
        assertEquals("two", result.workspace.baselineRevision)
        assertContentEquals(originalBytes, File(result.backupDirectory, "asm/base/manifest.json").readBytes())
    }
}
