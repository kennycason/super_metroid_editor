package com.supermetroid.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.supermetroid.editor.data.AppConfig
import com.supermetroid.editor.data.AppSettings

/**
 * Application-wide patch favorites. Favorites are editor preferences, not project data.
 *
 * [migrateLegacyFavorites] imports the project-local favorites written by older SMEDIT
 * builds once. An explicit toggle also completes migration so an intentionally empty
 * global list is not later repopulated from an old project.
 */
class PatchFavoriteStore(
    initialSettings: AppSettings = AppConfig.load(),
    private val persist: (favoriteIds: List<String>, migrationComplete: Boolean) -> Unit =
        { favoriteIds, migrationComplete ->
            AppConfig.update {
                copy(
                    patchFavoriteIds = favoriteIds,
                    patchFavoritesMigratedToGlobalConfig = migrationComplete,
                )
            }
        },
) {
    var favoriteIds by mutableStateOf(initialSettings.patchFavoriteIds.toSet())
        private set

    private var migrationComplete = initialSettings.patchFavoritesMigratedToGlobalConfig

    fun migrateLegacyFavorites(legacyFavoriteIds: Collection<String>) {
        if (migrationComplete || legacyFavoriteIds.isEmpty()) return
        update(favoriteIds + legacyFavoriteIds, migrationComplete = true)
    }

    fun toggle(id: String, legacyFavoriteIds: Collection<String> = emptyList()) {
        val current = favoritesIncludingLegacy(legacyFavoriteIds)
        val updated = if (id in current) current - id else current + id
        update(updated, migrationComplete = true)
    }

    fun remove(id: String, legacyFavoriteIds: Collection<String> = emptyList()) {
        val current = favoritesIncludingLegacy(legacyFavoriteIds)
        update(current - id, migrationComplete = true)
    }

    private fun favoritesIncludingLegacy(legacyFavoriteIds: Collection<String>): Set<String> =
        if (migrationComplete) favoriteIds else favoriteIds + legacyFavoriteIds

    private fun update(updatedIds: Set<String>, migrationComplete: Boolean) {
        if (favoriteIds == updatedIds && this.migrationComplete == migrationComplete) return
        favoriteIds = updatedIds
        this.migrationComplete = migrationComplete
        persist(updatedIds.toList(), migrationComplete)
    }
}
