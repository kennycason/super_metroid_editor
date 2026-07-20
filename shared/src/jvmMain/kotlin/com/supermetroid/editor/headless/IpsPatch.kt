package com.supermetroid.editor.headless

import com.supermetroid.editor.data.PatchWrite
import java.io.ByteArrayOutputStream
import kotlin.math.min

object IpsPatch {
    fun encodeWrites(writes: List<PatchWrite>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("PATCH".toByteArray(Charsets.US_ASCII))

        for (write in writes) {
            var offset = write.offset.toInt()
            var bytesOffset = 0
            while (bytesOffset < write.bytes.size) {
                val chunkSize = min(0xFFFF, write.bytes.size - bytesOffset)
                require(offset in 0..0xFFFFFF && offset + chunkSize - 1 <= 0xFFFFFF) {
                    "IPS offset out of range: $offset (${write.bytes.size} bytes)"
                }
                write24(out, offset)
                write16(out, chunkSize)
                for (i in 0 until chunkSize) {
                    out.write(write.bytes[bytesOffset + i] and 0xFF)
                }
                offset += chunkSize
                bytesOffset += chunkSize
            }
        }

        out.write("EOF".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    fun encodeDiff(original: ByteArray, modified: ByteArray): ByteArray {
        require(original.size == modified.size) {
            "IPS diff requires equal-size ROMs; original=${original.size}, modified=${modified.size}"
        }
        val out = ByteArrayOutputStream()
        out.write("PATCH".toByteArray(Charsets.US_ASCII))

        var offset = 0
        while (offset < original.size) {
            if (original[offset] == modified[offset]) {
                offset++
                continue
            }

            val start = offset
            while (offset < original.size &&
                original[offset] != modified[offset] &&
                offset - start < 0xFFFF
            ) {
                offset++
            }

            write24(out, start)
            write16(out, offset - start)
            out.write(modified, start, offset - start)
        }

        out.write("EOF".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    private fun write16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun write24(out: ByteArrayOutputStream, value: Int) {
        require(value in 0..0xFFFFFF) { "IPS offset out of range: $value" }
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
