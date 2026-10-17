package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.TestRomHelper
import java.io.File

/** A disposable project; no tests read or publish build artifacts into a user's hack. */
internal data class AsmProjectTestFixture(
    val projectFile: File,
    val romFile: File,
    val rom: ByteArray,
    val workspace: AsmProjectBuildWorkspace,
) {
    companion object {
        /** Full build pipeline using a copyright-free inert test ROM. */
        fun synthetic(directory: File, rom: ByteArray): AsmProjectTestFixture {
            val root = File(directory, "synthetic-reference")
            val src = File(root, "src").apply { mkdirs() }
            val data = File(root, "data").apply { mkdirs() }
            File(data, "base.bin").writeBytes(rom)
            val assets = AsmAssetManifest.loadBundled()
            assets.forEach { asset -> File(data, asset.path).apply { parentFile.mkdirs(); writeBytes(rom.copyOfRange(asset.pcOffset, asset.endExclusive)) } }
            File(src, "main.asm").writeText(buildString {
                appendLine("lorom")
                for (offset in rom.indices step 0x8000) {
                    appendLine("org ${'$'}${((0x80 + offset / 0x8000) * 0x10000 + 0x8000).toString(16).uppercase()}")
                    appendLine("incbin \"../data/base.bin\":${offset.toString(16)}-${(offset + 0x8000).toString(16)}")
                }
                appendLine("incsrc \"macros.asm\"\nincsrc \"bank_8F.asm\"\nincsrc \"bank_83.asm\"")
            })
            File(src, "macros.asm").writeText("macro PLMPopEntry(ID,X,Y,Param)\n dw <ID>\n db <X>,<Y>\n dw <Param>\nendmacro\n")
            File(src, PLM_SOURCE_FILE).writeText("; Native data comes from the inert test ROM.\n")
            File(src, DOOR_SOURCE_FILE).writeText("; Shared and ordinary authored doors.\n")
            val reference = AsmReferenceWorkspace(root, AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL,
                AsmReferenceContract.COMMIT, "synthetic", "fixture.sfc", AsmVerifiedCache.hash(rom), rom.size, assets.size),
                AsmSourceParser().parse(src, data, assets))
            val file = File(directory, "Synthetic.smedit").apply { writeText("{}") }
            val romFile = File(directory, "Synthetic.sfc").apply { writeBytes(rom) }
            val repository = AsmProjectWorkspaceRepository(starterModules = emptyList())
            repository.initialize(file.path, reference)
            return AsmProjectTestFixture(file, romFile, rom, requireNotNull(repository.loadForBuild(file.path)))
        }

        fun create(directory: File): AsmProjectTestFixture {
            val rom = TestRomHelper.requireRomBytes()
            val checkout = TestRomHelper.requireDisassemblyDir()
            val referenceRoot = File(directory, "reference")
            File(checkout, "src").copyRecursively(File(referenceRoot, "src"))
            val assets = AsmAssetManifest.loadBundled()
            assets.forEach { asset ->
                File(referenceRoot, "data/${asset.path}").apply {
                    parentFile.mkdirs()
                    writeBytes(rom.copyOfRange(asset.pcOffset, asset.endExclusive))
                }
            }
            val reference = AsmReferenceWorkspace(
                referenceRoot,
                AsmReferenceMetadata(
                    AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT,
                    "test-fixture", "fixture.sfc", TestRomHelper.sha256(rom), rom.size, assets.size,
                ),
                AsmSourceParser().parse(File(referenceRoot, "src"), File(referenceRoot, "data"), assets),
            )
            val projectFile = File(directory, "Fixture.smedit").apply { writeText("{}") }
            val romFile = File(directory, "Fixture.sfc").apply { writeBytes(rom) }
            val repository = AsmProjectWorkspaceRepository(starterModules = emptyList())
            repository.initialize(projectFile.absolutePath, reference)
            val workspace = requireNotNull(repository.loadForBuild(projectFile.absolutePath))
            return AsmProjectTestFixture(projectFile, romFile, rom, workspace)
        }
    }
}
