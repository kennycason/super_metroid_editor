package com.supermetroid.editor.rom

import java.io.ByteArrayOutputStream

/**
 * Minimal GIF89a encoder for animated sprites.
 *
 * Supports:
 * - Animated GIF with per-frame delay
 * - Transparency (color index 0)
 * - Infinite looping via Netscape extension
 * - Up to 256 colors per frame
 *
 * Uses GIF LZW compression (variable-length codes). No external dependencies.
 */
object GifEncoder {

    /**
     * Encode an animated GIF from sprite animation frames.
     *
     * @param frames  List of ARGB pixel arrays with dimensions
     * @param loop    Number of loops (0 = infinite)
     * @return GIF file bytes
     */
    fun encode(frames: List<SpriteAnimationFrame>, loop: Int = 0): ByteArray {
        require(frames.isNotEmpty()) { "At least one frame required" }

        val width = frames.maxOf { it.width }
        val height = frames.maxOf { it.height }

        val out = ByteArrayOutputStream(width * height * frames.size)

        // Build a global palette from all frames
        val globalPalette = buildGlobalPalette(frames)
        val paletteBits = paletteBitsNeeded(globalPalette.size)
        val paletteSize = 1 shl paletteBits

        // ── GIF Header ──
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))

        // Logical Screen Descriptor
        writeU16(out, width)
        writeU16(out, height)
        // packed: global color table flag (1), color resolution (paletteBits-1), sort (0), size (paletteBits-1)
        val packed = 0x80 or ((paletteBits - 1) shl 4) or (paletteBits - 1)
        out.write(packed)
        out.write(0) // background color index
        out.write(0) // pixel aspect ratio

        // Global Color Table
        for (i in 0 until paletteSize) {
            if (i < globalPalette.size) {
                val argb = globalPalette[i]
                out.write((argb shr 16) and 0xFF) // R
                out.write((argb shr 8) and 0xFF)  // G
                out.write(argb and 0xFF)           // B
            } else {
                out.write(0); out.write(0); out.write(0) // padding
            }
        }

        // ── Netscape Application Extension (loop control) ──
        out.write(0x21) // extension introducer
        out.write(0xFF) // application extension
        out.write(11)   // block size
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3)    // sub-block size
        out.write(1)    // sub-block index
        writeU16(out, loop)
        out.write(0)    // block terminator

        // ── Frames ──
        for (frame in frames) {
            // Center the frame in the logical screen
            val ox = (width - frame.width) / 2
            val oy = (height - frame.height) / 2

            // Graphic Control Extension
            out.write(0x21) // extension introducer
            out.write(0xF9) // graphic control
            out.write(4)    // block size
            // packed: disposal=restore to bg (2), user input=0, transparent=1
            out.write(0x09) // 00 001 0 01 = disposal 2 (restore to bg), transparent flag
            // Delay in centiseconds: ticks * 100/60, minimum 2cs to avoid browser issues
            val delayCentiseconds = if (frame.durationTicks > 0) {
                ((frame.durationTicks * 100) / 60).coerceAtLeast(2)
            } else {
                7 // ~4 ticks default ≈ 67ms
            }
            writeU16(out, delayCentiseconds)
            out.write(0) // transparent color index
            out.write(0) // block terminator

            // Image Descriptor
            out.write(0x2C) // image separator
            writeU16(out, ox)
            writeU16(out, oy)
            writeU16(out, frame.width)
            writeU16(out, frame.height)
            out.write(0) // packed: no local color table, not interlaced

            // LZW Image Data
            val indices = quantizeFrame(frame.pixels, frame.width, frame.height, globalPalette)
            writeLzwData(out, indices, paletteBits)
        }

        // ── GIF Trailer ──
        out.write(0x3B)

        return out.toByteArray()
    }

    /**
     * Encode a single static frame as a GIF (for consistency with PNG export).
     */
    fun encodeSingle(pixels: IntArray, width: Int, height: Int): ByteArray {
        val frame = SpriteAnimationFrame(pixels, width, height, durationTicks = 0)
        return encode(listOf(frame), loop = 0)
    }

    // ─── Palette building ──────────────────────────────────────────────

    /**
     * Build a global palette from all frames. Index 0 is always transparent.
     * Collects unique non-transparent colors, caps at 255 (+ transparent = 256).
     */
    private fun buildGlobalPalette(frames: List<SpriteAnimationFrame>): IntArray {
        val colors = linkedSetOf(0x00000000) // index 0 = transparent
        for (frame in frames) {
            for (px in frame.pixels) {
                if ((px ushr 24) >= 128) { // non-transparent
                    colors.add(px or 0xFF000000.toInt()) // force full alpha
                }
                if (colors.size >= 256) break
            }
            if (colors.size >= 256) break
        }
        return colors.toIntArray()
    }

    /**
     * Map ARGB pixels to palette indices. Transparent pixels map to index 0.
     */
    private fun quantizeFrame(
        pixels: IntArray, width: Int, height: Int, palette: IntArray
    ): ByteArray {
        // Build reverse lookup
        val colorToIdx = HashMap<Int, Int>(palette.size * 2)
        for (i in palette.indices) {
            colorToIdx[palette[i]] = i
        }

        val indices = ByteArray(width * height)
        for (i in pixels.indices) {
            val px = pixels[i]
            if ((px ushr 24) < 128) {
                indices[i] = 0 // transparent
            } else {
                val opaque = px or 0xFF000000.toInt()
                indices[i] = (colorToIdx[opaque] ?: findClosestColor(opaque, palette)).toByte()
            }
        }
        return indices
    }

    /**
     * Find the closest palette color by Euclidean distance in RGB space.
     * Skips index 0 (transparent).
     */
    private fun findClosestColor(argb: Int, palette: IntArray): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        var bestIdx = 1
        var bestDist = Int.MAX_VALUE
        for (i in 1 until palette.size) {
            val pr = (palette[i] shr 16) and 0xFF
            val pg = (palette[i] shr 8) and 0xFF
            val pb = palette[i] and 0xFF
            val dist = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }
        return bestIdx
    }

    // ─── LZW compression ──────────────────────────────────────────────

    /**
     * Write LZW-compressed image data as GIF sub-blocks.
     */
    private fun writeLzwData(out: ByteArrayOutputStream, indices: ByteArray, minCodeBits: Int) {
        val minBits = minCodeBits.coerceAtLeast(2) // GIF minimum is 2
        out.write(minBits) // LZW minimum code size

        val lzwData = lzwCompress(indices, minBits)

        // Write as sub-blocks (max 255 bytes each)
        var offset = 0
        while (offset < lzwData.size) {
            val blockSize = (lzwData.size - offset).coerceAtMost(255)
            out.write(blockSize)
            out.write(lzwData, offset, blockSize)
            offset += blockSize
        }
        out.write(0) // block terminator
    }

    /**
     * GIF LZW compression. Produces a byte stream of variable-length codes
     * packed LSB-first.
     */
    private fun lzwCompress(data: ByteArray, minCodeBits: Int): ByteArray {
        val clearCode = 1 shl minCodeBits
        val eoiCode = clearCode + 1

        val bitStream = BitWriter()
        var codeSize = minCodeBits + 1
        var nextCode = eoiCode + 1
        val maxTableSize = 4096

        // Initialize code table with single-character entries
        var table = HashMap<Long, Int>(4096)
        fun resetTable() {
            table.clear()
            for (i in 0 until clearCode) {
                table[i.toLong()] = i
            }
            codeSize = minCodeBits + 1
            nextCode = eoiCode + 1
        }

        // Emit clear code to start
        bitStream.writeBits(clearCode, codeSize)
        resetTable()

        if (data.isEmpty()) {
            bitStream.writeBits(eoiCode, codeSize)
            return bitStream.toByteArray()
        }

        // Pack sequences as longs: up to 8 bytes of indices encoded in a long
        // For simplicity, use a prefix + extension approach with hash keys
        var prefix = (data[0].toInt() and 0xFF).toLong()

        for (i in 1 until data.size) {
            val k = data[i].toInt() and 0xFF
            val combined = (prefix shl 12) or k.toLong()

            if (table.containsKey(combined)) {
                prefix = combined
            } else {
                bitStream.writeBits(table[prefix]!!, codeSize)

                if (nextCode < maxTableSize) {
                    table[combined] = nextCode++
                    if (nextCode > (1 shl codeSize) && codeSize < 12) {
                        codeSize++
                    }
                } else {
                    // Table full — emit clear code and reset
                    bitStream.writeBits(clearCode, codeSize)
                    resetTable()
                }

                prefix = k.toLong()
            }
        }

        // Emit final prefix code
        bitStream.writeBits(table[prefix]!!, codeSize)
        bitStream.writeBits(eoiCode, codeSize)

        return bitStream.toByteArray()
    }

    /** Helper to write bits LSB-first into a byte array. */
    private class BitWriter {
        private val bytes = ByteArrayOutputStream(4096)
        private var currentByte = 0
        private var bitPos = 0

        fun writeBits(code: Int, numBits: Int) {
            var c = code
            var remaining = numBits
            while (remaining > 0) {
                currentByte = currentByte or ((c and 1) shl bitPos)
                c = c shr 1
                bitPos++
                remaining--
                if (bitPos == 8) {
                    bytes.write(currentByte)
                    currentByte = 0
                    bitPos = 0
                }
            }
        }

        fun toByteArray(): ByteArray {
            if (bitPos > 0) {
                bytes.write(currentByte)
            }
            return bytes.toByteArray()
        }
    }

    // ─── Utilities ──────────────────────────────────────────────────────

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    private fun paletteBitsNeeded(colorCount: Int): Int {
        var bits = 1
        while ((1 shl bits) < colorCount) bits++
        return bits.coerceIn(2, 8) // GIF requires minimum 2
    }
}
