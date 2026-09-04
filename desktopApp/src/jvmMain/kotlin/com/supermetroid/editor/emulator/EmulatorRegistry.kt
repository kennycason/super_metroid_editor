package com.supermetroid.editor.emulator

import com.supermetroid.editor.data.AppConfig

object EmulatorRegistry {

    private val factories = mutableMapOf<String, () -> EmulatorBackend>(
        "libretro" to { LibretroBackend() },
        "retroarch" to { RetroArchBackend() },
        "lsnes-b25" to { LsnesBackend() },
    )

    fun create(name: String): EmulatorBackend {
        val factory = factories[name]
            ?: throw IllegalArgumentException("Unknown emulator backend: '$name'. Available: ${availableBackends()}")
        return factory()
    }

    fun availableBackends(): List<String> = factories.keys.sorted()

    fun createFromConfig(): EmulatorBackend {
        val settings = AppConfig.load()
        return create(settings.emulatorBackend)
    }
}
