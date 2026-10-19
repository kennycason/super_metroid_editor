package com.supermetroid.editor.asm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class AsmSourceBaselineTest {
    @TempDir lateinit var temporary: File
    private val original = "Timers:\n dw \$0001 ;91B61F;\n"
    private val recipe = AsmSourceAugmentation("one", documentation = listOf(
        AsmSourceDocumentation("reloads", "bank_91.asm", "Timers", "dw \$0001", listOf("Initial timer reload.")),
    ))
    private fun reference(): AsmReferenceWorkspace {
        val root = File(temporary, "reference")
        val src = File(root, "src").apply { mkdirs() }
        File(src, "main.asm").writeText("incsrc bank_91.asm\n")
        File(src, "bank_91.asm").writeText(original)
        val data = File(root, "data").apply { mkdirs() }
        return AsmReferenceWorkspace(root, AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT,
            "fixture", "fixture.sfc", "ab".repeat(32), 0x300000, 0), AsmSourceParser().parse(src, data, emptyList()))
    }
    private fun repository(augmentation: AsmSourceAugmentation = recipe) = AsmProjectWorkspaceRepository(emptyList(), emptyList(), augmentation)
    private fun project() = File(temporary, "Project.smedit").apply { writeText("{}") }

    @Test fun `augmented baseline is portable and clean and does not mutate upstream`() {
        val reference = reference()
        val file = project()
        val workspace = repository().initialize(file.path, reference)
        val frozen = assertNotNull(AsmSourceBaseline.load(workspace.root))
        assertEquals("one", frozen.augmentationRevision)
        assertTrue(File(workspace.root, "base/source.zip").isFile)
        assertEquals(original, File(reference.root, "src/bank_91.asm").readText())
        assertTrue(File(workspace.originalDirectory, "src/bank_91.asm").readText().startsWith("; Initial timer reload.\n"))
        assertEquals(emptySet(), workspace.modifiedFileIds)
        assertEquals(emptySet(), repository().sourceOverrideFileIds(file.path))
        assertTrue(frozen.matches(File(workspace.originalDirectory, "src")))
    }

    @Test fun `save and restore compare against the augmented baseline`() {
        val file = project()
        val repo = repository()
        val workspace = repo.initialize(file.path, reference())
        val baseline = File(workspace.originalDirectory, "src/bank_91.asm").readText()
        assertFalse(repo.persistSource(file.path, "bank_91.asm", baseline).modified)
        val edited = baseline.replace("\$0001", "\$0007") + "; authored note\n"
        repo.persistSource(file.path, "bank_91.asm", edited, baseline)
        assertEquals(edited, File(workspace.root, "overrides/src/bank_91.asm").readText())
        assertEquals(setOf("bank_91.asm"), repo.load(file.path)?.modifiedFileIds)
        val restored = repo.restoreSource(file.path, "bank_91.asm")
        assertEquals(baseline, File(restored.workingDirectory, "src/bank_91.asm").readText())
        assertFalse(File(workspace.root, "overrides/src/bank_91.asm").exists())
    }

    @Test fun `app recipe changes never rewrite a pinned baseline or an authored module`() {
        val file = project()
        val repo = repository()
        val workspace = repo.initialize(file.path, reference())
        val (withModule, moduleId) = repo.createModule(file.path, "objectives", initialSource = "; Objectives page\nMyObjectives:\n RTS\n")
        val manifest = File(workspace.root, "base/manifest.json").readBytes()
        val newer = repository(AsmSourceAugmentation("two", documentation = listOf(
            AsmSourceDocumentation("wrong", "missing.asm", "Missing", "invalid", listOf("Must not run.")),
        )))
        val loaded = assertNotNull(newer.load(file.path))
        assertContentEquals(manifest, File(workspace.root, "base/manifest.json").readBytes())
        assertEquals(listOf(moduleId), loaded.projectModuleFileIds)
        assertEquals("; Objectives page\nMyObjectives:\n RTS\n", File(withModule.root, "modules/objectives.asm").readText())
    }

    @Test fun `cache reconstruction uses the frozen source and preserves bank edits and module settings`() {
        val file = project()
        val repo = repository()
        val reference = reference()
        val initial = repo.initialize(file.path, reference)
        val baseline = File(initial.originalDirectory, "src/bank_91.asm").readText()
        val edited = baseline.replace("\$0001", "\$0009")
        repo.persistSource(file.path, "bank_91.asm", edited)
        val (_, module) = repo.createModule(file.path, "objectives", initialSource = "Objectives:\n RTS\n")
        repo.setModuleEnabled(file.path, module, false)
        File(reference.root, "src/bank_91.asm").writeText("Changed global reference:\n RTS\n")
        // This is an isolated temporary project's generated cache; authored asm/ stays in place.
        assertTrue(initial.originalDirectory.parentFile.deleteRecursively())
        val rebuilt = repository(AsmSourceAugmentation("two")).initialize(file.path, reference)
        assertEquals(baseline, File(rebuilt.originalDirectory, "src/bank_91.asm").readText())
        assertEquals(edited, File(rebuilt.workingDirectory, "src/bank_91.asm").readText())
        assertEquals(setOf(module), rebuilt.disabledProjectModuleFileIds)
        assertEquals("Objectives:\n RTS\n", File(rebuilt.root, "modules/objectives.asm").readText())
        assertEquals("one", AsmSourceBaseline.load(rebuilt.root)?.augmentationRevision)
    }

    @Test fun `edited generated baseline is detected and repaired from the portable snapshot`() {
        val file = project()
        val repo = repository()
        val reference = reference()
        val initial = repo.initialize(file.path, reference)
        File(initial.originalDirectory, "src/bank_91.asm").writeText("Unexpected edit\n")
        assertNull(repo.loadForBuild(file.path))
        val repaired = repo.initialize(file.path, reference)
        assertTrue(File(repaired.originalDirectory, "src/bank_91.asm").readText().startsWith("; Initial timer reload."))
    }

    @Test fun `changed source snapshot cannot replace a valid baseline during repair`() {
        val file = project()
        val repo = repository()
        val reference = reference()
        val workspace = repo.initialize(file.path, reference)
        val archive = File(workspace.root, "base/source.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("bank_91.asm")); zip.write("Changed:\n RTS\n".toByteArray()); zip.closeEntry()
        }
        assertTrue(workspace.originalDirectory.parentFile.deleteRecursively())
        assertFailsWith<IllegalArgumentException> { repo.initialize(file.path, reference) }
        assertTrue(archive.isFile)
    }

    @Test fun `baseline lock mismatch and path traversal are rejected`() {
        val file = project()
        val repo = repository()
        val workspace = repo.initialize(file.path, reference())
        val baseline = assertNotNull(AsmSourceBaseline.load(workspace.root))
        val manifest = File(workspace.root, "base/manifest.json")
        manifest.writeText(Json.encodeToString(baseline.copy(augmentationRevision = "altered")))
        assertFailsWith<IllegalArgumentException> { repo.loadForBuild(file.path) }
        manifest.writeText(Json.encodeToString(baseline.copy(sourceHashes = baseline.sourceHashes + ("../escape.asm" to "a".repeat(64)))))
        assertFailsWith<IllegalArgumentException> { AsmSourceBaseline.load(workspace.root) }
        assertFalse(File(temporary, "escape.asm").exists())
    }

    @Test fun `cache repair cannot silently change the ROM assets of a frozen baseline`() {
        val file = project()
        val reference = reference()
        val workspace = repository().initialize(file.path, reference)
        assertTrue(workspace.originalDirectory.parentFile.deleteRecursively())
        val otherRom = reference.copy(metadata = reference.metadata.copy(romSha256 = "cd".repeat(32)))
        assertFailsWith<IllegalArgumentException> { repository().initialize(file.path, otherRom) }
    }

    @Test fun `repair pins asset names and ranges and extracts them from a headered ROM after app manifest changes`() {
        val file = project()
        val rom = ByteArray(0x10000) { (it and 255).toByte() }
        val oldRange = AsmAssetRange("OldTiles.bin", 32, 12)
        val reference = reference().let { it.copy(metadata = it.metadata.copy(romSha256 = AsmVerifiedCache.hash(rom), romSize = rom.size, assetCount = 1)) }
        File(reference.root, "data/OldTiles.bin").writeBytes(rom.copyOfRange(32, 44))
        val first = AsmProjectWorkspaceRepository(listOf(oldRange), emptyList(), recipe).initialize(file.path, reference)
        assertTrue(first.originalDirectory.parentFile.deleteRecursively())
        assertTrue(File(reference.root, "data/OldTiles.bin").delete())
        val newRange = AsmAssetRange("NewTiles.bin", 100, 20)
        File(reference.root, "data/NewTiles.bin").writeBytes(rom.copyOfRange(100, 120))
        val updatedApp = AsmProjectWorkspaceRepository(listOf(newRange), emptyList(), recipe.copy(revision = "two"))
        val repaired = updatedApp.initializeFromRom(file.path, reference, ByteArray(512) + rom)
        assertEquals(oldRange, repaired.referenceWorkspace.index.assets.single().range)
        assertContentEquals(rom.copyOfRange(32, 44), File(repaired.originalDirectory, "data/OldTiles.bin").readBytes())
        assertEquals("one", AsmSourceBaseline.load(repaired.root)?.augmentationRevision)
    }

    @Test fun `a mismatched supplied ROM cannot publish a new frozen baseline`() {
        val file = project()
        assertFailsWith<IllegalArgumentException> { repository().initializeFromRom(file.path, reference(), ByteArray(32)) }
        assertFalse(File(temporary, "asm/base").exists())
    }

    @Test fun `a frozen project's upstream revision is independent of the app's current upstream pin`() {
        val file = project()
        val revision = "1".repeat(40)
        val reference = reference().let { it.copy(metadata = it.metadata.copy(commit = revision)) }
        val initial = repository(recipe.copy(upstreamRevision = revision)).initialize(file.path, reference)
        val loaded = assertNotNull(repository().load(file.path))
        assertEquals(revision, loaded.referenceWorkspace.metadata.commit)
        assertEquals(initial.originalDirectory, loaded.originalDirectory)
    }

    @Test fun `manifest caching detects same length external edits with preserved timestamps`() {
        val file = project()
        val workspace = repository().initialize(file.path, reference())
        val manifest = File(workspace.root, "base/manifest.json")
        val before = manifest.readText()
        val timestamp = manifest.lastModified()
        assertEquals("one", AsmSourceBaseline.load(workspace.root)?.augmentationRevision)
        val edited = before.replace("\"one\"", "\"two\"")
        assertEquals(before.length, edited.length)
        manifest.writeText(edited)
        assertTrue(manifest.setLastModified(timestamp))
        assertEquals("two", AsmSourceBaseline.load(workspace.root)?.augmentationRevision)
        assertFailsWith<IllegalArgumentException> { repository().loadForBuild(file.path) }
    }

    @Test fun `older manifest identity survives newly introduced serializer defaults without rewriting source`() {
        val file = project()
        val repo = repository()
        val workspace = repo.initialize(file.path, reference())
        val manifest = File(workspace.root, "base/manifest.json")
        val fields = Json.parseToJsonElement(manifest.readText()).jsonObject.filterKeys { it != "nativeSymbols" }.toMutableMap()
        fields["formatVersion"] = JsonPrimitive(1)
        val legacy = JsonObject(fields).toString()
        manifest.writeText(legacy)
        val identity = AsmVerifiedCache.hash(legacy)
        val metadata = File(workspace.originalDirectory.parentFile, ".smedit-project-asm.properties")
        metadata.writeText(metadata.readText().replace(Regex("(?m)^baselineId=.*$"), "baselineId=$identity"))
        val loaded = assertNotNull(AsmSourceBaseline.load(workspace.root))
        assertEquals(1, loaded.formatVersion)
        assertEquals(identity, loaded.id)
        assertNotEquals(identity, AsmVerifiedCache.hash(Json { encodeDefaults = true }.encodeToString(loaded)))
        assertNotNull(repo.loadForBuild(file.path))
        assertEquals(legacy, manifest.readText())
    }

    @Test fun `manifest identity uses exact saved bytes including formatting`() {
        val file = project()
        val workspace = repository().initialize(file.path, reference())
        val manifest = File(workspace.root, "base/manifest.json")
        val old = assertNotNull(AsmSourceBaseline.load(workspace.root)).id
        manifest.appendText("\n")
        assertEquals(AsmVerifiedCache.hash(manifest.readBytes()), AsmSourceBaseline.load(workspace.root)?.id)
        assertNotEquals(old, AsmSourceBaseline.load(workspace.root)?.id)
        assertFailsWith<IllegalArgumentException> { repository().loadForBuild(file.path) }
    }

    @Test fun `native identities and source spellings remain frozen when a newer app changes both`() {
        val file = project()
        val raw = reference()
        val src = File(raw.root, "src")
        File(src, ENEMY_HEADER_SOURCE_FILE).writeText("EnemyHeaders_Test:\n %health(15)\n")
        val reference = raw.copy(index = AsmSourceParser().parse(src, File(raw.root, "data"), emptyList()))
        val native = AsmNativeSymbol(ENEMY_HEADER_SOURCE_FILE, "EnemyHeaders_Test", 0xA0DCFF)
        val firstRecipe = recipe.copy(renames = listOf(AsmSourceRename(native.name, "CrawlerRecord")))
        val repo = AsmProjectWorkspaceRepository(emptyList(), emptyList(), firstRecipe, listOf(native))
        val workspace = repo.initialize(file.path, reference)
        val snapshot = assertNotNull(AsmSourceBaseline.load(workspace.root))
        assertEquals(listOf(native), snapshot.nativeSymbols)
        assertEquals(mapOf(0xDCFF to "CrawlerRecord"), snapshot.bindings.enemyHeaders(File(workspace.originalDirectory, "src/$ENEMY_HEADER_SOURCE_FILE").readText()))
        assertTrue(workspace.originalDirectory.parentFile.deleteRecursively())
        val updated = AsmProjectWorkspaceRepository(emptyList(), emptyList(), recipe.copy(revision = "two"), listOf(native.copy(address = 0xA0D47F)))
        val repaired = updated.initialize(file.path, reference)
        assertEquals(snapshot.id, AsmSourceBaseline.load(repaired.root)?.id)
        assertEquals(mapOf(0xDCFF to "CrawlerRecord"), AsmSourceBindings.load(repaired.originalDirectory)
            .enemyHeaders(File(repaired.originalDirectory, "src/$ENEMY_HEADER_SOURCE_FILE").readText()))
    }

    @Test fun `invalid frozen native inventories and missing format two inventories fail closed`() {
        val file = project()
        val workspace = repository().initialize(file.path, reference())
        val snapshot = assertNotNull(AsmSourceBaseline.load(workspace.root))
        val manifest = File(workspace.root, "base/manifest.json")
        for (symbols in listOf(null, listOf(AsmNativeSymbol("../outside.asm", "Valid", 0xA0DCFF)),
            listOf(AsmNativeSymbol("bank_91.asm", "Bad.Name", 0xA0DCFF)),
            listOf(AsmNativeSymbol("bank_91.asm", "Valid", -1)),
            listOf(AsmNativeSymbol("bank_91.asm", "Valid", 0xA0DCFF, listOf("Bad.Name"))),
            listOf(AsmNativeSymbol("bank_91.asm", "Valid", 0xA0DCFF, listOf("repeated", "repeated"))),
            List(2) { AsmNativeSymbol("bank_91.asm", "Repeated", 0xA0DCFF) })) {
            manifest.writeText(Json.encodeToString(snapshot.copy(nativeSymbols = symbols)))
            assertFailsWith<IllegalArgumentException> { AsmSourceBaseline.load(workspace.root) }
        }
    }

    @Test fun `repair never replaces a missing authored baseline with the app recipe`() {
        val file = project()
        val repo = repository()
        val reference = reference()
        val workspace = repo.initialize(file.path, reference)
        val (_, module) = repo.createModule(file.path, "objectives", initialSource = "Objectives:\n RTS\n")
        assertTrue(File(workspace.root, "base").deleteRecursively())
        assertFailsWith<IllegalArgumentException> { repo.initialize(file.path, reference) }
        assertTrue(workspace.originalDirectory.parentFile.deleteRecursively())
        assertFailsWith<IllegalArgumentException> {
            repo.initializeFromRom(file.path, reference, null, requireExistingBaseline = true)
        }
        assertFalse(File(workspace.root, "base").exists())
        assertEquals("Objectives:\n RTS\n", File(workspace.root, "modules/${module.removePrefix(ASM_PROJECT_MODULE_PREFIX)}").readText())
    }

    @Test fun `invalid supplied native identities are rejected before publishing a snapshot`() {
        val file = project()
        val repo = AsmProjectWorkspaceRepository(emptyList(), emptyList(), recipe,
            listOf(AsmNativeSymbol("bank_91.asm", "Timers", -1)))
        assertFailsWith<IllegalArgumentException> { repo.initialize(file.path, reference()) }
        assertFalse(File(temporary, "asm/base").exists())
    }

    @Test fun `baseline creation refreshes progressive indexes before freezing source and native identities`() {
        val file = project()
        val reference = reference()
        val source = File(reference.root, "src")
        File(source, ENEMY_HEADER_SOURCE_FILE).writeText("EnemyHeaders_Test:\n %health(15)\n")
        File(source, "main.asm").appendText("incsrc bank_A0.asm\n")
        val native = AsmNativeSymbol(ENEMY_HEADER_SOURCE_FILE, "EnemyHeaders_Test", 0xA0DCFF)
        // The reference's hint predates the new bank, as progressive startup indexes can.
        val repo = AsmProjectWorkspaceRepository(emptyList(), emptyList(), recipe, listOf(native))
        val workspace = repo.initialize(file.path, reference)
        val baseline = assertNotNull(AsmSourceBaseline.load(workspace.root))
        assertEquals(listOf(native), baseline.nativeSymbols)
        assertEquals(AsmVerifiedCache.hash(File(source, ENEMY_HEADER_SOURCE_FILE).readBytes()), baseline.upstreamSourceHashes[ENEMY_HEADER_SOURCE_FILE])
        assertEquals(mapOf(0xDCFF to "EnemyHeaders_Test"), baseline.bindings.enemyHeaders(File(workspace.originalDirectory, "src/$ENEMY_HEADER_SOURCE_FILE").readText()))
    }
}
