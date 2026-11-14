package com.supermetroid.editor.asm

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsmBuildArtifactsTest {
    @Test
    fun `post assembler safety failures have visible diagnostics without losing source errors`() {
        val directory = createTempDirectory("smedit-pipeline-report-").toFile()
        try {
            val projectFile = File(directory, "Example.smedit").apply { writeText("{}") }
            val repository = AsmBuildArtifactRepository(sourceFingerprintProvider = { "fixture" })
            repository.publishSuccess(projectFile.path, ByteArray(0), listOf("Assembly complete"), "fixture")
            repository.publishPipelineFailure(projectFile.path, "PLM list does not match the supported six-byte format")
            val failure = requireNotNull(repository.load(projectFile.path))
            assertEquals(false, failure.succeeded)
            assertTrue(failure.diagnostics.single().message.contains("six-byte format"))
            assertNull(failure.diagnostics.single().fileId, "pipeline errors must not invent source lines")
            repository.publishPipelineFailure(projectFile.path,
                "Export preview failed safely: bank_8F.asm:15740: Expected %PLMPopEntry; unsupported object source: v")
            val locatedFailure = requireNotNull(repository.load(projectFile.path)).diagnostics.single()
            assertEquals("bank_8F.asm", locatedFailure.fileId)
            assertEquals(15739, locatedFailure.lineIndex)
            assertEquals("Expected %PLMPopEntry; unsupported object source: v", locatedFailure.message)
            repository.publishFailure(projectFile.path, listOf("src/bank_90.asm:8: error: Unknown command"))
            repository.publishPipelineFailure(projectFile.path, "Compilation failed")
            val sourceError = requireNotNull(repository.load(projectFile.path)).diagnostics.single()
            assertEquals("bank_90.asm", sourceError.fileId)
            assertEquals(7, sourceError.lineIndex)
            assertEquals("Unknown command", sourceError.message)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `generated room snapshot is separate from live diagnostics and empty builds clear it`() {
        val directory = createTempDirectory("smedit-generated-report-").toFile()
        try {
            val projectFile = File(directory, "Example.smedit").apply { writeText("{}") }
            var fingerprint = "source-v1"
            val repository = AsmBuildArtifactRepository(sourceFingerprintProvider = { fingerprint })
            val source = "SmeditRoom_91F8_State0_Plms:\n    dw ${'$'}0000\n"
            repository.publishSuccess(projectFile.path, ByteArray(0), emptyList(), "fixture", source)
            val snapshot = requireNotNull(repository.loadGeneratedRooms(projectFile.path))
            assertEquals(source.lines(), snapshot.lines)
            assertTrue(snapshot.generatedAt.isNotBlank())
            fingerprint = "source-v2"
            assertNull(repository.load(projectFile.path))
            assertEquals(snapshot, repository.loadGeneratedRooms(projectFile.path), "saved source must not relabel an old snapshot as current")
            repository.publishFailure(projectFile.path, listOf("Build failed"))
            assertEquals(false, repository.load(projectFile.path)?.succeeded)
            assertEquals(snapshot, repository.loadGeneratedRooms(projectFile.path), "an earlier compilation remains inspectable after failure")
            repository.publishSuccess(projectFile.path, ByteArray(0), emptyList(), "fixture")
            assertNull(repository.loadGeneratedRooms(projectFile.path), "a no-room compilation must not retain stale generated tables")
            assertNull(repository.loadGeneratedRooms(File(directory, "Other.smedit").path))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `validated room data survives preliminary compilation and failure but can be explicitly cleared`() {
        val directory = createTempDirectory("smedit-room-data-report-").toFile()
        try {
            val file = File(directory, "Example.smedit").apply { writeText("{}") }
            val repository = AsmBuildArtifactRepository(sourceFingerprintProvider = { "fixture" })
            val text = "${AsmRoomDataSnapshot.MARKER}\nSmeditRoom_91F8_Header:\ndb ${'$'}00\n"
            repository.publishRoomData(file.path, text)
            val snapshot = requireNotNull(repository.loadRoomData(file.path))
            repository.publishSuccess(file.path, ByteArray(0), emptyList(), "fixture")
            assertEquals(snapshot, repository.loadRoomData(file.path))
            repository.publishPipelineFailure(file.path, "Failed after compilation")
            assertEquals(snapshot, repository.loadRoomData(file.path))
            repository.publishRoomData(file.path, "; Unsupported new-room project\n")
            assertNull(repository.loadRoomData(file.path))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `WLA symbols retain labels and exact source line addresses`() {
        val symbols = AsmWlaSymbolParser.parse(
            """
                ; wla symbolic information file
                [labels]
                90:8029 AnimateSamus
                7E:0AFA SamusYSpeed

                [source files]
                0000 12345678 src/main.asm
                0001 abcdef01 src/bank_90.asm

                [rom checksum]
                deadbeef

                [addr-to-line mapping]
                90:8029 0001:0000001b
                90:802c 0001:0000001c
            """.trimIndent(),
        )

        assertEquals(0x908029, symbols.labels["AnimateSamus"])
        assertEquals(0x7E0AFA, symbols.labels["SamusYSpeed"])
        assertEquals(0x908029, symbols.addressAt("bank_90.asm", 26))
        assertEquals(0x90802C, symbols.addressAt("bank_90.asm", 27))
        assertEquals(2, symbols.mappedLineCount)
        assertEquals(
            listOf(0x908029, 0x90802C),
            symbols.orderedLocations().map(AsmCompiledLocation::snesAddress),
        )
    }

    @Test
    fun `generated diagnostic input is retained separately and labeled as generated authority`() {
        val directory = createTempDirectory("smedit-generated-diagnostic-").toFile()
        try {
            val projectFile = File(directory, "Example.smedit").apply { writeText("{}") }
            val repository = AsmBuildArtifactRepository(sourceFingerprintProvider = { "fixture" })
            val generated = "; @room 91F8 Waterway\nSmeditRoom_91F8_Header:\n    db ${'$'}GG\n"
            repository.publishFailure(
                projectFile.path,
                listOf("src/__smedit_generated_rooms.asm:3: error: Invalid hex value"),
                generatedSources = mapOf("__smedit_generated_rooms.asm" to generated),
            )

            val report = requireNotNull(repository.load(projectFile.path))
            assertEquals("    db ${'$'}GG", report.generatedSources.getValue("__smedit_generated_rooms.asm")[2])
            assertTrue(generatedAsmAuthority("__smedit_generated_rooms.asm")!!.contains("Generated build input"))
            assertEquals(generated.lines(), requireNotNull(repository.loadGeneratedRooms(projectFile.path)).lines)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `Asar diagnostics expose clickable source locations`() {
        val diagnostics = AsmBuildOutputParser.diagnostics(
            listOf(
                "src/bank_90.asm:108: error: (E5117): Unknown command. [NOPE]",
                "/tmp/build/src/bank_81.asm:27: warning: (W1001): Example warning",
                "Errors were detected while assembling the patch.",
            ),
        )

        assertEquals(2, diagnostics.size)
        assertEquals(AsmDiagnosticSeverity.ERROR, diagnostics[0].severity)
        assertEquals("bank_90.asm", diagnostics[0].fileId)
        assertEquals(107, diagnostics[0].lineIndex)
        assertEquals("Unknown command. [NOPE]", diagnostics[0].message)
        assertEquals(AsmDiagnosticSeverity.WARNING, diagnostics[1].severity)
        assertEquals("bank_81.asm", diagnostics[1].fileId)
        assertTrue(diagnostics[1].message.contains("Example warning"))
    }

    @Test
    fun `pipeline checks retain explicit ASM locations across supported path formats`() {
        for (path in listOf("bank_8F.asm", "src/bank_8F.asm", "/tmp/My Project/src/bank_8F.asm",
            "C:\\My Project\\src\\bank_8F.asm")) {
            val diagnostic = AsmBuildOutputParser.diagnostics(listOf(
                "SMEDIT: error: Export preview failed safely: $path:15740: Unsupported object source: v",
            )).single()
            assertEquals("bank_8F.asm", diagnostic.fileId)
            assertEquals(15739, diagnostic.lineIndex)
            assertEquals("Unsupported object source: v", diagnostic.message)
        }
        for (message in listOf("ROM conflict at PC 0x8F15740", "bank_8F.asm:0: no valid location",
            "bank_8F.asm:99999999999999999999: invalid location")) {
            val diagnostic = AsmBuildOutputParser.pipelineDiagnostic(message)
            assertEquals(message, diagnostic.message)
            assertNull(diagnostic.fileId)
            assertNull(diagnostic.lineIndex)
        }
    }

    @Test
    fun `latest build report and symbol map round trip through the project sidecar`() {
        val directory = createTempDirectory("smedit-asm-report-").toFile()
        try {
            val projectFile = File(directory, "Example.smedit").apply { writeText("{}") }
            var sourceFingerprint = "source-v1"
            val repository = AsmBuildArtifactRepository(sourceFingerprintProvider = { sourceFingerprint })
            repository.publishSuccess(
                projectFilePath = projectFile.absolutePath,
                symbols = """
                    [labels]
                    90:8029 AnimateSamus
                    [source files]
                    0001 abcdef01 src/bank_90.asm
                    [rom checksum]
                    deadbeef
                    [addr-to-line mapping]
                    90:8029 0001:0000001b
                """.trimIndent().toByteArray(),
                output = listOf("Assembly complete"),
                assemblerVersion = "Asar 1.81",
            )

            val report = requireNotNull(repository.load(projectFile.absolutePath))
            assertTrue(report.succeeded)
            assertEquals("Asar 1.81", report.assemblerVersion)
            assertEquals(listOf("Assembly complete"), report.output)
            assertEquals(0x908029, report.symbols.addressAt("bank_90.asm", 26))

            sourceFingerprint = "source-v2"
            assertNull(repository.load(projectFile.absolutePath), "feedback from older source must be ignored")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `source validation rejects silently truncated data literals`() {
        val diagnostics = AsmSourceLinter.lintText(
            "bank_90.asm",
            """
                dw ${'$'}9000000900*!SPF*${'$'}100,${'$'}0280
                db ${'$'}123 & ${'$'}FF
                dl ${'$'}7E2000
                db ${'$'}04,${'$'}04 : dw ${'$'}0000
            """.trimIndent(),
        )

        assertEquals(1, diagnostics.size)
        assertEquals(0, diagnostics.single().lineIndex)
        assertTrue(diagnostics.single().message.contains("does not fit dw"))
        assertTrue(diagnostics.single().message.contains("silently keep only the low 16 bits"))
    }

    @Test
    fun `source validation ignores disabled project modules`() {
        val directory = createTempDirectory("smedit-disabled-module-lint-").toFile()
        try {
            val sourceRoot = File(directory, "src").apply { mkdirs() }
            File(sourceRoot, "bank_80.asm").writeText("dw ${'$'}1234\n")
            File(sourceRoot, "project/unfinished.asm").apply {
                parentFile.mkdirs()
                writeText("dw ${'$'}123456\n")
            }

            assertEquals(
                emptyList(),
                AsmSourceLinter.lintTree(
                    sourceRoot,
                    excludedFileIds = setOf("project/unfinished.asm"),
                ),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `source validation checks complete macro calls without guessing at incomplete edits`() {
        val macros = listOf(
            AsmMacro("Pair", listOf("left", "right"), false, "macros.asm", 0),
            AsmMacro("Many", listOf("first", "..."), true, "macros.asm", 4),
        )
        val diagnostics = AsmSourceLinter.lintText(
            "project/example.asm",
            """
                %Pair(${ '$' }01, function(${ '$' }02, ${ '$' }03))
                %Pair(${ '$' }01)
                %Many()
                %Missing(${ '$' }00)
                %StillTyping(
                db "%Missing(${ '$' }00)"
            """.trimIndent(),
            macros,
        )

        assertEquals(3, diagnostics.size)
        assertTrue(diagnostics[0].message.contains("expects 2 arguments; found 1"))
        assertTrue(diagnostics[1].message.contains("expects at least 1 argument"))
        assertTrue(diagnostics[2].message.contains("Unknown macro %Missing"))
        assertEquals(listOf(1, 2, 3), diagnostics.mapNotNull(AsmBuildDiagnostic::lineIndex))
    }

    @Test
    fun `pinned source has no unsafe data literal diagnostics when local fixture exists`() {
        val cursor: File? = File(System.getProperty("user.dir")).absoluteFile
        val sourceRoot = generateSequence(cursor) { it.parentFile }
            .take(6)
            .map { root ->
                File(
                    root,
                    "parity/work/sm_disassembly/src",
                )
            }
            .firstOrNull(File::isDirectory)
            ?: return

        val diagnostics = AsmSourceLinter.lintTree(sourceRoot)
        assertEquals(emptyList(), diagnostics.filter { it.severity == AsmDiagnosticSeverity.ERROR })
        diagnostics.forEach { diagnostic ->
            assertTrue(diagnostic.severity == AsmDiagnosticSeverity.WARNING,
                "Pinned-source inspections may expose review warnings but must not invent build-blocking errors: $diagnostic")
        }
    }
}
