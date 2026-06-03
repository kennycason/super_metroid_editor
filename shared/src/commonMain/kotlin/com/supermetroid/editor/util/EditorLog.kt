package com.supermetroid.editor.util

enum class EditorLogLevel {
    INFO,
    WARN,
    ERROR
}

object EditorLog {
    fun info(message: String) {
        emitEditorLog(EditorLogLevel.INFO, message, null)
    }

    fun warn(message: String) {
        emitEditorLog(EditorLogLevel.WARN, message, null)
    }

    fun warn(throwable: Throwable, message: String) {
        emitEditorLog(EditorLogLevel.WARN, message, throwable)
    }

    fun error(message: String) {
        emitEditorLog(EditorLogLevel.ERROR, message, null)
    }

    fun error(throwable: Throwable, message: String) {
        emitEditorLog(EditorLogLevel.ERROR, message, throwable)
    }
}

internal expect fun emitEditorLog(level: EditorLogLevel, message: String, throwable: Throwable?)
