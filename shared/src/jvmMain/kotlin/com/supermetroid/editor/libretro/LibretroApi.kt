package com.supermetroid.editor.libretro

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Pointer
import com.sun.jna.Structure

// ── Libretro C API constants ───────────────────────────────────────────────

object LibretroConstants {
    // Memory regions
    const val RETRO_MEMORY_SAVE_RAM = 0
    const val RETRO_MEMORY_RTC = 1
    const val RETRO_MEMORY_SYSTEM_RAM = 2
    const val RETRO_MEMORY_VIDEO_RAM = 3

    // Device types
    const val RETRO_DEVICE_NONE = 0
    const val RETRO_DEVICE_JOYPAD = 1

    // Joypad button IDs (match SNES layout)
    const val RETRO_DEVICE_ID_JOYPAD_B = 0
    const val RETRO_DEVICE_ID_JOYPAD_Y = 1
    const val RETRO_DEVICE_ID_JOYPAD_SELECT = 2
    const val RETRO_DEVICE_ID_JOYPAD_START = 3
    const val RETRO_DEVICE_ID_JOYPAD_UP = 4
    const val RETRO_DEVICE_ID_JOYPAD_DOWN = 5
    const val RETRO_DEVICE_ID_JOYPAD_LEFT = 6
    const val RETRO_DEVICE_ID_JOYPAD_RIGHT = 7
    const val RETRO_DEVICE_ID_JOYPAD_A = 8
    const val RETRO_DEVICE_ID_JOYPAD_X = 9
    const val RETRO_DEVICE_ID_JOYPAD_L = 10
    const val RETRO_DEVICE_ID_JOYPAD_R = 11
    const val RETRO_DEVICE_ID_JOYPAD_MASK = 256

    const val RETRO_ENVIRONMENT_GET_INPUT_BITMASKS = 51

    // Pixel formats
    const val RETRO_PIXEL_FORMAT_0RGB1555 = 0
    const val RETRO_PIXEL_FORMAT_XRGB8888 = 1
    const val RETRO_PIXEL_FORMAT_RGB565 = 2

    // Environment commands
    const val RETRO_ENVIRONMENT_SET_PIXEL_FORMAT = 10
    const val RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY = 9
    const val RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY = 31
    const val RETRO_ENVIRONMENT_GET_LOG_INTERFACE = 27
    const val RETRO_ENVIRONMENT_GET_CAN_DUPE = 3
    const val RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS = 11
    const val RETRO_ENVIRONMENT_SET_VARIABLES = 16
    const val RETRO_ENVIRONMENT_GET_VARIABLE = 15
    const val RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE = 17
    const val RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME = 18
    const val RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL = 8
    const val RETRO_ENVIRONMENT_SET_MEMORY_MAPS = 36
    const val RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION = 52
    const val RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2 = 67
    const val RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE = 65
    const val RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION = 59
    const val RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS = 44
}

// ── JNA Structures ─────────────────────────────────────────────────────────

@Structure.FieldOrder("path", "data", "size", "meta")
open class RetroGameInfo : Structure() {
    /** const char *path — use Pointer so JNA treats it as a native pointer, not inline char[] */
    @JvmField var path: Pointer? = null
    @JvmField var data: Pointer? = null
    @JvmField var size: com.sun.jna.NativeLong = com.sun.jna.NativeLong(0)
    @JvmField var meta: Pointer? = null
}

@Structure.FieldOrder("base_width", "base_height", "max_width", "max_height", "aspect_ratio")
open class RetroGameGeometry : Structure() {
    @JvmField var base_width: Int = 0
    @JvmField var base_height: Int = 0
    @JvmField var max_width: Int = 0
    @JvmField var max_height: Int = 0
    @JvmField var aspect_ratio: Float = 0f
}

@Structure.FieldOrder("fps", "sample_rate")
open class RetroSystemTiming : Structure() {
    @JvmField var fps: Double = 0.0
    @JvmField var sample_rate: Double = 0.0
}

@Structure.FieldOrder("geometry", "timing")
open class RetroSystemAvInfo : Structure() {
    @JvmField var geometry: RetroGameGeometry = RetroGameGeometry()
    @JvmField var timing: RetroSystemTiming = RetroSystemTiming()
}

@Structure.FieldOrder("library_name", "library_version", "valid_extensions", "need_fullpath", "block_extract")
open class RetroSystemInfo : Structure() {
    @JvmField var library_name: Pointer? = null
    @JvmField var library_version: Pointer? = null
    @JvmField var valid_extensions: Pointer? = null
    @JvmField var need_fullpath: Boolean = false
    @JvmField var block_extract: Boolean = false

    fun getLibraryName(): String? = library_name?.getString(0)
    fun getLibraryVersion(): String? = library_version?.getString(0)
}

// ── Callback interfaces ────────────────────────────────────────────────────

fun interface RetroVideoRefreshCallback : Callback {
    fun invoke(data: Pointer?, width: Int, height: Int, pitch: Long)
}

fun interface RetroAudioSampleCallback : Callback {
    fun invoke(left: Short, right: Short)
}

fun interface RetroAudioSampleBatchCallback : Callback {
    fun invoke(data: Pointer, frames: Long): Long
}

fun interface RetroInputPollCallback : Callback {
    fun invoke()
}

fun interface RetroInputStateCallback : Callback {
    fun invoke(port: Int, device: Int, index: Int, id: Int): Short
}

fun interface RetroEnvironmentCallback : Callback {
    fun invoke(cmd: Int, data: Pointer?): Boolean
}

// ── Library interface ──────────────────────────────────────────────────────

interface LibretroLib : Library {
    fun retro_init()
    fun retro_deinit()
    fun retro_api_version(): Int

    fun retro_get_system_info(info: RetroSystemInfo)
    fun retro_get_system_av_info(info: RetroSystemAvInfo)

    fun retro_set_environment(cb: RetroEnvironmentCallback)
    fun retro_set_video_refresh(cb: RetroVideoRefreshCallback)
    fun retro_set_audio_sample(cb: RetroAudioSampleCallback)
    fun retro_set_audio_sample_batch(cb: RetroAudioSampleBatchCallback)
    fun retro_set_input_poll(cb: RetroInputPollCallback)
    fun retro_set_input_state(cb: RetroInputStateCallback)

    fun retro_set_controller_port_device(port: Int, device: Int)

    fun retro_load_game(game: RetroGameInfo): Boolean
    fun retro_unload_game()
    fun retro_run()
    fun retro_reset()

    fun retro_serialize_size(): Long
    fun retro_serialize(data: Pointer, size: Long): Boolean
    fun retro_unserialize(data: Pointer, size: Long): Boolean

    fun retro_get_memory_data(id: Int): Pointer?
    fun retro_get_memory_size(id: Int): Long
}
