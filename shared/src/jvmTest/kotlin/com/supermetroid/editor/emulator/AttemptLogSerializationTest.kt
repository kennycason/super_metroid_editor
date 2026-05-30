package com.supermetroid.editor.emulator

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AttemptLogSerializationTest {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    @Test
    fun `attempt log serializes and deserializes losslessly`() {
        val log = AttemptLog(
            emulatorCore = "snes9x 1.62",
            romHash = "rom-hash",
            initialStateHash = "state-hash",
            frames = listOf(
                FrameRecord(
                    frameNumber = 0,
                    inputBits = SnesInputBits.fromPressed(SnesButton.RIGHT),
                    systemRamHash = "frame-0-hash",
                    frameState = SuperMetroidFrameState(
                        roomId = 0x91F8,
                        samusX = 128,
                    ),
                ),
                FrameRecord(
                    frameNumber = 1,
                    inputBits = SnesInputBits.fromPressed(SnesButton.RIGHT, SnesButton.A),
                    systemRamHash = "frame-1-hash",
                    frameState = SuperMetroidFrameState(
                        roomId = 0x91F8,
                        samusX = 129,
                    ),
                ),
            ),
        )

        val encoded = json.encodeToString(log)
        val decoded = json.decodeFromString<AttemptLog>(encoded)

        assertEquals(log, decoded)
    }

    @Test
    fun `frame record preserves frame number input bits and hashes`() {
        val inputBits = SnesInputBits.fromPressed(SnesButton.B, SnesButton.RIGHT, SnesButton.R)
        val record = FrameRecord(
            frameNumber = 42,
            inputBits = inputBits,
            systemRamHash = "abc123",
            frameState = SuperMetroidFrameState(health = 99),
        )

        assertEquals(42, record.frameNumber)
        assertEquals(inputBits, record.inputBits)
        assertEquals("abc123", record.systemRamHash)
        assertEquals(SuperMetroidFrameState(health = 99), record.frameState)
    }

    @Test
    fun `snes input bitmasks round trip through libretro button order`() {
        val inputBits = SnesInputBits.fromPressed(
            SnesButton.B,
            SnesButton.RIGHT,
            SnesButton.R,
        )

        assertEquals((1 shl 0) or (1 shl 7) or (1 shl 11), inputBits)
        assertEquals(inputBits, SnesInputBits.fromButtonList(SnesInputBits.toButtonList(inputBits)))
        assertEquals(SnesInputBits.VALID_MASK, SnesInputBits.normalize(-1))
    }

    @Test
    fun `super metroid wram decoder reads selected frame addresses`() {
        val wram = ByteArray(SuperMetroidWram.minimumFrameRecordBytes)
        wram.writeWord(SuperMetroidWram.ROOM_ID, 0x92FD)
        wram.writeWord(SuperMetroidWram.GAME_STATE, 0x0009)
        wram.writeWord(SuperMetroidWram.SAMUS_X, 1234)
        wram.writeWord(SuperMetroidWram.SAMUS_X_SUBPIXEL, 0x0010)
        wram.writeWord(SuperMetroidWram.SAMUS_Y, 567)
        wram.writeWord(SuperMetroidWram.SAMUS_Y_SUBPIXEL, 0x0020)
        wram.writeWord(SuperMetroidWram.SAMUS_HORIZONTAL_SPEED_PIXELS, 2)
        wram.writeWord(SuperMetroidWram.SAMUS_HORIZONTAL_SPEED_SUBPIXELS, 0x4000)
        wram.writeWord(SuperMetroidWram.SAMUS_VERTICAL_SPEED_PIXELS, 3)
        wram.writeWord(SuperMetroidWram.SAMUS_VERTICAL_SPEED_SUBPIXELS, 0x8000)
        wram.writeWord(SuperMetroidWram.HEALTH, 99)

        val frameState = SuperMetroidWram.frameState(wram)

        assertEquals(0x92FD, frameState.roomId)
        assertEquals(0x0009, frameState.gameState)
        assertEquals(1234, frameState.samusX)
        assertEquals(0x0010, frameState.samusXSubpixel)
        assertEquals(567, frameState.samusY)
        assertEquals(0x0020, frameState.samusYSubpixel)
        assertEquals(2, frameState.samusHorizontalSpeedPixels)
        assertEquals(0x4000, frameState.samusHorizontalSpeedSubpixels)
        assertEquals(3, frameState.samusVerticalSpeedPixels)
        assertEquals(0x8000, frameState.samusVerticalSpeedSubpixels)
        assertEquals(99, frameState.health)
        assertTrue(frameState.doorTransition)
    }

    private fun ByteArray.writeWord(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
