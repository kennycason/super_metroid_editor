package com.supermetroid.editor.service

import com.supermetroid.editor.headless.SmeditBuildReport
import com.supermetroid.editor.headless.SmeditBuildRequest
import com.supermetroid.editor.headless.SmeditBuildResult
import com.supermetroid.editor.headless.SmeditBuildService
import com.supermetroid.editor.headless.SmeditConfigSchema
import com.supermetroid.editor.headless.SmeditGeneratorReport
import com.supermetroid.editor.headless.SmeditGeneratorRequest
import com.supermetroid.editor.headless.SmeditMazetroidGenerator
import com.supermetroid.editor.headless.SmeditPatchCatalog
import com.supermetroid.editor.headless.SmeditPatchRandomizer
import com.supermetroid.editor.headless.SmeditRandomizationReport
import com.supermetroid.editor.headless.SmeditRandomizationRequest
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.rom.PaletteEffects
import com.supermetroid.editor.rom.RoomMapExporter
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpritePalettes
import com.supermetroid.editor.rom.TileGraphics
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.streamProvider
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.BindException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import kotlin.system.exitProcess

private const val DEFAULT_SERVICE_HOST = "0.0.0.0"
private const val DEFAULT_SERVICE_PORT = 8080
private const val SERVICE_HOST_ENV = "SMEDIT_SERVICE_HOST"
private const val SERVICE_PORT_ENV = "SMEDIT_SERVICE_PORT"
private const val SERVICE_HOST_PROPERTY = "smedit.service.host"
private const val SERVICE_PORT_PROPERTY = "smedit.service.port"
private val FALLBACK_SERVICE_PORTS = (8090..8099).toList()

private val serviceJson = Json {
    ignoreUnknownKeys = false
    encodeDefaults = false
    prettyPrint = false
}

@Serializable
data class SmeditServicePatchRequest(
    val romBase64: String,
    val build: SmeditBuildRequest = SmeditBuildRequest(),
    val randomize: SmeditRandomizationRequest = SmeditRandomizationRequest(),
    val generator: SmeditGeneratorRequest = SmeditGeneratorRequest(),
)

@Serializable
data class SmeditServiceRomMetadataRequest(
    val romBase64: String,
)

@Serializable
data class SmeditServicePatchResponse(
    val romBase64: String,
    val ipsBase64: String,
    val report: SmeditBuildReport,
    val resolvedBuild: SmeditBuildRequest? = null,
    val randomization: SmeditRandomizationReport? = null,
    val generator: SmeditGeneratorReport? = null,
)

@Serializable
data class SmeditServiceError(
    val error: String,
)

@Serializable
data class SmeditServiceMetadataResponse(
    val schemaVersion: Int = 1,
    val patches: List<SmeditServicePatchMetadata>,
    val configSchemas: List<SmeditConfigSchema>,
    val rooms: List<SmeditServiceRoomMetadata>,
    val randomization: SmeditServiceRandomizationMetadata,
    val colorize: SmeditServiceColorizeMetadata,
)

@Serializable
data class SmeditServiceRomMetadataResponse(
    val schemaVersion: Int = 1,
    val layout: SmeditServiceRomLayoutMetadata,
    val rooms: List<SmeditServiceRoomMetadata>,
)

@Serializable
data class SmeditServiceRomLayoutMetadata(
    val source: String,
    val editable: Boolean,
    val readOnly: Boolean,
    val message: String? = null,
    val discoveredRooms: Int,
    val discoveredStates: Int,
    val parsedLevelDataStates: Int,
    val expandedLevelPointers: Int,
)

@Serializable
data class SmeditServicePatchMetadata(
    val id: String,
    val internalId: String,
    val name: String,
    val description: String = "",
    val configType: String? = null,
    val aliases: List<String> = emptyList(),
    val headlessSupported: Boolean,
    val supportsPatchOnly: Boolean,
    val requiresRom: Boolean,
)

@Serializable
data class SmeditServiceRoomMetadata(
    val id: String,
    val roomId: Int,
    val handle: String,
    val name: String,
)

@Serializable
data class SmeditServiceRandomizationMetadata(
    val presets: List<String>,
    val beams: List<String>,
    val enemyCategories: List<String>,
    val enemies: List<SmeditServiceEnemyMetadata>,
)

@Serializable
data class SmeditServiceEnemyMetadata(
    val key: String,
    val label: String,
    val category: String,
)

@Serializable
data class SmeditServiceColorizeMetadata(
    val effects: List<SmeditServiceColorEffectMetadata>,
    val tilesetCount: Int,
    val spriteRegions: List<SmeditServiceSpriteRegionMetadata>,
)

@Serializable
data class SmeditServiceColorEffectMetadata(
    val id: String,
    val name: String,
)

@Serializable
data class SmeditServiceSpriteRegionMetadata(
    val id: String,
    val name: String,
    val category: String,
    val colors: Int,
)

fun main() {
    val host = System.getenv(SERVICE_HOST_ENV)?.takeIf { it.isNotBlank() }
        ?: System.getProperty(SERVICE_HOST_PROPERTY)?.takeIf { it.isNotBlank() }
        ?: DEFAULT_SERVICE_HOST
    val configuredPort = configuredServicePort()
    val port = if (configuredPort != null) {
        val occupiedAddress = firstOccupiedLocalAddress(host, configuredPort)
        if (occupiedAddress != null) {
            printPortInUseMessage(host, configuredPort, occupiedAddress)
            exitProcess(1)
        }
        configuredPort
    } else {
        resolveDefaultServicePort(host) ?: run {
            printNoAvailablePortMessage(host)
            exitProcess(1)
        }
    }

    if (configuredPort == null && port != DEFAULT_SERVICE_PORT) {
        System.err.println(
            "Port $DEFAULT_SERVICE_PORT is already in use; starting SMEDIT service on http://localhost:$port instead.",
        )
    }

    val occupiedAddress = firstOccupiedLocalAddress(host, port)
    if (occupiedAddress != null) {
        printPortInUseMessage(host, port, occupiedAddress)
        exitProcess(1)
    }

    try {
        println("Starting SMEDIT service at http://$host:$port")
        embeddedServer(Netty, host = host, port = port) {
            smeditServiceModule()
        }.start(wait = true)
    } catch (t: Throwable) {
        if (t.causedByBindException()) {
            printPortInUseMessage(host, port)
        }
        throw t
    }
}

private fun configuredServicePort(): Int? =
    System.getenv(SERVICE_PORT_ENV)?.toIntOrNull()
        ?: System.getProperty(SERVICE_PORT_PROPERTY)?.toIntOrNull()

private fun resolveDefaultServicePort(host: String): Int? =
    (listOf(DEFAULT_SERVICE_PORT) + FALLBACK_SERVICE_PORTS)
        .firstOrNull { firstOccupiedLocalAddress(host, it) == null }

private fun firstOccupiedLocalAddress(host: String, port: Int): String? {
    val hostsToCheck = when (host) {
        "0.0.0.0", "::", "[::]" -> listOf("127.0.0.1", "::1")
        else -> listOf(host)
    }
    return hostsToCheck.firstOrNull { canConnectTo(it, port) }
}

private fun canConnectTo(host: String, port: Int): Boolean =
    runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 200)
        }
    }.isSuccess

private fun printPortInUseMessage(host: String, port: Int, occupiedAddress: String? = null) {
    val detected = occupiedAddress?.let { " ($it:$port is already accepting connections)" }.orEmpty()
    System.err.println(
        """
        Cannot start SMEDIT service on http://$host:$port; that port is already in use$detected.

        Find the owner:
          lsof -nP -iTCP:$port -sTCP:LISTEN

        Stop that process, or run SMEDIT on a free port:
          ./gradlew :smedit-service:runService -D$SERVICE_PORT_PROPERTY=8090

        In IntelliJ, add either this environment variable:
          $SERVICE_PORT_ENV=8090

        Or add this VM option to the SMEDIT service run configuration:
          -D$SERVICE_PORT_PROPERTY=8090

        Then set SMEDIT Lite's API URL to http://localhost:8090.
        """.trimIndent(),
    )
}

private fun printNoAvailablePortMessage(host: String) {
    val ports = (listOf(DEFAULT_SERVICE_PORT) + FALLBACK_SERVICE_PORTS).joinToString()
    System.err.println(
        """
        Cannot start SMEDIT service on $host; none of these ports are available: $ports.

        Find current owners:
          lsof -nP -iTCP -sTCP:LISTEN

        Stop another local service, or choose a free port explicitly:
          ./gradlew :smedit-service:runService -D$SERVICE_PORT_PROPERTY=8100
        """.trimIndent(),
    )
}

private fun Throwable.causedByBindException(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is BindException) return true
        current = current.cause
    }
    return false
}

fun Application.smeditServiceModule(
    buildService: SmeditBuildService = SmeditBuildService(),
) {
    install(ContentNegotiation) {
        json(serviceJson)
    }
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.Accept)
        allowHeader(HttpHeaders.ContentType)
        allowNonSimpleContentTypes = true

        exposeHeader("X-SMEDIT-Changed-Bytes")
        exposeHeader("X-SMEDIT-Patch-Bytes")
        exposeHeader("X-SMEDIT-Warnings")
        exposeHeader("X-SMEDIT-Randomization-Seed")
        exposeHeader("X-SMEDIT-Randomization-Preset")
        exposeHeader("X-SMEDIT-Randomized-Config-Types")
        exposeHeader("X-SMEDIT-Randomized-Field-Counts")
        exposeHeader("X-SMEDIT-Rendered-Rooms")
        exposeHeader("X-SMEDIT-Failed-Rooms")
        exposeHeader(HttpHeaders.ContentDisposition)

        allowHost("localhost:5173", schemes = listOf("http"))
        allowHost("127.0.0.1:5173", schemes = listOf("http"))
        allowHost("localhost:4173", schemes = listOf("http"))
        allowHost("127.0.0.1:4173", schemes = listOf("http"))
    }
    install(StatusPages) {
        exception<SerializationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                SmeditServiceError("Invalid JSON request: ${cause.message ?: "serialization failed"}"),
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                SmeditServiceError(cause.message ?: "Bad request"),
            )
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled SMEDIT service error", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                SmeditServiceError("Internal server error"),
            )
        }
    }

    routing {
        get("/metadata") {
            call.respond(serviceMetadata())
        }

        post("/metadata/rom") {
            val request = call.receive<SmeditServiceRomMetadataRequest>()
            call.respond(romMetadata(decodeRomBase64(request.romBase64)))
        }

        post("/rooms/metadata") {
            val romBytes = call.receiveRomBytes()
            val parser = RomParser(romBytes)
            require(parser.roomCatalog.readable) {
                parser.compatibilityReport.userMessage("uploaded ROM")
            }
            val exporter = RoomMapExporter(parser)
            call.respond(exporter.exportMetadata())
        }

        post("/rooms/render") {
            val showItems = call.request.queryParameters["items"]?.lowercase() in listOf("true", "1", "yes")
            val romBytes = call.receiveRomBytes()
            val parser = RomParser(romBytes)
            require(parser.roomCatalog.readable) {
                parser.compatibilityReport.userMessage("uploaded ROM")
            }
            val exporter = RoomMapExporter(parser)
            val baos = ByteArrayOutputStream()
            val result = exporter.renderToZip(baos, showItems = showItems)
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName, "rooms.zip",
                ).toString(),
            )
            call.response.header("X-SMEDIT-Rendered-Rooms", result.renderedCount.toString())
            call.response.header("X-SMEDIT-Failed-Rooms", result.failedCount.toString())
            call.respondBytes(baos.toByteArray(), ContentType("application", "zip"))
        }

        post("/patch") {
            val request = call.receiveDecodedPatchRequest()
            val response = buildPatchedRom(request, buildService)
            val result = response.result
            when (call.patchResponseFormat()) {
                PatchResponseFormat.Json -> {
                    call.respond(
                        SmeditServicePatchResponse(
                            romBase64 = Base64.getEncoder().encodeToString(result.romBytes),
                            ipsBase64 = Base64.getEncoder().encodeToString(result.ipsPatchBytes),
                            report = result.report,
                            resolvedBuild = response.resolvedBuild,
                            randomization = response.randomization,
                            generator = response.generator,
                        )
                    )
                }
                PatchResponseFormat.Ips -> {
                    call.writeReportHeaders(result, response.randomization)
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"smedit.ips\"")
                    call.respondBytes(result.ipsPatchBytes, ContentType.Application.OctetStream)
                }
                PatchResponseFormat.Rom -> {
                    call.writeReportHeaders(result, response.randomization)
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"smedit.smc\"")
                    call.respondBytes(result.romBytes, ContentType.Application.OctetStream)
                }
            }
        }
    }
}

private enum class PatchResponseFormat {
    Rom,
    Json,
    Ips,
}

private fun serviceMetadata(): SmeditServiceMetadataResponse {
    val schemas = SmeditPatchCatalog.configSchemas()
    val schemasByConfigType = schemas.associateBy { it.configType }
    val schemasByPatchId = schemas.associateBy { it.patchId }
    val patches = SmeditPatchCatalog.defaultPatches()
        .map { patch ->
            val schema = patch.configType?.let(schemasByConfigType::get) ?: schemasByPatchId[patch.id]
            SmeditServicePatchMetadata(
                id = SmeditPatchCatalog.publicPatchId(patch),
                internalId = patch.id,
                name = patch.name,
                description = patch.description,
                configType = patch.configType,
                aliases = SmeditPatchCatalog.patchAliasesFor(patch.id),
                headlessSupported = schema?.headlessSupported ?: true,
                supportsPatchOnly = schema?.supportsPatchOnly ?: true,
                requiresRom = schema?.requiresRom ?: false,
            )
        }
        .distinctBy { it.id }
        .sortedBy { it.id }

    val beamKeys = schemasByConfigType["beam_damage"]
        ?.fields
        ?.map { it.key }
        .orEmpty()
        .sorted()
    val enemyFields = schemasByConfigType["enemy_stats"]?.fields.orEmpty()
    val enemies = enemyFields
        .filter { it.key.endsWith("_hp") }
        .map { field ->
            SmeditServiceEnemyMetadata(
                key = field.key.removeSuffix("_hp"),
                label = field.label.removeSuffix(" HP"),
                category = field.category.orEmpty(),
            )
        }
        .sortedBy { it.key }
    val enemyCategories = enemies
        .map { it.category }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()

    return SmeditServiceMetadataResponse(
        patches = patches,
        configSchemas = schemas,
        rooms = RoomRepository().getAllRooms()
            .map {
                SmeditServiceRoomMetadata(
                    id = it.id,
                    roomId = it.getRoomIdAsInt(),
                    handle = it.handle,
                    name = it.name,
                )
            }
            .sortedBy { it.roomId },
        randomization = SmeditServiceRandomizationMetadata(
            presets = SmeditPatchRandomizer.availablePresets,
            beams = beamKeys,
            enemyCategories = enemyCategories,
            enemies = enemies,
        ),
        colorize = SmeditServiceColorizeMetadata(
            effects = PaletteEffects.EFFECTS.map {
                SmeditServiceColorEffectMetadata(id = it.id, name = it.name)
            },
            tilesetCount = TileGraphics.NUM_TILESETS,
            spriteRegions = SpritePalettes.REGIONS.map {
                SmeditServiceSpriteRegionMetadata(
                    id = it.id,
                    name = it.name,
                    category = it.category,
                    colors = it.colorCount,
                )
            },
        ),
    )
}

private fun romMetadata(romBytes: ByteArray): SmeditServiceRomMetadataResponse {
    val parser = RomParser(romBytes)
    val catalog = parser.roomCatalog
    require(catalog.readable) {
        parser.compatibilityReport.userMessage("uploaded ROM")
    }
    return SmeditServiceRomMetadataResponse(
        layout = SmeditServiceRomLayoutMetadata(
            source = catalog.source.name.lowercase(),
            editable = catalog.editable,
            readOnly = catalog.readOnly,
            message = catalog.loadNotice("uploaded ROM"),
            discoveredRooms = catalog.discoveredRoomCount,
            discoveredStates = catalog.discoveredStateCount,
            parsedLevelDataStates = catalog.parsedLevelDataStateCount,
            expandedLevelPointers = catalog.expandedLevelPointerCount,
        ),
        rooms = catalog.rooms
            .map {
                SmeditServiceRoomMetadata(
                    id = it.id,
                    roomId = it.getRoomIdAsInt(),
                    handle = it.handle,
                    name = it.name,
                )
            }
            .sortedBy { it.roomId },
    )
}

private fun ApplicationCall.writeReportHeaders(
    result: SmeditBuildResult,
    randomization: SmeditRandomizationReport?,
) {
    response.header("X-SMEDIT-Changed-Bytes", result.report.changedBytes.toString())
    response.header("X-SMEDIT-Patch-Bytes", result.report.patchBytes.toString())
    response.header("X-SMEDIT-Warnings", result.report.warnings.size.toString())
    randomization?.let {
        response.header("X-SMEDIT-Randomization-Seed", it.seed.toString())
        it.preset?.let { preset ->
            response.header("X-SMEDIT-Randomization-Preset", preset)
        }
        response.header("X-SMEDIT-Randomized-Config-Types", it.randomizedConfigTypes.joinToString(","))
        response.header(
            "X-SMEDIT-Randomized-Field-Counts",
            it.randomizedFieldCounts.entries.joinToString(",") { (configType, count) ->
                "$configType=$count"
            },
        )
    }
}

private fun ApplicationCall.patchResponseFormat(): PatchResponseFormat {
    return when {
        request.queryParameters["format"].equals("ips", ignoreCase = true) -> PatchResponseFormat.Ips
        request.queryParameters["format"].equals("json", ignoreCase = true) -> PatchResponseFormat.Json
        acceptsJson() -> PatchResponseFormat.Json
        else -> PatchResponseFormat.Rom
    }
}

private data class SmeditServiceDecodedPatchRequest(
    val romBytes: ByteArray,
    val build: SmeditBuildRequest,
    val randomize: SmeditRandomizationRequest,
    val generator: SmeditGeneratorRequest,
)

private data class SmeditServiceBuildResponse(
    val result: SmeditBuildResult,
    val resolvedBuild: SmeditBuildRequest?,
    val randomization: SmeditRandomizationReport?,
    val generator: SmeditGeneratorReport?,
)

private suspend fun ApplicationCall.receiveDecodedPatchRequest(): SmeditServiceDecodedPatchRequest =
    if (request.contentType().match(ContentType.MultiPart.FormData)) {
        receiveMultipartPatchRequest()
    } else {
        receive<SmeditServicePatchRequest>().toDecoded()
    }

private fun SmeditServicePatchRequest.toDecoded(): SmeditServiceDecodedPatchRequest =
    SmeditServiceDecodedPatchRequest(
        romBytes = decodeRomBase64(romBase64),
        build = build,
        randomize = randomize,
        generator = generator,
    )

private suspend fun ApplicationCall.receiveMultipartPatchRequest(): SmeditServiceDecodedPatchRequest {
    var romBytes: ByteArray? = null
    var build = SmeditBuildRequest()
    var randomize = SmeditRandomizationRequest()
    var generator = SmeditGeneratorRequest()
    val multipart = receiveMultipart()

    while (true) {
        val part = multipart.readPart() ?: break
        try {
            when (part) {
                is PartData.FileItem -> {
                    if (part.name == "rom") {
                        romBytes = part.streamProvider().use { it.readBytes() }
                    }
                }
                is PartData.FormItem -> {
                    when (part.name) {
                        "build" -> build = decodeMultipartJson(part.value, "build")
                        "randomize" -> randomize = decodeMultipartJson(part.value, "randomize")
                        "generator" -> generator = decodeMultipartJson(part.value, "generator")
                    }
                }
                else -> Unit
            }
        } finally {
            part.dispose()
        }
    }

    return SmeditServiceDecodedPatchRequest(
        romBytes = requireNotNull(romBytes) {
            "multipart/form-data request must include a rom file field."
        },
        build = build,
        randomize = randomize,
        generator = generator,
    )
}

private inline fun <reified T> decodeMultipartJson(
    value: String,
    fieldName: String,
): T =
    try {
        serviceJson.decodeFromString(value)
    } catch (e: SerializationException) {
        throw IllegalArgumentException("multipart field '$fieldName' is not valid JSON: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("multipart field '$fieldName' is not valid JSON: ${e.message}", e)
    }

private fun buildPatchedRom(
    request: SmeditServiceDecodedPatchRequest,
    buildService: SmeditBuildService,
): SmeditServiceBuildResponse {
    require(request.build.project == null) {
        "Project file paths are not supported by the service endpoint; include patch settings directly in build."
    }
    val romBytes = request.romBytes
    val parser = RomParser(romBytes)
    require(parser.roomCatalog.editable) {
        parser.compatibilityReport.userMessage("uploaded ROM")
    }
    val randomized = SmeditPatchRandomizer.apply(request.build.copy(project = null), request.randomize)
    val generated = if (request.generator.mazetroid) {
        SmeditMazetroidGenerator.generate(romBytes, request.generator)
    } else {
        null
    }
    return SmeditServiceBuildResponse(
        result = buildService.build(romBytes, randomized.build, generated?.project),
        resolvedBuild = randomized.build.takeIf { randomized.report != null },
        randomization = randomized.report,
        generator = generated?.report,
    )
}

private fun decodeRomBase64(value: String): ByteArray =
    try {
        Base64.getDecoder().decode(value)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("romBase64 is not valid base64.", e)
    }

private suspend fun ApplicationCall.receiveRomBytes(): ByteArray =
    if (request.contentType().match(ContentType.MultiPart.FormData)) {
        var romBytes: ByteArray? = null
        val multipart = receiveMultipart()
        while (true) {
            val part = multipart.readPart() ?: break
            try {
                if (part is PartData.FileItem && part.name == "rom") {
                    romBytes = part.streamProvider().use { it.readBytes() }
                }
            } finally {
                part.dispose()
            }
        }
        requireNotNull(romBytes) { "multipart/form-data request must include a rom file field." }
    } else {
        val request = receive<SmeditServiceRomMetadataRequest>()
        decodeRomBase64(request.romBase64)
    }

private fun ApplicationCall.acceptsJson(): Boolean {
    val acceptHeader = request.headers[HttpHeaders.Accept].orEmpty()
    return acceptHeader
        .split(',')
        .map { it.substringBefore(';').trim() }
        .any { it.equals(ContentType.Application.Json.toString(), ignoreCase = true) }
}
