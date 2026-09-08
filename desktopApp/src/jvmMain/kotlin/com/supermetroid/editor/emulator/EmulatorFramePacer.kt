package com.supermetroid.editor.emulator

/**
 * Live-play pacing for the Compose frame loop.
 *
 * Headless lsnes RPC is slower than 16.67ms, so catching up with multi-frame
 * [repeat] bursts makes emulated FPS swing (58–70) and running feel uneven.
 * Cap catch-up at 1 unless playback speed is above 1×.
 */
object EmulatorFramePacer {
    const val TARGET_FPS = 60.0
    const val FRAME_DURATION_NANOS = (1_000_000_000.0 / TARGET_FPS).toLong()
    const val BUNDLED_MAX_CATCH_UP = 4
    const val HEADLESS_MAX_CATCH_UP = 1
    const val BUNDLED_FRAME_INTERVAL = 2L
    const val HEADLESS_WRAM_INTERVAL = 10L

    fun maxCatchUp(presentation: EmulatorPresentation, speed: Int = 1): Int {
        if (speed > 1) return EmulatorPlaybackSpeed.clamp(speed)
        return if (presentation == EmulatorPresentation.HeadlessChild) {
            HEADLESS_MAX_CATCH_UP
        } else {
            BUNDLED_MAX_CATCH_UP
        }
    }

    fun repeat(pendingFrames: Double, maxCatchUp: Int): Int =
        pendingFrames.toInt().coerceIn(1, maxCatchUp)

    fun includeFrame(presentation: EmulatorPresentation, tick: Long): Boolean =
        presentation == EmulatorPresentation.HeadlessChild ||
            tick % BUNDLED_FRAME_INTERVAL == 0L

    fun includeWram(presentation: EmulatorPresentation, tick: Long): Boolean =
        presentation != EmulatorPresentation.HeadlessChild ||
            tick % HEADLESS_WRAM_INTERVAL == 0L
}
