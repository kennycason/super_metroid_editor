package com.supermetroid.editor.service

import com.supermetroid.editor.headless.SmeditBuildReport
import com.supermetroid.editor.headless.SmeditBuildRequest
import com.supermetroid.editor.headless.SmeditBuildResult
import com.supermetroid.editor.headless.SmeditBuildService
import com.supermetroid.editor.headless.SmeditConfigSchema
import com.supermetroid.editor.headless.SmeditPatchCatalog
import com.supermetroid.editor.headless.SmeditPatchRandomizer
import com.supermetroid.editor.headless.SmeditRandomizationReport
import com.supermetroid.editor.headless.SmeditRandomizationRequest
import com.supermetroid.editor.rom.PaletteEffects
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.SpritePalettes
import com.supermetroid.editor.rom.TileGraphics
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
import java.util.Base64

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
)

@Serializable
data class SmeditServicePatchResponse(
    val romBase64: String,
    val ipsBase64: String,
    val report: SmeditBuildReport,
    val resolvedBuild: SmeditBuildRequest? = null,
    val randomization: SmeditRandomizationReport? = null,
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
    val randomization: SmeditServiceRandomizationMetadata,
    val colorize: SmeditServiceColorizeMetadata,
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
    val port = System.getenv("SMEDIT_SERVICE_PORT")?.toIntOrNull()
        ?: System.getProperty("smedit.service.port")?.toIntOrNull()
        ?: 8080

    embeddedServer(Netty, host = "0.0.0.0", port = port) {
        smeditServiceModule()
    }.start(wait = true)
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
)

private data class SmeditServiceBuildResponse(
    val result: SmeditBuildResult,
    val resolvedBuild: SmeditBuildRequest?,
    val randomization: SmeditRandomizationReport?,
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
    )

private suspend fun ApplicationCall.receiveMultipartPatchRequest(): SmeditServiceDecodedPatchRequest {
    var romBytes: ByteArray? = null
    var build = SmeditBuildRequest()
    var randomize = SmeditRandomizationRequest()
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
    require(romBytes.size == RomConstants.ROM_SIZE || romBytes.size == RomConstants.ROM_SIZE_WITH_HEADER) {
        "ROM input must be ${RomConstants.ROM_SIZE} bytes headerless or " +
            "${RomConstants.ROM_SIZE_WITH_HEADER} bytes with a 512-byte SMC header."
    }
    val randomized = SmeditPatchRandomizer.apply(request.build.copy(project = null), request.randomize)
    return SmeditServiceBuildResponse(
        result = buildService.build(romBytes, randomized.build),
        resolvedBuild = randomized.build.takeIf { randomized.report != null },
        randomization = randomized.report,
    )
}

private fun decodeRomBase64(value: String): ByteArray =
    try {
        Base64.getDecoder().decode(value)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("romBase64 is not valid base64.", e)
    }

private fun ApplicationCall.acceptsJson(): Boolean {
    val acceptHeader = request.headers[HttpHeaders.Accept].orEmpty()
    return acceptHeader
        .split(',')
        .map { it.substringBefore(';').trim() }
        .any { it.equals(ContentType.Application.Json.toString(), ignoreCase = true) }
}
