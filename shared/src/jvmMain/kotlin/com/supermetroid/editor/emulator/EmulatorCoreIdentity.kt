package com.supermetroid.editor.emulator

/** Compare libretro cores by library name + version, ignoring git hash suffix drift. */
fun normalizeEmulatorCoreIdentity(identity: String): String {
    val parts = identity.trim().split(Regex("\\s+"))
    return when {
        parts.size >= 2 -> "${parts[0]} ${parts[1]}"
        else -> identity.trim()
    }
}

fun emulatorCoresCompatible(expected: String, actual: String): Boolean {
    return normalizeEmulatorCoreIdentity(expected) == normalizeEmulatorCoreIdentity(actual)
}
