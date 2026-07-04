package com.supermetroid.editor.rom

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.supermetroid.editor.util.EditorLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JNA bridge to blargg's snes_spc library for cycle-accurate SPC700 emulation.
 *
 * Usage:
 *   val emu = NativeSpcEmulator()
 *   emu.loadFromRam(baseRam, transferBlocks, playIndex)
 *   val stereo = emu.render(seconds = 15)
 *   emu.close()
 */
class NativeSpcEmulator : AutoCloseable {

    private var spc: Pointer? = null
    private var filter: Pointer? = null
    private var loadedRamSnapshot: ByteArray? = null

    companion object {
        const val SAMPLE_RATE = 32000
        private const val SPC_FILE_SIZE = 0x10200
        private const val RAM_SIZE = RomConstants.SPC_RAM_SIZE
        private const val ROM_SIZE = 0x40

        private val IPL_ROM = byteArrayOf(
            0xCD.toByte(), 0xEF.toByte(), 0xBD.toByte(), 0xE8.toByte(),
            0x00.toByte(), 0xC6.toByte(), 0x1D.toByte(), 0xD0.toByte(),
            0xFC.toByte(), 0x8F.toByte(), 0xAA.toByte(), 0xF4.toByte(),
            0x8F.toByte(), 0xBB.toByte(), 0xF5.toByte(), 0x78.toByte(),
            0xCC.toByte(), 0xF4.toByte(), 0xD0.toByte(), 0xFB.toByte(),
            0x2F.toByte(), 0x19.toByte(), 0xEB.toByte(), 0xF4.toByte(),
            0xD0.toByte(), 0xFC.toByte(), 0x7E.toByte(), 0xF4.toByte(),
            0xD0.toByte(), 0x0B.toByte(), 0xE4.toByte(), 0xF5.toByte(),
            0xCB.toByte(), 0xF4.toByte(), 0xD7.toByte(), 0x00.toByte(),
            0xFC.toByte(), 0xD0.toByte(), 0xF3.toByte(), 0xAB.toByte(),
            0x01.toByte(), 0x10.toByte(), 0xEF.toByte(), 0x7E.toByte(),
            0xF4.toByte(), 0x10.toByte(), 0xEB.toByte(), 0xBA.toByte(),
            0xF6.toByte(), 0xDA.toByte(), 0x00.toByte(), 0xBA.toByte(),
            0xF4.toByte(), 0xC4.toByte(), 0xF4.toByte(), 0xDD.toByte(),
            0x5D.toByte(), 0xD0.toByte(), 0xDB.toByte(), 0x1F.toByte(),
            0x00.toByte(), 0x00.toByte(), 0xC0.toByte(), 0xFF.toByte()
        )

        private val lib: SpcLib? by lazy {
            try {
                Native.load("spc", SpcLib::class.java)
            } catch (e: UnsatisfiedLinkError) {
                EditorLog.warn(e, "[SPC-JNA] Failed to load libspc: ${e.message}")
                null
            }
        }

        private val stateSaveSymbolsAvailable: Boolean by lazy {
            if (lib == null) {
                false
            } else {
                try {
                    val nativeLib = NativeLibrary.getInstance("spc")
                    nativeLib.getFunction("spc_init_header")
                    nativeLib.getFunction("spc_save_spc")
                    true
                } catch (_: LinkageError) {
                    false
                } catch (_: RuntimeException) {
                    false
                }
            }
        }

        fun isAvailable(): Boolean = lib != null

        /**
         * Build a standard .spc file image from a 64KB SPC RAM snapshot.
         *
         * The image uses Super Metroid's SPC engine entry point ($1500) and is
         * suitable as an interchange/debug snapshot. Importers can always read
         * the 64KB RAM back from offset $100 even if a player ignores ID666 tags.
         */
        fun buildSpcFileImage(baseRam: ByteArray, title: String = ""): ByteArray {
            require(baseRam.size == RAM_SIZE) { "Base RAM must be exactly 64KB" }
            val spcFile = ByteArray(SPC_FILE_SIZE)

            val sig = "SNES-SPC700 Sound File Data v0.30"
            for (i in sig.indices) spcFile[i] = sig[i].code.toByte()
            spcFile[33] = 0x1A.toByte()
            spcFile[34] = 0x1A.toByte()
            spcFile[35] = 26
            spcFile[36] = 30

            // CPU registers: PC=$1500, SP=$CF, PSW=$02
            spcFile[37] = 0x00 // PCL
            spcFile[38] = 0x15 // PCH
            spcFile[42] = 0x02 // PSW
            spcFile[43] = 0xCF.toByte() // SP

            if (title.isNotBlank()) {
                writeId666Title(spcFile, title)
            }

            // RAM at offset 0x100
            System.arraycopy(baseRam, 0, spcFile, 0x100, RAM_SIZE)

            // Clear port/control registers
            spcFile[0x100 + 0xF0] = 0x0A
            spcFile[0x100 + 0xF1] = 0x80.toByte()
            spcFile[0x100 + 0xF4] = 0
            spcFile[0x100 + 0xF5] = 0
            spcFile[0x100 + 0xF6] = 0
            spcFile[0x100 + 0xF7] = 0

            // DSP registers at 0x10100
            spcFile[0x10100 + 0x5D] = 0x6C // DIR page
            spcFile[0x10100 + 0x6C] = 0x20 // FLG: mute (engine clears when ready)

            // IPL ROM at 0x101C0
            System.arraycopy(IPL_ROM, 0, spcFile, 0x101C0, ROM_SIZE)

            return spcFile
        }

        fun readSpcRam(spcFile: ByteArray): ByteArray {
            val sig = "SNES-SPC700 Sound File Data v0.30".encodeToByteArray()
            require(spcFile.size >= 0x100 + RAM_SIZE) { "SPC file is too small" }
            require(spcFile.copyOfRange(0, sig.size).contentEquals(sig)) { "Not an SPC v0.30 file" }
            return spcFile.copyOfRange(0x100, 0x100 + RAM_SIZE)
        }

        private fun writeId666Title(spcFile: ByteArray, title: String) {
            val titleBytes = title.take(32).encodeToByteArray()
            titleBytes.copyInto(spcFile, destinationOffset = 0x2E, endIndex = minOf(titleBytes.size, 32))
        }
    }

    private interface SpcLib : Library {
        fun spc_new(): Pointer?
        fun spc_delete(spc: Pointer)
        fun spc_init_rom(spc: Pointer, rom: ByteArray)
        fun spc_load_spc(spc: Pointer, data: ByteArray, size: Long): String?
        fun spc_clear_echo(spc: Pointer)
        fun spc_play(spc: Pointer, count: Int, out: ShortArray?): String?
        fun spc_skip(spc: Pointer, count: Int): String?
        fun spc_write_port(spc: Pointer, time: Int, port: Int, data: Int)
        fun spc_read_port(spc: Pointer, time: Int, port: Int): Int
        fun spc_mute_voices(spc: Pointer, mask: Int)
        fun spc_init_header(spcOut: ByteArray)
        fun spc_save_spc(spc: Pointer, spcOut: ByteArray)

        fun spc_filter_new(): Pointer?
        fun spc_filter_delete(filter: Pointer)
        fun spc_filter_run(filter: Pointer, io: ShortArray, count: Int)
        fun spc_filter_clear(filter: Pointer)
    }

    /**
     * Build an SPC file image from a 64KB RAM dump and load it into the emulator.
     * Starts at engine entry point $1500.
     */
    /**
     * Initialize the emulator with base engine RAM, pre-apply song set transfer
     * blocks, and send a play command for the given track ID.
     *
     * @param baseRam 64KB SPC RAM with the engine loaded (from SpcData.buildInitialSpcRam)
     * @param songBlocks Song set transfer blocks to apply to the SPC RAM image
     * @param playIndex Track ID to send via port 0
     */
    fun loadFromRam(
        baseRam: ByteArray,
        songBlocks: List<SpcData.TransferBlock>,
        playIndex: Int
    ) {
        val library = lib ?: throw IllegalStateException("libspc not available")
        close()

        val spcPtr = library.spc_new()
            ?: throw OutOfMemoryError("spc_new returned null")
        spc = spcPtr

        library.spc_init_rom(spcPtr, IPL_ROM)

        val initialRam = if (songBlocks.isEmpty()) {
            baseRam
        } else {
            baseRam.copyOf().also { SpcData.applyTransferBlocks(it, songBlocks) }
        }
        loadedRamSnapshot = initialRam.copyOf()
        val spcImage = buildSpcFileImage(initialRam)
        val err = library.spc_load_spc(spcPtr, spcImage, spcImage.size.toLong())
        if (err != null) {
            close()
            throw RuntimeException("spc_load_spc: $err")
        }
        library.spc_clear_echo(spcPtr)

        // Let the engine initialize (~2 seconds)
        library.spc_skip(spcPtr, SAMPLE_RATE * 2 * 2)

        // Send play command: track ID to port 0
        library.spc_write_port(spcPtr, 0, 0, playIndex)

        // Warmup: let the command take effect (1 second)
        library.spc_skip(spcPtr, SAMPLE_RATE * 2)

        // Create output filter
        val filterPtr = library.spc_filter_new()
        if (filterPtr != null) {
            library.spc_filter_clear(filterPtr)
            filter = filterPtr
        }
    }

    fun saveSpcSnapshot(title: String = ""): ByteArray {
        val library = lib ?: throw IllegalStateException("libspc not available")
        val spcPtr = spc ?: throw IllegalStateException("Emulator not loaded")
        val fallbackRam = loadedRamSnapshot ?: throw IllegalStateException("No loaded SPC RAM snapshot")
        if (!stateSaveSymbolsAvailable) {
            EditorLog.warn("[SPC-JNA] libspc state-save symbols unavailable; exporting static SPC image")
            return buildSpcFileImage(fallbackRam, title)
        }
        val out = ByteArray(SPC_FILE_SIZE)
        return try {
            library.spc_init_header(out)
            library.spc_save_spc(spcPtr, out)
            if (title.isNotBlank()) {
                writeId666Title(out, title)
            }
            out
        } catch (e: LinkageError) {
            EditorLog.warn("[SPC-JNA] libspc state-save call failed (${e.message}); exporting static SPC image")
            buildSpcFileImage(fallbackRam, title)
        }
    }

    /**
     * Render stereo PCM audio from the emulator.
     * Returns interleaved stereo samples (L, R, L, R, ...).
     *
     * @param seconds Duration to render
     * @return Stereo PCM samples at 32000 Hz
     */
    fun renderStereo(seconds: Int): ShortArray {
        val library = lib ?: throw IllegalStateException("libspc not available")
        val spcPtr = spc ?: throw IllegalStateException("Emulator not loaded")

        val totalStereo = SAMPLE_RATE * seconds * 2
        val output = ShortArray(totalStereo)
        val filterPtr = filter
        val chunkSize = 4096

        var pos = 0
        while (pos < totalStereo) {
            val chunk = minOf(chunkSize, totalStereo - pos)
            val buf = ShortArray(chunk)
            val err = library.spc_play(spcPtr, chunk, buf)
            if (err != null) {
                EditorLog.warn("[SPC-JNA] Play error at sample ${pos / 2}: $err")
                break
            }
            if (filterPtr != null) {
                library.spc_filter_run(filterPtr, buf, chunk)
            }
            System.arraycopy(buf, 0, output, pos, chunk)
            pos += chunk
        }

        return output.copyOf(pos)
    }

    /**
     * Render and downmix to mono PCM at 32000 Hz.
     */
    fun renderMono(seconds: Int): ShortArray {
        val stereo = renderStereo(seconds)
        val mono = ShortArray(stereo.size / 2)
        for (i in mono.indices) {
            val l = stereo[i * 2].toInt()
            val r = stereo[i * 2 + 1].toInt()
            mono[i] = ((l + r) / 2).coerceIn(-32768, 32767).toShort()
        }
        return mono
    }

    override fun close() {
        val library = lib ?: return
        filter?.let { library.spc_filter_delete(it) }
        filter = null
        spc?.let { library.spc_delete(it) }
        spc = null
        loadedRamSnapshot = null
    }
}
