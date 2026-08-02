package com.supermetroid.editor.service

import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.headless.BEAM_DAMAGE_CONFIG_TYPE
import com.supermetroid.editor.headless.ENEMY_DROPS_CONFIG_TYPE
import com.supermetroid.editor.headless.ENEMY_STATS_CONFIG_TYPE
import com.supermetroid.editor.headless.ENEMY_VULN_CONFIG_TYPE
import com.supermetroid.editor.headless.SmeditBuildRequest
import com.supermetroid.editor.headless.SmeditBeamDamageRandomization
import com.supermetroid.editor.headless.SmeditColorizeRequest
import com.supermetroid.editor.headless.SmeditEnemyDropsRandomization
import com.supermetroid.editor.headless.SmeditEnemyStatsRandomization
import com.supermetroid.editor.headless.SmeditEnemyVulnerabilityRandomization
import com.supermetroid.editor.headless.SmeditGeneratorRequest
import com.supermetroid.editor.headless.SmeditItemPlacementRequest
import com.supermetroid.editor.headless.SmeditPatchRequest
import com.supermetroid.editor.headless.SmeditRandomizationRequest
import com.supermetroid.editor.rom.PaletteEffects
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpritePalettes
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
                    "higher_jump" to SmeditPatchRequest(),
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
                    "higher_jump" to SmeditPatchRequest(),
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
        assertTrue(body.report.applied.any { it.identifier == "higher_jump" })
    }

    @Test
    fun `patch endpoint can return ips only`() = testApplication {
        application {
            smeditServiceModule()
        }

        val request = patchRequest(
            SmeditBuildRequest(
                patches = mapOf(
                    "higher_jump" to SmeditPatchRequest(),
                )
            )
        )
        val response = client.post("/patch?format=ips") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("1", response.headers["X-SMEDIT-Changed-Bytes"])
        assertTrue(response.headers[HttpHeaders.ContentDisposition].orEmpty().contains("smedit.ips"))
        val writes = PatchRepository.parseIps(response.body())
        assertTrue(writes.any { it.offset == 0x81EB9L && it.bytes == listOf(0x05) })
    }

    @Test
    fun `metadata endpoint returns patch randomizer and color catalogs`() = testApplication {
        application {
            smeditServiceModule()
        }

        val response = client.get("/metadata") {
            accept(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<SmeditServiceMetadataResponse>(response.body())
        assertTrue(body.patches.any { it.id == "skip_intro_and_ceres" })
        assertTrue(body.patches.any { it.id == "energy_free_shinesparks" })
        assertTrue(body.patches.any { it.id == "spider_ball" })
        assertTrue(body.rooms.any { it.id == "0x91F8" && it.name == "Landing Site" })
        assertTrue(body.randomization.presets.contains("spicy"))
        assertTrue(body.randomization.beams.contains("power"))
        assertTrue(body.randomization.enemyCategories.contains("Aquatic"))
        assertFalse(body.randomization.enemyCategories.contains("Boss"))
        assertTrue(body.colorize.effects.any { it.id == "psychedelic" })
        assertEquals(SpritePalettes.REGIONS.size, body.colorize.spriteRegions.size)
    }

    @Test
    fun `rom metadata endpoint reports editable vanilla room catalog`() = testApplication {
        application {
            smeditServiceModule()
        }

        val response = client.post("/metadata/rom") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    SmeditServiceRomMetadataRequest(
                        romBase64 = Base64.getEncoder().encodeToString(ByteArray(RomConstants.ROM_SIZE)),
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<SmeditServiceRomMetadataResponse>(response.body())
        assertTrue(body.layout.editable)
        assertFalse(body.layout.readOnly)
        assertTrue(body.rooms.any { it.id == "0x91F8" && it.name == "Landing Site" })
    }

    @Test
    fun `rom metadata endpoint reports read only expanded discovered catalog`() = testApplication {
        application {
            smeditServiceModule()
        }
        val rom = expandedReadableRom()

        val response = client.post("/metadata/rom") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    SmeditServiceRomMetadataRequest(
                        romBase64 = Base64.getEncoder().encodeToString(rom),
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<SmeditServiceRomMetadataResponse>(response.body())
        assertFalse(body.layout.editable)
        assertTrue(body.layout.readOnly)
        assertEquals(1, body.layout.discoveredRooms)
        assertEquals(0x8094, body.rooms.single().roomId)
        assertTrue(body.layout.message.orEmpty().contains("Read-only expanded ROM layout loaded"))
    }

    @Test
    fun `patch endpoint applies colorize build option`() = testApplication {
        application {
            smeditServiceModule()
        }

        val rom = ByteArray(0x300000)
        val colors = IntArray(SpritePalettes.BEAM_STANDARD.colorCount) { index -> if (index == 0) 0 else 0x03E0 }
        SpritePalettes.colorsToBytes(colors).copyInto(rom, SpritePalettes.BEAM_STANDARD.offset)
        val request = patchRequest(
            build = SmeditBuildRequest(
                colorize = SmeditColorizeRequest(
                    effect = "psychedelic",
                    includeTilesets = false,
                    spriteRegions = listOf(SpritePalettes.BEAM_STANDARD.id),
                )
            ),
            romBytes = rom,
        )

        val response = client.post("/patch?format=json") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<SmeditServicePatchResponse>(response.body())
        val patchedRom = Base64.getDecoder().decode(body.romBase64)
        val expectedColors = colors.copyOf()
        PaletteEffects.psychedelic(expectedColors)
        val expectedPalette = SpritePalettes.colorsToBytes(expectedColors)
        assertContentEquals(
            expectedPalette.toIntList(),
            patchedRom.readBytes(SpritePalettes.BEAM_STANDARD.offset, expectedPalette.size),
        )
        assertTrue(body.report.applied.any { it.identifier == "colorize" })
    }

    @Test
    fun `patch endpoint applies spider ball patch and request item placement`() = testApplication {
        val romBytes = loadTestRomBytes()
        assumeTrue(romBytes != null, "Test ROM not found")
        application {
            smeditServiceModule()
        }

        val request = patchRequest(
            build = SmeditBuildRequest(
                patches = mapOf(
                    "spider_ball" to SmeditPatchRequest(),
                ),
                items = listOf(
                    SmeditItemPlacementRequest(
                        item = "spider_ball",
                        roomId = 0x91F8,
                        x = 83,
                        y = 68,
                        kind = "chozo",
                        param = 0x5A,
                    )
                ),
            ),
            romBytes = requireNotNull(romBytes),
        )
        val response = client.post("/patch?format=json") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<SmeditServicePatchResponse>(response.body())
        assertTrue(body.report.applied.any { it.identifier == "spider_ball" })
        assertTrue(body.report.applied.any { it.identifier == "request_item_placements" })
        val patchedRom = Base64.getDecoder().decode(body.romBase64)
        val patchedParser = RomParser(patchedRom)
        val landingSite = requireNotNull(patchedParser.readRoomHeader(0x91F8))
        val plms = patchedParser.parsePlmSet(landingSite.plmSetPtr)
        assertTrue(plms.any { it.id == 0xF204 && it.x == 83 && it.y == 68 && it.param == 0x5A })
    }

    @Test
    fun `patch endpoint applies mazetroid generator and places starter items`() = testApplication {
        val romBytes = loadTestRomBytes()
        assumeTrue(romBytes != null, "Test ROM not found")
        application {
            smeditServiceModule()
        }

        val request = patchRequest(
            build = SmeditBuildRequest(),
            generator = SmeditGeneratorRequest(
                mazetroid = true,
                seed = 123456L,
            ),
            romBytes = requireNotNull(romBytes),
        )
        val response = client.post("/patch?format=json") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<SmeditServicePatchResponse>(response.body())
        assertEquals(123456L, body.generator?.seed)
        assertTrue((body.generator?.generatedRooms ?: 0) > 0)
        assertTrue((body.generator?.changedTiles ?: 0) > 0)
        assertTrue(body.report.applied.any { it.identifier == "project_room_edits" })
        val patchedRom = Base64.getDecoder().decode(body.romBase64)
        val originalParser = RomParser(requireNotNull(romBytes))
        val patchedParser = RomParser(patchedRom)
        val landingSite = patchedParser.readRoomHeader(0x91F8)
        val plms = patchedParser.parsePlmSet(requireNotNull(landingSite).plmSetPtr)
        assertTrue(plms.any { it.id == 0xEF23 && it.x == 67 && it.y == 69 })
        assertTrue(plms.any { it.id == 0xEEE7 && it.x == 75 && it.y == 68 })

        val landingScrolls = patchedParser.parseScrollData(landingSite.roomScrollsPtr, landingSite.width, landingSite.height)
        assertContentEquals(mazetroidScrollsFor(landingSite), landingScrolls.toList())

        val originalMotherBrain = requireNotNull(originalParser.readRoomHeader(0xDD58))
        val patchedMotherBrain = requireNotNull(patchedParser.readRoomHeader(0xDD58))
        assertEquals(originalMotherBrain.levelDataPtr, patchedMotherBrain.levelDataPtr)
        assertContentEquals(
            originalParser.decompressLZ2(originalMotherBrain.levelDataPtr),
            patchedParser.decompressLZ2(patchedMotherBrain.levelDataPtr),
            "Mazetroid service generation should skip Mother Brain room level data",
        )

        val roomWithScrollPlms = RoomRepository().getAllRooms()
            .map { it.getRoomIdAsInt() }
            .firstOrNull { roomId -> originalParser.getAllPlmEntriesForRoom(roomId).any { RomParser.isScrollPlm(it.id) } }
        assertTrue(roomWithScrollPlms != null, "Test ROM should contain at least one scroll trigger PLM")
        val patchedScrollPlms = patchedParser.getAllPlmEntriesForRoom(requireNotNull(roomWithScrollPlms))
            .filter { RomParser.isScrollPlm(it.id) }
        assertTrue(patchedScrollPlms.isEmpty(), "Mazetroid export should remove original scroll trigger PLMs")
    }

    @Test
    fun `patch endpoint accepts multipart rom uploads`() = testApplication {
        application {
            smeditServiceModule()
        }

        val response = client.post("/patch") {
            setBody(
                multipartPatchBody(
                    build = SmeditBuildRequest(
                        patches = mapOf(
                            "higher_jump" to SmeditPatchRequest(),
                        )
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("1", response.headers["X-SMEDIT-Changed-Bytes"])
        val patchedRom = response.body<ByteArray>()
        assertEquals(0x300000, patchedRom.size)
        assertEquals(0x05, patchedRom[0x81EB9].toInt() and 0xFF)
    }

    @Test
    fun `multipart patch endpoint can return json and randomization details`() = testApplication {
        application {
            smeditServiceModule()
        }

        val response = client.post("/patch?format=json") {
            accept(ContentType.Application.Json)
            setBody(
                multipartPatchBody(
                    build = SmeditBuildRequest(),
                    randomize = SmeditRandomizationRequest(
                        seed = 888L,
                        preset = "balanced",
                        includeEnemies = listOf("zoomer"),
                        includeBeams = listOf("power"),
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<SmeditServicePatchResponse>(response.body())
        val patchedRom = Base64.getDecoder().decode(body.romBase64)
        assertEquals(0x300000, patchedRom.size)
        assertEquals(888L, body.randomization?.seed)
        assertEquals("balanced", body.randomization?.preset)
        assertEquals(1, body.randomization?.randomizedFieldCounts?.get(BEAM_DAMAGE_CONFIG_TYPE))
        assertTrue(body.resolvedBuild?.patches.orEmpty().containsKey(BEAM_DAMAGE_CONFIG_TYPE))
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

    @Test
    fun `multipart patch endpoint requires rom file field`() = testApplication {
        application {
            smeditServiceModule()
        }

        val response = client.post("/patch") {
            setBody(
                multipartPatchBody(
                    romBytes = null,
                    build = SmeditBuildRequest(),
                )
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = json.decodeFromString<SmeditServiceError>(response.body())
        assertTrue(body.error.contains("rom file field"))
    }

    @Test
    fun `patch endpoint rejects ambiguous rom sizes`() = testApplication {
        application {
            smeditServiceModule()
        }

        val response = client.post("/patch") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    patchRequest(
                        build = SmeditBuildRequest(),
                        romBytes = ByteArray(RomConstants.ROM_SIZE + 1),
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = json.decodeFromString<SmeditServiceError>(response.body())
        assertTrue(body.error.contains("headerless"))
    }

    private fun patchRequest(
        build: SmeditBuildRequest,
        randomize: SmeditRandomizationRequest = SmeditRandomizationRequest(),
        generator: SmeditGeneratorRequest = SmeditGeneratorRequest(),
        romBytes: ByteArray = ByteArray(0x300000),
    ): SmeditServicePatchRequest =
        SmeditServicePatchRequest(
            romBase64 = Base64.getEncoder().encodeToString(romBytes),
            build = build,
            randomize = randomize,
            generator = generator,
        )

    private fun multipartPatchBody(
        romBytes: ByteArray? = ByteArray(0x300000),
        build: SmeditBuildRequest = SmeditBuildRequest(),
        randomize: SmeditRandomizationRequest? = null,
        generator: SmeditGeneratorRequest? = null,
    ): MultiPartFormDataContent =
        MultiPartFormDataContent(
            formData {
                if (romBytes != null) {
                    append(
                        key = "rom",
                        value = romBytes,
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                            append(HttpHeaders.ContentDisposition, "filename=\"base.smc\"")
                        },
                    )
                }
                append("build", json.encodeToString(build))
                randomize?.let {
                    append("randomize", json.encodeToString(it))
                }
                generator?.let {
                    append("generator", json.encodeToString(it))
                }
            }
        )

    private fun loadTestRomBytes(): ByteArray? {
        val paths = listOf(
            "test-resources/Super Metroid (JU) [!].smc",
            "../test-resources/Super Metroid (JU) [!].smc",
            "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc",
        )
        return paths
            .asSequence()
            .map(::File)
            .firstOrNull { it.exists() }
            ?.readBytes()
    }

    private fun expandedReadableRom(): ByteArray =
        ByteArray(0x400000) { 0xFF.toByte() }.also { rom ->
            val roomPc = 0x78094
            rom[roomPc] = 0x01
            rom[roomPc + 1] = 0x00
            rom[roomPc + 2] = 0x12
            rom[roomPc + 3] = 0x03
            rom[roomPc + 4] = 0x05
            rom[roomPc + 5] = 0x01
            rom[roomPc + 6] = 0x70
            rom[roomPc + 7] = 0xA0.toByte()
            rom[roomPc + 8] = 0x00
            write16(rom, roomPc + 9, 0x9000)
            write16(rom, roomPc + 11, 0xE5E6)

            val statePc = roomPc + 13
            write24(rom, statePc, 0xE18000)
            rom[statePc + 3] = 0x04

            val levelDataPc = 0x308000
            rom[levelDataPc] = 0x01
            rom[levelDataPc + 1] = 0x00
            rom[levelDataPc + 2] = 0x00
            rom[levelDataPc + 3] = 0xFF.toByte()
        }

    private fun write16(rom: ByteArray, offset: Int, value: Int) {
        rom[offset] = (value and 0xFF).toByte()
        rom[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun write24(rom: ByteArray, offset: Int, value: Int) {
        rom[offset] = (value and 0xFF).toByte()
        rom[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        rom[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    }

    private fun ByteArray.readBytes(offset: Int, count: Int): List<Int> =
        (0 until count).map { this[offset + it].toInt() and 0xFF }

    private fun ByteArray.toIntList(): List<Int> =
        map { it.toInt() and 0xFF }

    private fun mazetroidScrollsFor(room: Room): List<Int> =
        buildList {
            for (screenY in 0 until room.height) {
                for (screenX in 0 until room.width) {
                    add(if (screenY == room.height - 1) 0x02 else 0x01)
                }
            }
        }
}
