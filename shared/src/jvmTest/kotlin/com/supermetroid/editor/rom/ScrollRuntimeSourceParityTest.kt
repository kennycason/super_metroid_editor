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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrollRuntimeSourceParityTest {

    @Tag("parity")
    @Test
    fun `all generic scroll PLM commands and extension chains match source`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        val plms = root.getValue("genericPlms").jsonObject

        assertEquals(TestRomHelper.referenceInt("scrollRuntime.plm.trigger.count"), plms.int("triggerCount"))
        assertEquals(TestRomHelper.referenceInt("scrollRuntime.plm.solidTrigger.count"), plms.int("solidTriggerCount"))
        assertEquals(TestRomHelper.referenceInt("scrollRuntime.plm.population.count"), plms.int("populationCount"))
        assertEquals(TestRomHelper.referenceInt("scrollRuntime.plm.stateAssociation.count"), plms.int("stateAssociationCount"))
        assertEquals(TestRomHelper.referenceInt("scrollRuntime.commandStream.count"), plms.int("uniqueCommandStreamCount"))
        assertEquals(TestRomHelper.referenceInt("scrollRuntime.command.count"), plms.int("commandCount"))
        assertEquals(TestRomHelper.referenceInt("scrollRuntime.extension.count"), plms.int("extensionCount"))
        assertEquals(TestRomHelper.referenceInt("scrollRuntime.extension.orphan.count"), plms.int("orphanExtensionCount"))
        assertPinnedHash(root, "commandStreams")
        assertPinnedHash(root, "extensions")

        plms.getValue("commandStreams").jsonArray.map { it.jsonObject }.forEach { stream ->
            val expected = stream.getValue("commands").jsonArray.map { command ->
                val record = command.jsonObject
                record.int("screenIndex") to record.int("scrollValue")
            }
            val actual = RomParser.decodeScrollCommands(parser, stream.int("pointer"), roomWidth = 1)
                .map { (screenIndex, _, scrollValue) -> screenIndex to scrollValue }
            assertEquals(expected, actual, stream.string("sourceLabel"))
            assertTrue(expected.all { (_, value) -> value in 0..2 }, stream.string("sourceLabel"))
        }

        val roomManifest = loadRoomManifest()
        val populations = roomManifest.getValue("resources").jsonObject.getValue("plm").jsonArray
            .map { it.jsonObject }
            .associateBy { it.int("pointer") }
        val extensionRecords = plms.getValue("extensions").jsonArray.map { it.jsonObject }
        extensionRecords.forEach { expected ->
            val population = populations.getValue(expected.int("populationPointer"))
            val entries = population.getValue("entries").jsonArray.map { entry ->
                val record = entry.jsonObject
                RomParser.PlmEntry(
                    id = record.int("id"),
                    x = record.int("x"),
                    y = record.int("y"),
                    param = record.int("param"),
                )
            }
            val extension = entries[expected.int("entryIndex")]
            assertEquals(
                expected.boolean("connected"),
                scrollExtensionConnectsToTrigger(extension, entries),
                "${population.string("address")} entry ${expected.int("entryIndex")}",
            )
        }
        val orphans = plms.getValue("orphanExtensions").jsonArray.map { it.jsonObject }
        assertEquals(1, orphans.size)
        assertEquals(0xC6AD, orphans.single().int("populationPointer"))
        assertEquals(10, orphans.single().int("x"))
        assertEquals(18, orphans.single().int("y"))
    }

    @Tag("parity")
    @Test
    fun `all active door ASM scroll writes match source and mixed routines fail closed`() {
        val parser = TestRomHelper.requireRomParser()
        val root = loadManifest()
        val doors = root.getValue("doors").jsonObject

        assertEquals(TestRomHelper.referenceInt("scrollRuntime.door.nonzeroAssociation.count"), doors.int("nonzeroValidAssociationCount"))
        assertEquals(TestRomHelper.referenceInt("scrollRuntime.door.nonzeroRoutine.count"), doors.int("nonzeroValidRoutineCount"))
        assertEquals(TestRomHelper.referenceInt("scrollRuntime.door.writerAssociation.count"), doors.int("scrollWriterAssociationCount"))
        assertEquals(TestRomHelper.referenceInt("scrollRuntime.door.writerRoutine.count"), doors.int("scrollWriterRoutineCount"))
        assertEquals(TestRomHelper.referenceInt("scrollRuntime.door.pureWriter.count"), doors.int("pureScrollRoutineCount"))
        assertEquals(TestRomHelper.referenceInt("scrollRuntime.door.mixedWriter.count"), doors.int("mixedScrollRoutineCount"))
        assertEquals(TestRomHelper.referenceInt("scrollRuntime.door.unusedWriter.count"), doors.int("sourceDeclaredUnusedWriterCount"))
        assertPinnedHash(root, "doorRoutines")

        val mixed = mutableListOf<String>()
        doors.getValue("scrollWriterRoutines").jsonArray.map { it.jsonObject }.forEach { routine ->
            val expectedWrites = routine.getValue("staticWrites").jsonArray.map { write ->
                val record = write.jsonObject
                DoorScrollWrite(
                    scrollValue = record.int("scrollValue"),
                    addressLowByte = 0x20 + record.int("screenIndex"),
                )
            }
            val analysis = analyzeDoorScrollAsm(parser, routine.int("entryCode"))
            assertEquals(expectedWrites, analysis.writes, routine.string("sourceLabel"))
            if (routine.boolean("pureScrollBySource")) {
                assertTrue(analysis.isPureScrollRoutine, routine.string("sourceLabel"))
            } else {
                assertFalse(analysis.isPureScrollRoutine, routine.string("sourceLabel"))
                assertTrue(analysis.hasOtherEffects, routine.string("sourceLabel"))
                mixed += routine.string("sourceLabel")
            }
        }
        assertEquals(listOf("DoorASM_ResetElevatubeOnSouthExit"), mixed)
    }

    @Tag("parity")
    @Test
    fun `complete direct writer inventory and load precedence match source`() {
        val root = loadManifest()
        val inventory = root.getValue("writerInventory").jsonObject
        assertEquals(TestRomHelper.referenceInt("scrollRuntime.writer.routine.count"), inventory.int("routineCount"))
        assertEquals(TestRomHelper.referenceInt("scrollRuntime.writer.storeSite.count"), inventory.int("storeSiteCount"))
        val categories = inventory.getValue("categoryCounts").jsonObject
        listOf("doorAsm", "plm", "fx", "enemy", "roomLoad", "demo").forEach { category ->
            assertEquals(
                TestRomHelper.referenceInt("scrollRuntime.writer.$category.count"),
                categories.int(category),
                category,
            )
        }
        assertPinnedHash(root, "writerInventory")

        val order = root.getValue("loadOrder").jsonObject
        assertEquals(
            listOf("staticScrolls", "plmSetup", "incomingDoorAsm", "roomSetupAsm"),
            order.getValue("precedence").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("82:E8C9", order.getValue("combinedPath").jsonObject.string("plmSpawnAddress"))
        assertEquals("82:E8D5", order.getValue("combinedPath").jsonObject.string("doorAsmCallAddress"))
        assertEquals("82:E8D9", order.getValue("combinedPath").jsonObject.string("setupAsmCallAddress"))
    }

    private fun assertPinnedHash(root: JsonObject, name: String) {
        assertEquals(
            TestRomHelper.referenceString("scrollRuntime.$name.aggregate.sha256"),
            root.getValue("aggregateHashes").jsonObject.string(name),
            name,
        )
    }

    private fun loadManifest(): JsonObject = Json.parseToJsonElement(
        TestRomHelper.requireParityReport("scroll-runtime.json", "parityScrollRuntime").readText(),
    ).jsonObject

    private fun loadRoomManifest(): JsonObject = Json.parseToJsonElement(
        TestRomHelper.requireParityReport("rooms.json", "parityRooms").readText(),
    ).jsonObject

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
    private fun JsonObject.boolean(name: String): Boolean = getValue(name).jsonPrimitive.boolean
}
