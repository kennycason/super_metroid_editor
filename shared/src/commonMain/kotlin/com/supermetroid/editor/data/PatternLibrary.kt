package com.supermetroid.editor.data

import com.supermetroid.editor.util.EditorLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Wrapper for serializing a list of patterns.
 */
@Serializable
data class PatternList(
    val patterns: MutableList<TilePattern> = mutableListOf()
)

/**
 * Shared pattern library stored in ~/.smedit/patterns/.
 * CRE (common) patterns live in cre.json.
 * URE (tileset-specific) patterns live in tileset_{id}.json.
 * Built-in patterns are never persisted here.
 */
object PatternLibrary {
    private val patternsDir = File(System.getProperty("user.home"), ".smedit/patterns")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = false }

    private fun creFile(): File = File(patternsDir, "cre.json")
    private fun tilesetFile(tilesetId: Int): File = File(patternsDir, "tileset_$tilesetId.json")

    fun loadCrePatterns(): List<TilePattern> = loadFile(creFile())
    fun loadTilesetPatterns(tilesetId: Int): List<TilePattern> = loadFile(tilesetFile(tilesetId))

    fun loadAllPatterns(): List<TilePattern> {
        val result = mutableListOf<TilePattern>()
        result.addAll(loadCrePatterns())
        if (patternsDir.exists()) {
            patternsDir.listFiles()
                ?.filter { it.name.startsWith("tileset_") && it.extension == "json" }
                ?.forEach { file ->
                    result.addAll(loadFile(file))
                }
        }
        return result
    }

    fun saveCrePatterns(patterns: List<TilePattern>) {
        val userPatterns = patterns.filter { !it.builtIn && it.tilesetId == null }
        saveFile(creFile(), userPatterns)
    }

    fun saveTilesetPatterns(tilesetId: Int, patterns: List<TilePattern>) {
        val userPatterns = patterns.filter { !it.builtIn && it.tilesetId == tilesetId }
        saveFile(tilesetFile(tilesetId), userPatterns)
    }

    /**
     * Persist all non-built-in patterns from the given list.
     * CRE patterns go to cre.json, URE patterns to tileset_{id}.json.
     */
    fun saveAll(patterns: List<TilePattern>) {
        val userPatterns = patterns.filter { !it.builtIn }
        val cre = userPatterns.filter { it.tilesetId == null }
        val byTileset = userPatterns.filter { it.tilesetId != null }.groupBy { it.tilesetId!! }

        saveFile(creFile(), cre)
        for ((tsId, pats) in byTileset) {
            saveFile(tilesetFile(tsId), pats)
        }

        // Remove tileset files that no longer have patterns
        if (patternsDir.exists()) {
            val activeTilesetIds = byTileset.keys.map { "tileset_$it.json" }.toSet()
            patternsDir.listFiles()
                ?.filter { it.name.startsWith("tileset_") && it.extension == "json" && it.name !in activeTilesetIds }
                ?.forEach { it.delete() }
        }
    }

    private fun loadFile(file: File): List<TilePattern> {
        if (!file.exists()) return emptyList()
        return try {
            val data = json.decodeFromString<PatternList>(file.readText())
            data.patterns
        } catch (e: Exception) {
            EditorLog.error(e, "[PatternLibrary] Failed to read ${file.name}: ${e.message}")
            emptyList()
        }
    }

    private fun saveFile(file: File, patterns: List<TilePattern>) {
        try {
            patternsDir.mkdirs()
            if (patterns.isEmpty()) {
                file.delete()
                return
            }
            file.writeText(json.encodeToString(PatternList(patterns.toMutableList())))
        } catch (e: Exception) {
            EditorLog.error(e, "[PatternLibrary] Failed to write ${file.name}: ${e.message}")
        }
    }
}
