package com.supermetroid.editor.emulator

class AttemptLogReplayer(
    private val stepper: FrameStepper,
) {
    fun replayAndVerify(
        log: AttemptLog,
        initialState: ByteArray,
        actualRomHash: String,
    ): ReplayVerificationResult {
        if (log.version != AttemptLog.CURRENT_VERSION) {
            return ReplayVerificationResult.failed(
                checkedFrames = 0,
                failure = ReplayFailure(
                    kind = ReplayFailureKind.UNSUPPORTED_LOG_VERSION,
                    message = "Unsupported attempt log version: ${log.version}",
                ),
            )
        }

        val actualEmulatorCore = stepper.emulatorCore
        if (actualEmulatorCore != log.emulatorCore) {
            return ReplayVerificationResult.failed(
                checkedFrames = 0,
                failure = ReplayFailure(
                    kind = ReplayFailureKind.EMULATOR_CORE_MISMATCH,
                    message = "Emulator core mismatch: expected ${log.emulatorCore}, got $actualEmulatorCore",
                ),
            )
        }

        if (actualRomHash != log.romHash) {
            return ReplayVerificationResult.failed(
                checkedFrames = 0,
                failure = ReplayFailure(
                    kind = ReplayFailureKind.ROM_HASH_MISMATCH,
                    message = "ROM hash mismatch: expected ${log.romHash}, got $actualRomHash",
                ),
            )
        }

        val actualInitialStateHash = Sha256.hex(initialState)
        if (actualInitialStateHash != log.initialStateHash) {
            return ReplayVerificationResult.failed(
                checkedFrames = 0,
                failure = ReplayFailure(
                    kind = ReplayFailureKind.INITIAL_STATE_HASH_MISMATCH,
                    message = "Initial state hash mismatch: expected ${log.initialStateHash}, " +
                        "got $actualInitialStateHash",
                ),
            )
        }

        val frameSequenceFailure = findFrameSequenceFailure(log.frames)
        if (frameSequenceFailure != null) {
            return ReplayVerificationResult.failed(
                checkedFrames = 0,
                failure = frameSequenceFailure,
            )
        }

        stepper.loadState(initialState)
        log.frames.firstOrNull()?.let { stepper.resetFrameCounter(it.frameNumber) }

        var actualFinalFrame: FrameRecord? = null
        for ((index, expected) in log.frames.withIndex()) {
            val actual = try {
                stepper.runOneFrame(expected.inputBits)
            } catch (error: RuntimeException) {
                return ReplayVerificationResult.failed(
                    checkedFrames = index,
                    failure = ReplayFailure(
                        kind = ReplayFailureKind.REPLAY_STEP_FAILED,
                        message = "Replay step failed before frame ${expected.frameNumber}: ${error.message}",
                        frameNumber = expected.frameNumber,
                        expected = expected,
                    ),
                    expectedFinalFrame = log.frames.lastOrNull(),
                    actualFinalFrame = actualFinalFrame,
                )
            }
            actualFinalFrame = actual

            val failure = compareFrame(expected, actual, isFinalFrame = index == log.frames.lastIndex)
            if (failure != null) {
                return ReplayVerificationResult.failed(
                    checkedFrames = index + 1,
                    failure = failure,
                    expectedFinalFrame = if (index == log.frames.lastIndex) expected else log.frames.lastOrNull(),
                    actualFinalFrame = actual,
                )
            }
        }
        return ReplayVerificationResult.matched(
            checkedFrames = log.frames.size,
            expectedFinalFrame = log.frames.lastOrNull(),
            actualFinalFrame = actualFinalFrame,
        )
    }

    fun replayAndVerify(
        log: AttemptLog,
        initialState: ByteArray,
        romBytes: ByteArray,
    ): ReplayVerificationResult = replayAndVerify(
        log = log,
        initialState = initialState,
        actualRomHash = Sha256.hex(romBytes),
    )

    private fun findFrameSequenceFailure(frames: List<FrameRecord>): ReplayFailure? {
        if (frames.isEmpty()) return null
        var expectedFrameNumber = frames.first().frameNumber
        for (frame in frames) {
            if (frame.frameNumber != expectedFrameNumber) {
                return ReplayFailure(
                    kind = ReplayFailureKind.FRAME_SEQUENCE_MISMATCH,
                    message = "Attempt log skipped or reordered frames: " +
                        "expected $expectedFrameNumber, got ${frame.frameNumber}",
                    frameNumber = frame.frameNumber,
                    expected = frame.copy(frameNumber = expectedFrameNumber),
                    actual = frame,
                )
            }
            expectedFrameNumber++
        }
        return null
    }

    private fun compareFrame(
        expected: FrameRecord,
        actual: FrameRecord,
        isFinalFrame: Boolean,
    ): ReplayFailure? {
        val differences = frameDifferences(expected, actual)
        if (differences.isEmpty()) return null
        return ReplayFailure(
            kind = if (isFinalFrame) ReplayFailureKind.FINAL_STATE_MISMATCH else ReplayFailureKind.FRAME_MISMATCH,
            message = "Replay mismatch at frame ${expected.frameNumber}: ${differences.joinToString()}",
            frameNumber = expected.frameNumber,
            expected = expected,
            actual = actual,
        )
    }

    private fun frameDifferences(expected: FrameRecord, actual: FrameRecord): List<String> = buildList {
        if (expected.frameNumber != actual.frameNumber) add("frameNumber")
        if (expected.inputBits != actual.inputBits) add("inputBits")
        if (expected.systemRamHash != actual.systemRamHash) add("systemRamHash")
        val stateDifferences = expected.frameState.differingFields(actual.frameState)
        if (stateDifferences.isNotEmpty()) add("frameState.${stateDifferences.joinToString(separator = "|")}")
    }
}
