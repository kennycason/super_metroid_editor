package com.supermetroid.editor.data

import com.supermetroid.editor.util.EditorLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class PatchMeta(
    val file: String,
    val name: String,
    val description: String = "",
    val customItems: List<CustomItemDef> = emptyList(),
    val compatibleRomHashes: List<String> = listOf(VANILLA_JU_SHA256),
    val resources: List<PatchResourceClaim> = emptyList(),
)

const val VANILLA_JU_SHA256 = "12b77c4bc9c1832cee8881244659065ee1d84c70c3d29e6eaf92e6798cc2ca72"

/** Load a classpath resource with fallback to thread context classloader. */
fun loadResource(path: String): java.io.InputStream? =
    PatchRepository::class.java.classLoader.getResourceAsStream(path)
        ?: Thread.currentThread().contextClassLoader.getResourceAsStream(path)

object PatchRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val warnedLegacyAssets = mutableSetOf<String>()

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
                val ipsData = ipsStream.readBytes()
                if (!hasIpsEof(ipsData) && warnedLegacyAssets.add(meta.file)) {
                    EditorLog.warn(
                        "[PatchRepository] Bundled patch ${meta.file} has no IPS EOF marker; " +
                            "accepting this trusted legacy asset at a complete record boundary"
                    )
                }
                val writes = parseIps(ipsData, allowLegacyMissingEof = true)
                val id = "bundled_" + meta.file.removeSuffix(".ips")
                SmPatch(
                    id = id,
                    name = meta.name,
                    description = meta.description,
                    enabled = false,
                    writes = writes.toMutableList(),
                    customItems = meta.customItems.map { it.copy() }.toMutableList(),
                    compatibleRomHashes = meta.compatibleRomHashes.map { it.lowercase() }.toMutableList(),
                    resources = meta.resources.map { it.copy() }.toMutableList(),
                )
            } catch (e: Exception) {
                EditorLog.warn(e, "[PatchRepository] Failed to parse ${meta.file}: ${e.message}")
                null
            }
        }
    }

    fun parseIps(data: ByteArray): List<PatchWrite> = parseIps(data, allowLegacyMissingEof = false)

    private fun parseIps(data: ByteArray, allowLegacyMissingEof: Boolean): List<PatchWrite> {
        if (data.size < 8 || String(data, 0, 5, Charsets.US_ASCII) != "PATCH")
            throw IllegalArgumentException("Not a valid IPS file")

        val writes = mutableListOf<PatchWrite>()
        var pos = 5
        var foundEof = false
        while (true) {
            if (pos + 3 > data.size) {
                if (allowLegacyMissingEof && pos == data.size) break
                throw IllegalArgumentException("Truncated IPS file: missing EOF marker at byte $pos")
            }
            if (data[pos] == 0x45.toByte() && data[pos + 1] == 0x4F.toByte() && data[pos + 2] == 0x46.toByte()) {
                pos += 3
                foundEof = true
                break
            }
            if (pos + 5 > data.size) {
                throw IllegalArgumentException("Truncated IPS record header at byte $pos")
            }
            val offset = ((data[pos].toInt() and 0xFF) shl 16) or
                    ((data[pos + 1].toInt() and 0xFF) shl 8) or
                    (data[pos + 2].toInt() and 0xFF)
            val size = ((data[pos + 3].toInt() and 0xFF) shl 8) or (data[pos + 4].toInt() and 0xFF)
            pos += 5
            if (size == 0) {
                if (pos + 3 > data.size) {
                    throw IllegalArgumentException("Truncated IPS RLE record at byte ${pos - 5}")
                }
                val runLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                val value = data[pos + 2].toInt() and 0xFF
                pos += 3
                require(runLen > 0) { "Invalid zero-length IPS RLE record at offset 0x${offset.toString(16)}" }
                require(offset.toLong() + runLen <= IPS_ADDRESS_SPACE_SIZE) {
                    "IPS RLE record at 0x${offset.toString(16)} exceeds the 24-bit address space"
                }
                writes.add(PatchWrite(offset.toLong(), List(runLen) { value }))
            } else {
                if (pos + size > data.size) {
                    throw IllegalArgumentException("Truncated IPS payload at byte ${pos - 5}: expected $size bytes")
                }
                require(offset.toLong() + size <= IPS_ADDRESS_SPACE_SIZE) {
                    "IPS record at 0x${offset.toString(16)} exceeds the 24-bit address space"
                }
                val bytes = (0 until size).map { data[pos + it].toInt() and 0xFF }
                pos += size
                writes.add(PatchWrite(offset.toLong(), bytes))
            }
        }
        if (foundEof) {
            // IPS permits an optional three-byte truncate/expand size after EOF.
            require(pos == data.size || pos + 3 == data.size) {
                "Invalid IPS trailing data: ${data.size - pos} bytes after EOF"
            }
        }
        return writes
    }

    private fun hasIpsEof(data: ByteArray): Boolean {
        fun markerAt(index: Int): Boolean =
            index >= 0 && index + 2 < data.size &&
                data[index] == 0x45.toByte() &&
                data[index + 1] == 0x4F.toByte() &&
                data[index + 2] == 0x46.toByte()
        return markerAt(data.size - 3) || markerAt(data.size - 6)
    }

    private const val IPS_ADDRESS_SPACE_SIZE = 0x1000000L
}
