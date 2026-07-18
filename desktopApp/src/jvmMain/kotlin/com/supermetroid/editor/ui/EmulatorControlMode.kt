package com.supermetroid.editor.ui

import com.supermetroid.editor.emulator.SessionState

enum class EmulatorControlMode {
    Manual,
    Recording,
    Replaying,
}

fun SessionState.controlMode(): EmulatorControlMode = when {
    replaying -> EmulatorControlMode.Replaying
    recording -> EmulatorControlMode.Recording
    else -> EmulatorControlMode.Manual
}

fun SessionState.replayPaused(isRunning: Boolean): Boolean = replaying && !isRunning
