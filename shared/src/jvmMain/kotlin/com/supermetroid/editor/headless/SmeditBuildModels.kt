package com.supermetroid.editor.headless

import kotlinx.serialization.Serializable

@Serializable
data class SmeditBuildRequest(
    val schemaVersion: Int = 1,
    val project: String? = null,
    val patches: Map<String, SmeditPatchRequest> = emptyMap(),
    val rawWrites: List<SmeditRawWriteRequest> = emptyList(),
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

data class SmeditBuildResult(
    val romBytes: ByteArray,
    val ipsPatchBytes: ByteArray,
    val report: SmeditBuildReport,
)

data class SmeditPatchBuildResult(
    val ipsPatchBytes: ByteArray,
    val report: SmeditBuildReport,
)
