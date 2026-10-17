package com.supermetroid.editor.rom

import com.supermetroid.editor.data.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class SourceDoorReferencesTest {
    private val reference = ProjectSourceDoorReference(0x91F8, 7)
    private val incoming = projectRoomStateCondition(ProjectRoomStateConditionKind.INCOMING_DOOR, sourceDoor = reference)
    private fun doors(destination: Int = 0xA000) = mapOf(0x91F8 to SourceRoomDoors(0x8060, listOf(
        SourceRoomDoor(0, DoorChange(0, destination, 0, 0, 0, 0, 0x8000, 0), identity = 7)), structural = true))

    @Test fun `symbolic conditions serialize identities and resolve nested references without mutating their source`() {
        val tree = projectRoomStateCondition(ProjectRoomStateConditionKind.ANY_OF, children = listOf(incoming,
            projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 7, true)), negated = true)
        val json = Json.encodeToString(ProjectRoomStateCondition.serializer(), tree)
        assertEquals(tree, Json.decodeFromString<ProjectRoomStateCondition>(json))
        assertNull(tree.children.first().argument)
        assertContains(tree.displaySummary(0), "door ID 7")
        val resolved = tree.resolveSourceDoors(0xA000, doors(), mapOf(reference to 0xF000))
        assertEquals(0xF000, resolved.children.first().argument)
        assertNull(resolved.children.first().sourceDoor)
        assertEquals(reference, tree.children.first().sourceDoor)
        assertContentEquals(SmEditCompiledRoomStateConditionFormat.expressionBytes(tree.copy(children = listOf(
            projectRoomStateCondition(ProjectRoomStateConditionKind.INCOMING_DOOR, 0xF000), tree.children[1])), 0),
            SmEditCompiledRoomStateConditionFormat.expressionBytes(resolved, 0))
        assertFails { SmEditCompiledRoomStateConditionFormat.expressionBytes(tree, 0) }
    }

    @Test fun `missing retired wrong destination ambiguous or conflicting references fail closed`() {
        assertFails { incoming.resolveSourceDoors(0xA000, emptyMap(), mapOf(reference to 0xF000)) }
        assertFails { incoming.resolveSourceDoors(0xA000, doors(), emptyMap()) }
        assertFails { incoming.resolveSourceDoors(0xA000, doors(), mapOf(reference to 0)) }
        assertFails { incoming.resolveSourceDoors(0xA000, doors(0xB000), mapOf(reference to 0xF000)) }
        assertFails { incoming.copy(argument = 0x8000).resolveSourceDoors(0xA000, doors(), mapOf(reference to 0xF000)) }
        assertFails { projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, sourceDoor = reference) }
        assertFails { projectRoomStateCondition(ProjectRoomStateConditionKind.INCOMING_DOOR, 0x8000, sourceDoor = reference) }
        assertNull(ProjectSourceDoorReference.fromEntryLabel("${reference.entryLabel}+2"))
        assertEquals(reference, ProjectSourceDoorReference.fromEntryLabel(reference.entryLabel))
        assertNull(ProjectSourceDoorReference.fromEntryLabel("SmeditDoors_91F8_Door_07_Entry"))
    }

    @Test fun `simulator never aliases unrelated unallocated doors or treats null as an incoming match`() {
        assertTrue(incoming.matches(RoomStateSimulationContext(0, incomingSourceDoor = reference)))
        assertFalse(incoming.matches(RoomStateSimulationContext(0)))
        assertFalse(incoming.matches(RoomStateSimulationContext(0, incomingDoorPointer = 0)))
        assertFalse(incoming.matches(RoomStateSimulationContext(0, incomingSourceDoor = reference.copy(identity = 8))))
        assertTrue(incoming.copy(negated = true).matches(RoomStateSimulationContext(0)))
        assertFalse(projectRoomStateCondition(ProjectRoomStateConditionKind.INCOMING_DOOR, 0x8000)
            .matches(RoomStateSimulationContext(0, incomingSourceDoor = reference)))
    }

    @Test fun `FX and save references resolve only in build copies and never encode placeholder zero`() {
        val fx = SourceFxResource("symbolic FX", listOf(emptyDefaultFxEntry().copy(sourceDoor = reference, fxType = 4), emptyDefaultFxEntry()))
        val save = SourceSaveResource("symbolic save", 0xA000, 1, 2, listOf(0xA000, 0, 7, 256, 0, 144, 0xFFE0), reference)
        assertFalse(fx.entries.first().isDefault)
        assertFalse(save.cleared)
        save.validate()
        assertFails { fx.bytes() }; assertFails { save.bytes() }
        val resolvedFx = fx.resolveSourceDoors(0xA000, doors(), mapOf(reference to 0xF000))
        val resolvedSave = save.resolveSourceDoors(doors(), mapOf(reference to 0xF000))
        assertEquals(0xF000, resolvedFx.entries.first().doorSelect)
        assertEquals(0xF000, resolvedSave.words[1])
        assertNull(resolvedFx.entries.first().sourceDoor); assertNull(resolvedSave.sourceDoor)
        assertEquals(32, resolvedFx.bytes().size); assertEquals(14, resolvedSave.bytes().size)
        assertEquals(reference, fx.entries.first().sourceDoor); assertEquals(reference, save.sourceDoor)
        for (bad in listOf(emptyMap(), doors(0xB000))) {
            assertFails { fx.resolveSourceDoors(0xA000, bad, mapOf(reference to 0xF000)) }
            assertFails { save.resolveSourceDoors(bad, mapOf(reference to 0xF000)) }
        }
        assertFails { save.copy(words = List(7) { 0 }).validate() }
        assertFails { fx.copy(entries = listOf(emptyDefaultFxEntry(), fx.entries.first())) }
        assertFails { fx.copy(entries = listOf(fx.entries.first(), fx.entries.first())) }
        assertFails { fx.copy(entries = listOf(fx.entries.first().copy(doorSelect = 0x8000))) }
        assertFails { save.copy(words = save.words.toMutableList().also { it[1] = 0x8000 }).validate() }
    }

    @Test fun `FX numeric and symbolic aliases cannot shadow one another`() {
        val source = doors().mapValues { (_, resource) -> resource.copy(entries = resource.entries.map { it.copy(doorDefPtr = 0x9000) }) }
        val fx = SourceFxResource("alias", listOf(emptyDefaultFxEntry().copy(sourceDoor = reference),
            emptyDefaultFxEntry().copy(doorSelect = 0x9000), emptyDefaultFxEntry()))
        assertFails { validateSourceFxDoors(0xA000, fx, RomParser(ByteArray(0x300000)), source) }
        assertFails { fx.resolveSourceDoors(0xA000, source, mapOf(reference to 0x9000)) }
    }
}
