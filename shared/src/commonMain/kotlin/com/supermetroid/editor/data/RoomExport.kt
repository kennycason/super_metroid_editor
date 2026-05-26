package com.supermetroid.editor.data

import kotlinx.serialization.Serializable

/**
 * Self-contained room data for JSON export/import.
 * Includes all data needed to reconstruct a room: level data, enemies, PLMs,
 * scrolls, doors, FX, and metadata.
 */
@Serializable
data class RoomExportData(
    val version: Int = 1,
    val roomId: String,          // hex, e.g., "91F8"
    val roomName: String,
    val width: Int,              // screens
    val height: Int,             // screens
    val tileset: Int,
    val area: Int,
    val levelDataBase64: String, // base64 of decompressed level data
    val scrollData: List<Int>,   // one byte per screen (0=red, 1=blue, 2=green)
    val enemies: List<EnemyExport> = emptyList(),
    val plms: List<PlmExport> = emptyList(),
    val doors: List<DoorExport> = emptyList(),
    val fx: FxExport? = null,
    val musicTrack: Int = 0,
    val musicControl: Int = 0,
)

@Serializable
data class EnemyExport(
    val species: String,    // hex species ID
    val x: Int, val y: Int,
    val initParam: Int = 0,
    val properties: Int = 0,
    val extra1: Int = 0,
    val extra2: Int = 0,
    val extra3: Int = 0,
)

@Serializable
data class PlmExport(
    val id: String,     // hex PLM ID
    val x: Int, val y: Int,
    val param: Int = 0,
)

@Serializable
data class DoorExport(
    val destRoom: String,   // hex room ID
    val bitflag: Int = 0,
    val direction: Int = 0,
    val doorCapCode: Int = 0,
    val screenX: Int = 0, val screenY: Int = 0,
    val distFromDoor: Int = 0,
    val entryCode: Int = 0,
)

@Serializable
data class FxExport(
    val type: Int = 0,
    val surfaceStart: Int = 0,
    val surfaceNew: Int = 0,
    val surfaceSpeed: Int = 0,
    val surfaceDelay: Int = 0,
)
