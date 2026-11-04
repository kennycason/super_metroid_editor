package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomWriteConflictException
import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlan
import com.supermetroid.editor.rom.RomConstants
import java.io.File
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AsmProjectCompilerTest {
    @TempDir
    lateinit var tempDirectory: File

    @Test
    fun `managed Asar cleanup removes read only git packs and preserves sibling installations`() {
        val parent = File(tempDirectory, "asm").apply { mkdirs() }
        val staging = File(parent, "asar.installing-test")
        val packs = File(staging, ".git/objects/pack").apply { mkdirs() }
        val readOnlyFiles = listOf("pack", "idx", "rev").map { extension ->
            File(packs, "pack-test.$extension").apply {
                writeText("synthetic git pack")
                assertTrue(setReadOnly(), "Could not mark $name read-only")
            }
        }
        val installed = File(parent, "asar/keep.txt").apply { parentFile.mkdirs(); writeText("installed toolchain") }
        try {
            val toolchain = AsmToolchain()
            toolchain.deleteManagedTree(parent, staging)
            assertFalse(staging.exists())
            assertEquals("installed toolchain", installed.readText())
            toolchain.deleteManagedTree(parent, staging)
        } finally {
            readOnlyFiles.filter(File::exists).forEach { it.setWritable(true) }
        }
    }

    @Test
    fun `managed Asar cleanup refuses its parent and paths outside it`() {
        val parent = File(tempDirectory, "asm").apply { mkdirs() }
        val outside = File(tempDirectory, "unrelated").apply { mkdirs() }
        val sentinel = File(outside, "keep.txt").apply { writeText("unrelated data") }
        val toolchain = AsmToolchain()
        for (target in listOf(parent, outside)) {
            assertFailsWith<IllegalArgumentException> { toolchain.deleteManagedTree(parent, target) }
        }
        assertTrue(parent.isDirectory)
        assertEquals("unrelated data", sentinel.readText())
    }

    @Test
    fun `source ownership ranges are contiguous minimal and deterministic`() {
        val reference = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        val project = byteArrayOf(9, 1, 8, 8, 4, 5, 0, 7)

        assertEquals(
            listOf(
                AsmSourceOwnedRange(0, 1),
                AsmSourceOwnedRange(2, 2),
                AsmSourceOwnedRange(6, 1),
            ),
            AsmProjectCompiler.changedRanges(reference, project),
        )
    }

    @Test
    fun `generated asset offsets are excluded from authored source ownership`() {
        val reference = byteArrayOf(0, 1, 2, 3, 4, 5)
        val project = byteArrayOf(9, 8, 7, 6, 4, 5)

        assertEquals(
            listOf(AsmSourceOwnedRange(0, 1), AsmSourceOwnedRange(3, 1)),
            AsmProjectCompiler.changedRanges(reference, project, listOf(1..2)),
        )
    }

    @Test
    fun `compiled source ownership rejects a later incompatible patch`() {
        val plan = RomWritePlan(byteArrayOf(0, 1, 2, 3))
        plan.claimCurrentRange(
            owner = "asm-source:project",
            label = "Compiled source",
            offset = 1,
            size = 1,
            kind = RomWriteKind.ASM_SOURCE,
        )

        val conflict = assertFailsWith<RomWriteConflictException> {
            plan.add(
                owner = "patch:example",
                label = "Example patch",
                offset = 1,
                bytes = listOf(0xFF),
                kind = RomWriteKind.FIXED_PATCH,
            )
        }

        assertTrue(conflict.message.orEmpty().contains("asm-source:project"))
        assertEquals(1, plan.romData[1].toInt())
    }

    @Test
    fun `project module gateway preserves explicit order without editing authored main source`() {
        val source = File(tempDirectory, "src").apply { mkdirs() }
        val main = File(source, "main.asm").apply {
            writeText("incsrc \"bank_80.asm\"\nprint \"Assembly complete. Total bytes written: \", bytes\n")
        }
        val project = File(source, "project").apply { mkdirs() }
        File(project, "later.asm").writeText("Later:\n    RTS\n")
        File(project, "first.asm").writeText("First:\n    RTS\n")
        File(project, ASM_PROJECT_MODULE_ORDER_FILE).writeText("first.asm\nlater.asm\n")

        assertEquals(
            listOf("project/first.asm", "project/later.asm"),
            AsmProjectModuleGateway.apply(source),
        )

        val gateway = File(source, "__smedit_project_modules.asm").readText()
        assertTrue(gateway.indexOf("project/first.asm") < gateway.indexOf("project/later.asm"))
        val generatedMain = main.readText()
        assertTrue(generatedMain.indexOf("__smedit_project_modules.asm") < generatedMain.indexOf("Assembly complete"))
    }

    @Test
    fun `project module gateway omits disabled modules without deleting their source`() {
        val source = File(tempDirectory, "disabled-src").apply { mkdirs() }
        val main = File(source, "main.asm").apply {
            writeText("incsrc \"bank_80.asm\"\nprint \"Assembly complete. Total bytes written: \", bytes\n")
        }
        val project = File(source, "project").apply { mkdirs() }
        File(project, "enabled.asm").writeText("Enabled:\n    RTS\n")
        File(project, "unfinished.asm").writeText("this is intentionally invalid\n")
        File(project, ASM_PROJECT_MODULE_ORDER_FILE).writeText("enabled.asm\nunfinished.asm\n")
        File(project, ASM_PROJECT_MODULE_DISABLED_FILE).writeText("unfinished.asm\n")

        assertEquals(listOf("project/enabled.asm"), AsmProjectModuleGateway.apply(source))

        val gateway = File(source, "__smedit_project_modules.asm").readText()
        assertTrue(gateway.contains("project/enabled.asm"))
        assertFalse(gateway.contains("project/unfinished.asm"))
        assertTrue(File(project, "unfinished.asm").isFile)
        assertTrue(main.readText().contains("__smedit_project_modules.asm"))
    }

    @Test
    fun `module source map gives its changed bytes a specific owner`() {
        val symbols = """
            [source files]
            0001 0 src/project/jump_hook.asm
            0002 0 src/bank_90.asm
            [addr-to-line mapping]
            90:9EB9 0001:00000001
            90:9EC0 0002:00000001
            [rom checksum]
        """.trimIndent().toByteArray()
        val start = checkNotNull(snesLoRomToPc(0x909EB9))

        val attributed = AsmProjectCompiler.attributeSourceFiles(
            ranges = listOf(AsmSourceOwnedRange(start + 1, 2)),
            symbols = symbols,
        ).single()

        assertEquals("project/jump_hook.asm", attributed.sourceFileId)
        assertEquals("asm-module:jump_hook", attributed.owner)
        assertEquals("Project module jump_hook.asm", attributed.label)
    }

    @Test
    fun `bundled Asar fails closed when its package checksum is wrong`() {
        val osName = System.getProperty("os.name")
        val executableName = if (osName.contains("Windows", ignoreCase = true)) {
            "asar-standalone.exe"
        } else {
            "asar-standalone"
        }
        val resources = File(tempDirectory, "resources")
        val toolDirectory = File(resources, "tools/asar/1.81").apply { mkdirs() }
        File(toolDirectory, executableName).writeBytes(byteArrayOf(1, 2, 3, 4))
        File(toolDirectory, "SHA256").writeText("${"0".repeat(64)}  $executableName\n")
        val toolchain = AsmToolchain(
            homeDirectory = File(tempDirectory, "home"),
            environment = emptyMap(),
            systemProperties = mapOf(
                "compose.application.resources.dir" to resources.absolutePath,
                "os.name" to osName,
                "os.arch" to System.getProperty("os.arch"),
                "user.dir" to tempDirectory.absolutePath,
            ),
        )

        val failure = assertFailsWith<AsmCompilationException> {
            toolchain.resolve(File(tempDirectory, "reference"))
        }
        assertTrue(failure.message.orEmpty().contains("SHA-256"))
    }

    @Test
    fun `saved jump edit compiles with separate authored asset expanded room and patch ownership`() {
        val fixture = AsmProjectTestFixture.create(tempDirectory)
        val projectFile = fixture.projectFile
        val sourceRoot = File(fixture.workspace.workingDirectory, "src")
        val originalSource = File(sourceRoot, "bank_90.asm").readText()
        val editedSource = originalSource.replace("dw \$04E0*!SPF", "dw \$0600*!SPF")
        assertTrue(editedSource != originalSource)
        AsmProjectWorkspaceRepository().saveSource(projectFile.absolutePath, "bank_90.asm", editedSource)
        val loadedRom = fixture.rom
        val headerSize = if (loadedRom.size % 0x8000 == 0x200) 0x200 else 0

        val compiled = AsmProjectCompiler().compile(
            projectFile.absolutePath,
            loadedRom,
            outputRomSize = RomConstants.EXPANDED_ROM_SIZE,
        )
        val ownedOffsets = compiled.sourceOwnedRanges.flatMap { range ->
            (range.pcOffset until range.pcOffset + range.length).toList()
        }.toSet()

        assertTrue(0x081EB9 in ownedOffsets, "normal jump Y speed must be source-owned")
        assertTrue(0x081EC0 in ownedOffsets, "normal jump sub-speed high byte must be source-owned")
        assertTrue(compiled.assemblerVersion.contains("Asar 1.81"))

        val workspace = requireNotNull(AsmProjectWorkspaceRepository().loadForBuild(projectFile.absolutePath))
        val asset = AsmAssetManifest.loadBundled().firstOrNull { range ->
            range.length > 0 && File(workspace.workingDirectory, "data/${range.path}").isFile
        } ?: error("Fixture has no source assets")
        val assetFile = File(workspace.workingDirectory, "data/${asset.path}")
        val override = assetFile.readBytes()
        override[0] = (override[0].toInt() xor 1).toByte()
        val claim = AsmGeneratedAssetClaim(
            owner = "asm-asset:${asset.path}",
            label = "Compiler integration asset",
            assetPath = asset.path,
            pcOffset = asset.pcOffset,
            bytes = byteArrayOf(override[0]),
        )
        val roomPc = 0x300100
        val roomByte = 0x5A.toByte()
        val roomClaim = AsmGeneratedRoomClaim(
            owner = ASM_ROOM_GRAPH_OWNER,
            label = "Compiler integration room graph",
            pcOffset = roomPc,
            bytes = byteArrayOf(roomByte),
        )
        val roomSnes = checkNotNull(pcToSnesLoRom(roomPc))
        val materialized = AsmProjectCompiler().compile(
            projectFile.absolutePath,
            loadedRom,
            AsmAssetMaterialization(
                assetOverrides = mapOf(asset.path to override),
                claims = listOf(claim),
                eligibleWrites = 1,
                skippedWrites = 0,
            ),
            patchMaterialization = run {
                val patchPc = 0x081EA2
                val patchByte = ((loadedRom[headerSize + patchPc].toInt() and 0xFF) xor 1).toByte()
                val patchClaim = AsmGeneratedPatchClaim(
                    owner = "patch:compiler-test",
                    label = "Compiler patch overlay",
                    pcOffset = patchPc,
                    bytes = byteArrayOf(patchByte),
                )
                val patchSnes = checkNotNull(pcToSnesLoRom(patchPc))
                AsmPatchMaterialization(
                    source = "org \$${patchSnes.toString(16).uppercase()}\ndb \$${(patchByte.toInt() and 0xFF).toString(16).uppercase()}\n",
                    claims = listOf(patchClaim),
                    eligibleWrites = 1,
                    skippedWrites = 0,
                )
            },
            roomMaterialization = AsmRoomMaterialization(
                source = "org \$${roomSnes.toString(16).uppercase()}\ndb \$5A\n",
                claims = listOf(roomClaim),
                roomIds = setOf("91F8"),
                eligibleWrites = 1,
                skippedWrites = 0,
            ),
            referenceRomBody = compiled.referenceRomBody,
            outputRomSize = RomConstants.EXPANDED_ROM_SIZE,
        )
        assertEquals(headerSize + RomConstants.EXPANDED_ROM_SIZE, materialized.romBytes.size)
        assertEquals(compiled.referenceRomSha256, materialized.referenceRomSha256)
        assertEquals(override[0], materialized.romBytes[headerSize + asset.pcOffset])
        assertEquals(listOf(claim), materialized.generatedAssetClaims)
        assertTrue(materialized.sourceOwnedRanges.none { asset.pcOffset in it.pcOffset until it.pcOffset + it.length })
        assertEquals(1, materialized.generatedPatchClaims.size)
        val patchClaim = materialized.generatedPatchClaims.single()
        assertEquals(patchClaim.bytes[0], materialized.romBytes[headerSize + patchClaim.pcOffset])
        assertTrue(materialized.sourceOwnedRanges.none {
            patchClaim.pcOffset in it.pcOffset until it.pcOffset + it.length
        })
        assertEquals(roomByte, materialized.romBytes[headerSize + roomPc])
        assertEquals(listOf(roomClaim), materialized.generatedRoomClaims)
        assertEquals(setOf("91F8"), materialized.generatedRoomIds)
        assertTrue(materialized.sourceOwnedRanges.none {
            roomPc in it.pcOffset until it.pcOffset + it.length
        })
    }

}
