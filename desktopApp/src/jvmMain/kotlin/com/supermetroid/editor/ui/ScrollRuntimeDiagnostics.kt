package com.supermetroid.editor.ui

import com.supermetroid.editor.data.ScrollCommand
import com.supermetroid.editor.rom.RomParser

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
) {
    val affectedScreenCount: Int get() = valuesByScreen.size
    val competingScreenCount: Int get() = valuesByScreen.count { (_, values) -> values.size > 1 }
    val issueCount: Int get() =
        invalidTargetCount + invalidValueCount + emptyCommandCount + unreadableCommandCount + orphanExtensionCount
}

internal fun buildScrollRuntimeDiagnostics(
    plms: List<RomParser.PlmEntry>,
    roomWidth: Int,
    roomHeight: Int,
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
        !extensionConnectsToTrigger(extension, plms)
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
    )
}

private val SCROLL_EXTENSION_DIRECTIONS = mapOf(
    0xB63B to Pair(1, 0),   // Extends right from a trigger or another right extension.
    0xB63F to Pair(-1, 0),  // Extends left.
    0xB647 to Pair(0, -1),  // Extends up.
    0xB643 to Pair(0, 1),   // Extends down.
)

internal fun canPlaceScrollExtension(
    extensionId: Int,
    x: Int,
    y: Int,
    plms: List<RomParser.PlmEntry>,
): Boolean {
    if (extensionId !in SCROLL_EXTENSION_DIRECTIONS) return false
    val candidate = RomParser.PlmEntry(extensionId, x, y, 0x8000)
    return extensionConnectsToTrigger(candidate, plms + candidate)
}

private fun extensionConnectsToTrigger(
    extension: RomParser.PlmEntry,
    plms: List<RomParser.PlmEntry>,
): Boolean {
    val direction = SCROLL_EXTENSION_DIRECTIONS[extension.id] ?: return true
    val byPosition = plms.groupBy { Pair(it.x, it.y) }
    var x = extension.x - direction.first
    var y = extension.y - direction.second
    var remaining = plms.size + 1
    while (remaining-- > 0) {
        val entries = byPosition[Pair(x, y)].orEmpty()
        if (entries.any { it.id == 0xB703 }) return true
        if (entries.none { it.id == extension.id }) return false
        x -= direction.first
        y -= direction.second
    }
    return false
}
