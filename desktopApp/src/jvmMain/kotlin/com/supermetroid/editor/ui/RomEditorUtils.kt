package com.supermetroid.editor.ui

internal fun bytesSha256(bytes: List<Int>): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    for (b in bytes) digest.update((b and 0xFF).toByte())
    return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
