package com.supermetroid.editor.libretro

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

// dlopen flags for loading libretro cores with isolated symbol scope
private const val RTLD_LAZY = 0x00001
private const val RTLD_LOCAL = 0x00000  // default on Linux, but explicit for clarity

/**
 * High-level wrapper around libretro core loaded via JNA.
 * All public methods must be called from a single thread (not thread-safe).
 */
class LibretroCore(private val corePath: String) {

    data class FrameSnapshot(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
    )

    data class RuntimeSnapshot(
        val wram: ByteArray?,
        val frameWidth: Int,
        val frameHeight: Int,
        val frame: FrameSnapshot?,
    )

    private lateinit var lib: LibretroLib
    private var gameLoaded = false

    // Frame buffer (ARGB8888) — double-buffered to avoid per-frame allocation
    private var frameBuffer: IntArray = IntArray(0)
    private var frameCopyBuffer: IntArray = IntArray(0)
    private var frameWidth = 0
    private var frameHeight = 0

    // Audio ring buffer (interleaved stereo int16 samples)
    private val audioBuffer = ShortArray(AUDIO_BUFFER_SIZE)
    private var audioWritePos = 0
    private var audioReadPos = 0
    // Reusable drain buffer to avoid per-frame allocation
    private var audioDrainBuffer = ShortArray(4096)

    // Input state: [port][buttonId] = pressed
    private val inputState = Array(2) { IntArray(16) }

    // Pixel format reported by core
    private var pixelFormat = LibretroConstants.RETRO_PIXEL_FORMAT_0RGB1555

    // System/save directory for the core
    private var systemDir: String = System.getProperty("user.home") + "/.smedit/system"
    private var saveDir: String = System.getProperty("user.home") + "/.smedit/saves"

    // Keep strong references to callbacks so GC doesn't collect them
    private lateinit var envCallback: RetroEnvironmentCallback
    private lateinit var videoCallback: RetroVideoRefreshCallback
    private lateinit var audioSampleCallback: RetroAudioSampleCallback
    private lateinit var audioBatchCallback: RetroAudioSampleBatchCallback
    private lateinit var inputPollCallback: RetroInputPollCallback
    private lateinit var inputStateCallback: RetroInputStateCallback

    fun init() {
        // Load with RTLD_LOCAL to prevent symbol conflicts with Compose/Skia's bundled native libs
        val options = mapOf(
            Library.OPTION_OPEN_FLAGS to (RTLD_LAZY or RTLD_LOCAL)
        )
        lib = Native.load(corePath, LibretroLib::class.java, options)

        File(systemDir).mkdirs()
        File(saveDir).mkdirs()

        envCallback = RetroEnvironmentCallback { cmd, data -> handleEnvironment(cmd, data) }
        videoCallback = RetroVideoRefreshCallback { pixelData, w, h, pitch -> handleVideoRefresh(pixelData, w, h, pitch) }
        audioSampleCallback = RetroAudioSampleCallback { left, right -> handleAudioSample(left, right) }
        audioBatchCallback = RetroAudioSampleBatchCallback { data, frames -> handleAudioBatch(data, frames) }
        inputPollCallback = RetroInputPollCallback { /* nothing needed */ }
        inputStateCallback = RetroInputStateCallback { port, _, _, id -> handleInputState(port, id) }

        lib.retro_set_environment(envCallback)
        lib.retro_init()
        lib.retro_set_video_refresh(videoCallback)
        lib.retro_set_audio_sample(audioSampleCallback)
        lib.retro_set_audio_sample_batch(audioBatchCallback)
        lib.retro_set_input_poll(inputPollCallback)
        lib.retro_set_input_state(inputStateCallback)
    }

    fun loadGame(romPath: String): Boolean {
        if (gameLoaded) {
            unloadGame()
        }
        // Allocate native string for the path — must stay alive during retro_load_game
        val pathBytes = romPath.toByteArray(Charsets.UTF_8)
        val pathMem = Memory((pathBytes.size + 1).toLong())
        pathMem.write(0, pathBytes, 0, pathBytes.size)
        pathMem.setByte(pathBytes.size.toLong(), 0) // null terminator
        val info = RetroGameInfo().apply {
            path = pathMem
            data = null
            size = com.sun.jna.NativeLong(0)
            meta = null
        }
        val loaded = lib.retro_load_game(info)
        if (loaded) {
            gameLoaded = true
            lib.retro_set_controller_port_device(0, LibretroConstants.RETRO_DEVICE_JOYPAD)
            lib.retro_set_controller_port_device(1, LibretroConstants.RETRO_DEVICE_JOYPAD)
        }
        return loaded
    }

    fun unloadGame() {
        if (gameLoaded) {
            lib.retro_unload_game()
            gameLoaded = false
        }
        clearRuntimeBuffers()
    }

    fun isGameLoaded(): Boolean = gameLoaded

    fun run() {
        check(gameLoaded) { "No game loaded" }
        lib.retro_run()
    }

    fun getAvInfo(): RetroSystemAvInfo {
        val info = RetroSystemAvInfo()
        lib.retro_get_system_av_info(info)
        return info
    }

    fun getSystemInfo(): RetroSystemInfo {
        val info = RetroSystemInfo()
        lib.retro_get_system_info(info)
        return info
    }

    /** Drain all pending audio samples (interleaved stereo int16). */
    fun drainAudio(): ShortArray {
        val available = audioAvailable()
        if (available == 0) return EMPTY_SHORT_ARRAY
        // Reuse drain buffer when possible
        if (audioDrainBuffer.size < available) {
            audioDrainBuffer = ShortArray(available)
        }
        for (i in 0 until available) {
            audioDrainBuffer[i] = audioBuffer[audioReadPos % AUDIO_BUFFER_SIZE]
            audioReadPos++
        }
        // Return a view of the reusable buffer — caller must consume before next drain
        return if (available == audioDrainBuffer.size) audioDrainBuffer
        else audioDrainBuffer.copyOf(available)
    }

    fun captureRuntimeSnapshot(includeFrame: Boolean = false, includeWram: Boolean = true): RuntimeSnapshot {
        val wram = if (includeWram && gameLoaded) {
            runCatching { readWram(0, 0x2000) }.getOrNull()
        } else {
            null
        }
        val frame = if (includeFrame && frameWidth > 0 && frameHeight > 0 && frameBuffer.isNotEmpty()) {
            // Reuse copy buffer to avoid per-frame allocation
            val size = frameBuffer.size
            if (frameCopyBuffer.size != size) {
                frameCopyBuffer = IntArray(size)
            }
            System.arraycopy(frameBuffer, 0, frameCopyBuffer, 0, size)
            FrameSnapshot(
                pixels = frameCopyBuffer,
                width = frameWidth,
                height = frameHeight,
            )
        } else {
            null
        }
        return RuntimeSnapshot(
            wram = wram,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            frame = frame,
        )
    }

    /** Set input for a port. buttons is a list of 12 values (0/1) in SNES order: B,Y,Sel,Start,U,D,L,R,A,X,L,R */
    fun setInput(port: Int, buttons: List<Int>) {
        if (port !in 0..1) return
        for (i in buttons.indices) {
            if (i < inputState[port].size) {
                inputState[port][i] = buttons[i]
            }
        }
    }

    fun serializeState(): ByteArray {
        val size = lib.retro_serialize_size()
        if (size <= 0) throw IllegalStateException("Core does not support serialization")
        val mem = Memory(size)
        if (!lib.retro_serialize(mem, size)) throw IllegalStateException("retro_serialize failed")
        return mem.getByteArray(0, size.toInt())
    }

    fun unserializeState(data: ByteArray) {
        val mem = Memory(data.size.toLong())
        mem.write(0, data, 0, data.size)
        if (!lib.retro_unserialize(mem, data.size.toLong())) {
            throw IllegalStateException("retro_unserialize failed")
        }
    }

    /** Read bytes from WRAM at the given offset. */
    fun readWram(address: Int, size: Int): ByteArray {
        require(address >= 0) { "Address must be non-negative" }
        require(size >= 0) { "Size must be non-negative" }
        val ptr = lib.retro_get_memory_data(LibretroConstants.RETRO_MEMORY_SYSTEM_RAM)
            ?: throw IllegalStateException("WRAM not available")
        val memSize = lib.retro_get_memory_size(LibretroConstants.RETRO_MEMORY_SYSTEM_RAM)
        if (address + size > memSize) throw IllegalArgumentException("Address out of range: $address + $size > $memSize")
        return ptr.getByteArray(address.toLong(), size)
    }

    /** Write bytes to WRAM at the given offset. */
    fun writeWram(address: Int, data: ByteArray) {
        require(address >= 0) { "Address must be non-negative" }
        val ptr = lib.retro_get_memory_data(LibretroConstants.RETRO_MEMORY_SYSTEM_RAM)
            ?: throw IllegalStateException("WRAM not available")
        val memSize = lib.retro_get_memory_size(LibretroConstants.RETRO_MEMORY_SYSTEM_RAM)
        if (address + data.size > memSize) throw IllegalArgumentException("Address out of range")
        ptr.write(address.toLong(), data, 0, data.size)
    }

    /** Read the cartridge's battery-backed save RAM, when the loaded game exposes it. */
    fun readSaveRam(): ByteArray {
        val size = lib.retro_get_memory_size(LibretroConstants.RETRO_MEMORY_SAVE_RAM)
        if (size <= 0) return EMPTY_BYTE_ARRAY
        val ptr = lib.retro_get_memory_data(LibretroConstants.RETRO_MEMORY_SAVE_RAM)
            ?: return EMPTY_BYTE_ARRAY
        return ptr.getByteArray(0, size.toInt())
    }

    /** Write persisted battery-backed save RAM into the loaded game. */
    fun writeSaveRam(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val size = lib.retro_get_memory_size(LibretroConstants.RETRO_MEMORY_SAVE_RAM)
        if (size <= 0) return false
        val ptr = lib.retro_get_memory_data(LibretroConstants.RETRO_MEMORY_SAVE_RAM)
            ?: return false
        val bytesToCopy = minOf(data.size, size.toInt())
        ptr.write(0, data, 0, bytesToCopy)
        return bytesToCopy > 0
    }

    fun setSaveDirectory(path: String) {
        saveDir = path
        File(saveDir).mkdirs()
    }

    fun close() {
        unloadGame()
        lib.retro_deinit()
    }

    // ── Callback handlers ──────────────────────────────────────────────────

    private fun handleEnvironment(cmd: Int, data: Pointer?): Boolean {
        return when (cmd) {
            LibretroConstants.RETRO_ENVIRONMENT_SET_PIXEL_FORMAT -> {
                if (data != null) {
                    pixelFormat = data.getInt(0)
                }
                true
            }
            LibretroConstants.RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY -> {
                data?.setPointer(0, Pointer.createConstant(0))
                if (data != null) {
                    val mem = Memory((systemDir.length + 1).toLong())
                    mem.setString(0, systemDir)
                    data.setPointer(0, mem)
                    // Store reference to prevent GC
                    systemDirMem = mem
                }
                true
            }
            LibretroConstants.RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY -> {
                if (data != null) {
                    val mem = Memory((saveDir.length + 1).toLong())
                    mem.setString(0, saveDir)
                    data.setPointer(0, mem)
                    saveDirMem = mem
                }
                true
            }
            LibretroConstants.RETRO_ENVIRONMENT_GET_LOG_INTERFACE -> {
                // We don't provide a log interface; the core falls back to stderr
                false
            }
            LibretroConstants.RETRO_ENVIRONMENT_GET_CAN_DUPE -> {
                data?.setByte(0, 1)
                true
            }
            LibretroConstants.RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS -> true
            LibretroConstants.RETRO_ENVIRONMENT_SET_VARIABLES -> true
            LibretroConstants.RETRO_ENVIRONMENT_GET_VARIABLE -> false
            LibretroConstants.RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE -> {
                data?.setByte(0, 0)
                true
            }
            LibretroConstants.RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME -> true
            LibretroConstants.RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL -> true
            LibretroConstants.RETRO_ENVIRONMENT_SET_MEMORY_MAPS -> true
            LibretroConstants.RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION -> {
                data?.setInt(0, 0)
                true
            }
            LibretroConstants.RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2 -> true
            LibretroConstants.RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE -> true
            LibretroConstants.RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION -> {
                data?.setInt(0, 0)
                true
            }
            LibretroConstants.RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS -> true
            else -> false
        }
    }

    private fun handleVideoRefresh(data: Pointer?, width: Int, height: Int, pitch: Long) {
        if (data == null) return // duped frame
        if (width <= 0 || height <= 0) return

        val totalPixels = width * height
        if (frameBuffer.size != totalPixels) {
            frameBuffer = IntArray(totalPixels)
        }
        frameWidth = width
        frameHeight = height

        when (pixelFormat) {
            LibretroConstants.RETRO_PIXEL_FORMAT_XRGB8888 -> {
                for (y in 0 until height) {
                    val srcOffset = y * pitch
                    val dstOffset = y * width
                    for (x in 0 until width) {
                        frameBuffer[dstOffset + x] = data.getInt(srcOffset + x * 4L)
                    }
                }
            }
            LibretroConstants.RETRO_PIXEL_FORMAT_RGB565 -> {
                for (y in 0 until height) {
                    val srcOffset = y * pitch
                    val dstOffset = y * width
                    for (x in 0 until width) {
                        val pixel = data.getShort(srcOffset + x * 2L).toInt() and 0xFFFF
                        val r = (pixel shr 11) and 0x1F
                        val g = (pixel shr 5) and 0x3F
                        val b = pixel and 0x1F
                        frameBuffer[dstOffset + x] = (0xFF shl 24) or
                            ((r * 255 / 31) shl 16) or
                            ((g * 255 / 63) shl 8) or
                            (b * 255 / 31)
                    }
                }
            }
            LibretroConstants.RETRO_PIXEL_FORMAT_0RGB1555 -> {
                for (y in 0 until height) {
                    val srcOffset = y * pitch
                    val dstOffset = y * width
                    for (x in 0 until width) {
                        val pixel = data.getShort(srcOffset + x * 2L).toInt() and 0xFFFF
                        val r = (pixel shr 10) and 0x1F
                        val g = (pixel shr 5) and 0x1F
                        val b = pixel and 0x1F
                        frameBuffer[dstOffset + x] = (0xFF shl 24) or
                            ((r * 255 / 31) shl 16) or
                            ((g * 255 / 31) shl 8) or
                            (b * 255 / 31)
                    }
                }
            }
        }
    }

    private fun handleAudioSample(left: Short, right: Short) {
        if (audioAvailable() < AUDIO_BUFFER_SIZE - 2) {
            audioBuffer[audioWritePos % AUDIO_BUFFER_SIZE] = left
            audioWritePos++
            audioBuffer[audioWritePos % AUDIO_BUFFER_SIZE] = right
            audioWritePos++
        }
    }

    private fun handleAudioBatch(data: Pointer, frames: Long): Long {
        val samples = (frames * 2).toInt() // stereo
        val space = AUDIO_BUFFER_SIZE - audioAvailable()
        val toCopy = minOf(samples, space)
        for (i in 0 until toCopy) {
            audioBuffer[audioWritePos % AUDIO_BUFFER_SIZE] = data.getShort((i * 2).toLong())
            audioWritePos++
        }
        return frames
    }

    private fun handleInputState(port: Int, id: Int): Short {
        if (port !in 0..1) return 0
        if (id !in inputState[port].indices) return 0
        return inputState[port][id].toShort()
    }

    private fun audioAvailable(): Int = audioWritePos - audioReadPos

    private fun clearRuntimeBuffers() {
        frameWidth = 0
        frameHeight = 0
        audioWritePos = 0
        audioReadPos = 0
        for (port in inputState.indices) {
            inputState[port].fill(0)
        }
    }

    // Prevent GC of directory strings passed to the core
    @Suppress("unused")
    private var systemDirMem: Memory? = null
    @Suppress("unused")
    private var saveDirMem: Memory? = null

    companion object {
        // ~1 second of stereo audio at 32040 Hz
        private const val AUDIO_BUFFER_SIZE = 32040 * 2 * 2
        private val EMPTY_SHORT_ARRAY = ShortArray(0)
        private val EMPTY_BYTE_ARRAY = ByteArray(0)
    }
}
