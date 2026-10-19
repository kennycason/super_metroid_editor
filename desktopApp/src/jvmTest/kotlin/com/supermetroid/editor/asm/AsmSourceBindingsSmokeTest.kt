package com.supermetroid.editor.asm

import java.io.File
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Runs in public cross-platform CI using only our tiny source and inert ROM, never Nintendo data. */
@Tag("asm-toolchain")
class AsmSourceBindingsSmokeTest {
    @TempDir lateinit var temporary: File

    @Test fun `new modules and source edits compile against two frozen baselines across cache repair`() {
        val resources = requireNotNull(System.getProperty("smedit.testPackagedResourcesDir"))
        val toolchain = AsmToolchain(File(temporary, "home"), emptyMap(), mapOf(
            "compose.application.resources.dir" to resources,
            "os.name" to System.getProperty("os.name"), "os.arch" to System.getProperty("os.arch"),
            "user.dir" to temporary.absolutePath,
        ))
        for (version in listOf("Older", "Newer")) {
            AsmProjectCompiler.clearBuildCacheForTests()
            val directory = File(temporary, version).apply { mkdirs() }
            val root = File(directory, "reference")
            val src = File(root, "src").apply { mkdirs() }
            val data = File(root, "data").apply { mkdirs() }
            File(src, "main.asm").writeText("lorom\nincsrc bank_80.asm\nincsrc bank_8F.asm\n")
            File(src, "bank_80.asm").writeText("org \$808000\nNativeEntry:\n RTL\nNativeTable:\n dw \$000F\n")
            File(src, "bank_8F.asm").writeText("; No room data in this independent fixture.\n")
            val rom = ByteArray(0x300000) { 0xFF.toByte() }.apply { this[0] = 0x6B; this[1] = 15; this[2] = 0 }
            val reference = AsmReferenceWorkspace(root, AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL,
                AsmReferenceContract.COMMIT, "synthetic", "inert.sfc", AsmVerifiedCache.hash(rom), rom.size, 0),
                AsmSourceParser().parse(src, data, emptyList()))
            val recipe = AsmSourceAugmentation(version, renames = listOf(
                AsmSourceRename("NativeEntry", "${version}Entry"), AsmSourceRename("NativeTable", "${version}Table")))
            val repo = AsmProjectWorkspaceRepository(emptyList(), listOf(
                AsmStarterModule("objectives.asm", "/asm-binding-smoke/objectives.asm")), recipe)
            val file = File(directory, "Test.smedit").apply { writeText("{}") }
            val workspace = repo.initialize(file.path, reference)
            val baseline = assertNotNull(AsmSourceBaseline.load(workspace.root))
            val snapshot = File(workspace.root, "base/source.zip").readBytes()
            repo.setModuleEnabled(file.path, "project/objectives.asm", true)
            val authored = File(workspace.root, "modules/objectives.asm").readText() + "; My custom objectives page\n"
            assertContains(authored, "JSL ${version}Entry")
            repo.persistSource(file.path, "project/objectives.asm", authored)

            // Simulate a later app whose current recipe cannot apply to this original source.
            val newerApp = AsmProjectWorkspaceRepository(emptyList(), emptyList(), AsmSourceAugmentation("future-app",
                documentation = listOf(AsmSourceDocumentation("missing", "missing.asm", "NoSuchSymbol", "RTS", listOf("Not replayed.")))))
            assertTrue(workspace.originalDirectory.parentFile.deleteRecursively())
            val repaired = newerApp.initialize(file.path, reference)
            assertEquals(baseline.id, AsmSourceBaseline.load(repaired.root)?.id)
            assertContentEquals(snapshot, File(repaired.root, "base/source.zip").readBytes())
            assertEquals(authored, File(repaired.root, "modules/objectives.asm").readText())

            val bound = AsmSourceBindings.load(repaired.originalDirectory).template(
                "org \$808300\nNewModule:\n JSL NativeEntry\n RTL\n dw NativeTable\n; NativeEntry is a contract name.\n")
            newerApp.createModule(file.path, "new_module.asm", initialSource = bound)
            assertContains(bound, "JSL ${version}Entry")
            assertContains(bound, "dw ${version}Table")
            assertContains(bound, "; NativeEntry is a contract name.")
            val bank = File(repaired.workingDirectory, "src/bank_80.asm").readText()
            newerApp.persistSource(file.path, "bank_80.asm", bank.replace("\$000F", "\$002A"), bank)

            val compiler = AsmProjectCompiler(newerApp, toolchain)
            val compiled = compiler.compile(file.path, rom)
            assertEquals(42, compiled.romBytes[1].toInt() and 255)
            assertEquals(15, compiled.referenceRomBody[1].toInt() and 255)
            assertContentEquals(byteArrayOf(0x22, 0, 0x80.toByte(), 0x80.toByte(), 0x6B, 1, 0x80.toByte()),
                compiled.romBytes.copyOfRange(0x300, 0x307))
            val labels = AsmWlaSymbolParser.parse(compiled.compilerSymbols.toString(Charsets.UTF_8)).labels
            assertEquals(0x808000, labels["${version}Entry"])
            assertEquals(listOf("project/objectives.asm", "project/new_module.asm"), newerApp.load(file.path)?.projectModuleFileIds)
            AsmProjectCompiler.clearBuildCacheForTests()
            val header = ByteArray(512) { 0x5A }
            val cold = AsmProjectCompiler(newerApp, toolchain).compile(file.path, header + rom)
            assertContentEquals(header, cold.romBytes.copyOfRange(0, 512))
            assertContentEquals(compiled.romBytes, cold.romBytes.copyOfRange(512, cold.romBytes.size))
            assertEquals(authored, File(repaired.root, "modules/objectives.asm").readText())
        }
    }
}
