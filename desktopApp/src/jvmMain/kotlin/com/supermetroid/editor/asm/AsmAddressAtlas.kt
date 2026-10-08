package com.supermetroid.editor.asm

internal enum class AsmAddressSpace { SNES, PC }

internal data class AsmAddressQuery(
    val snesAddress: Int,
    val enteredAs: AsmAddressSpace,
) {
    val pcOffset: Int get() = checkNotNull(snesLoRomToPc(snesAddress))
}

internal enum class AsmAddressAnchorKind {
    ORIGIN,
    SECTION,
    RECORDED,
    LABEL,
}

internal data class AsmAddressAnchor(
    val snesAddress: Int,
    val fileId: String,
    val lineIndex: Int,
    val kind: AsmAddressAnchorKind,
    val label: String? = null,
) {
    val pcOffset: Int? get() = snesLoRomToPc(snesAddress)
}

internal data class AsmAddressResolution(
    val query: AsmAddressQuery,
    val exactSourceAnchors: List<AsmAddressAnchor>,
    val nearestSourceAnchor: AsmAddressAnchor?,
    val containingAsset: AsmAsset?,
) {
    val sourceAnchor: AsmAddressAnchor? get() = exactSourceAnchors.firstOrNull() ?: nearestSourceAnchor
    val sourceDelta: Int?
        get() = sourceAnchor?.let { query.snesAddress - it.snesAddress }
    val assetDelta: Int?
        get() = containingAsset?.let { query.pcOffset - it.range.pcOffset }
}

internal class AsmAddressAtlas private constructor(
    anchors: List<AsmAddressAnchor>,
    private val assets: List<AsmAsset>,
) {
    val anchors: List<AsmAddressAnchor> = anchors.sortedWith(
        compareBy<AsmAddressAnchor> { it.snesAddress }
            .thenBy(::anchorKindPriority)
            .thenBy { it.fileId }
            .thenBy { it.lineIndex },
    )

    private val anchorsByAddress = this.anchors.groupBy(AsmAddressAnchor::snesAddress)
    private val anchorsByLine = this.anchors.groupBy { it.fileId to it.lineIndex }
    private val anchorsByBank = this.anchors.groupBy { (it.snesAddress ushr 16) and 0xFF }
    private val anchorsByFile = this.anchors.groupBy(AsmAddressAnchor::fileId)
        .mapValues { (_, fileAnchors) ->
            fileAnchors.sortedWith(
                compareBy<AsmAddressAnchor> { it.lineIndex }
                    .thenBy(::anchorKindPriority),
            )
        }

    fun exactAt(fileId: String, lineIndex: Int): List<AsmAddressAnchor> =
        anchorsByLine[fileId to lineIndex].orEmpty()

    fun contextAt(fileId: String, lineIndex: Int): AsmAddressAnchor? {
        exactAt(fileId, lineIndex).firstOrNull()?.let { return it }
        return anchorsByFile[fileId]
            .orEmpty()
            .asSequence()
            .filter { it.lineIndex <= lineIndex }
            .maxWithOrNull(
                compareBy<AsmAddressAnchor> { it.lineIndex }
                    .thenBy { -anchorKindPriority(it) },
            )
    }

    fun resolve(query: AsmAddressQuery): AsmAddressResolution {
        val bank = (query.snesAddress ushr 16) and 0xFF
        val exact = anchorsByAddress[query.snesAddress].orEmpty()
        val nearest = anchorsByBank[bank]
            .orEmpty()
            .asSequence()
            .filter { it.snesAddress <= query.snesAddress }
            .maxWithOrNull(
                compareBy<AsmAddressAnchor> { it.snesAddress }
                    .thenBy { -anchorKindPriority(it) },
            )
        val asset = assets.firstOrNull { query.pcOffset in it.range.pcOffset until it.range.endExclusive }
        return AsmAddressResolution(query, exact, nearest, asset)
    }

    companion object {
        private val orgRegex = Regex("""^\s*org\s+\$([0-9A-Fa-f]{6})\b""", RegexOption.IGNORE_CASE)
        private val recordedAddressRegex = Regex(""";([0-9A-Fa-f]{6});""")
        private val bankFileRegex = Regex("""bank_([0-9A-Fa-f]{2})\.asm$""", RegexOption.IGNORE_CASE)

        fun build(files: List<AsmSourceFile>, labels: List<AsmLabel>, assets: List<AsmAsset>): AsmAddressAtlas {
            val labelsByLine = labels.groupBy { it.fileId to it.lineIndex }
            val filesById = files.associateBy(AsmSourceFile::id)
            val collected = mutableListOf<AsmAddressAnchor>()

            files.forEach { source ->
                var currentBank = bankFileRegex.find(source.id.substringAfterLast('/'))
                    ?.groupValues?.get(1)
                    ?.toInt(16)
                val sectionsByLine = source.sections.groupBy(AsmSection::lineIndex)

                source.lines.forEachIndexed { lineIndex, line ->
                    val orgAddress = orgRegex.find(line)?.groupValues?.get(1)?.toInt(16)
                    if (orgAddress != null) {
                        currentBank = (orgAddress ushr 16) and 0xFF
                        collected += anchor(orgAddress, source.id, lineIndex, AsmAddressAnchorKind.ORIGIN, labelsByLine)
                    }

                    recordedAddressRegex.find(line)?.groupValues?.get(1)?.toInt(16)?.let { address ->
                        currentBank = (address ushr 16) and 0xFF
                        collected += anchor(address, source.id, lineIndex, AsmAddressAnchorKind.RECORDED, labelsByLine)
                    }

                    sectionsByLine[lineIndex].orEmpty().forEach { section ->
                        parseSectionAddress(section.address, currentBank)?.let { address ->
                            currentBank = (address ushr 16) and 0xFF
                            collected += anchor(address, source.id, lineIndex, AsmAddressAnchorKind.SECTION, labelsByLine)
                        }
                    }
                }
            }

            val directAnchors = collected
                .distinctBy { listOf(it.snesAddress, it.fileId, it.lineIndex, it.kind, it.label) }
                .toMutableList()
            val directByFile = directAnchors.groupBy(AsmAddressAnchor::fileId)
            val directlyNamedLines = directAnchors.mapNotNullTo(mutableSetOf()) { anchor ->
                anchor.label?.let { Triple(anchor.fileId, anchor.lineIndex, it) }
            }
            labels.forEach { label ->
                if (Triple(label.fileId, label.lineIndex, label.name) in directlyNamedLines) return@forEach
                val source = filesById[label.fileId] ?: return@forEach
                val fileAnchors = directByFile[label.fileId].orEmpty().sortedBy(AsmAddressAnchor::lineIndex)
                val previous = fileAnchors.lastOrNull { it.lineIndex < label.lineIndex && it.kind in setOf(AsmAddressAnchorKind.ORIGIN, AsmAddressAnchorKind.SECTION) }
                    ?.takeIf { onlyCommentsBetween(source.lines, it.lineIndex + 1, label.lineIndex) }
                val next = fileAnchors.firstOrNull { it.lineIndex > label.lineIndex }
                    ?.takeIf { onlyCommentsBetween(source.lines, label.lineIndex + 1, it.lineIndex) }
                val address = previous?.snesAddress ?: next?.snesAddress ?: return@forEach
                directAnchors += AsmAddressAnchor(address, label.fileId, label.lineIndex, AsmAddressAnchorKind.LABEL, label.name)
            }

            return AsmAddressAtlas(
                anchors = directAnchors
                    .sortedBy(::anchorKindPriority)
                    .distinctBy { Triple(it.snesAddress, it.fileId, it.lineIndex) },
                assets = assets,
            )
        }

        private fun anchor(
            address: Int,
            fileId: String,
            lineIndex: Int,
            kind: AsmAddressAnchorKind,
            labelsByLine: Map<Pair<String, Int>, List<AsmLabel>>,
        ): AsmAddressAnchor = AsmAddressAnchor(
            snesAddress = address,
            fileId = fileId,
            lineIndex = lineIndex,
            kind = kind,
            label = labelsByLine[fileId to lineIndex]?.firstOrNull()?.name,
        )

        private fun parseSectionAddress(address: String?, bank: Int?): Int? {
            val digits = address?.removePrefix("\$") ?: return null
            return when (digits.length) {
                4 -> bank?.let { (it shl 16) or digits.toInt(16) }
                6 -> digits.toInt(16)
                else -> null
            }
        }

        private fun onlyCommentsBetween(lines: List<String>, start: Int, endExclusive: Int): Boolean =
            (start until endExclusive).all { lineIndex ->
                val trimmed = lines.getOrNull(lineIndex)?.trim().orEmpty()
                trimmed.isEmpty() || trimmed.startsWith(';')
            }
    }
}

private fun anchorKindPriority(anchor: AsmAddressAnchor): Int = when (anchor.kind) {
    AsmAddressAnchorKind.RECORDED -> 0
    AsmAddressAnchorKind.LABEL -> 1
    AsmAddressAnchorKind.SECTION -> 2
    AsmAddressAnchorKind.ORIGIN -> 3
}

internal fun parseAsmAddressQuery(text: String): AsmAddressQuery? {
    val compact = text.trim().replace(" ", "").uppercase()
    if (compact.isEmpty()) return null

    val explicitPc = compact.startsWith("PC:") || compact.startsWith("0X")
    val explicitSnes = compact.startsWith("SNES:") || ':' in compact
    val digits = compact
        .removePrefix("PC:")
        .removePrefix("SNES:")
        .removePrefix("0X")
        .removePrefix("\$")
        .replace(":", "")
    if (digits.length != 6 || !digits.all { it in '0'..'9' || it in 'A'..'F' }) return null

    val raw = digits.toInt(16)
    val enteredAs = when {
        explicitPc -> AsmAddressSpace.PC
        explicitSnes || compact.startsWith('$') || raw ushr 16 >= 0x80 -> AsmAddressSpace.SNES
        else -> AsmAddressSpace.PC
    }
    val snesAddress = when (enteredAs) {
        AsmAddressSpace.SNES -> raw.takeIf { snesLoRomToPc(it) != null }
        AsmAddressSpace.PC -> pcToSnesLoRom(raw)
    } ?: return null
    return AsmAddressQuery(snesAddress, enteredAs)
}

internal fun snesLoRomToPc(snesAddress: Int): Int? {
    if (snesAddress !in 0..0xFFFFFF) return null
    val bank = (snesAddress ushr 16) and 0xFF
    val offset = snesAddress and 0xFFFF
    if (offset < 0x8000 || bank == 0x7E || bank == 0x7F) return null
    val normalizedBank = bank and 0x7F
    return normalizedBank * 0x8000 + (offset - 0x8000)
}

internal fun pcToSnesLoRom(pcOffset: Int): Int? {
    if (pcOffset !in 0 until 0x400000) return null
    val bank = 0x80 + pcOffset / 0x8000
    val offset = 0x8000 + pcOffset % 0x8000
    return (bank shl 16) or offset
}

internal fun formatSnesAddress(snesAddress: Int): String =
    "\$%02X:%04X".format((snesAddress ushr 16) and 0xFF, snesAddress and 0xFFFF)

internal fun formatPcOffset(pcOffset: Int): String = "0x%06X".format(pcOffset)
