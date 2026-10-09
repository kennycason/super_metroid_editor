package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.AsmCompiledBuildBase
import com.supermetroid.editor.asm.AsmCompilationException
import com.supermetroid.editor.asm.AsmProjectCompiler
import com.supermetroid.editor.asm.AsmPatchBackendRegistry
import com.supermetroid.editor.asm.AsmProjectWorkspaceRepository
import com.supermetroid.editor.asm.AsmSourceAssetMaterializer
import com.supermetroid.editor.asm.AsmSourcePatchMaterializer
import com.supermetroid.editor.asm.copyForReuse
import com.supermetroid.editor.data.PatternLibrary
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmEditProjectFormat
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TileGraphics
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

internal object ProjectFileService {
    private val asmProjectCompiler = AsmProjectCompiler()
    private const val PREPARED_ASM_CACHE_VERSION = "smedit-prepared-asm-base-v1"
    private const val MAX_PREPARED_ASM_BASES = 2
    private val preparedAsmBases = object : LinkedHashMap<String, AsmCompiledBuildBase>(
        MAX_PREPARED_ASM_BASES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, AsmCompiledBuildBase>?,
        ): Boolean = size > MAX_PREPARED_ASM_BASES
    }
    private val json = Json {
        // Project files can contain hundreds of thousands of generated tile edits.
        // Keep saves compact; use jq or an editor formatter when human-readable JSON is needed.
        prettyPrint = false
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    fun loadProject(file: File): SmEditProject =
        SmEditProjectFormat.decode(json, file.readText()).also { project ->
            // Markerless beta files use the same semantic model with defaulted fields.
            // Normalize the in-memory project so its next save writes the current schema.
            project.projectFormatVersion = SmEditProject.CURRENT_PROJECT_FORMAT_VERSION
        }

    /** Isolate non-writing previews from exporter metadata hydration. */
    fun snapshotProject(project: SmEditProject): SmEditProject =
        SmEditProjectFormat.decode(json, json.encodeToString(SmEditProject.serializer(), project))

    fun saveProject(
        project: SmEditProject,
        projectFilePath: String,
        romParser: RomParser?,
        savePatternLibrary: Boolean,
        onLog: (String) -> Unit,
    ): Boolean {
        if (projectFilePath.isEmpty()) return false
        return try {
            val emptyKeys = project.rooms.entries.filter { !it.value.hasEdits }.map { it.key }
            for (key in emptyKeys) project.rooms.remove(key)

            val projectFile = File(projectFilePath)
            if (projectFile.isFile) {
                val existingVersion = runCatching {
                    SmEditProjectFormat.decode(json, projectFile.readText()).projectFormatVersion
                }.getOrNull()
                if (existingVersion != null && existingVersion < project.projectFormatVersion) {
                    val backup = File(projectFile.parentFile, "${projectFile.name}.before-schema-upgrade.backup")
                    if (!backup.exists()) {
                        projectFile.copyTo(backup, overwrite = false)
                        onLog("Backed up project before schema upgrade: ${backup.absolutePath}")
                    }
                }
            }
            projectFile.writeText(json.encodeToString(SmEditProject.serializer(), project))
            onLog("Project saved: $projectFilePath")
            romParser?.let { exportCustomGfxPngs(project, projectFilePath, it, onLog) }
            if (savePatternLibrary) PatternLibrary.saveAll(project.patterns)
            true
        } catch (e: Exception) {
            onLog("Save failed: ${e.message}")
            false
        }
    }

    fun exportToRom(
        project: SmEditProject,
        romParser: RomParser,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        projectFilePath: String = "",
    ): String? = try {
        val compiledAsmBase = prepareBuildBase(project, projectFilePath, romParser, onLog, onStatus)
        RomExporter(project, romParser, onLog, onStatus, compiledAsmBase = compiledAsmBase).export()
    } catch (e: Exception) {
        val message = "Export failed safely: ${e.message ?: e::class.simpleName}"
        onLog("ERROR: $message")
        onStatus(message)
        null
    }

    fun buildRom(
        project: SmEditProject,
        romParser: RomParser,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        projectFilePath: String = "",
    ): RomBuildResult? = try {
        val compiledAsmBase = prepareBuildBase(project, projectFilePath, romParser, onLog, onStatus)
        RomExporter(
            project = project,
            romParser = romParser,
            onLog = onLog,
            onStatus = onStatus,
            logWritePlanDetails = false,
            compiledAsmBase = compiledAsmBase,
        ).build()
    } catch (e: Exception) {
        val message = "Export preview failed safely: ${e.message ?: e::class.simpleName}"
        onLog("ERROR: $message")
        onStatus(message)
        null
    }

    fun exportToIps(
        project: SmEditProject,
        romParser: RomParser,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
        projectFilePath: String = "",
    ): String? {
        val original = romParser.getRomData()
        val smcPath = exportToRom(project, romParser, onLog, onStatus, projectFilePath) ?: return null
        val patched = File(smcPath).readBytes()
        val ipsData = buildIpsPatch(original, patched)
        val orig = File(project.romPath)
        val version = "v${project.versionMajor}.${project.versionMinor}"
        val build = project.buildName.trim()
        val suffix = if (build.isNotEmpty()) "$build-$version" else version
        val ipsFile = File(orig.parent, "${orig.nameWithoutExtension}-$suffix.ips")
        try {
            writeBytesAtomically(ipsFile, ipsData)
        } catch (e: Exception) {
            val message = "Export failed safely while writing IPS: ${e.message ?: e::class.simpleName}"
            onLog("ERROR: $message")
            onStatus(message)
            return null
        }
        val message = "Exported IPS: ${ipsFile.absolutePath} (${ipsData.size} bytes)"
        onLog(message)
        onStatus(message)
        return ipsFile.absolutePath
    }

    private fun prepareBuildBase(
        project: SmEditProject,
        projectFilePath: String,
        romParser: RomParser,
        onLog: (String) -> Unit,
        onStatus: (String) -> Unit,
    ): AsmCompiledBuildBase? {
        if (project.asmWorkspace.buildMode == ProjectRomBuildMode.PATCHED_ROM) {
            val sourceEdits = if (project.asmWorkspace.enabled && projectFilePath.isNotBlank()) {
                AsmProjectWorkspaceRepository().sourceOverrideFileIds(projectFilePath)
            } else {
                emptySet()
            }
            require(sourceEdits.isEmpty()) {
                "Loaded ROM mode cannot ignore saved ASM source edits (${sourceEdits.sorted().joinToString()}). " +
                    "Restore those files to their original source or select ASM source mode."
            }
            return null
        }
        require(project.asmWorkspace.enabled) { "Enable Project ASM before selecting the ASM source build" }
        require(projectFilePath.isNotBlank()) { "Save the SMEDIT project before building from ASM source" }
        onLog("[ASM-BUILD] Preparing clean source build for $projectFilePath")
        val compiler = asmProjectCompiler
        val loadedRom = romParser.copyRomData()
        val sourceInputs = compiler.sourceInputsFingerprint(projectFilePath, loadedRom) { progress ->
            onLog("[ASM-BUILD] $progress")
            onStatus(progress)
        }
        val preparedKey = preparedAsmCacheKey(project, sourceInputs)
        getPreparedAsmBase(preparedKey)?.let { cached ->
            val message = "ASM/project inputs unchanged — reusing prepared source base…"
            onLog("[ASM-BUILD] $message")
            onStatus(message)
            return cached
        }
        val initial = compiler.compile(
            projectFilePath = projectFilePath,
            loadedRom = loadedRom,
        ) { progress ->
            onLog("[ASM-BUILD] $progress")
            onStatus(progress)
        }
        onLog("[ASM-BUILD] Planning editor-owned source assets…")
        val planningLog = mutableListOf<String>()
        val planned = RomExporter(
            project = project,
            romParser = romParser,
            onLog = planningLog::add,
            onStatus = {},
            logWritePlanDetails = false,
            compiledAsmBase = initial,
        ).build() ?: throw AsmCompilationException(
            planningLog.lastOrNull { it.startsWith("ERROR:") }
                ?.removePrefix("ERROR: ")
                ?: "Could not validate editor data against the compiled ASM source"
        )
        val materialization = AsmSourceAssetMaterializer().materialize(projectFilePath, planned.writeReport)
        val patchMaterialization = AsmSourcePatchMaterializer().materialize(
            report = planned.writeReport,
            sourceOwners = AsmPatchBackendRegistry.sourceOwners(project.patches),
            compiledRomSize = AsmProjectCompiler.ASM_ROM_SIZE,
        )
        val compiled = if (materialization.isEmpty && patchMaterialization.isEmpty) {
            onLog(
                "[ASM-BUILD] No fixed source assets or source-capable patches required generation " +
                    "(${materialization.skippedWrites}/${materialization.eligibleWrites} asset candidate writes " +
                    "remain post-compile)"
            )
            initial
        } else {
            val generatedBytes = materialization.claims.sumOf { it.length }
            val generatedPatchBytes = patchMaterialization.claims.sumOf { it.length }
            if (!materialization.isEmpty) {
                onLog(
                    "[ASM-BUILD] Staging ${materialization.assetOverrides.size} source asset(s): " +
                        "$generatedBytes byte(s) across ${materialization.claims.size} range(s); " +
                        "${materialization.skippedWrites} candidate write(s) remain post-compile"
                )
            }
            if (!patchMaterialization.isEmpty) {
                onLog(
                    "[ASM-BUILD] Selected ASM backend for ${patchMaterialization.claims.map { it.owner }.distinct().size} " +
                        "patch(es): $generatedPatchBytes byte(s) across ${patchMaterialization.claims.size} range(s); " +
                        "${patchMaterialization.skippedWrites} write(s) remain on the ROM backend"
                )
            }
            val materializedCompile = compiler.compile(
                projectFilePath = projectFilePath,
                loadedRom = loadedRom,
                materialization = materialization,
                patchMaterialization = patchMaterialization,
                referenceRomBody = initial.referenceRomBody,
            ) { progress ->
                onLog("[ASM-BUILD] $progress")
                onStatus(progress)
            }
            materializedCompile.copy(
                compilerOutput = (initial.compilerOutput + materializedCompile.compilerOutput).distinct(),
                expectedFinalRomSha256 = bytesSha256(planned.resultRom),
            )
        }
        compiled.compilerOutput
            .filter { it.contains("warn", ignoreCase = true) || it.contains("error", ignoreCase = true) }
            .forEach { onLog("[ASAR] $it") }
        onLog(
            "[ASM-BUILD] Source base ready: ${compiled.sourceOwnedRanges.sumOf { it.length }} changed byte(s) " +
                "across ${compiled.sourceOwnedRanges.size} authored range(s), " +
                "${compiled.generatedAssetClaims.sumOf { it.length }} generated asset byte(s), " +
                "${compiled.generatedPatchClaims.sumOf { it.length }} ASM patch byte(s)"
        )
        putPreparedAsmBase(preparedKey, compiled)
        // The exporter may hydrate deterministic patch safety metadata on its
        // isolated project snapshot. Accept that equivalent post-plan snapshot
        // too, without allowing unrelated project changes to hit this entry.
        val hydratedPreparedKey = preparedAsmCacheKey(project, sourceInputs)
        if (hydratedPreparedKey != preparedKey) putPreparedAsmBase(hydratedPreparedKey, compiled)
        return compiled
    }

    private fun preparedAsmCacheKey(project: SmEditProject, sourceInputs: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun update(value: ByteArray) {
            val size = value.size
            digest.update((size ushr 24).toByte())
            digest.update((size ushr 16).toByte())
            digest.update((size ushr 8).toByte())
            digest.update(size.toByte())
            digest.update(value)
        }
        update(PREPARED_ASM_CACHE_VERSION.toByteArray())
        update(sourceInputs.toByteArray())
        update(json.encodeToString(SmEditProject.serializer(), project).toByteArray())
        return digest.digest().joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    @Synchronized
    private fun getPreparedAsmBase(key: String): AsmCompiledBuildBase? =
        preparedAsmBases[key]?.copyForReuse()

    @Synchronized
    private fun putPreparedAsmBase(key: String, base: AsmCompiledBuildBase) {
        preparedAsmBases[key] = base.copyForReuse()
    }

    @Synchronized
    internal fun clearAsmBuildCachesForTests() {
        preparedAsmBases.clear()
        AsmProjectCompiler.clearBuildCacheForTests()
    }

    private fun exportCustomGfxPngs(
        project: SmEditProject,
        projectFilePath: String,
        romParser: RomParser,
        onLog: (String) -> Unit,
    ) {
        val gfx = project.customGfx
        val hasVar = gfx.varGfx.isNotEmpty()
        val hasCre = gfx.creGfx != null
        if (!hasVar && !hasCre) return

        val projectFile = File(projectFilePath)
        val folder = File(projectFile.parentFile, "${projectFile.nameWithoutExtension}_smedit")
        folder.mkdirs()
        val tileGraphics = TileGraphics(romParser)

        for ((tilesetId, base64) in gfx.varGfx) {
            if (!tileGraphics.loadTileset(tilesetId.toIntOrNull() ?: continue)) continue
            try {
                tileGraphics.applyCustomVarGfx(java.util.Base64.getDecoder().decode(base64))
                val result = tileGraphics.renderTileSheet(0, tileGraphics.getVarTileCount())
                if (result != null) {
                    val (pixels, width, height) = result
                    val out = File(folder, "ure_$tilesetId.png")
                    if (writePng(out.absolutePath, pixels, width, height)) onLog("Exported $out")
                }
            } catch (_: Exception) {
            }
        }

        if (hasCre && gfx.creGfx != null && tileGraphics.loadTileset(0)) {
            try {
                tileGraphics.applyCustomCreGfx(java.util.Base64.getDecoder().decode(gfx.creGfx))
                val result = tileGraphics.renderTileSheet(tileGraphics.getCreOffset(), tileGraphics.getCreTileCount())
                if (result != null) {
                    val (pixels, width, height) = result
                    val out = File(folder, "cre.png")
                    if (writePng(out.absolutePath, pixels, width, height)) onLog("Exported $out")
                }
            } catch (_: Exception) {
            }
        }
    }
}
