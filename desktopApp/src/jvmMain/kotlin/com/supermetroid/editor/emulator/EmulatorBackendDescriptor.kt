package com.supermetroid.editor.emulator

data class EmulatorBackendDescriptor(
    val id: String,
    val displayName: String,
    val presentation: EmulatorPresentation,
    val installKind: EmulatorInstallKind,
) {
    val workspaceTitle: String
        get() = when (presentation) {
            EmulatorPresentation.InProcess -> "Embedded Emulator"
            EmulatorPresentation.HeadlessChild -> "lsnes TAS extra"
            EmulatorPresentation.ExternalHost -> "External Emulator"
        }

    val workspaceHelp: String
        get() = when (presentation) {
            EmulatorPresentation.InProcess ->
                "In-process SNES emulator via libretro. Click Play to start, then focus the viewport for keyboard input."
            EmulatorPresentation.HeadlessChild ->
                "Headless optional TAS extra. Frames come from the user-installed lsnes worker; it is not bundled with SMEDIT."
            EmulatorPresentation.ExternalHost ->
                "External RetroArch via NWA. Enable Network Commands in RetroArch (Settings > Network). Editor syncs room & position."
        }

    val idleStatus: String
        get() = when (presentation) {
            EmulatorPresentation.InProcess -> "Click Play to start the emulator."
            EmulatorPresentation.HeadlessChild -> "Click Play to start the lsnes TAS extra."
            EmulatorPresentation.ExternalHost -> "Connect RetroArch, then click Play."
        }
}
