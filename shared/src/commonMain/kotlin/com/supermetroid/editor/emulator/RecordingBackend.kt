package com.supermetroid.editor.emulator

interface RecordingBackend {
    suspend fun setRecording(active: Boolean): StepResult
    suspend fun startReplay(bundle: LoadedReplayBundle): StepResult
    suspend fun stopReplay(): StepResult
}
