package com.supermetroid.editor.data

import com.supermetroid.editor.util.EditorLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class PatchMeta(
    val file: String,
    val name: String,
    val description: String = ""
)

/** Load a classpath resource with fallback to thread context classloader. */
fun loadResource(path: String): java.io.InputStream? =
    PatchRepository::class.java.classLoader.getResourceAsStream(path)
        ?: Thread.currentThread().contextClassLoader.getResourceAsStream(path)

object PatchRepository {

    private val json = Json { ignoreUnknownKeys = true }

    fun loadBundledPatches(): List<SmPatch> {
        val metaStream = loadResource("patches/patches.json") ?: return emptyList()

        val metaList: List<PatchMeta> = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(PatchMeta.serializer()),
            metaStream.bufferedReader().readText()
        )

        return metaList.mapNotNull { meta ->
            val ipsStream = loadResource("patches/${meta.file}")
            if (ipsStream == null) {
                EditorLog.warn("[PatchRepository] Bundled patch ${meta.file} not found")
                return@mapNotNull null
            }
            try {
                val writes = parseIps(ipsStream.readBytes())
                val id = "bundled_" + meta.file.removeSuffix(".ips")
                SmPatch(
                    id = id,
                    name = meta.name,
                    description = meta.description,
                    enabled = false,
                    writes = writes.toMutableList()
                )
            } catch (e: Exception) {
                EditorLog.warn(e, "[PatchRepository] Failed to parse ${meta.file}: ${e.message}")
                null
            }
        }
    }

    fun parseIps(data: ByteArray): List<PatchWrite> {
        if (data.size < 8 || String(data, 0, 5, Charsets.US_ASCII) != "PATCH")
            throw IllegalArgumentException("Not a valid IPS file")

        val writes = mutableListOf<PatchWrite>()
        var pos = 5
        while (pos + 3 <= data.size) {
            if (data[pos] == 0x45.toByte() && data[pos + 1] == 0x4F.toByte() && data[pos + 2] == 0x46.toByte())
                break
            if (pos + 5 > data.size) break
            val offset = ((data[pos].toInt() and 0xFF) shl 16) or
                    ((data[pos + 1].toInt() and 0xFF) shl 8) or
                    (data[pos + 2].toInt() and 0xFF)
            val size = ((data[pos + 3].toInt() and 0xFF) shl 8) or (data[pos + 4].toInt() and 0xFF)
            pos += 5
            if (size == 0) {
                if (pos + 3 > data.size) break
                val runLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                val value = data[pos + 2].toInt() and 0xFF
                pos += 3
                writes.add(PatchWrite(offset.toLong(), List(runLen) { value }))
            } else {
                if (pos + size > data.size) break
                val bytes = (0 until size).map { data[pos + it].toInt() and 0xFF }
                pos += size
                writes.add(PatchWrite(offset.toLong(), bytes))
            }
        }
        return writes
    }
}
