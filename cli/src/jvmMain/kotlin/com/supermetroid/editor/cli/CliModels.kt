package com.supermetroid.editor.cli

import kotlinx.serialization.Serializable

// ── Per-room full export ────────────────────────────────────────────

@Serializable
data class RoomExport(
    val roomId: Int,
    val roomIdHex: String,
    val handle: String,
    val name: String,
    val area: Int,
    val areaName: String,
    val mapX: Int,
    val mapY: Int,
    val widthScreens: Int,
    val heightScreens: Int,
    val widthBlocks: Int,
    val heightBlocks: Int,
    val tileset: Int,
    val collision: List<List<Int>>,
    val bts: List<List<Int>>,
    val doors: List<DoorExport>,
    val items: List<ItemExport>,
    val enemies: List<EnemyExport>,
    val plms: List<PlmExport>,
)

// ── Rooms list (lightweight) ────────────────────────────────────────

@Serializable
data class RoomSummary(
    val roomId: Int,
    val roomIdHex: String,
    val handle: String,
    val name: String,
    val area: Int,
    val areaName: String,
    val mapX: Int,
    val mapY: Int,
    val widthScreens: Int,
    val heightScreens: Int,
)

// ── Sub-objects ─────────────────────────────────────────────────────

@Serializable
data class DoorExport(
    val destRoomId: Int,
    val destRoomIdHex: String,
    val destRoomHandle: String?,
    val direction: String,
    val isElevator: Boolean,
    val doorCapColor: String?,
    val requiredAbility: String?,
)

@Serializable
data class ItemExport(
    val name: String,
    val blockX: Int,
    val blockY: Int,
    val plmId: Int,
    val plmIdHex: String,
)

@Serializable
data class EnemyExport(
    val id: Int,
    val idHex: String,
    val name: String,
    val pixelX: Int,
    val pixelY: Int,
    val blockX: Int,
    val blockY: Int,
)

@Serializable
data class PlmExport(
    val id: Int,
    val idHex: String,
    val blockX: Int,
    val blockY: Int,
    val param: Int,
    val category: String,
)

// ── Room-model training export ─────────────────────────────────────

@Serializable
data class RoomTrainingManifest(
    val schemaVersion: Int,
    val roomCount: Int,
    val skippedRoomCount: Int,
    val arrayLayout: String = "row-major",
    val layer1WordFormat: String = "bits 0-9 metatile, bit 10 h-flip, bit 11 v-flip, bits 12-15 block type",
    val btsFormat: String = "unsigned byte; meaning depends on block type (including slope shape)",
    val rooms: List<RoomTrainingIndex>,
)

@Serializable
data class RoomTrainingIndex(
    val roomId: Int,
    val roomIdHex: String,
    val handle: String,
    val name: String,
    val area: Int,
    val tileset: Int,
    val widthBlocks: Int,
    val heightBlocks: Int,
    val contentHash: String,
    val file: String,
)

@Serializable
data class RoomTrainingExport(
    val schemaVersion: Int,
    val roomId: Int,
    val roomIdHex: String,
    val handle: String,
    val name: String,
    val area: Int,
    val areaName: String,
    val tileset: Int,
    val widthScreens: Int,
    val heightScreens: Int,
    val widthBlocks: Int,
    val heightBlocks: Int,
    val contentHash: String,
    /** Complete unsigned 16-bit layer-1 words, flattened row-major. */
    val layer1Words: List<Int>,
    /** Raw high-nibble block types, including horizontal/vertical extensions. */
    val blockTypes: List<Int>,
    /** Block types after resolving horizontal/vertical extension chains. */
    val resolvedBlockTypes: List<Int>,
    /** Complete unsigned BTS bytes, flattened row-major. */
    val bts: List<Int>,
    /** Type-9 tile clusters. Door PLMs, covers, destinations, and room states are deliberately excluded. */
    val doorGroups: List<RoomTrainingDoorGroup>,
)

@Serializable
data class RoomTrainingDoorGroup(
    val edge: String,
    val orientation: String,
    val cellIndices: List<Int>,
)

// ── Navigation graph ────────────────────────────────────────────────

@Serializable
data class NavGraph(
    val nodes: List<NavNode>,
    val edges: List<NavEdge>,
)

@Serializable
data class NavNode(
    val roomId: Int,
    val roomIdHex: String,
    val handle: String,
    val name: String,
    val area: Int,
    val areaName: String,
    val mapX: Int,
    val mapY: Int,
    val widthScreens: Int,
    val heightScreens: Int,
)

@Serializable
data class NavEdge(
    val fromRoomId: Int,
    val fromRoomIdHex: String,
    val toRoomId: Int,
    val toRoomIdHex: String,
    val direction: String,
    val isElevator: Boolean,
    val doorCapColor: String?,
    val requiredAbility: String?,
)
