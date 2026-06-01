package com.supermetroid.editor.emulator

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

internal data class RecordingStopResult(
    val log: AttemptLog,
    val logFile: File,
    val bundleFile: File?,
)

internal class AttemptRecordingSession(
    private val recordingsDirProvider: () -> File,
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true },
) {
    private var logFile: File? = null
    private var coreName = "unknown-libretro-core"
    private var romHash = ""
    private var initialStateHash = ""
    private val frames = mutableListOf<FrameRecord>()

    val isActive: Boolean get() = logFile != null
    val frameCount: Int get() = frames.size
    val activeLogPath: String? get() = logFile?.absolutePath

    fun start(
        stepper: FrameStepper,
        romHash: String,
        nextFrameNumber: Long,
    ): File {
        if (isActive) error("Recording already active")
        val stateData = stepper.saveState()
        coreName = stepper.emulatorCore
        this.romHash = romHash
        initialStateHash = Sha256.hex(stateData)
        stepper.resetFrameCounter(nextFrameNumber)

        val dir = recordingsDirProvider().apply { mkdirs() }
        val file = nextRecordingFile(dir, nextFrameNumber)
        AttemptReplayBundle.companionStateFile(file).writeBytes(stateData)

        frames.clear()
        logFile = file
        return file
    }

    fun recordFrame(stepper: FrameStepper, inputBits: Int): FrameRecord {
        val frame = stepper.runOneFrame(inputBits)
        frames += frame
        return frame
    }

    fun stop(stepper: FrameStepper): RecordingStopResult {
        val file = logFile ?: error("Recording is not active")
        val finalState = stepper.saveState()
        val finalStateHash = Sha256.hex(finalState)
        AttemptReplayBundle.companionFinalStateFile(file).writeBytes(finalState)
        val log = AttemptLog(
            emulatorCore = coreName,
            romHash = romHash,
            initialStateHash = initialStateHash,
            finalStateHash = finalStateHash,
            frames = frames.toList(),
        )
        file.parentFile.mkdirs()
        file.writeText(json.encodeToString(log))
        logFile = null

        val bundleFile = runCatching { AttemptReplayBundle.writeFromRecording(file) }.getOrNull()
        return RecordingStopResult(log = log, logFile = file, bundleFile = bundleFile)
    }

    fun clear() {
        logFile = null
        frames.clear()
        coreName = "unknown-libretro-core"
        romHash = ""
        initialStateHash = ""
    }

    private fun nextRecordingFile(dir: File, frameNumber: Long): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        return File(dir, "attempt-$stamp-frame-$frameNumber.json")
    }
}

internal class AttemptReplaySession(
    val frames: List<FrameRecord>,
    val title: String,
) {
    var index: Int = 0

    val frameCount: Int get() = frames.size
    val finished: Boolean get() = index >= frames.size

    fun primeStepper(stepper: FrameStepper) {
        val firstFrameNumber = frames.firstOrNull()?.frameNumber ?: 0L
        stepper.resetFrameCounter(firstFrameNumber)
    }

    fun runSteps(stepper: FrameStepper, count: Int): Int {
        val steps = count.coerceAtLeast(1)
        var ran = 0
        repeat(steps) {
            if (index >= frames.size) return ran
            stepper.runOneFrame(frames[index].inputBits)
            index += 1
            ran += 1
        }
        return ran
    }
}
