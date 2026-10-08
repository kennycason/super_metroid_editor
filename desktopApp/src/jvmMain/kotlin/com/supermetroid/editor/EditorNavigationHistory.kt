package com.supermetroid.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.supermetroid.editor.asm.AsmWorkspaceLocation

/** A restorable point in the editor's application-wide navigation trail. */
internal data class EditorNavigationLocation(
    val tab: Int,
    val roomId: Int?,
    val tilesetSubTab: Int,
    val tilesetId: Int,
    val spriteIndex: Int,
    val soundTrackId: Int,
    val minimapArea: Int,
    val minimapRoomId: Int?,
    val asmLocation: AsmWorkspaceLocation?,
)

/**
 * Browser-style history for major editor destinations.
 *
 * The current destination is owned by the application. This class stores only
 * the places departed from, which keeps state restoration explicit and avoids
 * history entries caused by restoring an older location.
 */
internal class EditorNavigationHistory<T>(
    private val maxEntries: Int = 100,
) {
    private val backStack = mutableListOf<T>()
    private val forwardStack = mutableListOf<T>()

    var revision by mutableIntStateOf(0)
        private set

    val canGoBack: Boolean
        get() {
            revision // Make stack availability observable in Compose callers.
            return backStack.isNotEmpty()
        }
    val canGoForward: Boolean
        get() {
            revision
            return forwardStack.isNotEmpty()
        }

    fun recordDeparture(location: T) {
        if (backStack.lastOrNull() != location) {
            backStack += location
            if (backStack.size > maxEntries) backStack.removeAt(0)
        }
        forwardStack.clear()
        revision++
    }

    fun goBack(current: T): T? {
        val target = backStack.removeLastOrNull() ?: return null
        if (forwardStack.lastOrNull() != current) forwardStack += current
        revision++
        return target
    }

    fun goForward(current: T): T? {
        val target = forwardStack.removeLastOrNull() ?: return null
        if (backStack.lastOrNull() != current) backStack += current
        revision++
        return target
    }

    fun clear() {
        if (backStack.isEmpty() && forwardStack.isEmpty()) return
        backStack.clear()
        forwardStack.clear()
        revision++
    }
}
