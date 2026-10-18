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
    fun `simple and unchanged patches toggle immediately but changed settings require a cancellable confirmation`() {
        val editor = EditorState().apply { testMode = true }
        val simple = editor.addPatch("Simple")
        editor.requestTogglePatch(simple.id)
        assertTrue(simple.enabled); assertEquals(null, editor.pendingPatchDisable)
        editor.requestTogglePatch(simple.id)
        assertFalse(simple.enabled); assertEquals(null, editor.pendingPatchDisable)
        val rates = SmPatch("rates", "Rates", enabled = true, configType = "environmental_damage",
            configData = mutableMapOf("heat_per_second" to 15, "lava_per_second" to 30, "acid_per_second" to 90))
        editor.project.patches.add(rates)
        editor.requestTogglePatch(rates.id)
        assertFalse(rates.enabled); assertEquals(null, editor.pendingPatchDisable)
        editor.setPatchConfigData(rates.id, "lava_per_second", 45)
        editor.requestTogglePatch(rates.id)
        assertTrue(rates.enabled); assertSame(rates, editor.pendingPatchDisable)
        editor.dismissPatchDisable(); assertTrue(rates.enabled); assertEquals(45, rates.configData?.get("lava_per_second"))
        editor.requestTogglePatch(rates.id); editor.confirmPatchDisable()
        assertFalse(rates.enabled); assertEquals(45, rates.configData?.get("lava_per_second"))
        editor.requestTogglePatch(rates.id); assertTrue(rates.enabled)
    }

    @Test
    fun `default comparisons use the input ROM rather than vanilla values`() {
        val rom = ByteArray(0x300000)
        val model = com.supermetroid.editor.rom.EnvironmentalDamagePatch
        model.FIELDS.forEach { field ->
            val rate = model.encodeEnergyPerSecond(60)
            rom[field.lowWordPc] = rate.lowWord.toByte(); rom[field.lowWordPc + 1] = (rate.lowWord ushr 8).toByte()
            rom[field.highWordPc] = rate.highWord.toByte(); rom[field.highWordPc + 1] = (rate.highWord ushr 8).toByte()
        }
        val parser = com.supermetroid.editor.rom.RomParser(rom)
        val patch = SmPatch("rates", "Rates", configType = model.CONFIG_TYPE, configData = mutableMapOf(model.HEAT_KEY to 60))
        assertFalse(patchSettingsDifferFromBaseline(patch, parser))
        patch.configData!![model.HEAT_KEY] = 15
        assertTrue(patchSettingsDifferFromBaseline(patch, parser))
        assertFalse(patchSettingsDifferFromBaseline(SmPatch("beam", "Beam", configType = "beam_damage", configData = mutableMapOf("power" to 20)), null))
        assertFalse(patchSettingsDifferFromBaseline(SmPatch("bomb", "Bomb", configType = BOMB_CONFIG_TYPE, configData = mutableMapOf(BOMB_FUSE_FRAMES_KEY to BOMB_DEFAULT_FUSE_FRAMES)), null))
        rom[model.FIELDS.last().lowWordPc] = 1
        val rounded = SmPatch("fraction", "Fraction", configType = model.CONFIG_TYPE, configData = mutableMapOf(model.ACID_KEY to 60))
        assertTrue(patchSettingsDifferFromBaseline(rounded, parser), "Equal rounded GUI values can still replace exact fractional ROM data")
        assertFalse(patchSettingsDifferFromBaseline(SmPatch("controls", "Controls", configType = "controller_config", configData = CONTROLLER_SLOTS.associate { it.key to it.defaultMask }.toMutableMap()), null))
    }

    @Test
    fun `confirmation cannot toggle a replacement patch or a different project`() {
        val editor = EditorState().apply { testMode = true }
        val patch = SmPatch("custom", "Custom", enabled = true, configType = "environmental_damage", configData = mutableMapOf("lava_per_second" to 45))
        editor.project.patches.add(patch); editor.requestTogglePatch(patch.id)
        editor.project.patches.remove(patch)
        val replacement = patch.copy(); editor.project.patches.add(replacement)
        editor.confirmPatchDisable(); assertTrue(replacement.enabled)
    }
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
