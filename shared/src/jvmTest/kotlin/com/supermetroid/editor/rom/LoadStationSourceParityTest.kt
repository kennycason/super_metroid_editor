package com.supermetroid.editor.rom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LoadStationSourceParityTest {

    @Tag("parity")
    @Test
    fun `all load station lists and runtime spawn fields match source`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        assertPinnedCounts(root)
        assertPinnedHash(root, "pointers")
        assertPinnedHash(root, "entries")

        root.getValue("areas").jsonArray.map { it.jsonObject }.forEach { area ->
            val areaIndex = area.int("area")
            assertEquals(area.int("snesAddress"), parser.readLoadStationListAddress(areaIndex), area.string("name"))
            assertEquals(area.int("entryCount"), parser.saveEntryCount(areaIndex), area.string("name"))
            area.getValue("entries").jsonArray.map { it.jsonObject }.forEach { expected ->
                val actual = assertNotNull(parser.readSaveEntry(areaIndex, expected.int("index")))
                assertEquals(expected.int("roomId"), actual.roomId)
                assertEquals(expected.int("doorPtr"), actual.doorPtr)
                assertEquals(expected.int("doorBts"), actual.doorBts)
                assertEquals(expected.int("scrollX"), actual.scrollX)
                assertEquals(expected.int("scrollY"), actual.scrollY)
                assertEquals(expected.int("samusY"), actual.samusY)
                assertEquals(expected.int("samusX"), actual.samusX)
                assertEquals(parser.snesToPc(expected.int("snesAddress")), actual.pcOffset)
            }
            assertNull(parser.readSaveEntry(areaIndex, area.int("entryCount")))
        }
    }

    @Tag("parity")
    @Test
    fun `save PLMs own exactly the addressable save entries`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        val placements = root.getValue("savePlms").jsonArray.map { it.jsonObject }
        assertEquals(TestRomHelper.referenceInt("loadStations.savePlm.count"), placements.size)

        placements.forEach { placement ->
            val entries = parser.parsePlmSet(placement.int("populationPointer"))
            val plm = entries[placement.int("entryIndex")]
            assertEquals(0xB76F, plm.id)
            assertEquals(placement.int("x"), plm.x)
            assertEquals(placement.int("y"), plm.y)
            assertEquals(placement.int("param"), plm.param)
            assertEquals(placement.int("saveIndex"), plm.param and 7)
            assertEquals(
                placement.int("roomId"),
                parser.readSaveEntry(placement.int("area"), placement.int("saveIndex"))?.roomId,
            )
        }
        assertPinnedHash(root, "savePlms")
    }

    @Tag("parity")
    @Test
    fun `special elevator debug Ceres and gunship entries stay distinct`() {
        val root = loadManifest()
        val entries = root.getValue("areas").jsonArray
            .flatMap { it.jsonObject.getValue("entries").jsonArray }
            .map { it.jsonObject }

        assertEquals(14, entries.count { it.string("kind") == "elevator" && !it.bool("empty") })
        assertEquals(37, entries.count { it.string("kind") == "debug" })
        assertEquals(17, entries.count { it.string("kind") == "ceresSequence" })
        val landing = entries.single { it.string("kind") == "gunshipLanding" }
        assertEquals(0, landing.int("area"))
        assertEquals(0x12, landing.int("index"))
        assertEquals(0x91F8, landing.int("roomId"))

        val unmatched = root.getValue("unmatchedOccupiedSaveSlots").jsonArray.map { it.jsonObject }
        assertEquals(1, unmatched.size)
        assertEquals(0, unmatched.single().int("area"))
        assertEquals(0, unmatched.single().int("index"))
        assertEquals(0x91F8, unmatched.single().int("roomId"))
        assertPinnedHash(root, "consumers")
    }

    private fun assertPinnedCounts(root: JsonObject) {
        val fields = mapOf(
            "areaCount" to "loadStations.area.count",
            "entryCount" to "loadStations.entry.count",
            "entryByteCount" to "loadStations.entry.byte.count",
            "saveSlotCount" to "loadStations.saveSlot.count",
            "occupiedSaveSlotCount" to "loadStations.saveSlot.occupied.count",
            "emptySaveSlotCount" to "loadStations.saveSlot.empty.count",
            "savePlmPlacementCount" to "loadStations.savePlm.count",
            "elevatorEntryCount" to "loadStations.elevator.count",
            "occupiedElevatorEntryCount" to "loadStations.elevator.occupied.count",
            "debugEntryCount" to "loadStations.debug.count",
            "occupiedDebugEntryCount" to "loadStations.debug.occupied.count",
            "ceresSequenceEntryCount" to "loadStations.ceresSequence.count",
            "gunshipLandingEntryCount" to "loadStations.gunshipLanding.count",
            "doorBtsNonzeroCount" to "loadStations.doorBts.nonzero.count",
            "engineConsumerCount" to "loadStations.engineConsumer.count",
        )
        fields.forEach { (field, property) ->
            assertEquals(TestRomHelper.referenceInt(property), root.int(field), field)
        }
    }

    private fun assertPinnedHash(root: JsonObject, name: String) {
        assertEquals(
            TestRomHelper.referenceString("loadStations.$name.aggregate.sha256"),
            root.getValue("aggregateHashes").jsonObject.string(name),
            name,
        )
    }

    private fun loadManifest(): JsonObject = Json.parseToJsonElement(
        TestRomHelper.requireParityReport("load-stations.json", "parityLoadStations").readText(),
    ).jsonObject

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
    private fun JsonObject.bool(name: String): Boolean = getValue(name).jsonPrimitive.boolean
}
