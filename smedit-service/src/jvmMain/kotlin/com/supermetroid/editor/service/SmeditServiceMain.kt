package com.supermetroid.editor.service

import com.supermetroid.editor.headless.SmeditBuildReport
import com.supermetroid.editor.headless.SmeditBuildRequest
import com.supermetroid.editor.headless.SmeditBuildResult
import com.supermetroid.editor.headless.SmeditBuildService
import com.supermetroid.editor.headless.SmeditPatchRandomizer
import com.supermetroid.editor.headless.SmeditRandomizationReport
import com.supermetroid.editor.headless.SmeditRandomizationRequest
import com.supermetroid.editor.rom.RomConstants
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
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
        post("/patch") {
            val request = call.receive<SmeditServicePatchRequest>()
            val response = buildPatchedRom(request, buildService)
            val result = response.result
            if (call.wantsJsonResponse()) {
                call.respond(
                    SmeditServicePatchResponse(
                        romBase64 = Base64.getEncoder().encodeToString(result.romBytes),
                        ipsBase64 = Base64.getEncoder().encodeToString(result.ipsPatchBytes),
                        report = result.report,
                        resolvedBuild = response.resolvedBuild,
                        randomization = response.randomization,
                    )
                )
            } else {
                call.response.header("X-SMEDIT-Changed-Bytes", result.report.changedBytes.toString())
                call.response.header("X-SMEDIT-Patch-Bytes", result.report.patchBytes.toString())
                call.response.header("X-SMEDIT-Warnings", result.report.warnings.size.toString())
                response.randomization?.let { randomization ->
                    call.response.header("X-SMEDIT-Randomization-Seed", randomization.seed.toString())
                    randomization.preset?.let { preset ->
                        call.response.header("X-SMEDIT-Randomization-Preset", preset)
                    }
                    call.response.header("X-SMEDIT-Randomized-Config-Types", randomization.randomizedConfigTypes.joinToString(","))
                    call.response.header(
                        "X-SMEDIT-Randomized-Field-Counts",
                        randomization.randomizedFieldCounts.entries.joinToString(",") { (configType, count) ->
                            "$configType=$count"
                        },
                    )
                }
                call.respondBytes(result.romBytes, ContentType.Application.OctetStream)
            }
        }
    }
}

private data class SmeditServiceBuildResponse(
    val result: SmeditBuildResult,
    val resolvedBuild: SmeditBuildRequest?,
    val randomization: SmeditRandomizationReport?,
)

private fun buildPatchedRom(
    request: SmeditServicePatchRequest,
    buildService: SmeditBuildService,
): SmeditServiceBuildResponse {
    require(request.build.project == null) {
        "Project file paths are not supported by the service endpoint; include patch settings directly in build."
    }
    val romBytes = decodeRomBase64(request.romBase64)
    require(romBytes.size >= RomConstants.ROM_SIZE) {
        "romBase64 must contain a Super Metroid ROM of at least ${RomConstants.ROM_SIZE} bytes."
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

private fun ApplicationCall.wantsJsonResponse(): Boolean {
    if (request.queryParameters["format"].equals("json", ignoreCase = true)) return true
    val acceptHeader = request.headers[HttpHeaders.Accept].orEmpty()
    return acceptHeader
        .split(',')
        .map { it.substringBefore(';').trim() }
        .any { it.equals(ContentType.Application.Json.toString(), ignoreCase = true) }
}
