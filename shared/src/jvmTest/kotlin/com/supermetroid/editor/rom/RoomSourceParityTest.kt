package com.supermetroid.editor.rom

import com.supermetroid.editor.data.RoomHeaderChange
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.StateDataChange
import com.supermetroid.editor.data.DoorChange
import com.supermetroid.editor.data.EditOperation
import com.supermetroid.editor.data.EnemyChange
import com.supermetroid.editor.data.FxChange
import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.ScrollChange
import com.supermetroid.editor.data.TileEdit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomSourceParityTest {

    @Tag("parity")
    @Test
    fun `all room headers selectors and state records match named source macros`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        val rooms = root.getValue("rooms").jsonArray.map { it.jsonObject }
        val states = root.getValue("states").jsonArray.map { it.jsonObject }
            .associateBy { it.string("sourceLabel") }

        assertEquals(TestRomHelper.referenceInt("rooms.room.count"), root.int("roomCount"))
        assertEquals(TestRomHelper.referenceInt("rooms.state.count"), root.int("stateCount"))
        assertEquals(
            TestRomHelper.referenceInt("rooms.selector.conditional.count"),
            root.int("conditionalSelectorCount"),
        )
        assertPinnedHash(root, "roomHeaders")
        assertPinnedHash(root, "selectors")
        assertPinnedHash(root, "states")

        var checkedStates = 0
        rooms.forEach { expected ->
            val roomId = expected.int("roomId")
            val room = requireNotNull(parser.readRoomHeader(roomId)) { expected.string("sourceLabel") }
            assertEquals(expected.int("index"), room.index, expected.string("sourceLabel"))
            assertEquals(expected.int("area"), room.area, expected.string("sourceLabel"))
            assertEquals(expected.int("mapX"), room.mapX, expected.string("sourceLabel"))
            assertEquals(expected.int("mapY"), room.mapY, expected.string("sourceLabel"))
            assertEquals(expected.int("width"), room.width, expected.string("sourceLabel"))
            assertEquals(expected.int("height"), room.height, expected.string("sourceLabel"))
            assertEquals(expected.int("upScroller"), room.upScroller, expected.string("sourceLabel"))
            assertEquals(expected.int("downScroller"), room.downScroller, expected.string("sourceLabel"))
            assertEquals(expected.int("creBitflag"), room.creBitflag, expected.string("sourceLabel"))
            assertEquals(expected.int("doorListPtr"), room.doorOut, expected.string("sourceLabel"))

            val inspection = parser.inspectRoomStates(roomId)
            assertTrue(inspection.isComplete, expected.string("sourceLabel"))
            val selectors = expected.getValue("selectors").jsonArray.map { it.jsonObject }
            assertEquals(selectors.size, inspection.states.size, expected.string("sourceLabel"))
            selectors.zip(inspection.states).forEach { (source, inspected) ->
                assertEquals(source.int("index"), inspected.index, source.string("stateLabel"))
                assertEquals(source.string("conditionKind"), inspected.condition.kind.name, source.string("stateLabel"))
                assertEquals(source.int("routineCode"), inspected.condition.code, source.string("stateLabel"))
                assertEquals(source.nullableInt("argument"), inspected.condition.argument, source.string("stateLabel"))
                assertEquals(
                    source.int("selectorSnesAddress"),
                    parser.pcToSnes(inspected.selectorPcOffset),
                    source.string("stateLabel"),
                )
                val statePc = requireNotNull(inspected.stateDataPcOffset) { source.string("stateLabel") }
                assertEquals(source.int("stateSnesAddress"), parser.pcToSnes(statePc), source.string("stateLabel"))
                assertStateData(parser.readStateData(statePc), states.getValue(source.string("stateLabel")))
                checkedStates++
            }
        }
        assertEquals(root.int("stateCount"), checkedStates)
    }

    @Tag("parity")
    @Test
    fun `all extracted room level streams match source and production decoding`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        val levels = root.getValue("levels").jsonArray.map { it.jsonObject }

        assertEquals(TestRomHelper.referenceInt("rooms.level.total.count"), levels.size)
        assertEquals(
            TestRomHelper.referenceInt("rooms.level.active.count"),
            levels.count { !it.getValue("unused").jsonPrimitive.boolean },
        )
        assertEquals(
            TestRomHelper.referenceInt("rooms.level.unused.count"),
            levels.count { it.getValue("unused").jsonPrimitive.boolean },
        )
        assertEquals(
            TestRomHelper.referenceInt("rooms.level.layer2.count"),
            levels.count { it.getValue("hasLayer2").jsonPrimitive.boolean },
        )
        assertPinnedHash(root, "levels")

        val dimensionMismatches = mutableListOf<String>()
        levels.forEach { level ->
            val (decoded, consumed) = parser.decompressLZ2WithSize(level.int("snesAddress"))
            assertEquals(level.int("compressedSize"), consumed, level.string("sourceLabel"))
            assertEquals(level.int("decompressedSize"), decoded.size, level.string("sourceLabel"))
            assertEquals(level.string("decompressedSha256"), TestRomHelper.sha256(decoded), level.string("sourceLabel"))
            assertEquals(level.int("layer1ByteCount"), readU16(decoded, 0), level.string("sourceLabel"))
            assertEquals(level.int("blockCount"), readU16(decoded, 0) / 2, level.string("sourceLabel"))
            val expectedSize = 2 + level.int("layer1ByteCount") + level.int("btsByteCount") +
                level.int("layer2ByteCount")
            assertEquals(expectedSize, decoded.size, level.string("sourceLabel"))
            if (!level.getValue("consumerDimensionsMatch").jsonPrimitive.boolean) {
                dimensionMismatches += level.string("sourceLabel")
            }
        }
        assertEquals(
            listOf("LevelData_BowlingAlley", "LevelData_DoubleChamber"),
            dimensionMismatches,
            "source-owned over-allocated layouts remain explicit",
        )
    }

    @Tag("parity")
    @Test
    fun `all state PLM enemy GFX FX scroll and door resources match production parsers`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        val resources = root.getValue("resources").jsonObject
        val counts = root.getValue("resourceCounts").jsonObject
        assertPinnedHash(root, "resources")
        assertPinnedHash(root, "doors")

        assertEquals(TestRomHelper.referenceInt("rooms.resource.plm.count"), counts.int("plm"))
        assertEquals(TestRomHelper.referenceInt("rooms.resource.enemyPopulation.count"), counts.int("enemyPopulation"))
        assertEquals(TestRomHelper.referenceInt("rooms.resource.enemyGfx.count"), counts.int("enemyGfx"))
        assertEquals(TestRomHelper.referenceInt("rooms.resource.fx.count"), counts.int("fx"))
        assertEquals(TestRomHelper.referenceInt("rooms.resource.scrollAssociation.count"), counts.int("scrollAssociation"))
        assertEquals(TestRomHelper.referenceInt("rooms.resource.scrollTable.count"), counts.int("scrollTable"))
        assertEquals(
            TestRomHelper.referenceInt("rooms.resource.scrollSentinelAssociation.count"),
            counts.int("scrollSentinelAssociation"),
        )
        assertEquals(TestRomHelper.referenceInt("rooms.resource.doorList.count"), counts.int("doorList"))
        assertEquals(TestRomHelper.referenceInt("rooms.resource.doorAssociation.count"), counts.int("doorAssociation"))
        assertEquals(TestRomHelper.referenceInt("rooms.resource.uniqueDoorDef.count"), counts.int("uniqueDoorDef"))

        resources.getValue("plm").jsonArray.map { it.jsonObject }.forEach { record ->
            val expected = record.getValue("entries").jsonArray.map { it.jsonObject }
            val actual = parser.parsePlmSet(record.int("pointer"))
            assertEquals(expected.size, actual.size, record.string("address"))
            expected.zip(actual).forEach { (source, parsed) ->
                assertEquals(source.int("id"), parsed.id)
                assertEquals(source.int("x"), parsed.x)
                assertEquals(source.int("y"), parsed.y)
                assertEquals(source.int("param"), parsed.param)
            }
            val serialized = RomParser.serializePlmSet(actual).map(Int::toByte).toByteArray()
            val pc = parser.snesToPc(record.int("snesAddress"))
            assertContentEquals(
                parser.getRomData().copyOfRange(pc, pc + record.int("byteCount")),
                serialized,
                record.string("address"),
            )
        }

        resources.getValue("enemyPopulation").jsonArray.map { it.jsonObject }.forEach { record ->
            val expected = record.getValue("entries").jsonArray.map { it.jsonObject }
            val actual = parser.parseEnemyPopulation(record.int("pointer"))
            assertEquals(expected.size, actual.size, record.string("address"))
            expected.zip(actual).forEach { (source, parsed) ->
                assertEquals(source.int("id"), parsed.id)
                assertEquals(source.int("x"), parsed.x)
                assertEquals(source.int("y"), parsed.y)
                assertEquals(source.int("initParam"), parsed.initParam)
                assertEquals(source.int("properties"), parsed.properties)
                assertEquals(source.int("extra1"), parsed.extra1)
                assertEquals(source.int("extra2"), parsed.extra2)
                assertEquals(source.int("extra3"), parsed.extra3)
            }
        }

        resources.getValue("enemyGfx").jsonArray.map { it.jsonObject }.forEach { record ->
            val expected = record.getValue("entries").jsonArray.map { it.jsonObject }
            val actual = parser.parseEnemyGfxSet(record.int("pointer"))
            assertEquals(expected.size, actual.size, record.string("address"))
            expected.zip(actual).forEach { (source, parsed) ->
                assertEquals(source.int("speciesId"), parsed.speciesId)
                assertEquals(source.int("paletteIndex"), parsed.paletteIndex)
            }
            assertTrue(actual.size <= 4, record.string("address"))
        }

        resources.getValue("fx").jsonArray.map { it.jsonObject }.forEach { record ->
            val expected = record.getValue("entries").jsonArray.map { it.jsonObject }
            val actual = parser.parseFxEntries(record.int("pointer"))
            assertEquals(expected.size, actual.size, record.string("address"))
            expected.zip(actual).forEach { (source, parsed) ->
                assertEquals(source.int("doorSelect"), parsed.doorSelect)
                assertEquals(source.int("liquidSurfaceStart"), parsed.liquidSurfaceStart)
                assertEquals(source.int("liquidSurfaceNew"), parsed.liquidSurfaceNew)
                assertEquals(source.int("liquidSpeed"), parsed.liquidSpeed)
                assertEquals(source.int("liquidDelay"), parsed.liquidDelay)
                assertEquals(source.int("fxType"), parsed.fxType)
                assertEquals(source.int("fxBitA"), parsed.fxBitA)
                assertEquals(source.int("fxBitB"), parsed.fxBitB)
                assertEquals(source.int("fxBitC"), parsed.fxBitC)
                assertEquals(source.int("paletteFxBitflags"), parsed.paletteFxBitflags)
                assertEquals(source.int("tileAnimBitflags"), parsed.tileAnimBitflags)
                assertEquals(source.int("paletteBlend"), parsed.paletteBlend)
            }
        }

        val roomsById = root.getValue("rooms").jsonArray.map { it.jsonObject }.associateBy { it.int("roomId") }
        resources.getValue("scroll").jsonArray.map { it.jsonObject }.forEach { record ->
            val room = roomsById.getValue(record.int("roomId"))
            val actual = parser.parseScrollData(record.int("pointer"), room.int("width"), room.int("height"))
            val expected = record.getValue("values").jsonArray.map { it.jsonPrimitive.int }.toIntArray()
            assertContentEquals(expected, actual, "${record.string("roomLabel")} ${record.string("stateLabel")}")
        }
        val nonCanonicalScrolls = resources.getValue("scroll").jsonArray
            .map { it.jsonObject }
            .filter { it.getValue("nonCanonicalRawValues").jsonArray.isNotEmpty() }
        assertEquals(1, nonCanonicalScrolls.size)
        assertEquals("RoomHeader_DoubleChamber", nonCanonicalScrolls.single().string("roomLabel"))
        assertContentEquals(IntArray(8) { 2 }, parser.parseScrollData(0xADDA, 4, 2))

        root.getValue("doors").jsonArray.map { it.jsonObject }.forEach { record ->
            val sourceEntries = record.getValue("entries").jsonArray.map { it.jsonObject }
            val expected = sourceEntries.takeWhile { it.int("destRoomPtr") != 0 }
            val actual = parser.parseDoorList(record.int("snesAddress") and 0xFFFF)
            assertEquals(expected.size, actual.size, record.string("sourceLabel"))
            expected.zip(actual).forEach { (source, parsed) ->
                assertEquals(source.int("doorDefPtr"), parsed.doorDefPtr)
                assertEquals(source.int("destRoomPtr"), parsed.destRoomPtr)
                assertEquals(
                    source.int("elevatorProperties") or (source.int("direction") shl 8),
                    parsed.bitflag,
                )
                assertEquals(source.int("doorCapX") or (source.int("doorCapY") shl 8), parsed.doorCapCode)
                assertEquals(source.int("screenX"), parsed.screenX)
                assertEquals(source.int("screenY"), parsed.screenY)
                assertEquals(source.int("spawnDistance"), parsed.distFromDoor)
                assertEquals(source.int("entryCode"), parsed.entryCode)
            }
        }
    }

    @Tag("parity")
    @Test
    fun `no-edit room export is byte exact and every header and state field mutation stays allowlisted`() {
        val original = TestRomHelper.requireRomBytes()
        val untouched = original.copyOf()
        val emptyResult = ProjectRoomExporter(
            SmEditProject("base.sfc"),
            RomParser(untouched),
            untouched,
        ).exportRooms()
        assertTrue(emptyResult.roomsPatched.isEmpty())
        assertContentEquals(original, untouched, "empty project export")

        val root = loadManifest()
        val rooms = root.getValue("rooms").jsonArray.map { it.jsonObject }
        val parser = RomParser(original)
        val project = SmEditProject("base.sfc")
        val expectedOffsets = mutableSetOf<Int>()
        rooms.forEach { sourceRoom ->
            val roomId = sourceRoom.int("roomId")
            val edits = project.getOrCreateRoom(roomId)
            edits.roomHeaderChange = RoomHeaderChange(upScroller = sourceRoom.int("upScroller") xor 1)
            expectedOffsets += parser.roomIdToPc(roomId) + 6
            val stateManifest = edits.ensureStateManifest(parser)
            val inspected = parser.inspectRoomStates(roomId).states
            stateManifest.forEachIndexed { index, state ->
                val statePc = requireNotNull(inspected[index].stateDataPcOffset)
                val originalTrack = parser.getRomData()[statePc + 5].toInt() and 0xFF
                state.stateDataChange = StateDataChange(musicTrack = originalTrack xor 1)
                expectedOffsets += statePc + 5
            }
        }

        val mutated = original.copyOf()
        ProjectRoomExporter(project, RomParser(mutated), mutated).exportRooms()
        val actualOffsets = original.indices.filterTo(mutableSetOf()) { original[it] != mutated[it] }
        assertEquals(expectedOffsets, actualOffsets, "complete room/state one-byte mutation allowlist")
        expectedOffsets.forEach { offset ->
            assertEquals((original[offset].toInt() and 0xFF) xor 1, mutated[offset].toInt() and 0xFF)
        }
    }

    @Tag("parity")
    @Test
    fun `explicit no-op edits never rewrite or relocate room resources`() {
        val original = TestRomHelper.requireRomBytes()
        val root = loadManifest()
        val rooms = root.getValue("rooms").jsonArray.map { it.jsonObject }
        val roomsById = rooms.associateBy { it.int("roomId") }
        val resources = root.getValue("resources").jsonObject

        fun verify(label: String, configure: (SmEditProject, RomParser) -> Unit) {
            val rom = original.copyOf()
            val parser = RomParser(rom)
            val project = SmEditProject("base.sfc")
            configure(project, parser)
            val result = ProjectRoomExporter(project, parser, rom).exportRooms()
            assertTrue(result.allocations.isEmpty(), "$label unexpectedly relocated a ROM resource")
            var changedBytes = 0
            val firstChanges = mutableListOf<String>()
            original.indices.forEach { offset ->
                if (original[offset] != rom[offset]) {
                    changedBytes++
                    if (firstChanges.size < 16) {
                        firstChanges += "0x${offset.toString(16).uppercase()}:" +
                            "${(original[offset].toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')}->" +
                            (rom[offset].toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
                    }
                }
            }
            assertEquals(
                0,
                changedBytes,
                "$label changed $changedBytes ROM byte(s); first changes: ${firstChanges.joinToString()}",
            )
        }

        fun stateFor(
            project: SmEditProject,
            parser: RomParser,
            consumer: JsonObject,
        ): com.supermetroid.editor.data.RoomStateEdits {
            val roomId = consumer.int("roomId")
            val stateIndex = roomsById.getValue(roomId).getValue("selectors").jsonArray
                .indexOfFirst { it.jsonObject.string("stateLabel") == consumer.string("stateLabel") }
            assertTrue(stateIndex >= 0, consumer.string("stateLabel"))
            return project.getOrCreateRoom(roomId).ensureStateManifest(parser)[stateIndex]
        }

        verify("header and state fields") { project, parser ->
            // A room-level state-data change intentionally broadcasts to every
            // state. Use a one-state room so this is a genuine no-op rather than
            // flattening meaningful differences between conditional states.
            val roomId = rooms.first { it.getValue("selectors").jsonArray.size == 1 }.int("roomId")
            val room = requireNotNull(parser.readRoomHeader(roomId))
            val edits = project.getOrCreateRoom(roomId)
            edits.roomHeaderChange = RoomHeaderChange(upScroller = room.upScroller)
            edits.ensureStateManifest(parser).first().stateDataChange =
                StateDataChange(musicTrack = room.musicTrack)
        }

        val level = root.getValue("levels").jsonArray.map { it.jsonObject }.first { record ->
            !record.getValue("unused").jsonPrimitive.boolean &&
                record.int("consumerCount") == 1 &&
                record.getValue("consumerDimensionsMatch").jsonPrimitive.boolean &&
                roomsById.getValue(record.getValue("consumers").jsonArray.single().jsonObject.int("roomId"))
                    .getValue("selectors").jsonArray.size == 1
        }
        verify("level data") { project, parser ->
            val consumer = level.getValue("consumers").jsonArray.single().jsonObject
            val roomId = consumer.int("roomId")
            val decoded = parser.decompressLZ2(level.int("snesAddress"))
            val layer1Bytes = readU16(decoded, 0)
            val blockWord = readU16(decoded, 2)
            val bts = decoded[2 + layer1Bytes].toInt() and 0xFF
            project.getOrCreateRoom(roomId).operations += EditOperation(
                "parity no-op",
                edits = listOf(TileEdit(0, 0, blockWord, blockWord, bts, bts)),
            )
        }

        val plm = resources.getValue("plm").jsonArray.map { it.jsonObject }
            .first { it.int("consumerCount") == 1 && it.getValue("entries").jsonArray.isNotEmpty() }
        verify("PLM population") { project, parser ->
            val consumer = plm.getValue("consumers").jsonArray.single().jsonObject
            val entry = plm.getValue("entries").jsonArray.last().jsonObject
            stateFor(project, parser, consumer).plmChanges += listOf(
                PlmChange("remove", entry.int("id"), entry.int("x"), entry.int("y"), entry.int("param")),
                PlmChange("add", entry.int("id"), entry.int("x"), entry.int("y"), entry.int("param")),
            )
        }

        val enemy = resources.getValue("enemyPopulation").jsonArray.map { it.jsonObject }
            .first { it.int("consumerCount") == 1 && it.getValue("entries").jsonArray.isNotEmpty() }
        verify("enemy population and GFX") { project, parser ->
            val consumer = enemy.getValue("consumers").jsonArray.single().jsonObject
            val entry = enemy.getValue("entries").jsonArray.last().jsonObject
            val state = stateFor(project, parser, consumer)
            state.enemyChanges += EnemyChange(
                action = "remove", enemyId = entry.int("id"), x = entry.int("x"), y = entry.int("y"),
                origX = entry.int("x"), origY = entry.int("y"),
            )
            state.enemyChanges += EnemyChange(
                action = "add", enemyId = entry.int("id"), x = entry.int("x"), y = entry.int("y"),
                initParam = entry.int("initParam"), properties = entry.int("properties"),
                extra1 = entry.int("extra1"), extra2 = entry.int("extra2"), extra3 = entry.int("extra3"),
            )
        }

        val fx = resources.getValue("fx").jsonArray.map { it.jsonObject }.first { record ->
            record.int("consumerCount") == 1 && record.getValue("entries").jsonArray
                .any { it.jsonObject.int("doorSelect") == 0 }
        }
        verify("FX") { project, parser ->
            val consumer = fx.getValue("consumers").jsonArray.single().jsonObject
            val entry = fx.getValue("entries").jsonArray
                .first { it.jsonObject.int("doorSelect") == 0 }.jsonObject
            stateFor(project, parser, consumer).fxChange = FxChange(fxType = entry.int("fxType"))
        }

        val scroll = resources.getValue("scroll").jsonArray.map { it.jsonObject }.first()
        verify("scroll table") { project, parser ->
            val state = stateFor(project, parser, scroll)
            val value = scroll.getValue("values").jsonArray.first().jsonPrimitive.int
            state.scrollChanges += ScrollChange(0, 0, value, value)
        }

        val door = root.getValue("doors").jsonArray.map { it.jsonObject }
            .first { record -> record.getValue("entries").jsonArray.any { it.jsonObject.int("destRoomPtr") != 0 } }
        verify("door data") { project, _ ->
            val entry = door.getValue("entries").jsonArray.first { it.jsonObject.int("destRoomPtr") != 0 }.jsonObject
            val doorIndex = door.getValue("entries").jsonArray.indexOf(entry)
            project.getOrCreateRoom(door.int("roomId")).doorChanges += DoorChange(
                doorIndex = doorIndex,
                destRoomPtr = entry.int("destRoomPtr"),
                bitflag = entry.int("elevatorProperties") or (entry.int("direction") shl 8),
                doorCapCode = entry.int("doorCapX") or (entry.int("doorCapY") shl 8),
                screenX = entry.int("screenX"), screenY = entry.int("screenY"),
                distFromDoor = entry.int("spawnDistance"), entryCode = entry.int("entryCode"),
            )
        }
    }

    private fun assertStateData(actual: Map<String, Int>, expected: JsonObject) {
        val mappings = mapOf(
            "levelDataPtr" to "levelDataPtr", "tileset" to "tileset",
            "musicData" to "musicData", "musicTrack" to "musicTrack", "fxPtr" to "fxPtr",
            "enemySetPtr" to "enemySetPtr", "enemyGfxPtr" to "enemyGfxPtr",
            "bgScrolling" to "bgScrolling", "roomScrollsPtr" to "scrollPtr",
            "xraySpecialCasingPtr" to "xraySpecialCasingPtr", "mainAsmPtr" to "mainAsmPtr",
            "plmSetPtr" to "plmSetPtr", "bgDataPtr" to "bgDataPtr", "setupAsmPtr" to "setupAsmPtr",
        )
        mappings.forEach { (actualName, expectedName) ->
            assertEquals(expected.int(expectedName), actual[actualName], expected.string("sourceLabel"))
        }
    }

    private fun assertPinnedHash(root: JsonObject, name: String) {
        assertEquals(
            TestRomHelper.referenceString("rooms.$name.aggregate.sha256"),
            root.getValue("aggregateHashes").jsonObject.string(name),
            name,
        )
    }

    private fun loadManifest(): JsonObject = Json.parseToJsonElement(
        TestRomHelper.requireParityReport("rooms.json", "parityRooms").readText(),
    ).jsonObject

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
    private fun JsonObject.nullableInt(name: String): Int? = getValue(name).jsonPrimitive.contentOrNull?.toIntOrNull()

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
