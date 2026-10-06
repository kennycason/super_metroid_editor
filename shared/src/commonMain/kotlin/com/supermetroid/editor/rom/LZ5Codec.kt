package com.supermetroid.editor.rom

/**
 * Strict decoder for Super Metroid's LZ5 stream format.
 *
 * This follows `Decompression_VariableDestination` at $80:B119. The engine advances
 * only the 16-bit destination address, so one invocation cannot safely emit more
 * than one 64 KiB bank. Callers with a non-zero destination offset should pass the
 * smaller remaining capacity as [maxOutputSize].
 */
object LZ5Codec {
    const val MAX_ENGINE_OUTPUT = 0x10000

    data class DecodeResult(
        val data: ByteArray,
        /** Number of source bytes consumed, including the $FF terminator. */
        val consumed: Int,
    )

    class FormatException(message: String) : IllegalArgumentException(message)

    fun decompress(
        source: ByteArray,
        startOffset: Int = 0,
        maxOutputSize: Int = MAX_ENGINE_OUTPUT,
    ): DecodeResult {
        if (startOffset !in source.indices) {
            throw FormatException(
                "Start offset $startOffset is outside ${source.size} source bytes"
            )
        }
        if (maxOutputSize !in 0..MAX_ENGINE_OUTPUT) {
            throw IllegalArgumentException(
                "LZ5 destination capacity must be between 0 and $MAX_ENGINE_OUTPUT bytes"
            )
        }

        val output = ByteArray(maxOutputSize)
        var sourcePosition = startOffset
        var outputPosition = 0

        fun readByte(context: String): Int {
            if (sourcePosition >= source.size) {
                throw FormatException(
                    "Truncated $context at source offset ${sourcePosition - startOffset}"
                )
            }
            return source[sourcePosition++].toInt() and 0xFF
        }

        fun write(value: Int) {
            output[outputPosition++] = value.toByte()
        }

        while (true) {
            val header = readByte("command header")
            if (header == 0xFF) {
                return DecodeResult(
                    data = output.copyOf(outputPosition),
                    consumed = sourcePosition - startOffset,
                )
            }

            val topBits = header ushr 5
            val command: Int
            val length: Int
            if (topBits == 7) {
                // $FF terminated above, so command 7 has only $FC..$FE (length <= 768).
                command = (header ushr 2) and 7
                length = (((header and 3) shl 8) or readByte("extended length")) + 1
            } else {
                command = topBits
                length = (header and 0x1F) + 1
            }

            if (outputPosition + length > maxOutputSize) {
                throw FormatException(
                    "Command $command would exceed the $maxOutputSize-byte destination capacity"
                )
            }

            when (command) {
                0 -> repeat(length) { write(readByte("direct-copy operand")) }
                1 -> {
                    val value = readByte("byte-fill operand")
                    repeat(length) { write(value) }
                }
                2 -> {
                    val first = readByte("word-fill operand")
                    val second = readByte("word-fill operand")
                    repeat(length) { index -> write(if (index and 1 == 0) first else second) }
                }
                3 -> {
                    val first = readByte("incrementing-fill operand")
                    repeat(length) { index -> write((first + index) and 0xFF) }
                }
                4, 5 -> {
                    val low = readByte("absolute-copy operand")
                    val high = readByte("absolute-copy operand")
                    var copyPosition = low or (high shl 8)
                    repeat(length) {
                        requireWrittenOutput(command, copyPosition, outputPosition)
                        val value = output[copyPosition++].toInt() and 0xFF
                        write(if (command == 5) value xor 0xFF else value)
                    }
                }
                6, 7 -> {
                    val distance = readByte("sliding-copy operand")
                    var copyPosition = outputPosition - distance
                    repeat(length) {
                        requireWrittenOutput(command, copyPosition, outputPosition)
                        val value = output[copyPosition++].toInt() and 0xFF
                        write(if (command == 7) value xor 0xFF else value)
                    }
                }
            }
        }
    }

    private fun requireWrittenOutput(command: Int, copyPosition: Int, outputPosition: Int) {
        if (copyPosition < 0 || copyPosition >= outputPosition) {
            throw FormatException(
                "Command $command reads unwritten output offset $copyPosition at output offset $outputPosition"
            )
        }
    }
}
