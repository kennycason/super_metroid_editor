package com.supermetroid.editor.ui

import com.supermetroid.editor.emulator.AttemptReplayBundle
import com.supermetroid.editor.emulator.EmulatorBackend
import com.supermetroid.editor.emulator.ReplayEvent
import com.supermetroid.editor.emulator.RecordingBackend
import com.supermetroid.editor.emulator.StepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class EmulatorReplayController(
    private val host: Host,
) {
    interface Host {
        fun emulatorBackend(): EmulatorBackend?
        val sessionActive: Boolean
        val sessionRecording: Boolean
        val sessionReplaying: Boolean
        var recordingPath: String?
        var replayBundlePath: String?
        var isBusy: Boolean
        var isRunning: Boolean
        suspend fun applyStepResult(result: StepResult)
        fun setStatus(message: String)
        fun setLoopRunning(running: Boolean)
    }

    suspend fun setRecording(active: Boolean) {
        val b = host.emulatorBackend() as? RecordingBackend ?: run {
            host.setStatus("Recording is only supported by the libretro backend")
            return
        }
        if (!host.sessionActive) {
            host.setStatus("Start the emulator before recording")
            return
        }
        if (active && host.sessionReplaying) {
            host.setStatus("Stop replay before recording")
            return
        }
        host.isBusy = true
        try {
            host.applyStepResult(b.setRecording(active))
        } catch (e: Exception) {
            host.setStatus("Recording failed: ${e.message}")
        } finally {
            host.isBusy = false
        }
    }

    suspend fun openReplay(path: String) {
        val b = host.emulatorBackend() as? RecordingBackend ?: run {
            host.setStatus("Replay is only supported by the libretro backend")
            return
        }
        if (!host.sessionActive) {
            host.setStatus("Start the emulator before replay")
            return
        }
        host.isBusy = true
        try {
            val bundle = withContext(Dispatchers.IO) { AttemptReplayBundle.read(File(path)) }
            host.applyStepResult(b.startReplay(bundle))
            host.replayBundlePath = bundle.sourcePath
            host.setLoopRunning(true)
        } catch (e: Exception) {
            host.setStatus("Replay failed: ${e.message}")
        } finally {
            host.isBusy = false
        }
    }

    suspend fun watchLastRecording() {
        val path = host.replayBundlePath
            ?: host.recordingPath?.let {
                AttemptReplayBundle.bundleFileForLog(File(it)).takeIf { f -> f.isFile }?.absolutePath
            }
            ?: host.recordingPath
        if (path == null) {
            host.setStatus("No recording available to replay")
            return
        }
        openReplay(path)
    }

    suspend fun exportReplayBundle(outputPath: String? = null): String? {
        val logPath = host.recordingPath ?: run {
            host.setStatus("No recording to export")
            return null
        }
        host.isBusy = true
        return try {
            val logFile = File(logPath)
            val bundleFile = withContext(Dispatchers.IO) {
                if (outputPath != null) {
                    val bundle = AttemptReplayBundle.read(logFile)
                    AttemptReplayBundle.write(
                        File(outputPath),
                        bundle.log,
                        bundle.initialState,
                        bundle.manifest,
                        bundle.finalState,
                    )
                } else {
                    AttemptReplayBundle.writeFromRecording(logFile)
                }
            }
            host.replayBundlePath = bundleFile.absolutePath
            host.setStatus("Shareable replay saved: ${bundleFile.name}")
            bundleFile.absolutePath
        } catch (e: Exception) {
            host.setStatus("Export failed: ${e.message}")
            null
        } finally {
            host.isBusy = false
        }
    }

    suspend fun stopReplay() {
        val b = host.emulatorBackend() as? RecordingBackend ?: return
        if (!host.sessionReplaying) return
        host.isBusy = true
        try {
            host.applyStepResult(b.stopReplay())
            host.setLoopRunning(true)
        } catch (e: Exception) {
            host.setStatus("Stop replay failed: ${e.message}")
        } finally {
            host.isBusy = false
        }
    }

    fun onStepResultApplied(result: StepResult) {
        if (result.replayEvent == ReplayEvent.Finished) {
            host.setLoopRunning(true)
        }
    }
}
