package com.supermetroid.editor.rom

val SCROLL_EXTENSION_DIRECTIONS: Map<Int, Pair<Int, Int>> = mapOf(
    0xB63B to Pair(1, 0),   // Extends right; predecessor is one block left.
    0xB63F to Pair(-1, 0),  // Extends left; predecessor is one block right.
    0xB647 to Pair(0, -1),  // Extends up; predecessor is one block down.
    0xB643 to Pair(0, 1),   // Extends down; predecessor is one block up.
)

/**
 * Follow an extension's predecessor graph back to a scroll trigger.
 *
 * A valid chain may turn: each predecessor extension contributes its own direction.
 * Treating the first extension's direction as the direction of the entire chain
 * incorrectly marks vanilla L-shaped chains as detached.
 */
fun scrollExtensionConnectsToTrigger(
    extension: RomParser.PlmEntry,
    plms: List<RomParser.PlmEntry>,
): Boolean {
    if (extension.id !in SCROLL_EXTENSION_DIRECTIONS) return true
    val byPosition = plms.groupBy { Pair(it.x, it.y) }
    val remaining = ArrayDeque<RomParser.PlmEntry>()
    val visited = mutableSetOf<Triple<Int, Int, Int>>()
    remaining.add(extension)
    while (remaining.isNotEmpty()) {
        val current = remaining.removeFirst()
        if (!visited.add(Triple(current.id, current.x, current.y))) continue
        val direction = SCROLL_EXTENSION_DIRECTIONS.getValue(current.id)
        val x = current.x - direction.first
        val y = current.y - direction.second
        val entries = byPosition[Pair(x, y)].orEmpty()
        if (entries.any { it.id == 0xB703 }) return true
        entries.filterTo(remaining) { it.id in SCROLL_EXTENSION_DIRECTIONS }
    }
    return false
}
