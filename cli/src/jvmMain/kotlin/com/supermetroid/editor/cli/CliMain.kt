package com.supermetroid.editor.cli

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.headless.SmeditBuildReport
import com.supermetroid.editor.headless.SmeditBuildRequest
import com.supermetroid.editor.headless.SmeditBuildService
import com.supermetroid.editor.headless.SmeditColorizeRequest
import com.supermetroid.editor.headless.SmeditPatchCatalog
import com.supermetroid.editor.rom.RomParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.system.exitProcess

private val jsonPretty = Json { prettyPrint = true }
private val jsonCompact = Json { prettyPrint = false }
private val jsonInput = Json { ignoreUnknownKeys = true }

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        exitProcess(1)
    }

    var romPath: String? = null
    var compact = false
    var command: String? = null
    val commandArgs = mutableListOf<String>()

    val iter = args.iterator()
    while (iter.hasNext()) {
        val arg = iter.next()
        when {
            arg == "--rom" && iter.hasNext() -> romPath = iter.next()
            arg == "--compact" -> compact = true
            arg == "--help" || arg == "-h" -> { printUsage(); return }
            command == null -> command = arg
            else -> commandArgs.add(arg)
        }
    }

    if (romPath == null && command != "build" && command != "patches" && command != "schemas" && command != "schema") {
        System.err.println("Error: --rom <path> is required")
        exitProcess(1)
    }
    if (command == null) {
        System.err.println("Error: no command specified")
        printUsage()
        exitProcess(1)
    }

    val json = if (compact) jsonCompact else jsonPretty
    try {
        when (command) {
            "rooms", "room", "graph", "export" -> {
                val requiredRomPath = romPath ?: error("ROM path was already validated")
                val parser = RomParser.loadRom(requiredRomPath)
                val repo = RoomRepository()
                val roomExporter = RoomExporter(parser, repo)
                when (command) {
                    "rooms" -> cmdRooms(roomExporter, json)
                    "room" -> cmdRoom(roomExporter, json, commandArgs)
                    "graph" -> cmdGraph(roomExporter, json)
                    "export" -> cmdExport(roomExporter, json, commandArgs)
                }
            }
            "build" -> cmdBuild(json, romPath, commandArgs)
            "patches" -> cmdPatches(json)
            "schemas" -> cmdSchemas(json)
            "schema" -> cmdSchema(json, commandArgs)
            else -> {
                System.err.println("Unknown command: $command")
                printUsage()
                exitProcess(1)
            }
        }
    } catch (e: IllegalArgumentException) {
        System.err.println("Error: ${e.message ?: "invalid argument"}")
        exitProcess(1)
    }
}

private fun cmdRooms(exporter: RoomExporter, json: Json) {
    val summaries = exporter.exportRoomSummaries()
    println(json.encodeToString(summaries))
}

private fun cmdRoom(exporter: RoomExporter, json: Json, args: List<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: room <roomId|handle>")
        System.err.println("  roomId: hex like 0x91F8 or 91F8")
        System.err.println("  handle: string like landingSite")
        exitProcess(1)
    }
    val roomId = exporter.resolveRoomId(args[0]) ?: run {
        System.err.println("Room not found: ${args[0]}")
        exitProcess(1)
    }
    val export = exporter.exportRoom(roomId) ?: run {
        System.err.println("Failed to parse room 0x${roomId.toString(16)}")
        exitProcess(1)
    }
    println(json.encodeToString(export))
}

private fun cmdGraph(roomExporter: RoomExporter, json: Json) {
    val graphExporter = GraphExporter(roomExporter)
    val graph = graphExporter.exportGraph()
    println(json.encodeToString(graph))
}

private fun cmdExport(
    roomExporter: RoomExporter,
    json: Json,
    args: List<String>,
) {
    var outDir: String? = null
    val iter = args.iterator()
    while (iter.hasNext()) {
        val arg = iter.next()
        if ((arg == "-o" || arg == "--output") && iter.hasNext()) {
            outDir = iter.next()
        }
    }
    if (outDir == null) {
        System.err.println("Usage: export -o <directory>")
        exitProcess(1)
    }

    val dir = File(outDir)
    dir.mkdirs()
    val roomsDir = File(dir, "rooms")
    roomsDir.mkdirs()

    // Export all rooms once and cache results
    val summaries = roomExporter.exportRoomSummaries()
    val roomExports = summaries.mapNotNull { s -> roomExporter.exportRoom(s.roomId)?.let { s to it } }

    File(dir, "rooms.json").writeText(json.encodeToString(summaries))
    System.err.println("Wrote rooms.json (${summaries.size} rooms)")

    // Build navigation graph from cached exports
    val graphExporter = GraphExporter(roomExporter)
    val graph = graphExporter.exportGraphFrom(roomExports.map { it.second })
    File(dir, "nav_graph.json").writeText(json.encodeToString(graph))
    System.err.println("Wrote nav_graph.json (${graph.nodes.size} nodes, ${graph.edges.size} edges)")

    // Write per-room files from cache
    for ((_, roomExport) in roomExports) {
        val filename = "room_${roomExport.roomIdHex.removePrefix("0x")}.json"
        File(roomsDir, filename).writeText(json.encodeToString(roomExport))
    }
    val failed = summaries.size - roomExports.size
    System.err.println("Wrote ${roomExports.size} room files to rooms/ ($failed failed)")
}

private fun cmdPatches(json: Json) {
    val supportedConfigTypes = SmeditPatchCatalog.supportedConfigTypes()
    val summaries = SmeditPatchCatalog.defaultPatches().map { patch ->
        val schema = patch.configType?.let { SmeditPatchCatalog.configSchema(it) }
        val publicId = SmeditPatchCatalog.publicPatchId(patch)
        CliPatchSummary(
            id = publicId,
            internalId = patch.id.takeIf { it != publicId },
            aliases = SmeditPatchCatalog.patchAliasesFor(patch.id),
            name = patch.name,
            description = patch.description,
            configType = patch.configType,
            headlessSupported = patch.writes.isNotEmpty() || patch.configType in supportedConfigTypes,
            supportsPatchOnly = schema?.supportsPatchOnly ?: patch.writes.isNotEmpty(),
            requiresRom = schema?.requiresRom ?: false,
            configFieldCount = schema?.fields?.size ?: 0,
            writeRecords = patch.writes.size,
            writeBytes = patch.writes.sumOf { it.bytes.size },
        )
    }
    println(json.encodeToString(summaries))
}

private fun cmdSchemas(json: Json) {
    println(json.encodeToString(SmeditPatchCatalog.configSchemas()))
}

private fun cmdSchema(json: Json, args: List<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: schema <patchId|configType>")
        exitProcess(1)
    }
    val schema = SmeditPatchCatalog.configSchema(args[0]) ?: run {
        System.err.println("No config schema found for: ${args[0]}")
        exitProcess(1)
    }
    println(json.encodeToString(schema))
}

private fun cmdBuild(
    json: Json,
    romPath: String?,
    args: List<String>,
) {
    var configPath: String? = null
    var outputPath: String? = null
    var patchPath: String? = null
    var reportPath: String? = null
    var strictConfigValidationOverride: Boolean? = null
    var colorizeEffectOverride: String? = null

    val iter = args.iterator()
    while (iter.hasNext()) {
        val arg = iter.next()
        when {
            arg == "--config" && iter.hasNext() -> configPath = iter.next()
            (arg == "-o" || arg == "--output") && iter.hasNext() -> outputPath = iter.next()
            arg == "--patch" && iter.hasNext() -> patchPath = iter.next()
            arg == "--report" && iter.hasNext() -> reportPath = iter.next()
            arg == "--colorize" && iter.hasNext() -> colorizeEffectOverride = iter.next()
            arg == "--lenient-config" -> strictConfigValidationOverride = false
            else -> {
                System.err.println("Unknown build option: $arg")
                printBuildUsage()
                exitProcess(1)
            }
        }
    }

    if (configPath == null || (outputPath == null && patchPath == null)) {
        printBuildUsage()
        exitProcess(1)
    }
    if (outputPath != null && romPath == null) {
        System.err.println("Error: build --output requires --rom <path.smc>; use --patch for ROM-free patch generation")
        exitProcess(1)
    }
    if (colorizeEffectOverride != null && romPath == null) {
        System.err.println("Error: build --colorize requires --rom <path.smc> because palette effects read base ROM palettes")
        exitProcess(1)
    }

    val configFile = File(configPath).absoluteFile
    val decodedRequest = jsonInput.decodeFromString(SmeditBuildRequest.serializer(), configFile.readText())
    val requestWithStrict = strictConfigValidationOverride
        ?.let { decodedRequest.copy(strictConfigValidation = it) }
        ?: decodedRequest
    val request = colorizeEffectOverride
        ?.let { requestWithStrict.copy(colorize = SmeditColorizeRequest(effect = it)) }
        ?: requestWithStrict
    val project = request.project?.let { projectPath ->
        val projectFile = resolveRelative(configFile.parentFile, projectPath)
        jsonInput.decodeFromString(SmEditProject.serializer(), projectFile.readText())
    }

    val service = SmeditBuildService()
    val report = if (romPath != null) {
        val result = service.build(File(romPath).readBytes(), request, project)
        outputPath?.let { path ->
            val outFile = File(path)
            outFile.parentFile?.mkdirs()
            outFile.writeBytes(result.romBytes)
            System.err.println("Wrote ROM: ${outFile.absolutePath}")
        }
        patchPath?.let { path ->
            val outFile = File(path)
            outFile.parentFile?.mkdirs()
            outFile.writeBytes(result.ipsPatchBytes)
            System.err.println("Wrote IPS: ${outFile.absolutePath}")
        }
        result.report
    } else {
        val result = service.buildPatch(request, project)
        val outFile = File(patchPath ?: error("patch path was already validated"))
        outFile.parentFile?.mkdirs()
        outFile.writeBytes(result.ipsPatchBytes)
        System.err.println("Wrote IPS: ${outFile.absolutePath}")
        result.report
    }

    val reportJson = json.encodeToString(SmeditBuildReport.serializer(), report)
    reportPath?.let { path ->
        val outFile = File(path)
        outFile.parentFile?.mkdirs()
        outFile.writeText(reportJson)
        System.err.println("Wrote report: ${outFile.absolutePath}")
    }
    println(reportJson)
}

private fun resolveRelative(baseDir: File?, path: String): File {
    val file = File(path)
    return if (file.isAbsolute) file else File(baseDir ?: File("."), path)
}

@Serializable
private data class CliPatchSummary(
    val id: String,
    val internalId: String? = null,
    val aliases: List<String> = emptyList(),
    val name: String,
    val description: String,
    val configType: String?,
    val headlessSupported: Boolean,
    val supportsPatchOnly: Boolean,
    val requiresRom: Boolean,
    val configFieldCount: Int,
    val writeRecords: Int,
    val writeBytes: Int,
)

private fun printBuildUsage() {
    System.err.println("""
Usage: build --config <build.json> [--output <patched.smc>] [--patch <patch.ips>] [--report <report.json>] [--colorize <effect>] [--lenient-config]

At least one of --output or --patch is required.
--output requires global --rom <path.smc>. --patch can run without --rom for ROM-free IPS generation.
--colorize applies a palette effect such as psychedelic and requires global --rom <path.smc>.
Config validation is strict by default. --lenient-config reports unknown config keys and out-of-range values as warnings.
    """.trimIndent())
}

private fun printUsage() {
    System.err.println("""
Super Metroid Editor CLI - Export ROM data and apply headless patches

Usage: [--rom <path.smc>] [--compact] <command> [options]

Commands:
  rooms              List all rooms with metadata (JSON to stdout)
  room <id|handle>   Export single room with full collision grid
  graph              Export navigation graph (nodes + edges)
  export -o <dir>    Export everything: rooms.json, nav_graph.json, rooms/*.json
  patches            List built-in patch IDs and headless support status
  schemas            List supported config patch schemas
  schema <id|type>   Show one config patch schema
  build --config <json>
                     Apply supported project/request patches and write a ROM and/or IPS

Options:
  --rom <path>       Path to Super Metroid ROM file (.smc); required except patches, schemas, or build --patch
  --compact          Output compact JSON (no indentation)
  -h, --help         Show this help

Examples:
  ... --rom rom.smc rooms
  ... --rom rom.smc room 0x91F8
  ... --rom rom.smc room landingSite
  ... --rom rom.smc graph
  ... --rom rom.smc export -o /tmp/sm_export
  ... patches
  ... schema enemy_stats
  ... --rom base.smc build --config build.json --output patched.smc --patch patched.ips
  ... --rom base.smc build --config build.json --colorize psychedelic --output patched.smc
  ... build --config build.json --patch patch-only.ips
    """.trimIndent())
}
