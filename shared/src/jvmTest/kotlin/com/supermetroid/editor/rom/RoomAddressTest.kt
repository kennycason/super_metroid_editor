package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Test to verify that room IDs are offsets within bank $8F
 * and that room headers can be read directly from those addresses.
 * 
 * Room ID 0x91F8 = SNES address $8F:91F8
 * Super Metroid uses HiROM mapping:
 *   PC offset = ((bank & 0x3F) * 0x10000) + address
 *   For $8F: (0x0F * 0x10000) + 0x91F8 = 0x0F91F8
 */
class RoomAddressTest {
    
    private fun getRom(): ByteArray? = TestRomHelper.loadRomBytes()
    
    @Test
    fun `verify room ID is offset in bank 8F`() {
        val romData = getRom() ?: return
        val output = File("/tmp/room_address_test.txt")
        val sb = StringBuilder()
        
        val hasHeader = romData.size == 0x300200
        val romStart = if (hasHeader) 0x200 else 0
        
        sb.appendLine("ROM size: ${romData.size} (has SMC header: $hasHeader)")
        sb.appendLine()
        
        // Check ROM internal header to determine mapping mode
        // LoROM header at PC $7FC0, mode byte at $7FD5
        // HiROM header at PC $FFC0, mode byte at $FFD5
        val loRomMode = romData[romStart + 0x7FD5].toInt() and 0xFF
        val hiRomMode = romData[romStart + 0xFFD5].toInt() and 0xFF
        val loRomTitle = String(romData, romStart + 0x7FC0, 21).trim()
        val hiRomTitle = String(romData, romStart + 0xFFC0, 21).trim()
        
        sb.appendLine("=== ROM Header Detection ===")
        sb.appendLine("LoROM check: mode=0x${loRomMode.toString(16)}, title='$loRomTitle'")
        sb.appendLine("HiROM check: mode=0x${hiRomMode.toString(16)}, title='$hiRomTitle'")
        sb.appendLine("  LoROM mode bit 0 clear = LoROM: ${(loRomMode and 0x01) == 0}")
        sb.appendLine("  HiROM mode bit 0 set = HiROM: ${(hiRomMode and 0x01) == 1}")
        sb.appendLine()
        
        // Room IDs from the auto tracker
        val knownRooms = mapOf(
            0x91F8 to "Landing Site",
            0x93AA to "Crateria Power Bomb Room",
            0x93FE to "West Ocean",
            0x9804 to "Bomb Torizo",
            0xA59F to "Kraid",
            0xB32E to "Ridley",
            0xCD13 to "Phantoon",
            0xDA60 to "Draygon",
            0xDD58 to "Mother Brain"
        )
        
        // Test BOTH HiROM and LoROM conversions
        for (mode in listOf("LoROM", "HiROM")) {
            sb.appendLine("========== $mode Conversion ==========")
            var validCount = 0
            
            for ((roomId, roomName) in knownRooms) {
                val pcOffset = if (mode == "LoROM") {
                    // LoROM: ROM offset = ((bank & 0x7F) * 0x8000) + (address - 0x8000)
                    // Bank $8F & 0x7F = 0x0F
                    romStart + (0x0F * 0x8000) + (roomId - 0x8000)
                } else {
                    // HiROM: ROM offset = ((bank & 0x3F) * 0x10000) + address
                    // Bank $8F & 0x3F = 0x0F
                    romStart + (0x0F * 0x10000) + roomId
                }
                
                sb.appendLine("  $roomName (0x${roomId.toString(16)}) -> PC 0x${pcOffset.toString(16)}")
                
                if (pcOffset + 11 < romData.size && pcOffset >= 0) {
                    val index = romData[pcOffset].toInt() and 0xFF
                    val area = romData[pcOffset + 1].toInt() and 0xFF
                    val mapX = romData[pcOffset + 2].toInt() and 0xFF
                    val mapY = romData[pcOffset + 3].toInt() and 0xFF
                    val width = romData[pcOffset + 4].toInt() and 0xFF
                    val height = romData[pcOffset + 5].toInt() and 0xFF
                    
                    val isValid = width in 1..16 && height in 1..16 && area in 0..6
                    if (isValid) validCount++
                    
                    val rawBytes = romData.sliceArray(pcOffset until minOf(pcOffset + 12, romData.size))
                    val hexStr = rawBytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                    
                    sb.appendLine("    Raw: $hexStr")
                    sb.appendLine("    index=$index area=$area size=${width}x${height} VALID=$isValid")
                } else {
                    sb.appendLine("    OUT OF BOUNDS!")
                }
            }
            sb.appendLine("  >>> Valid rooms with $mode: $validCount / ${knownRooms.size}")
            sb.appendLine()
        }
        
        output.writeText(sb.toString())
        println(sb.toString())
    }
}
