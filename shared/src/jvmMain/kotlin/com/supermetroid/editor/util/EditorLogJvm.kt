package com.supermetroid.editor.util

import io.github.oshai.kotlinlogging.KotlinLogging

private val sharedLog = KotlinLogging.logger {}

internal actual fun emitEditorLog(level: EditorLogLevel, message: String, throwable: Throwable?) {
    when (level) {
        EditorLogLevel.INFO -> {
            if (throwable == null) sharedLog.info { message } else sharedLog.info(throwable) { message }
        }
        EditorLogLevel.WARN -> {
            if (throwable == null) sharedLog.warn { message } else sharedLog.warn(throwable) { message }
        }
        EditorLogLevel.ERROR -> {
            if (throwable == null) sharedLog.error { message } else sharedLog.error(throwable) { message }
        }
    }
}
