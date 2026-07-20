package com.supermetroid.editor.service

import com.supermetroid.editor.headless.BEAM_DAMAGE_CONFIG_TYPE
import com.supermetroid.editor.headless.ENEMY_DROPS_CONFIG_TYPE
import com.supermetroid.editor.headless.ENEMY_STATS_CONFIG_TYPE
import com.supermetroid.editor.headless.ENEMY_VULN_CONFIG_TYPE
import com.supermetroid.editor.headless.SmeditBuildRequest
import com.supermetroid.editor.headless.SmeditBeamDamageRandomization
import com.supermetroid.editor.headless.SmeditEnemyDropsRandomization
import com.supermetroid.editor.headless.SmeditEnemyStatsRandomization
import com.supermetroid.editor.headless.SmeditEnemyVulnerabilityRandomization
import com.supermetroid.editor.headless.SmeditPatchRequest
import com.supermetroid.editor.headless.SmeditRandomizationRequest
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmeditServiceTest {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = false
    }

    @Test
    fun `patch endpoint returns patched rom bytes by default`() = testApplication {
        application {
            smeditServiceModule()
        }

        val request = patchRequest(
            SmeditBuildRequest(
                patches = mapOf(
                    "hex_higher_jump" to SmeditPatchRequest(),
                )
            )
        )
        val response = client.post("/patch") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("1", response.headers["X-SMEDIT-Changed-Bytes"])
        val patchedRom = response.body<ByteArray>()
        assertEquals(0x300000, patchedRom.size)
        assertEquals(0x05, patchedRom[0x81EB9].toInt() and 0xFF)
    }

    @Test
    fun `patch endpoint can return json with rom ips and report`() = testApplication {
        application {
            smeditServiceModule()
        }

        val request = patchRequest(
            SmeditBuildRequest(
                patches = mapOf(
                    "hex_higher_jump" to SmeditPatchRequest(),
                )
            )
        )
        val response = client.post("/patch?format=json") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<SmeditServicePatchResponse>(response.body())
        val patchedRom = Base64.getDecoder().decode(body.romBase64)
        assertEquals(0x05, patchedRom[0x81EB9].toInt() and 0xFF)
        assertTrue(body.ipsBase64.isNotBlank())
        assertTrue(body.report.applied.any { it.identifier == "hex_higher_jump" })
    }

    @Test
    fun `patch endpoint applies randomizers and returns resolved build in json`() = testApplication {
        application {
            smeditServiceModule()
        }

        val request = patchRequest(
            build = SmeditBuildRequest(
                patches = mapOf(
                    BEAM_DAMAGE_CONFIG_TYPE to SmeditPatchRequest(
                        config = mapOf("power" to 777)
                    )
                )
            ),
            randomize = SmeditRandomizationRequest(
                seed = 12345L,
                beamDamage = SmeditBeamDamageRandomization(enabled = true),
                enemyStats = SmeditEnemyStatsRandomization(
                    enabled = true,
                    randomizeContactDamage = false,
                    enemyHpMin = 0.5,
                    enemyHpMax = 3.5,
                ),
                enemyDrops = SmeditEnemyDropsRandomization(enabled = true),
                enemyVulnerabilities = SmeditEnemyVulnerabilityRandomization(
                    enabled = true,
                    noEffectChance = 1.0,
                ),
            )
        )
        val response = client.post("/patch?format=json") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<SmeditServicePatchResponse>(response.body())
        assertEquals(12345L, body.randomization?.seed)
        assertTrue(body.randomization?.randomizedConfigTypes.orEmpty().containsAll(
            listOf(BEAM_DAMAGE_CONFIG_TYPE, ENEMY_STATS_CONFIG_TYPE, ENEMY_DROPS_CONFIG_TYPE, ENEMY_VULN_CONFIG_TYPE)
        ))
        val resolvedPatches = body.resolvedBuild?.patches.orEmpty()
        assertEquals(777, resolvedPatches.getValue(BEAM_DAMAGE_CONFIG_TYPE).config["power"])
        assertTrue(resolvedPatches.getValue(ENEMY_STATS_CONFIG_TYPE).config.keys.any { it.endsWith("_hp") })
        assertTrue(resolvedPatches.getValue(ENEMY_DROPS_CONFIG_TYPE).config.isNotEmpty())
        assertTrue(resolvedPatches.getValue(ENEMY_VULN_CONFIG_TYPE).config.isNotEmpty())
    }

    @Test
    fun `patch endpoint returns randomization headers for raw rom responses`() = testApplication {
        application {
            smeditServiceModule()
        }

        val request = patchRequest(
            build = SmeditBuildRequest(),
            randomize = SmeditRandomizationRequest(
                seed = 777L,
                preset = "balanced",
                includeEnemies = listOf("zoomer"),
                includeBeams = listOf("power"),
            )
        )
        val response = client.post("/patch") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("777", response.headers["X-SMEDIT-Randomization-Seed"])
        assertEquals("balanced", response.headers["X-SMEDIT-Randomization-Preset"])
        assertEquals(
            listOf(BEAM_DAMAGE_CONFIG_TYPE, ENEMY_STATS_CONFIG_TYPE, ENEMY_DROPS_CONFIG_TYPE, ENEMY_VULN_CONFIG_TYPE).joinToString(","),
            response.headers["X-SMEDIT-Randomized-Config-Types"],
        )
        assertTrue(response.headers["X-SMEDIT-Randomized-Field-Counts"].orEmpty().contains("beam_damage=1"))
    }

    @Test
    fun `patch endpoint rejects invalid config by default`() = testApplication {
        application {
            smeditServiceModule()
        }

        val request = patchRequest(
            SmeditBuildRequest(
                patches = mapOf(
                    BEAM_DAMAGE_CONFIG_TYPE to SmeditPatchRequest(
                        config = mapOf("power_typo" to 123)
                    ),
                )
            )
        )
        val response = client.post("/patch") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = json.decodeFromString<SmeditServiceError>(response.body())
        assertTrue(body.error.contains("power_typo"))
    }

    @Test
    fun `patch endpoint rejects invalid randomizer parameters`() = testApplication {
        application {
            smeditServiceModule()
        }

        val request = patchRequest(
            build = SmeditBuildRequest(),
            randomize = SmeditRandomizationRequest(
                enemyVulnerabilities = SmeditEnemyVulnerabilityRandomization(
                    enabled = true,
                    noEffectChance = 1.5,
                )
            )
        )
        val response = client.post("/patch") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = json.decodeFromString<SmeditServiceError>(response.body())
        assertTrue(body.error.contains("noEffectChance"))
    }

    private fun patchRequest(
        build: SmeditBuildRequest,
        randomize: SmeditRandomizationRequest = SmeditRandomizationRequest(),
    ): SmeditServicePatchRequest =
        SmeditServicePatchRequest(
            romBase64 = Base64.getEncoder().encodeToString(ByteArray(0x300000)),
            build = build,
            randomize = randomize,
        )
}
