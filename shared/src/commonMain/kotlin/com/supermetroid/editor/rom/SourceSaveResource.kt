package com.supermetroid.editor.rom

import com.supermetroid.editor.data.SaveStationSpawnChange
import com.supermetroid.editor.data.ProjectSourceDoorReference

/** Fixed area/slot ABI. Values are transient decoded source, never a second project authority. */
data class SourceSaveResource(
    val identity: String,
    val ownerRoomId: Int,
    val area: Int,
    val slot: Int,
    /** Room, incoming door, door BTS, camera X/Y, Samus Y/X (seven unsigned words). */
    val words: List<Int>,
    /** Authored identity; words[1] is zero until the checked build allocation resolves it. */
    val sourceDoor: ProjectSourceDoorReference? = null,
) {
    val cleared: Boolean get() = sourceDoor == null && words.all { it == 0 }
    fun validate() {
        require(area in 0..5 && slot in 0..7) { "Ordinary save sources require area 0–5 and slot 0–7; special load stations are protected." }
        require(ownerRoomId in 0x8000..0xFFFE && words.size == 7 && words.all { it in 0..0xFFFF }) {
            "Save records require seven words and a valid owning room."
        }
        require(sourceDoor == null || words[1] == 0) { "A symbolic save door cannot also have a numeric pointer." }
        require(cleared || (words[0] == ownerRoomId && (sourceDoor != null || words[1] in 0x8000..0xFFF4))) {
            "A save spawn must point to its owning room and an incoming door; released slots contain seven zero words."
        }
    }
    fun bytes(): ByteArray {
        validate()
        require(sourceDoor == null) { "Resolve the symbolic save door before encoding." }
        return words.flatMap { listOf(it.toByte(), (it ushr 8).toByte()) }.toByteArray()
    }
}

fun RomParser.Companion.SaveEntry.words(): List<Int> = listOf(roomId, doorPtr, doorBts, scrollX, scrollY, samusY, samusX)

/** Do not classify the gunship or other special records as ordinary saves merely by their slot. */
fun validateSourceSaveOwnership(resource: SourceSaveResource, parser: RomParser, doors: Map<Int, SourceRoomDoors> = emptyMap()) {
    resource.validate()
    val base = requireNotNull(parser.readSaveEntry(resource.area, resource.slot)) { "Save source has no existing load-station slot." }
    require(base.roomId == 0 || base.roomId == resource.ownerRoomId) { "Save slot ${resource.area}:${resource.slot} belongs to another room." }
    if (base.roomId != 0) {
        require(parser.parseRoomStatesWithData(base.roomId).any { state ->
            parser.parsePlmSet(state.plmSetPtr).any { it.id == 0xB76F && (it.param and 0xFF) == resource.slot }
        }) { "Save slot ${resource.area}:${resource.slot} is an engine-owned load record (for example the gunship), not an ordinary save station." }
    }
    if (!resource.cleared) {
        if (resource.sourceDoor != null) {
            resource.sourceDoor.requireIncomingDoor(resource.ownerRoomId, doors)
            return
        }
        val live = doors.values.flatMap { it.entries }.filter { it.doorDefPtr == resource.words[1] }
        if (live.isNotEmpty()) {
            require(live.all { it.change.destRoomPtr == resource.ownerRoomId }) { "Save spawn door no longer enters the owning room." }
            return
        }
        val doorPc = parser.snesToPc(0x830000 or resource.words[1])
        require(doorPc >= 0 && doorPc + 12 <= parser.getRomData().size && parser.readUInt16At(doorPc) == resource.ownerRoomId) {
            "Save spawn door must enter the owning room."
        }
    }
}

/** Validate parameter-to-record relationships, including unchanged original records and old GUI overrides. */
fun validateSourceSaveLinks(roomId: Int, area: Int, entries: List<RomParser.PlmEntry>, saves: List<SourceSaveResource>,
                            legacy: List<SaveStationSpawnChange>, parser: RomParser, doors: Map<Int, SourceRoomDoors> = emptyMap()) {
    saves.forEach {
        require((it.cleared || it.area == area) && it.ownerRoomId == roomId) { "Save source area/room ownership changed." }
        require(legacy.none { old -> old.area == it.area && old.saveIndex == it.slot }) { "Save record has both GUI and source owners." }
        validateSourceSaveOwnership(it, parser, doors)
    }
    entries.filter { it.id == 0xB76F }.map { it.param and 255 }.distinct().forEach { slot ->
        require(slot in 0..7) { "Save PLM index must be 0–7." }
        val owned = saves.singleOrNull { it.area == area && it.slot == slot }
        if (owned != null) {
            require(!owned.cleared) { "Save PLM references a released spawn record." }
        } else {
            val old = legacy.singleOrNull { it.area == area && it.saveIndex == slot && !it.clearSlot }
            val native = requireNotNull(parser.readSaveEntry(area, slot)) { "Save PLM has no load-station record." }
            val words = old?.let { listOf(it.roomId, it.doorPtr, native.doorBts, it.scrollX, it.scrollY, it.samusY, it.samusX) } ?: native.words()
            require(words[0] == roomId) { "New save PLMs need a paired spawn record; add the station through the GUI first." }
            validateSourceSaveOwnership(SourceSaveResource("Original/legacy save", roomId, area, slot, words), parser, doors)
        }
    }
}

fun SourceSaveResource.resolveSourceDoors(doors: Map<Int, SourceRoomDoors>, placed: Map<ProjectSourceDoorReference, Int>): SourceSaveResource =
    sourceDoor?.let { copy(words = words.toMutableList().also { words -> words[1] = it.resolveIncomingDoor(ownerRoomId, doors, placed) },
        sourceDoor = null) } ?: this
