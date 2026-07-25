package com.supermetroid.editor.headless

import kotlinx.serialization.Serializable

@Serializable
data class SmeditBuildRequest(
    val schemaVersion: Int = 1,
    val project: String? = null,
    val strictConfigValidation: Boolean = true,
    val patches: Map<String, SmeditPatchRequest> = emptyMap(),
    val items: List<SmeditItemPlacementRequest> = emptyList(),
    val rawWrites: List<SmeditRawWriteRequest> = emptyList(),
    val colorize: SmeditColorizeRequest? = null,
)

@Serializable
data class SmeditPatchRequest(
    val enabled: Boolean = true,
    val configType: String? = null,
    val configValue: Int? = null,
    val config: Map<String, Int> = emptyMap(),
    val configData: Map<String, Int> = emptyMap(),
)

@Serializable
data class SmeditRawWriteRequest(
    val pcOffset: Int? = null,
    val snesAddress: Int? = null,
    val address: String? = null,
    val bytes: List<Int> = emptyList(),
    val label: String = "",
)

@Serializable
data class SmeditItemPlacementRequest(
    val item: String,
    val roomId: Int? = null,
    val room: String? = null,
    val x: Int,
    val y: Int,
    val kind: String = "visible",
    val param: Int? = null,
)

@Serializable
data class SmeditColorizeRequest(
    val effect: String,
    val seed: Long? = null,
    val includeTilesets: Boolean = true,
    val includeSprites: Boolean = true,
    val tilesets: List<Int> = emptyList(),
    val spriteRegions: List<String> = emptyList(),
)

@Serializable
data class SmeditGeneratorRequest(
    val mazetroid: Boolean = false,
    val seed: Long? = null,
)

@Serializable
data class SmeditGeneratorReport(
    val seed: Long,
    val generatedRooms: Int,
    val skippedRooms: Int,
    val changedTiles: Int,
    val itemPlacements: List<SmeditGeneratorItemPlacement> = emptyList(),
)

@Serializable
data class SmeditGeneratorItemPlacement(
    val roomId: Int,
    val item: String,
    val plmId: Int,
    val x: Int,
    val y: Int,
    val param: Int,
)

@Serializable
data class SmeditBuildReport(
    val schemaVersion: Int = 1,
    val mode: String = "rom",
    val inputRomBytes: Int,
    val outputRomBytes: Int,
    val changedBytes: Int,
    val patchBytes: Int = changedBytes,
    val applied: List<SmeditAppliedPatchReport>,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class SmeditAppliedPatchReport(
    val identifier: String,
    val name: String,
    val source: String,
    val configType: String? = null,
    val writes: Int,
    val bytes: Int,
)

@Serializable
data class SmeditConfigSchema(
    val configType: String,
    val patchId: String,
    val name: String,
    val description: String,
    val headlessSupported: Boolean,
    val supportsPatchOnly: Boolean,
    val requiresRom: Boolean,
    val fields: List<SmeditConfigFieldSchema> = emptyList(),
)

@Serializable
data class SmeditConfigFieldSchema(
    val key: String,
    val label: String,
    val type: String = "int",
    val min: Int = 0,
    val max: Int = 0xFFFF,
    val defaultValue: Int? = null,
    val description: String = "",
    val category: String? = null,
    val unit: String = "",
    val signed: Boolean = false,
    val logicalMin: Int? = null,
    val logicalMax: Int? = null,
    val requiresRom: Boolean = false,
    val choices: List<SmeditConfigChoiceSchema> = emptyList(),
)

@Serializable
data class SmeditConfigChoiceSchema(
    val label: String,
    val value: Int,
)

data class SmeditBuildResult(
    val romBytes: ByteArray,
    val ipsPatchBytes: ByteArray,
    val report: SmeditBuildReport,
)

data class SmeditPatchBuildResult(
    val ipsPatchBytes: ByteArray,
    val report: SmeditBuildReport,
)
