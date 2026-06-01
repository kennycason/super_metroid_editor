package com.supermetroid.editor.emulator

import kotlinx.serialization.Serializable

@Serializable
data class FrameRecord(
    val frameNumber: Long,
    val inputBits: Int,
    val systemRamHash: String,
    val frameState: SuperMetroidFrameState = SuperMetroidFrameState(),
)

@Serializable
data class AttemptLog(
    val version: Int = CURRENT_VERSION,
    val emulatorCore: String,
    val romHash: String,
    val initialStateHash: String,
    val finalStateHash: String = "",
    val frames: List<FrameRecord>,
) {
    val frameCount: Int get() = frames.size

    companion object {
        const val CURRENT_VERSION = 2
    }
}

/** Sidecar metadata for a self-contained shareable replay bundle (.smreplay). */
@Serializable
data class ReplayManifest(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val title: String = "Super Metroid replay",
    val description: String = "",
    val createdAt: String = "",
    val frameCount: Int = 0,
    val roomIds: List<String> = emptyList(),
    val emulatorCore: String = "",
    val romHash: String = "",
    val initialStateHash: String = "",
    val finalStateHash: String = "",
    val attemptLogFile: String = AttemptReplayBundleFiles.ATTEMPT,
    val initialStateFile: String = AttemptReplayBundleFiles.STATE,
    val finalStateFile: String = AttemptReplayBundleFiles.FINAL_STATE,
    val requiredRom: String = "Super Metroid (JU) [!].sfc",
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 1
    }
}

object AttemptReplayBundleFiles {
    const val EXTENSION = "smreplay"
    const val MANIFEST = "manifest.json"
    const val ATTEMPT = "attempt.json"
    const val STATE = "initial.state"
    const val FINAL_STATE = "final.state"
}

@Serializable
data class ReplayFailure(
    val kind: ReplayFailureKind,
    val message: String,
    val frameNumber: Long? = null,
    val expected: FrameRecord? = null,
    val actual: FrameRecord? = null,
)

@Serializable
enum class ReplayFailureKind {
    UNSUPPORTED_LOG_VERSION,
    EMULATOR_CORE_MISMATCH,
    ROM_HASH_MISMATCH,
    INITIAL_STATE_HASH_MISMATCH,
    FINAL_SAVESTATE_HASH_MISMATCH,
    FRAME_SEQUENCE_MISMATCH,
    REPLAY_STEP_FAILED,
    FRAME_MISMATCH,
    FINAL_STATE_MISMATCH,
}

@Serializable
data class ReplayVerificationResult(
    val matched: Boolean,
    val checkedFrames: Int,
    val failure: ReplayFailure? = null,
    val expectedFinalFrame: FrameRecord? = null,
    val actualFinalFrame: FrameRecord? = null,
) {
    companion object {
        fun matched(
            checkedFrames: Int,
            expectedFinalFrame: FrameRecord?,
            actualFinalFrame: FrameRecord?,
        ): ReplayVerificationResult = ReplayVerificationResult(
            matched = true,
            checkedFrames = checkedFrames,
            expectedFinalFrame = expectedFinalFrame,
            actualFinalFrame = actualFinalFrame,
        )

        fun failed(
            checkedFrames: Int,
            failure: ReplayFailure,
            expectedFinalFrame: FrameRecord? = null,
            actualFinalFrame: FrameRecord? = null,
        ): ReplayVerificationResult = ReplayVerificationResult(
            matched = false,
            checkedFrames = checkedFrames,
            failure = failure,
            expectedFinalFrame = expectedFinalFrame,
            actualFinalFrame = actualFinalFrame,
        )
    }
}
