package com.supermetroid.editor.rom

import com.supermetroid.editor.data.ProjectRoomStateCondition
import com.supermetroid.editor.data.ProjectRoomStateConditionKind

/** Postfix condition bytecode plus the shared bank-$8F interpreter that evaluates it. */
object SmEditCompiledRoomStateConditionFormat {
    const val ENTRY_SIZE_BYTES = 6 // routine pointer + expression pointer + state pointer
    private const val MAX_NODES = 64
    private const val MAX_DEPTH = 8

    private const val OP_INCOMING_DOOR = 0x01
    private const val OP_AREA_MAIN_BOSS = 0x02
    private const val OP_NEVER = 0x03
    private const val OP_EVENT = 0x04
    private const val OP_AREA_BOSS = 0x05
    private const val OP_MORPH = 0x06
    private const val OP_MORPH_MISSILES = 0x07
    private const val OP_POWER_BOMBS = 0x08
    private const val OP_SPEED_BOOSTER = 0x09
    private const val OP_EQUIPMENT_COLLECTED = 0x0A
    private const val OP_BEAM_COLLECTED = 0x0B
    private const val OP_MAX_MISSILES = 0x0C
    private const val OP_MAX_SUPERS = 0x0D
    private const val OP_MAX_POWER_BOMBS = 0x0E
    private const val OP_MAX_ENERGY = 0x0F
    private const val OP_MAX_RESERVE = 0x10
    private const val OP_ITEM_PICKUP = 0x11
    private const val OP_BOSS = 0x12
    private const val OP_EQUIPMENT_EQUIPPED = 0x13
    private const val OP_BEAM_EQUIPPED = 0x14
    private const val OP_CURRENT_ENERGY = 0x15
    private const val OP_CURRENT_MISSILES = 0x16
    private const val OP_CURRENT_SUPERS = 0x17
    private const val OP_CURRENT_POWER_BOMBS = 0x18
    private const val OP_CURRENT_RESERVE = 0x19
    private const val OP_DOOR_BIT = 0x1A
    private const val OP_CHOZO_BLOCK = 0x1B
    private const val OP_ESCAPE_ACTIVE = 0x1C
    private const val LAST_LEAF_OPCODE = OP_ESCAPE_ACTIVE

    private const val OP_ALL = 0xF0
    private const val OP_ANY = 0xF1
    private const val OP_NOT = 0xF2
    private const val OP_END = 0xFF

    private val expressionTag = byteArrayOf(0x53, 0x4D, 0x45, 0x58, 0x01) // SMEX, v1

    data class DecodedExpression(
        val condition: ProjectRoomStateCondition,
        val totalSizeBytes: Int,
    )

    fun expressionBytes(condition: ProjectRoomStateCondition, area: Int): ByteArray {
        val bytecode = mutableListOf<Int>()
        var nodeCount = 0

        fun emitWord(value: Int) {
            bytecode += value and 0xFF
            bytecode += (value ushr 8) and 0xFF
        }

        fun emitLeaf(opcode: Int, value: Int) {
            bytecode += opcode
            emitWord(value)
        }

        fun encode(node: ProjectRoomStateCondition, depth: Int) {
            require(depth <= MAX_DEPTH) { "Condition nesting exceeds $MAX_DEPTH levels" }
            nodeCount++
            require(nodeCount <= MAX_NODES) { "Condition contains more than $MAX_NODES nodes" }
            when (node.kind) {
                ProjectRoomStateConditionKind.ALL_OF,
                ProjectRoomStateConditionKind.ANY_OF -> {
                    require(node.children.size >= 2) { "Compound conditions need at least two children" }
                    encode(node.children.first(), depth + 1)
                    for (child in node.children.drop(1)) {
                        encode(child, depth + 1)
                        bytecode += if (node.kind == ProjectRoomStateConditionKind.ALL_OF) OP_ALL else OP_ANY
                    }
                }
                ProjectRoomStateConditionKind.DEFAULT ->
                    error("The mandatory default branch cannot be nested in a condition")
                else -> {
                    val (opcode, value) = encodedLeaf(node, area)
                    emitLeaf(opcode, value)
                }
            }
            if (node.negated) bytecode += OP_NOT
        }

        encode(condition, 0)
        bytecode += OP_END
        require(bytecode.size <= 0xFF) { "Compiled condition exceeds 255 bytecode bytes" }
        return ByteArray(expressionTag.size + 1 + bytecode.size).also { result ->
            expressionTag.copyInto(result)
            result[expressionTag.size] = bytecode.size.toByte()
            bytecode.forEachIndexed { index, value -> result[expressionTag.size + 1 + index] = value.toByte() }
        }
    }

    fun decodeExpression(romData: ByteArray, pcOffset: Int, area: Int): DecodedExpression? {
        if (pcOffset < 0 || pcOffset + expressionTag.size + 1 > romData.size) return null
        if (expressionTag.indices.any { romData[pcOffset + it] != expressionTag[it] }) return null
        val length = romData[pcOffset + expressionTag.size].toInt() and 0xFF
        val start = pcOffset + expressionTag.size + 1
        val end = start + length
        if (length == 0 || end > romData.size) return null
        val stack = mutableListOf<ProjectRoomStateCondition>()
        var cursor = start
        while (cursor < end) {
            val opcode = romData[cursor++].toInt() and 0xFF
            when {
                opcode in 1..LAST_LEAF_OPCODE -> {
                    if (cursor + 2 > end) return null
                    val value = (romData[cursor].toInt() and 0xFF) or
                        ((romData[cursor + 1].toInt() and 0xFF) shl 8)
                    cursor += 2
                    stack += decodedLeaf(opcode, value) ?: return null
                }
                opcode == OP_ALL || opcode == OP_ANY -> {
                    if (stack.size < 2) return null
                    val right = stack.removeLast()
                    val left = stack.removeLast()
                    val kind = if (opcode == OP_ALL) {
                        ProjectRoomStateConditionKind.ALL_OF
                    } else {
                        ProjectRoomStateConditionKind.ANY_OF
                    }
                    val children = buildList {
                        if (left.kind == kind && !left.negated) addAll(left.children) else add(left)
                        if (right.kind == kind && !right.negated) addAll(right.children) else add(right)
                    }
                    stack += projectRoomStateCondition(kind, children = children)
                }
                opcode == OP_NOT -> {
                    if (stack.isEmpty()) return null
                    val value = stack.removeLast()
                    stack += value.copy(negated = !value.negated, routineCode = 0)
                }
                opcode == OP_END -> {
                    if (cursor != end || stack.size != 1) return null
                    val condition = stack.single()
                    if (!conditionTreeIsValid(condition)) return null
                    val canonical = expressionBytes(condition, area)
                    val actual = romData.copyOfRange(pcOffset, end)
                    if (!canonical.contentEquals(actual)) return null
                    return DecodedExpression(condition, end - pcOffset)
                }
                else -> return null
            }
        }
        return null
    }

    fun conditionTreeIsValid(condition: ProjectRoomStateCondition): Boolean {
        var nodes = 0
        fun visit(node: ProjectRoomStateCondition, depth: Int): Boolean {
            nodes++
            if (nodes > MAX_NODES || depth > MAX_DEPTH) return false
            return when (node.kind) {
                ProjectRoomStateConditionKind.DEFAULT -> false
                ProjectRoomStateConditionKind.ALL_OF,
                ProjectRoomStateConditionKind.ANY_OF ->
                    node.argument == null && node.children.size >= 2 && node.children.all { visit(it, depth + 1) }
                else -> node.children.isEmpty()
            }
        }
        return visit(condition, 0)
    }

    private fun encodedLeaf(condition: ProjectRoomStateCondition, area: Int): Pair<Int, Int> = when (condition.kind) {
        ProjectRoomStateConditionKind.INCOMING_DOOR -> OP_INCOMING_DOOR to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.AREA_MAIN_BOSS_DEAD -> OP_AREA_MAIN_BOSS to packedBossConditionArgument(area, 1)
        ProjectRoomStateConditionKind.NEVER -> OP_NEVER to 0
        ProjectRoomStateConditionKind.EVENT_SET -> OP_EVENT to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.AREA_BOSS_BIT_SET ->
            OP_AREA_BOSS to packedBossConditionArgument(area, condition.argument ?: 0)
        ProjectRoomStateConditionKind.MORPH_BALL_COLLECTED -> OP_MORPH to 0x0004
        ProjectRoomStateConditionKind.MORPH_BALL_AND_MISSILES -> OP_MORPH_MISSILES to 0
        ProjectRoomStateConditionKind.POWER_BOMBS_COLLECTED -> OP_POWER_BOMBS to 1
        ProjectRoomStateConditionKind.SPEED_BOOSTER_COLLECTED -> OP_SPEED_BOOSTER to 0x2000
        ProjectRoomStateConditionKind.EQUIPMENT_COLLECTED -> OP_EQUIPMENT_COLLECTED to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.BEAM_COLLECTED -> OP_BEAM_COLLECTED to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.MISSILE_CAPACITY_AT_LEAST -> OP_MAX_MISSILES to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.SUPER_MISSILE_CAPACITY_AT_LEAST -> OP_MAX_SUPERS to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.POWER_BOMB_CAPACITY_AT_LEAST -> OP_MAX_POWER_BOMBS to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.ENERGY_CAPACITY_AT_LEAST -> OP_MAX_ENERGY to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.RESERVE_CAPACITY_AT_LEAST -> OP_MAX_RESERVE to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.ITEM_PICKUP_COLLECTED -> OP_ITEM_PICKUP to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.BOSS_DEFEATED -> OP_BOSS to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.EQUIPMENT_EQUIPPED -> OP_EQUIPMENT_EQUIPPED to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.BEAM_EQUIPPED -> OP_BEAM_EQUIPPED to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.CURRENT_ENERGY_AT_LEAST -> OP_CURRENT_ENERGY to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST -> OP_CURRENT_MISSILES to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.CURRENT_SUPER_MISSILES_AT_LEAST -> OP_CURRENT_SUPERS to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.CURRENT_POWER_BOMBS_AT_LEAST -> OP_CURRENT_POWER_BOMBS to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.CURRENT_RESERVE_ENERGY_AT_LEAST -> OP_CURRENT_RESERVE to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.DOOR_BIT_SET -> OP_DOOR_BIT to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.CHOZO_BLOCK_DESTROYED -> OP_CHOZO_BLOCK to (condition.argument ?: 0)
        ProjectRoomStateConditionKind.ESCAPE_ACTIVE -> OP_ESCAPE_ACTIVE to 0x000E
        ProjectRoomStateConditionKind.DEFAULT,
        ProjectRoomStateConditionKind.ALL_OF,
        ProjectRoomStateConditionKind.ANY_OF -> error("${condition.kind} is not a condition leaf")
    }

    private fun decodedLeaf(opcode: Int, value: Int): ProjectRoomStateCondition? = when (opcode) {
        OP_INCOMING_DOOR -> projectRoomStateCondition(ProjectRoomStateConditionKind.INCOMING_DOOR, value)
        OP_AREA_MAIN_BOSS -> projectRoomStateCondition(ProjectRoomStateConditionKind.AREA_MAIN_BOSS_DEAD)
        OP_NEVER -> projectRoomStateCondition(ProjectRoomStateConditionKind.NEVER)
        OP_EVENT -> projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, value)
        OP_AREA_BOSS -> projectRoomStateCondition(ProjectRoomStateConditionKind.AREA_BOSS_BIT_SET, value and 0xFF)
        OP_MORPH -> projectRoomStateCondition(ProjectRoomStateConditionKind.MORPH_BALL_COLLECTED)
        OP_MORPH_MISSILES -> projectRoomStateCondition(ProjectRoomStateConditionKind.MORPH_BALL_AND_MISSILES)
        OP_POWER_BOMBS -> projectRoomStateCondition(ProjectRoomStateConditionKind.POWER_BOMBS_COLLECTED)
        OP_SPEED_BOOSTER -> projectRoomStateCondition(ProjectRoomStateConditionKind.SPEED_BOOSTER_COLLECTED)
        OP_EQUIPMENT_COLLECTED -> projectRoomStateCondition(ProjectRoomStateConditionKind.EQUIPMENT_COLLECTED, value)
        OP_BEAM_COLLECTED -> projectRoomStateCondition(ProjectRoomStateConditionKind.BEAM_COLLECTED, value)
        OP_MAX_MISSILES -> projectRoomStateCondition(ProjectRoomStateConditionKind.MISSILE_CAPACITY_AT_LEAST, value)
        OP_MAX_SUPERS -> projectRoomStateCondition(ProjectRoomStateConditionKind.SUPER_MISSILE_CAPACITY_AT_LEAST, value)
        OP_MAX_POWER_BOMBS -> projectRoomStateCondition(ProjectRoomStateConditionKind.POWER_BOMB_CAPACITY_AT_LEAST, value)
        OP_MAX_ENERGY -> projectRoomStateCondition(ProjectRoomStateConditionKind.ENERGY_CAPACITY_AT_LEAST, value)
        OP_MAX_RESERVE -> projectRoomStateCondition(ProjectRoomStateConditionKind.RESERVE_CAPACITY_AT_LEAST, value)
        OP_ITEM_PICKUP -> projectRoomStateCondition(ProjectRoomStateConditionKind.ITEM_PICKUP_COLLECTED, value)
        OP_BOSS -> projectRoomStateCondition(ProjectRoomStateConditionKind.BOSS_DEFEATED, value)
        OP_EQUIPMENT_EQUIPPED -> projectRoomStateCondition(ProjectRoomStateConditionKind.EQUIPMENT_EQUIPPED, value)
        OP_BEAM_EQUIPPED -> projectRoomStateCondition(ProjectRoomStateConditionKind.BEAM_EQUIPPED, value)
        OP_CURRENT_ENERGY -> projectRoomStateCondition(ProjectRoomStateConditionKind.CURRENT_ENERGY_AT_LEAST, value)
        OP_CURRENT_MISSILES -> projectRoomStateCondition(ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST, value)
        OP_CURRENT_SUPERS -> projectRoomStateCondition(ProjectRoomStateConditionKind.CURRENT_SUPER_MISSILES_AT_LEAST, value)
        OP_CURRENT_POWER_BOMBS -> projectRoomStateCondition(ProjectRoomStateConditionKind.CURRENT_POWER_BOMBS_AT_LEAST, value)
        OP_CURRENT_RESERVE -> projectRoomStateCondition(ProjectRoomStateConditionKind.CURRENT_RESERVE_ENERGY_AT_LEAST, value)
        OP_DOOR_BIT -> projectRoomStateCondition(ProjectRoomStateConditionKind.DOOR_BIT_SET, value)
        OP_CHOZO_BLOCK -> projectRoomStateCondition(ProjectRoomStateConditionKind.CHOZO_BLOCK_DESTROYED, value)
        OP_ESCAPE_ACTIVE -> projectRoomStateCondition(ProjectRoomStateConditionKind.ESCAPE_ACTIVE)
        else -> null
    }

    private class RoutineBuilder {
        private val bytes = mutableListOf<Int>()
        private val labels = mutableMapOf<String, Int>()
        private val rel8 = mutableListOf<Pair<Int, String>>()
        private val rel16 = mutableListOf<Pair<Int, String>>()
        fun emit(vararg values: Int) { bytes.addAll(values.toList()) }
        fun label(name: String) { check(labels.put(name, bytes.size) == null) }
        fun branch8(op: Int, target: String) { emit(op, 0); rel8 += bytes.lastIndex to target }
        fun branch16(target: String) { emit(0x82, 0, 0); rel16 += (bytes.size - 2) to target }
        fun build(): ByteArray {
            rel8.forEach { (operand, name) ->
                val distance = checkNotNull(labels[name]) - (operand + 1)
                check(distance in -128..127)
                bytes[operand] = distance and 0xFF
            }
            rel16.forEach { (lowOperand, name) ->
                val distance = checkNotNull(labels[name]) - (lowOperand + 2)
                check(distance in -32768..32767)
                bytes[lowOperand] = distance and 0xFF
                bytes[lowOperand + 1] = (distance ushr 8) and 0xFF
            }
            return bytes.map(Int::toByte).toByteArray()
        }
    }

    /** Shared 65816 postfix interpreter. `$0012` is the engine's documented DP scratch word. */
    val interpreterBytes: ByteArray = RoutineBuilder().run {
        emit(0xDA) // PHX: preserve selector payload pointer below the boolean stack
        emit(0xBD, 0x00, 0x00) // LDA expression pointer
        emit(0xAA) // TAX
        repeat(expressionTag.size + 1) { emit(0xE8) } // skip SMEX/version/length header
        label("loop")
        emit(0xBF, 0x00, 0x00, 0x8F) // LDA.l $8F0000,X
        emit(0x29, 0xFF, 0x00)
        emit(0xE8) // INX

        fun special(opcode: Int, label: String) {
            emit(0xC9, opcode, 0x00) // CMP #opcode
            val next = "specialNext${opcode.toString(16)}"
            branch8(0xD0, next)
            branch16(label)
            label(next)
        }
        special(OP_END, "end")
        special(OP_ALL, "all")
        special(OP_ANY, "any")
        special(OP_NOT, "not")

        val handlers = linkedMapOf(
            OP_INCOMING_DOOR to "incomingDoor",
            OP_AREA_MAIN_BOSS to "boss",
            OP_NEVER to "never",
            OP_EVENT to "event",
            OP_AREA_BOSS to "boss",
            OP_MORPH to "equipmentCollected",
            OP_MORPH_MISSILES to "morphMissiles",
            OP_POWER_BOMBS to "maxPowerBombs",
            OP_SPEED_BOOSTER to "equipmentCollected",
            OP_EQUIPMENT_COLLECTED to "equipmentCollected",
            OP_BEAM_COLLECTED to "beamCollected",
            OP_MAX_MISSILES to "maxMissiles",
            OP_MAX_SUPERS to "maxSupers",
            OP_MAX_POWER_BOMBS to "maxPowerBombs",
            OP_MAX_ENERGY to "maxEnergy",
            OP_MAX_RESERVE to "maxReserve",
            OP_ITEM_PICKUP to "itemPickup",
            OP_BOSS to "boss",
            OP_EQUIPMENT_EQUIPPED to "equipmentEquipped",
            OP_BEAM_EQUIPPED to "beamEquipped",
            OP_CURRENT_ENERGY to "currentEnergy",
            OP_CURRENT_MISSILES to "currentMissiles",
            OP_CURRENT_SUPERS to "currentSupers",
            OP_CURRENT_POWER_BOMBS to "currentPowerBombs",
            OP_CURRENT_RESERVE to "currentReserve",
            OP_DOOR_BIT to "doorBit",
            OP_CHOZO_BLOCK to "chozoBlock",
            OP_ESCAPE_ACTIVE to "event",
        )
        for ((opcode, handler) in handlers) {
            emit(0xC9, opcode, 0x00)
            val next = "leafNext$opcode"
            branch8(0xD0, next)
            branch16(handler)
            label(next)
        }
        branch16("invalid")

        fun readArgument() {
            emit(0xBF, 0x00, 0x00, 0x8F)
            emit(0xE8, 0xE8)
        }
        fun testMask(label: String, address: Int) {
            label(label)
            readArgument()
            emit(0x8D, 0x12, 0x00) // STA $0012
            emit(0xAD, address and 0xFF, (address ushr 8) and 0xFF)
            emit(0x2D, 0x12, 0x00)
            val yes = "${label}Yes"
            branch8(0xD0, yes)
            branch16("rawFalse")
            label(yes)
            branch16("rawTrue")
        }
        fun threshold(label: String, address: Int) {
            label(label)
            readArgument()
            emit(0x8D, 0x12, 0x00)
            emit(0xAD, address and 0xFF, (address ushr 8) and 0xFF)
            emit(0xCD, 0x12, 0x00) // CMP $0012
            val yes = "${label}Yes"
            branch8(0xB0, yes)
            branch16("rawFalse")
            label(yes)
            branch16("rawTrue")
        }
        fun persistentBit(label: String, base: Int) {
            label(label)
            readArgument()
            emit(0xDA) // save expression cursor
            emit(0x22, 0x8E, 0x81, 0x80)
            emit(0xBF, base and 0xFF, (base ushr 8) and 0xFF, (base ushr 16) and 0xFF)
            emit(0xFA) // PLX changes N/Z
            emit(0x2D, 0xE7, 0x05) // test after restoring X so branches see the bit result
            val yes = "${label}Yes"
            branch8(0xD0, yes)
            branch16("rawFalse")
            label(yes)
            branch16("rawTrue")
        }

        label("incomingDoor")
        readArgument()
        emit(0xCD, 0x8D, 0x07) // CMP $078D
        branch8(0xF0, "incomingDoorYes")
        branch16("rawFalse")
        label("incomingDoorYes")
        branch16("rawTrue")

        label("never")
        emit(0xE8, 0xE8)
        branch16("rawFalse")

        label("event")
        readArgument()
        emit(0xDA)
        emit(0x22, 0x33, 0x82, 0x80)
        emit(0xFA)
        branch8(0xB0, "eventYes")
        branch16("rawFalse")
        label("eventYes")
        branch16("rawTrue")

        testMask("equipmentCollected", 0x09A4)
        testMask("beamCollected", 0x09A8)
        testMask("equipmentEquipped", 0x09A2)
        testMask("beamEquipped", 0x09A6)

        label("morphMissiles")
        emit(0xE8, 0xE8)
        emit(0xAD, 0xA4, 0x09, 0x29, 0x04, 0x00)
        branch8(0xF0, "morphMissilesNo")
        emit(0xAD, 0xC8, 0x09)
        branch8(0xD0, "morphMissilesYes")
        label("morphMissilesNo")
        branch16("rawFalse")
        label("morphMissilesYes")
        branch16("rawTrue")

        threshold("maxMissiles", 0x09C8)
        threshold("maxSupers", 0x09CC)
        threshold("maxPowerBombs", 0x09D0)
        threshold("maxEnergy", 0x09C4)
        threshold("maxReserve", 0x09D4)
        threshold("currentEnergy", 0x09C2)
        threshold("currentMissiles", 0x09C6)
        threshold("currentSupers", 0x09CA)
        threshold("currentPowerBombs", 0x09CE)
        threshold("currentReserve", 0x09D6)

        persistentBit("itemPickup", 0x7ED870)
        persistentBit("doorBit", 0x7ED8B0)
        persistentBit("chozoBlock", 0x7ED830)

        label("boss")
        readArgument()
        emit(0x8D, 0x12, 0x00)
        emit(0xEB, 0x29, 0xFF, 0x00) // XBA; AND #$00FF
        emit(0xDA, 0xAA) // PHX; TAX
        emit(0xBF, 0x28, 0xD8, 0x7E, 0x29, 0xFF, 0x00)
        emit(0xFA, 0x2D, 0x12, 0x00)
        branch8(0xD0, "bossYes")
        branch16("rawFalse")
        label("bossYes")
        branch16("rawTrue")

        label("rawTrue")
        emit(0xA9, 0x01, 0x00, 0x48)
        branch16("loop")
        label("rawFalse")
        emit(0xA9, 0x00, 0x00, 0x48)
        branch16("loop")

        label("all")
        emit(0x68, 0x8D, 0x12, 0x00, 0x68, 0x2D, 0x12, 0x00, 0x48)
        branch16("loop")
        label("any")
        emit(0x68, 0x8D, 0x12, 0x00, 0x68, 0x0D, 0x12, 0x00, 0x48)
        branch16("loop")
        label("not")
        emit(0x68, 0x49, 0x01, 0x00, 0x48)
        branch16("loop")

        label("end")
        emit(0x68) // PLA boolean; test it before PLX changes the status flags
        branch8(0xF0, "endFalse")
        emit(0xFA) // selector payload pointer
        emit(0xBD, 0x02, 0x00, 0xAA, 0x4C, 0xE6, 0xE5)
        label("endFalse")
        emit(0xFA)
        label("failure")
        emit(0xE8, 0xE8, 0xE8, 0xE8, 0x60)
        label("invalid")
        emit(0x00, 0x00) // validated data can never reach this BRK

        emit(0x53, 0x4D, 0x45, 0x58, 0x52, 0x55, 0x4E, 0x01) // SMEXRUN, v1
        build()
    }

    fun interpreterMatchesAt(bytes: ByteArray, pcOffset: Int): Boolean =
        pcOffset >= 0 && pcOffset + interpreterBytes.size <= bytes.size &&
            interpreterBytes.indices.all { bytes[pcOffset + it] == interpreterBytes[it] }
}
