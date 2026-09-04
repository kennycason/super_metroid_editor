package com.supermetroid.editor.emulator

data class EmulatorBackendDescriptor(
    val id: String,
    val displayName: String,
    val presentation: EmulatorPresentation,
    val installKind: EmulatorInstallKind,
)
