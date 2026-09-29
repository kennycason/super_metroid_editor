package com.supermetroid.editor.ui

import com.supermetroid.editor.data.AppSettings
import com.supermetroid.editor.data.PatchSortOrder
import com.supermetroid.editor.data.SmPatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PatchEditorTest {
    @Test
    fun `patch search covers names descriptions ids and config types`() {
        val patches = listOf(
            SmPatch(id = "hex_alpha", name = "Alpha", description = "Movement tweak"),
            SmPatch(id = "config_beta", name = "Beta", description = "Damage controls", configType = "hazard_rates"),
        )

        assertEquals(listOf("hex_alpha"), patchResults(patches, "movement"))
        assertEquals(listOf("config_beta"), patchResults(patches, "config_beta"))
        assertEquals(listOf("config_beta"), patchResults(patches, "hazard_rates"))
    }

    @Test
    fun `favorite patches stay grouped first in the selected name direction`() {
        val patches = listOf(
            SmPatch(id = "charlie", name = "Charlie"),
            SmPatch(id = "alpha", name = "Alpha"),
            SmPatch(id = "beta", name = "Beta"),
        )

        assertEquals(
            listOf("beta", "alpha", "charlie"),
            patchResults(patches, favorites = setOf("beta")),
        )
        assertEquals(
            listOf("beta", "charlie", "alpha"),
            patchResults(
                patches,
                sortOrder = PatchSortOrder.NAME_DESCENDING,
                favorites = setOf("beta"),
            ),
        )
        assertEquals(
            listOf("alpha", "beta", "charlie"),
            patchResults(patches, favorites = setOf("beta"), favoritesFirst = false),
        )
    }

    @Test
    fun `enabled patches stay above disabled patches with favorites first inside each group`() {
        val patches = listOf(
            SmPatch(id = "disabled_favorite", name = "Alpha", enabled = false),
            SmPatch(id = "enabled_plain", name = "Beta", enabled = true),
            SmPatch(id = "enabled_favorite", name = "Charlie", enabled = true),
            SmPatch(id = "disabled_plain", name = "Delta", enabled = false),
        )

        assertEquals(
            listOf("enabled_favorite", "enabled_plain", "disabled_favorite", "disabled_plain"),
            patchResults(
                patches,
                favorites = setOf("disabled_favorite", "enabled_favorite"),
            ),
        )
    }

    @Test
    fun `patch favorites persist globally without changing project settings`() {
        var persistedSettings = AppSettings()
        var persistenceCount = 0
        fun favoriteStore() = PatchFavoriteStore(persistedSettings) { ids, migrationComplete ->
            persistedSettings = persistedSettings.copy(
                patchFavoriteIds = ids,
                patchFavoritesMigratedToGlobalConfig = migrationComplete,
            )
            persistenceCount++
        }
        val state = EditorState(favoriteStore()).also { it.testMode = true }
        val patch = state.addPatch("Favorite me")

        state.togglePatchFavorite(patch.id)

        assertTrue(state.isPatchFavorite(patch.id))
        assertTrue(patch.id in persistedSettings.patchFavoriteIds)
        assertTrue(state.project.generalSettings.patchBrowser.favoritePatchIds.isEmpty())
        assertEquals(1, persistenceCount)

        val reopenedState = EditorState(favoriteStore())
        assertTrue(reopenedState.isPatchFavorite(patch.id))

        state.togglePatchFavorite(patch.id)
        assertFalse(state.isPatchFavorite(patch.id))
        assertFalse(patch.id in persistedSettings.patchFavoriteIds)
        assertEquals(2, persistenceCount)
    }

    @Test
    fun `patch browser sorting choices remain project settings`() {
        val state = EditorState(PatchFavoriteStore(AppSettings()) { _, _ -> }).also { it.testMode = true }

        state.setPatchSortOrder(PatchSortOrder.NAME_DESCENDING)
        state.setPatchFavoritesFirst(false)

        assertEquals(PatchSortOrder.NAME_DESCENDING, state.project.generalSettings.patchBrowser.sortOrder)
        assertFalse(state.project.generalSettings.patchBrowser.favoritesFirst)
    }

    @Test
    fun `legacy project favorites migrate only once`() {
        var persistedIds = emptyList<String>()
        var migrationComplete = false
        var persistenceCount = 0
        val store = PatchFavoriteStore(AppSettings()) { ids, migrated ->
            persistedIds = ids
            migrationComplete = migrated
            persistenceCount++
        }

        store.migrateLegacyFavorites(listOf("legacy_patch", "legacy_patch"))
        store.migrateLegacyFavorites(listOf("another_project_patch"))

        assertEquals(setOf("legacy_patch"), store.favoriteIds)
        assertEquals(listOf("legacy_patch"), persistedIds)
        assertTrue(migrationComplete)
        assertEquals(1, persistenceCount)
    }

    @Test
    fun `imported IPS patch is selected and enabled`() {
        val ips = byteArrayOf(
            'P'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte(), 'C'.code.toByte(), 'H'.code.toByte(),
            0x00, 0x12, 0x34, 0x00, 0x02, 0xAB.toByte(), 0xCD.toByte(),
            'E'.code.toByte(), 'O'.code.toByte(), 'F'.code.toByte(),
        )
        val state = EditorState().also { it.testMode = true }

        val patch = importIpsPatch(state, "echolocation_beam.ips", ips)

        assertEquals("Echolocation beam", patch.name)
        assertEquals("Imported from echolocation_beam.ips", patch.description)
        assertTrue(patch.enabled)
        assertEquals(patch.id, state.selectedPatchId)
        assertSame(patch, state.project.patches.single())
        assertEquals(0x1234L, patch.writes.single().offset)
        assertEquals(listOf(0xAB, 0xCD), patch.writes.single().bytes)
    }

    private fun patchResults(
        patches: List<SmPatch>,
        query: String = "",
        sortOrder: PatchSortOrder = PatchSortOrder.NAME_ASCENDING,
        favorites: Set<String> = emptySet(),
        favoritesFirst: Boolean = true,
    ): List<String> = filterAndSortPatches(
        patches = patches,
        searchQuery = query,
        sortOrder = sortOrder,
        favoritePatchIds = favorites,
        favoritesFirst = favoritesFirst,
    ).map { it.id }
}
