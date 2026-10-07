package com.supermetroid.editor.ui

import com.supermetroid.editor.data.ScrollCommand
import com.supermetroid.editor.rom.DoorAsmScrollAnalysis
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SCROLL_EXTENSION_DIRECTIONS
import com.supermetroid.editor.rom.scrollExtensionConnectsToTrigger

/**
 * Compact, editor-facing summary of the room scroll state that scroll-trigger PLMs can
 * create after the room's initial scroll table has loaded.
 */
internal data class ScrollRuntimeDiagnostics(
    val triggerCount: Int,
    val extensionCount: Int,
    val valuesByScreen: Map<Int, Set<Int>>,
    val invalidTargetCount: Int,
    val invalidValueCount: Int,
    val emptyCommandCount: Int,
    val unreadableCommandCount: Int,
    val orphanExtensionCount: Int,
    val doorRoutineCount: Int,
    val doorValuesByScreen: Map<Int, Set<Int>>,
    val invalidDoorTargetCount: Int,
    val mixedDoorRoutineCount: Int,
) {
    val affectedScreenCount: Int get() = valuesByScreen.size
    val doorAffectedScreenCount: Int get() = doorValuesByScreen.size
    val competingScreenCount: Int get() = valuesByScreen.count { (_, values) -> values.size > 1 }
    val issueCount: Int get() =
        invalidTargetCount + invalidValueCount + emptyCommandCount + unreadableCommandCount +
            orphanExtensionCount + invalidDoorTargetCount
}

internal fun buildScrollRuntimeDiagnostics(
    plms: List<RomParser.PlmEntry>,
    roomWidth: Int,
    roomHeight: Int,
    doorAnalyses: List<DoorAsmScrollAnalysis> = emptyList(),
    commandsFor: (RomParser.PlmEntry) -> List<ScrollCommand>?,
): ScrollRuntimeDiagnostics {
    val triggers = plms.filter { it.id == 0xB703 }
    val extensions = plms.filter { it.id in SCROLL_EXTENSION_DIRECTIONS }
    val roomScreenCount = roomWidth * roomHeight
    val valuesByScreen = linkedMapOf<Int, MutableSet<Int>>()
    var invalidTargets = 0
    var invalidValues = 0
    var emptyCommands = 0
    var unreadableCommands = 0
    val doorValuesByScreen = linkedMapOf<Int, MutableSet<Int>>()
    var invalidDoorTargets = 0

    for (trigger in triggers) {
        val commands = commandsFor(trigger)
        if (commands == null) {
            unreadableCommands++
            continue
        }
        if (commands.isEmpty()) {
            emptyCommands++
            continue
        }
        for (command in commands) {
            if (command.screenIndex !in 0 until roomScreenCount) {
                invalidTargets++
                continue
            }
            if (command.scrollValue !in 0..2) {
                invalidValues++
                continue
            }
            valuesByScreen.getOrPut(command.screenIndex) { linkedSetOf() }.add(command.scrollValue)
        }
    }

    val orphanExtensions = extensions.count { extension ->
        !scrollExtensionConnectsToTrigger(extension, plms)
    }
    for (analysis in doorAnalyses) {
        for (write in analysis.writes) {
            if (write.screenIndex !in 0 until roomScreenCount) {
                invalidDoorTargets++
            } else {
                doorValuesByScreen.getOrPut(write.screenIndex) { linkedSetOf() }.add(write.scrollValue)
            }
        }
    }

    return ScrollRuntimeDiagnostics(
        triggerCount = triggers.size,
        extensionCount = extensions.size,
        valuesByScreen = valuesByScreen.mapValues { (_, values) -> values.toSet() },
        invalidTargetCount = invalidTargets,
        invalidValueCount = invalidValues,
        emptyCommandCount = emptyCommands,
        unreadableCommandCount = unreadableCommands,
        orphanExtensionCount = orphanExtensions,
        doorRoutineCount = doorAnalyses.size,
        doorValuesByScreen = doorValuesByScreen.mapValues { (_, values) -> values.toSet() },
        invalidDoorTargetCount = invalidDoorTargets,
        mixedDoorRoutineCount = doorAnalyses.count { it.writes.isNotEmpty() && !it.isPureScrollRoutine },
    )
}

internal fun canPlaceScrollExtension(
    extensionId: Int,
    x: Int,
    y: Int,
    plms: List<RomParser.PlmEntry>,
): Boolean {
    if (extensionId !in SCROLL_EXTENSION_DIRECTIONS) return false
    val candidate = RomParser.PlmEntry(extensionId, x, y, 0x8000)
    return scrollExtensionConnectsToTrigger(candidate, plms + candidate)
}
