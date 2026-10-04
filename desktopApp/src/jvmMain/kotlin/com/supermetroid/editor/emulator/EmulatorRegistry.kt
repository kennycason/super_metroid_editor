package com.supermetroid.editor.emulator

import com.supermetroid.editor.data.AppConfig

object EmulatorRegistry {

    private val descriptorsById = linkedMapOf(
        EmulatorBackendIds.LIBRETRO to EmulatorBackendDescriptor(
            id = EmulatorBackendIds.LIBRETRO,
            displayName = "libretro",
            presentation = EmulatorPresentation.InProcess,
            installKind = EmulatorInstallKind.Bundled,
        ),
        EmulatorBackendIds.RETROARCH to EmulatorBackendDescriptor(
            id = EmulatorBackendIds.RETROARCH,
            displayName = "retroarch",
            presentation = EmulatorPresentation.ExternalHost,
            installKind = EmulatorInstallKind.UserInstalled,
        ),
        EmulatorBackendIds.LSNES_B25 to EmulatorBackendDescriptor(
            id = EmulatorBackendIds.LSNES_B25,
            displayName = "lsnes b25 extra",
            presentation = EmulatorPresentation.HeadlessChild,
            installKind = EmulatorInstallKind.OptionalExtra,
        ),
    )

    private val factories = mutableMapOf<String, () -> EmulatorBackend>(
        EmulatorBackendIds.LIBRETRO to { LibretroBackend() },
        EmulatorBackendIds.RETROARCH to { RetroArchBackend() },
        EmulatorBackendIds.LSNES_B25 to { LsnesBackend() },
    )

    fun create(name: String): EmulatorBackend {
        val factory = factories[name]
            ?: throw IllegalArgumentException("Unknown emulator backend: '$name'. Available: ${availableBackends()}")
        return factory()
    }

    fun availableBackends(): List<String> = factories.keys.sorted()

    fun descriptors(): List<EmulatorBackendDescriptor> =
        availableBackends().map { descriptor(it) }

    fun descriptor(id: String): EmulatorBackendDescriptor =
        descriptorsById[id]
            ?: throw IllegalArgumentException("Unknown emulator backend: '$id'. Available: ${availableBackends()}")

    fun createFromConfig(): EmulatorBackend {
        val settings = AppConfig.load()
        return create(settings.emulatorBackend)
    }
}
