package com.supermetroid.editor.rom

import com.supermetroid.editor.data.ProjectRoomStateConditionKind
import kotlin.test.Test
import kotlin.test.assertEquals

class SmEditCompiledRoomStateConditionFormatTest {
    @Test
    fun `every compiled leaf agrees with semantic simulator for matching and nonmatching input`() {
        val area = 2
        val cases = listOf(
            projectRoomStateCondition(ProjectRoomStateConditionKind.INCOMING_DOOR, 0xA123) to
                RoomStateSimulationContext(area = area, incomingDoorPointer = 0xA123),
            projectRoomStateCondition(ProjectRoomStateConditionKind.AREA_MAIN_BOSS_DEAD) to
                RoomStateSimulationContext(area = area, bossBitsByArea = mapOf(area to 0x01)),
            projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0x0B) to
                RoomStateSimulationContext(area = area, events = setOf(0x0B)),
            projectRoomStateCondition(ProjectRoomStateConditionKind.AREA_BOSS_BIT_SET, 0x04) to
                RoomStateSimulationContext(area = area, bossBitsByArea = mapOf(area to 0x04)),
            projectRoomStateCondition(ProjectRoomStateConditionKind.MORPH_BALL_COLLECTED) to
                RoomStateSimulationContext(area = area, collectedEquipment = 0x0004),
            projectRoomStateCondition(ProjectRoomStateConditionKind.MORPH_BALL_AND_MISSILES) to
                RoomStateSimulationContext(area = area, collectedEquipment = 0x0004, maxMissiles = 5),
            projectRoomStateCondition(ProjectRoomStateConditionKind.POWER_BOMBS_COLLECTED) to
                RoomStateSimulationContext(area = area, maxPowerBombs = 5),
            projectRoomStateCondition(ProjectRoomStateConditionKind.SPEED_BOOSTER_COLLECTED) to
                RoomStateSimulationContext(area = area, collectedEquipment = 0x2000),
            projectRoomStateCondition(ProjectRoomStateConditionKind.EQUIPMENT_COLLECTED, 0x0020) to
                RoomStateSimulationContext(area = area, collectedEquipment = 0x0020),
            projectRoomStateCondition(ProjectRoomStateConditionKind.BEAM_COLLECTED, 0x1000) to
                RoomStateSimulationContext(area = area, collectedBeams = 0x1000),
            projectRoomStateCondition(ProjectRoomStateConditionKind.MISSILE_CAPACITY_AT_LEAST, 15) to
                RoomStateSimulationContext(area = area, maxMissiles = 15),
            projectRoomStateCondition(ProjectRoomStateConditionKind.SUPER_MISSILE_CAPACITY_AT_LEAST, 10) to
                RoomStateSimulationContext(area = area, maxSuperMissiles = 10),
            projectRoomStateCondition(ProjectRoomStateConditionKind.POWER_BOMB_CAPACITY_AT_LEAST, 5) to
                RoomStateSimulationContext(area = area, maxPowerBombs = 5),
            projectRoomStateCondition(ProjectRoomStateConditionKind.ENERGY_CAPACITY_AT_LEAST, 199) to
                RoomStateSimulationContext(area = area, maxEnergy = 199),
            projectRoomStateCondition(ProjectRoomStateConditionKind.RESERVE_CAPACITY_AT_LEAST, 100) to
                RoomStateSimulationContext(area = area, maxReserveEnergy = 100),
            projectRoomStateCondition(ProjectRoomStateConditionKind.ITEM_PICKUP_COLLECTED, 0x051) to
                RoomStateSimulationContext(area = area, collectedItemPickupIds = setOf(0x051)),
            projectRoomStateCondition(
                ProjectRoomStateConditionKind.BOSS_DEFEATED,
                packedBossConditionArgument(4, 0x02),
            ) to RoomStateSimulationContext(area = area, bossBitsByArea = mapOf(4 to 0x02)),
            projectRoomStateCondition(ProjectRoomStateConditionKind.EQUIPMENT_EQUIPPED, 0x0001) to
                RoomStateSimulationContext(area = area, equippedEquipment = 0x0001),
            projectRoomStateCondition(ProjectRoomStateConditionKind.BEAM_EQUIPPED, 0x0004) to
                RoomStateSimulationContext(area = area, equippedBeams = 0x0004),
            projectRoomStateCondition(ProjectRoomStateConditionKind.CURRENT_ENERGY_AT_LEAST, 30) to
                RoomStateSimulationContext(area = area, currentEnergy = 30),
            projectRoomStateCondition(ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST, 6) to
                RoomStateSimulationContext(area = area, currentMissiles = 6),
            projectRoomStateCondition(ProjectRoomStateConditionKind.CURRENT_SUPER_MISSILES_AT_LEAST, 3) to
                RoomStateSimulationContext(area = area, currentSuperMissiles = 3),
            projectRoomStateCondition(ProjectRoomStateConditionKind.CURRENT_POWER_BOMBS_AT_LEAST, 2) to
                RoomStateSimulationContext(area = area, currentPowerBombs = 2),
            projectRoomStateCondition(ProjectRoomStateConditionKind.CURRENT_RESERVE_ENERGY_AT_LEAST, 25) to
                RoomStateSimulationContext(area = area, currentReserveEnergy = 25),
            projectRoomStateCondition(ProjectRoomStateConditionKind.DOOR_BIT_SET, 0x122) to
                RoomStateSimulationContext(area = area, openedDoorIds = setOf(0x122)),
            projectRoomStateCondition(ProjectRoomStateConditionKind.CHOZO_BLOCK_DESTROYED, 0x133) to
                RoomStateSimulationContext(area = area, destroyedChozoBlockIds = setOf(0x133)),
            projectRoomStateCondition(ProjectRoomStateConditionKind.ESCAPE_ACTIVE) to
                RoomStateSimulationContext(area = area, events = setOf(0x0E)),
        )

        for ((condition, matchingContext) in cases) {
            assertEquals(true, condition.matches(matchingContext), "semantic fixture did not match $condition")
            assertEquals(true, runInterpreter(condition, matchingContext), "machine false-negative for $condition")
            val emptyContext = RoomStateSimulationContext(area = area)
            assertEquals(
                condition.matches(emptyContext),
                runInterpreter(condition, emptyContext),
                "machine false-path disagreed for $condition",
            )
        }

        val never = projectRoomStateCondition(ProjectRoomStateConditionKind.NEVER)
        assertEquals(false, runInterpreter(never, RoomStateSimulationContext(area = area)))
        assertEquals(
            true,
            runInterpreter(never.copy(negated = true), RoomStateSimulationContext(area = area)),
        )
    }

    @Test
    fun `postfix interpreter machine code agrees with semantic simulator`() {
        val condition = projectRoomStateCondition(
            ProjectRoomStateConditionKind.ALL_OF,
            children = listOf(
                projectRoomStateCondition(ProjectRoomStateConditionKind.ESCAPE_ACTIVE),
                projectRoomStateCondition(
                    ProjectRoomStateConditionKind.ANY_OF,
                    children = listOf(
                        projectRoomStateCondition(ProjectRoomStateConditionKind.EQUIPMENT_EQUIPPED, 0x0001),
                        projectRoomStateCondition(ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST, 10),
                    ),
                ),
                projectRoomStateCondition(ProjectRoomStateConditionKind.ITEM_PICKUP_COLLECTED, 0x51, true),
                projectRoomStateCondition(
                    ProjectRoomStateConditionKind.BOSS_DEFEATED,
                    packedBossConditionArgument(4, 0x02),
                ),
            ),
        )
        val contexts = listOf(
            RoomStateSimulationContext(
                area = 2,
                events = setOf(0x0E),
                currentMissiles = 12,
                bossBitsByArea = mapOf(4 to 0x02),
            ),
            RoomStateSimulationContext(
                area = 2,
                events = setOf(0x0E),
                equippedEquipment = 0x0001,
                collectedItemPickupIds = setOf(0x51),
                bossBitsByArea = mapOf(4 to 0x02),
            ),
            RoomStateSimulationContext(
                area = 2,
                events = emptySet(),
                currentMissiles = 99,
                bossBitsByArea = mapOf(4 to 0x02),
            ),
        )

        for (context in contexts) {
            assertEquals(
                condition.matches(context),
                runInterpreter(condition, context),
                "machine interpreter disagreed for $context",
            )
        }
    }

    @Test
    fun `postfix data decodes only when canonical and complete`() {
        val condition = projectRoomStateCondition(
            ProjectRoomStateConditionKind.ANY_OF,
            children = listOf(
                projectRoomStateCondition(ProjectRoomStateConditionKind.DOOR_BIT_SET, 0x22),
                projectRoomStateCondition(ProjectRoomStateConditionKind.CHOZO_BLOCK_DESTROYED, 0x33),
            ),
        )
        val bytes = SmEditCompiledRoomStateConditionFormat.expressionBytes(condition, area = 1)
        val rom = ByteArray(0x200) { 0xFF.toByte() }
        bytes.copyInto(rom, 0x40)

        assertEquals(condition, SmEditCompiledRoomStateConditionFormat.decodeExpression(rom, 0x40, 1)?.condition)
        rom[0x40 + bytes.lastIndex - 1] = 0x7F
        assertEquals(null, SmEditCompiledRoomStateConditionFormat.decodeExpression(rom, 0x40, 1))
    }

    @Test
    fun `largest valid flat group round trips`() {
        val condition = projectRoomStateCondition(
            ProjectRoomStateConditionKind.ALL_OF,
            children = List(63) { index ->
                projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, index)
            },
        )
        val bytes = SmEditCompiledRoomStateConditionFormat.expressionBytes(condition, area = 0)
        val rom = ByteArray(bytes.size + 16)
        bytes.copyInto(rom, 8)

        assertEquals(condition, SmEditCompiledRoomStateConditionFormat.decodeExpression(rom, 8, 0)?.condition)
    }

    private class Memory {
        private val bytes = mutableMapOf<Int, Int>()
        fun byte(address: Int, value: Int) { bytes[address] = value and 0xFF }
        fun word(address: Int, value: Int) { byte(address, value); byte(address + 1, value ushr 8) }
        fun readByte(address: Int): Int = bytes[address] ?: 0
        fun readWord(address: Int): Int = readByte(address) or (readByte(address + 1) shl 8)
    }

    private fun runInterpreter(
        condition: com.supermetroid.editor.data.ProjectRoomStateCondition,
        context: RoomStateSimulationContext,
    ): Boolean {
        val code = SmEditCompiledRoomStateConditionFormat.interpreterBytes
        val expression = SmEditCompiledRoomStateConditionFormat.expressionBytes(condition, context.area)
        val memory = Memory()
        val selector = 0x9000
        val expressionPointer = 0xA000
        memory.word(selector, expressionPointer)
        memory.word(selector + 2, 0xBEEF)
        expression.forEachIndexed { index, value ->
            memory.byte(0x8F0000 + expressionPointer + index, value.toInt())
        }
        memory.word(0x09A2, context.equippedEquipment)
        memory.word(0x09A4, context.collectedEquipment)
        memory.word(0x09A6, context.equippedBeams)
        memory.word(0x09A8, context.collectedBeams)
        memory.word(0x09C2, context.currentEnergy)
        memory.word(0x09C4, context.maxEnergy)
        memory.word(0x09C6, context.currentMissiles)
        memory.word(0x09C8, context.maxMissiles)
        memory.word(0x09CA, context.currentSuperMissiles)
        memory.word(0x09CC, context.maxSuperMissiles)
        memory.word(0x09CE, context.currentPowerBombs)
        memory.word(0x09D0, context.maxPowerBombs)
        memory.word(0x09D4, context.maxReserveEnergy)
        memory.word(0x09D6, context.currentReserveEnergy)
        memory.word(0x078D, context.incomingDoorPointer ?: 0)
        context.bossBitsByArea.forEach { (area, bits) -> memory.byte(0x7ED828 + area, bits) }
        context.collectedItemPickupIds.forEach { id ->
            val address = 0x7ED870 + (id ushr 3)
            memory.byte(address, memory.readByte(address) or (1 shl (id and 7)))
        }
        context.openedDoorIds.forEach { id ->
            val address = 0x7ED8B0 + (id ushr 3)
            memory.byte(address, memory.readByte(address) or (1 shl (id and 7)))
        }
        context.destroyedChozoBlockIds.forEach { id ->
            val address = 0x7ED830 + (id ushr 3)
            memory.byte(address, memory.readByte(address) or (1 shl (id and 7)))
        }

        var pc = 0
        var a = 0
        var x = selector
        var zero = false
        var carry = false
        val stack = mutableListOf<Int>()
        fun setA(value: Int) { a = value and 0xFFFF; zero = a == 0 }
        fun codeByte(): Int = code[pc++].toInt() and 0xFF
        fun codeWord(): Int = codeByte() or (codeByte() shl 8)
        fun signed8(value: Int): Int = if (value < 0x80) value else value - 0x100
        fun signed16(value: Int): Int = if (value < 0x8000) value else value - 0x10000

        repeat(4096) {
            when (val opcode = codeByte()) {
                0xDA -> stack += x
                0xBD -> setA(memory.readWord((x + codeWord()) and 0xFFFF))
                0xAA -> x = a
                0xE8 -> { x = (x + 1) and 0xFFFF; zero = x == 0 }
                0xBF -> {
                    val address = codeByte() or (codeByte() shl 8) or (codeByte() shl 16)
                    setA(memory.readWord(address + x))
                }
                0x29 -> setA(a and codeWord())
                0xA9 -> setA(codeWord())
                0xC9 -> {
                    val value = codeWord()
                    carry = a >= value
                    zero = a == value
                }
                0xD0 -> { val d = signed8(codeByte()); if (!zero) pc += d }
                0xF0 -> { val d = signed8(codeByte()); if (zero) pc += d }
                0xB0 -> { val d = signed8(codeByte()); if (carry) pc += d }
                0x82 -> { val d = signed16(codeWord()); pc += d }
                0xAD -> setA(memory.readWord(codeWord()))
                0x8D -> memory.word(codeWord(), a)
                0x2D -> setA(a and memory.readWord(codeWord()))
                0x0D -> setA(a or memory.readWord(codeWord()))
                0xCD -> {
                    val value = memory.readWord(codeWord())
                    carry = a >= value
                    zero = a == value
                }
                0x22 -> {
                    val address = codeByte() or (codeByte() shl 8) or (codeByte() shl 16)
                    when (address) {
                        0x80818E -> {
                            val bit = a
                            x = bit ushr 3
                            memory.word(0x05E7, 1 shl (bit and 7))
                            setA(x)
                        }
                        0x808233 -> carry = a in context.events
                        else -> error("Unsupported JSL \$${address.toString(16)}")
                    }
                }
                0xFA -> { x = stack.removeLast(); zero = x == 0 }
                0xEB -> setA(((a and 0xFF) shl 8) or ((a ushr 8) and 0xFF))
                0x48 -> stack += a
                0x68 -> setA(stack.removeLast())
                0x49 -> setA(a xor codeWord())
                0x4C -> {
                    check(codeWord() == 0xE5E6)
                    return x == 0xBEEF
                }
                0x60 -> return false
                0x00 -> error("Generated interpreter reached fail-closed BRK")
                else -> error("Unsupported interpreter opcode \$${opcode.toString(16)} at ${pc - 1}")
            }
        }
        error("Generated interpreter did not terminate")
    }
}
