package com.supermetroid.editor.rom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Produces the complete species-level rendering support ledger.
 *
 * This deliberately starts from the 164 source headers, not SMEDIT's curated
 * picker or bundled PNGs. A valid raw tile sheet is not counted as an
 * assembled sprite, and source-known composite renderers remain distinct from
 * ordinary OAM assembly.
 */
class EnemySpeciesStatusSourceParityTest {

    private enum class Status(val reportName: String) {
        ASSEMBLED("assembled"),
        TILE_SHEET_ONLY("tile-sheet-only"),
        COMPOSITE("composite"),
        NONVISUAL("nonvisual"),
        FAILED("failed"),
    }

    private data class PreviewProbe(
        val path: String,
        val frameCount: Int,
        val error: String? = null,
    ) {
        val available: Boolean get() = frameCount > 0 && error == null
    }

    private data class SpeciesStatus(
        val speciesId: Int,
        val sourceLabel: String,
        val displayName: String,
        val status: Status,
        val reason: String,
        val sourceDeclaredUnused: Boolean,
        val tileDataSize: Int,
        val hasTileData: Boolean,
        val hasPalette: Boolean,
        val preview: PreviewProbe,
        val inEnemyCatalog: Boolean,
        val inSpriteCatalog: Boolean,
    )

    @Tag("parity")
    @Test
    fun `all source enemy species have an explicit editor rendering status`() {
        val parser = TestRomHelper.requireRomParser()
        val headerManifest = loadHeaderManifest()
        val sourceHeaders = headerManifest.getValue("headers").jsonArray.map { it.jsonObject }
        val results = sourceHeaders.map { classify(parser, it) }
        val aggregateHash = aggregateHash(results)
        writeReports(headerManifest, results, aggregateHash)

        assertEquals(TestRomHelper.referenceInt("enemySpeciesStatus.species.count"), results.size)
        assertEquals(results.size, results.map { it.speciesId }.toSet().size)
        Status.entries.forEach { status ->
            assertEquals(
                TestRomHelper.referenceInt("enemySpeciesStatus.${status.reportName}.count"),
                results.count { it.status == status },
                status.reportName,
            )
        }
        assertEquals(
            TestRomHelper.referenceInt("enemySpeciesStatus.previewAvailable.count"),
            results.count { it.preview.available },
        )
        assertEquals(
            TestRomHelper.referenceInt("enemySpeciesStatus.tileDataAvailable.count"),
            results.count { it.hasTileData },
        )
        assertEquals(
            TestRomHelper.referenceInt("enemySpeciesStatus.paletteAvailable.count"),
            results.count { it.hasPalette },
        )
        assertTrue(results.none { it.preview.error != null }, "Production preview probe threw an exception")

        assertEquals(
            TestRomHelper.referenceString("enemySpeciesStatus.aggregate.sha256"),
            aggregateHash,
        )
    }

    private fun classify(parser: RomParser, source: JsonObject): SpeciesStatus {
        val speciesId = source.int("speciesId")
        val sourceLabel = source.string("sourceLabel")
        val fields = source.getValue("fields").jsonObject
        val tileDataSize = fields.getValue("tileDataSize").jsonObject.int("value") and 0x7FFF
        val sourceDeclaredUnused = source.getValue("sourceDeclaredUnused").jsonPrimitive.boolean
        val tileData = EnemySpriteGraphics.loadEnemyTileData(parser, speciesId)
        val palette = EnemySpriteGraphics.loadEnemyPreviewPaletteSource(parser, speciesId)?.colors
        val preview = if (sourceDeclaredUnused || speciesId in NONVISUAL_SPECIES) {
            PreviewProbe("not applicable", 0)
        } else {
            probePreview(parser, speciesId, tileData, palette)
        }
        val compositeReason = COMPOSITE_SPECIES[speciesId]

        val (status, reason) = when {
            sourceDeclaredUnused -> Status.NONVISUAL to
                "The disassembly declares this enemy header unused; it has no runtime preview contract."
            speciesId in NONVISUAL_SPECIES -> Status.NONVISUAL to NONVISUAL_SPECIES.getValue(speciesId)
            compositeReason != null -> Status.COMPOSITE to compositeReason
            preview.error != null -> Status.FAILED to
                "The production preview probe failed: ${preview.error}"
            preview.available -> Status.ASSEMBLED to
                "SMEDIT rendered at least one frame through ${preview.path}."
            tileData != null && tileData.isNotEmpty() && palette != null -> Status.TILE_SHEET_ONLY to
                "Raw GRAPHADR tiles and a palette load, but no production assembly path yields a frame."
            else -> Status.FAILED to when {
                tileData == null || tileData.isEmpty() ->
                    "This active visual species has no independently loadable GRAPHADR tile payload in its header."
                palette == null -> "The species tile payload loads, but its palette does not."
                else -> "No production preview is available."
            }
        }

        val enemyCatalogIds = RomParser.ENEMY_CATALOG.map { it.first }.toSet()
        val spriteCatalogIds = EnemySpriteGraphics.EDITOR_ENEMIES.map { it.speciesId }.toSet()
        return SpeciesStatus(
            speciesId = speciesId,
            sourceLabel = sourceLabel,
            displayName = RomParser.enemyName(speciesId),
            status = status,
            reason = reason,
            sourceDeclaredUnused = sourceDeclaredUnused,
            tileDataSize = tileDataSize,
            hasTileData = tileData != null && tileData.isNotEmpty(),
            hasPalette = palette != null,
            preview = preview,
            inEnemyCatalog = speciesId in enemyCatalogIds,
            inSpriteCatalog = speciesId in spriteCatalogIds,
        )
    }

    private fun probePreview(
        parser: RomParser,
        speciesId: Int,
        tileData: ByteArray?,
        palette: IntArray?,
    ): PreviewProbe {
        return try {
            if (speciesId == KRAID_SPECIES_ID) {
                val kraid = KraidSpritemap(parser)
                if (kraid.load() && kraid.renderFullBody() != null) {
                    return PreviewProbe("Kraid BG2 composite renderer", 1)
                }
            }
            if (speciesId == PHANTOON_SPECIES_ID) {
                val phantoon = PhantoonSpritemap(parser)
                if (phantoon.load()) {
                    val count = PhantoonSpritemap.COMPONENT_TILEMAPS.count {
                        phantoon.renderComponent(it) != null
                    }
                    if (count > 0) return PreviewProbe("Phantoon BG2 composite renderer", count)
                }
            }
            if (speciesId == DRAYGON_SPECIES_ID) {
                val draygon = DraygonSpritemap(parser)
                if (draygon.load(tileData)) {
                    val count = DraygonSpritemap.ANIMATIONS.sumOf { definition ->
                        draygon.renderAnimation(definition)?.frames?.size ?: 0
                    }
                    if (count > 0) return PreviewProbe("Draygon BG2 and OBJ composite renderer", count)
                }
            }
            val standardRenderTiles = EnemySpriteGraphics.loadStandardOamRenderTileData(
                parser,
                speciesId,
                tileData,
            )
            if (standardRenderTiles == null || standardRenderTiles.isEmpty() || palette == null) {
                return PreviewProbe("none", 0)
            }

            val spritemap = EnemySpritemap(parser)
            spritemap.buildSpecialEnemyAnimation(
                speciesId,
                standardRenderTiles,
                palette,
                RomParser.enemyName(speciesId),
            )
                ?.frames
                ?.takeIf { it.isNotEmpty() }
                ?.let { return PreviewProbe("special OAM animation", it.size) }
            spritemap.renderSpecialEnemyPreview(speciesId, standardRenderTiles, palette)
                ?.let { return PreviewProbe("special OAM renderer", 1) }

            if (BossPoseScanner.hasKnownPoses(speciesId)) {
                val renderTiles = EnemySpriteGraphics.loadEnemyRenderTileData(
                    parser,
                    speciesId,
                    tileData ?: standardRenderTiles,
                ) ?: standardRenderTiles
                val scanner = BossPoseScanner(parser)
                val rendered = scanner.scanPoses(speciesId, minEntries = 3).count { pose ->
                    scanner.renderPose(pose, renderTiles, palette) != null
                }
                if (rendered > 0) return PreviewProbe("source-mapped boss pose renderer", rendered)
            }

            spritemap.buildAnimation(
                speciesId,
                standardRenderTiles,
                palette,
                RomParser.enemyName(speciesId),
            )
                ?.frames
                ?.takeIf { it.isNotEmpty() }
                ?.let { return PreviewProbe("instruction-list OAM renderer", it.size) }
            val default = spritemap.findDefaultSpritemap(speciesId)
            if (default != null && spritemap.renderSpritemap(default, standardRenderTiles, palette) != null) {
                return PreviewProbe("default OAM renderer", 1)
            }
            PreviewProbe("none", 0)
        } catch (failure: Exception) {
            PreviewProbe(
                path = "error",
                frameCount = 0,
                error = "${failure::class.simpleName}: ${failure.message ?: "no message"}",
            )
        }
    }

    private fun aggregateHash(results: List<SpeciesStatus>): String {
        val canonical = results.joinToString("\n") { result ->
            listOf(
                "%04X".format(result.speciesId),
                result.sourceLabel,
                result.status.reportName,
                result.tileDataSize,
                result.hasTileData,
                result.hasPalette,
                result.preview.path,
                result.preview.frameCount,
                result.inEnemyCatalog,
                result.inSpriteCatalog,
            ).joinToString("|")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun writeReports(
        headerManifest: JsonObject,
        results: List<SpeciesStatus>,
        aggregateHash: String,
    ) {
        val output = TestRomHelper.repositoryFile("parity/reports/enemy-species-status.json")
        output.parentFile.mkdirs()
        val counts = Status.entries.associateWith { status -> results.count { it.status == status } }
        val root = buildJsonObject {
            put("schemaVersion", 1)
            put("disassemblyCommit", headerManifest.getValue("disassemblyCommit"))
            put(
                "oracle",
                "Complete source header inventory plus SMEDIT production tile, palette, ordinary OAM, special OAM, and composite preview paths",
            )
            put("definitions", buildJsonObject {
                put("assembled", "At least one ordinary or special OAM frame renders in production code.")
                put("tile-sheet-only", "Raw GRAPHADR tiles and palette load, but no assembled frame renders.")
                put("composite", "Source-known multi-part or BG2 composition requiring a dedicated renderer; preview availability is reported separately.")
                put("nonvisual", "Source-unused or intentionally invisible engine helper with no standalone visual contract.")
                put("failed", "An active visual species has missing assets, a renderer error, or no usable preview fallback.")
            })
            put("totals", buildJsonObject {
                put("speciesCount", results.size)
                Status.entries.forEach { status -> put("${status.reportName}Count", counts.getValue(status)) }
                put("previewAvailableCount", results.count { it.preview.available })
                put("tileDataAvailableCount", results.count { it.hasTileData })
                put("paletteAvailableCount", results.count { it.hasPalette })
                put("enemyCatalogCount", results.count { it.inEnemyCatalog })
                put("spriteCatalogCount", results.count { it.inSpriteCatalog })
            })
            put("aggregateHashes", buildJsonObject { put("species", aggregateHash) })
            put("species", buildJsonArray {
                results.forEach { result ->
                    add(buildJsonObject {
                        put("speciesId", result.speciesId)
                        put("speciesHex", "%04X".format(result.speciesId))
                        put("sourceLabel", result.sourceLabel)
                        put("displayName", result.displayName)
                        put("status", result.status.reportName)
                        put("reason", result.reason)
                        put("sourceDeclaredUnused", result.sourceDeclaredUnused)
                        put("tileDataSize", result.tileDataSize)
                        put("hasTileData", result.hasTileData)
                        put("hasPalette", result.hasPalette)
                        put("previewPath", result.preview.path)
                        put("previewFrameCount", result.preview.frameCount)
                        put("previewAvailable", result.preview.available)
                        result.preview.error?.let { put("previewError", it) }
                        put("inEnemyCatalog", result.inEnemyCatalog)
                        put("inSpriteCatalog", result.inSpriteCatalog)
                    })
                }
            })
        }
        output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root) + "\n")
        writeMarkdown(output.resolveSibling("enemy-species-status.md"), results, counts, aggregateHash)
    }

    private fun writeMarkdown(
        output: File,
        results: List<SpeciesStatus>,
        counts: Map<Status, Int>,
        aggregateHash: String,
    ) {
        val lines = mutableListOf(
            "# Enemy Species Rendering Status",
            "",
            "This report starts from every bank-\$A0 source header. A raw tile sheet does not count as an assembled sprite.",
            "",
            "| Status | Species |",
            "|---|---:|",
        )
        Status.entries.forEach { status -> lines += "| ${status.reportName} | ${counts.getValue(status)} |" }
        lines += listOf(
            "",
            "Preview available for **${results.count { it.preview.available }} / ${results.size}** source species.",
            "",
            "Aggregate SHA-256: `$aggregateHash`",
        )
        Status.entries.forEach { status ->
            lines += listOf(
                "",
                "## ${status.reportName}",
                "",
                "| ID | Source species | Editor preview | Catalogs | Reason |",
                "|---|---|---|---|---|",
            )
            results.filter { it.status == status }.forEach { result ->
                val preview = if (result.preview.available) {
                    "${result.preview.path} (${result.preview.frameCount})"
                } else {
                    result.preview.error ?: "none"
                }
                val catalogs = buildList {
                    if (result.inEnemyCatalog) add("enemy")
                    if (result.inSpriteCatalog) add("sprite")
                }.joinToString(", ").ifEmpty { "none" }
                lines += "| `${"%04X".format(result.speciesId)}` | ${result.sourceLabel.removePrefix("EnemyHeaders_")} | $preview | $catalogs | ${result.reason} |"
            }
        }
        output.writeText(lines.joinToString("\n") + "\n")
    }

    private fun loadHeaderManifest(): JsonObject {
        val report = TestRomHelper.requireParityReport("enemy-headers.json", "parityEnemyHeaders")
        return Json.parseToJsonElement(report.readText()).jsonObject
    }

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.contentOrNull ?: error("Missing string field $name")

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

    companion object {
        private const val KRAID_SPECIES_ID = 0xE2BF
        private const val PHANTOON_SPECIES_ID = 0xE4BF
        private const val DRAYGON_SPECIES_ID = 0xDE3F

        /** Explicit source/runtime composition cases; do not infer these from tile count. */
        private val COMPOSITE_SPECIES = mapOf(
            0xDD7F to "The visible Metroid combines independently animated inside, shell, and electricity OAM owners.",
            0xDDBF to "Crocomire mixes extended OAM, room BG tiles, and a separate skeleton tile transfer.",
            0xDE3F to "Draygon's body is composed from multiple extended-OAM parts and room BG tile data.",
            0xDE7F to "Draygon's eye participates in the boss's shared extended-OAM/room-tile composition.",
            0xDEBF to "Draygon's tail participates in the boss's shared extended-OAM/room-tile composition.",
            0xDEFF to "Draygon's arms participate in the boss's shared extended-OAM/room-tile composition.",
            0xDF3F to "Spore Spawn's body frames are extended spritemaps combining shell and mouth children.",
            0xE13F to "Ceres Ridley uses extended spritemaps composed from legs, hand, torso, and head/neck OAM.",
            0xE17F to "Ridley uses extended spritemaps composed from legs, hand, torso, and head/neck OAM.",
            0xE1BF to "Ridley's low-page forward-facing and explosion tiles are consumed by the consolidated Ridley composite renderer.",
            0xE2BF to "Kraid's visible body is a BG2 nametable/tilemap composition using room tileset graphics.",
            0xE4BF to "Phantoon's visible body and face components are BG2 extended tilemaps using room tiles.",
            0xEC3F to "Mother Brain's brain/head uses custom drawing and shares the phase-two composite graphics system.",
            0xEC7F to "Mother Brain's body mixes extended OAM, room BG tiles, and separately transferred head/leg tiles.",
            0xF293 to "Botwoon is assembled from one head plus thirteen repeated body/tail segment spritemaps.",
        )

        private val NONVISUAL_SPECIES = mapOf(
            0xDAFF to "The respawn placeholder header is an engine sentinel with no AI, graphics transfer, or standalone sprite.",
            0xE03F to "Kzan's bottom record is an invisible collision follower; the preceding Kzan top owns the only instruction list and visible spritemap.",
            0xF03F to "This no-op header transfers Tourian statue soul graphics; the visible soul is animated by the enemy-projectile engine, not as a standalone enemy.",
        )
    }
}
