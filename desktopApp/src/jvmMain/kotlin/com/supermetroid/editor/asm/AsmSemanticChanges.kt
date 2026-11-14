package com.supermetroid.editor.asm

/** A stable, user-facing description of what a raw source/data diff represents. */
internal enum class AsmSemanticChangeKind(val label: String) {
    ROOM_RESOURCE("Room resource"),
    PALETTE("Palette"),
    GRAPHICS("Graphics / metatiles"),
    MINIMAP("Map data"),
    TEXT("Text"),
    HOOK_OR_MODULE("Hook / code module"),
    ALLOCATION("Allocation"),
    SOURCE("ASM source"),
    BINARY_DATA("Binary data"),
    MULTIPLE("Multiple resources"),
}

internal enum class AsmSemanticChangeOrigin(val label: String) {
    AUTHORED_SOURCE("Authored project source"),
    AUTHORED_ASSET("Authored source asset"),
    VISUAL_EDITOR("Reciprocal Visual Editor"),
    MANAGED_RELOCATION("Managed relocation"),
    CONTAINS_MANAGED_RELOCATION("Contains managed relocation"),
    PROJECT_MODULE("Project module"),
    BASELINE_UPDATE("ASM version update"),
    MIXED("Project + ASM version"),
    GENERATED_BUILD_INPUT("Generated build input"),
}

internal data class AsmSemanticChangeSummary(
    val kind: AsmSemanticChangeKind,
    val title: String,
    val detail: String,
    val origin: AsmSemanticChangeOrigin,
    val originDetail: String,
    val editorLinks: List<AsmEditorLink> = emptyList(),
)

internal data class AsmProjectAllocationSummary(
    val label: String,
    val snesAddress: Int,
    val size: Int,
    val kind: String,
    val constraint: String? = null,
)

/** A restore request captures every value used to reject a stale dialog. */
internal sealed interface AsmProjectRestoreRequest {
    val transactionId: String
    val label: String
    val projectFilePath: String?

    data class SourceFile(
        val fileId: String,
        val expectedText: String,
        val expectedBaselineText: String? = null,
        override val projectFilePath: String? = null,
    ) : AsmProjectRestoreRequest {
        override val transactionId = "source:$fileId"
        override val label = fileId
    }

    data class ProjectModule(
        val fileId: String,
        val expectedText: String,
        val expectedModuleOrder: List<String> = emptyList(),
        val expectedDisabledModules: Set<String> = emptySet(),
        override val projectFilePath: String? = null,
    ) : AsmProjectRestoreRequest {
        override val transactionId = "module:$fileId"
        override val label = fileId
    }

    data class DirectAsset(
        val assetPath: String,
        val expectedBytes: ByteArray,
        val expectedBaselineBytes: ByteArray? = null,
        override val projectFilePath: String? = null,
    ) : AsmProjectRestoreRequest {
        override val transactionId = "asset:$assetPath"
        override val label = assetPath
    }

    data class ManagedAsset(
        val managedAssetId: String,
        val sourceFileId: String,
        val expectedBytes: ByteArray,
        val expectedSourceText: String,
        val sourceLabel: String,
        override val projectFilePath: String? = null,
    ) : AsmProjectRestoreRequest {
        override val transactionId = "managed:$managedAssetId"
        override val label = sourceLabel
    }
}

internal object AsmSemanticChanges {
    fun source(
        fileId: String,
        before: String,
        after: String,
        projectModule: Boolean,
        editorLinks: List<AsmEditorLink> = emptyList(),
        origin: AsmSemanticChangeOrigin = if (projectModule) {
            AsmSemanticChangeOrigin.PROJECT_MODULE
        } else {
            AsmSemanticChangeOrigin.AUTHORED_SOURCE
        },
    ): AsmSemanticChangeSummary {
        val changedText = changedText(before, after)
        val linkedKinds = editorLinks.map(::kindFor).filterNot { it == AsmSemanticChangeKind.SOURCE }.toSet()
        val kinds = buildSet {
            addAll(linkedKinds)
            if (linkedKinds.isEmpty()) kindFromText(changedText, projectModule)?.let(::add)
        }
        val kind = when {
            kinds.isEmpty() -> if (projectModule) AsmSemanticChangeKind.HOOK_OR_MODULE else AsmSemanticChangeKind.SOURCE
            kinds.size == 1 -> kinds.single()
            else -> AsmSemanticChangeKind.MULTIPLE
        }
        val distinctLinks = editorLinks.distinctBy(AsmEditorLink::target)
        return AsmSemanticChangeSummary(
            kind = kind,
            title = when {
                projectModule -> fileId.substringAfterLast('/').removeSuffix(".asm").replace('_', ' ')
                distinctLinks.size == 1 -> distinctLinks.single().title.removePrefix("Open ").removePrefix("Edit ")
                kind == AsmSemanticChangeKind.MULTIPLE -> "Several semantic resources"
                else -> kind.label
            },
            detail = when {
                distinctLinks.isNotEmpty() -> distinctLinks.joinToString(" · ") { it.detail }.ifBlank { kind.label }
                projectModule -> "Whole authored module; source is the durable project input"
                kind == AsmSemanticChangeKind.SOURCE -> "No narrower supported semantic resource was proven for these changed lines"
                else -> "Recognized from the changed source shape; the exact text diff remains authoritative"
            },
            origin = origin,
            originDetail = originDetail(origin),
            editorLinks = distinctLinks,
        )
    }

    fun asset(
        assetPath: String,
        editorLinks: List<AsmEditorLink> = emptyList(),
        managed: AsmManagedAssetRecord? = null,
    ): AsmSemanticChangeSummary {
        val kind = managed?.let { kindForManaged(it.kind) } ?: kindFromAssetPath(assetPath)
        val links = editorLinks.distinctBy(AsmEditorLink::target)
        return AsmSemanticChangeSummary(
            kind = kind,
            title = managed?.sourceLabel ?: assetPath.substringAfterLast('/'),
            detail = when {
                managed != null -> "Relocatable ${kind.label.lowercase()} and its checked source declaration"
                links.isNotEmpty() -> links.joinToString(" · ") { it.detail }
                kind == AsmSemanticChangeKind.BINARY_DATA -> "No narrower supported binary resource was proven"
                else -> "Direct fixed-envelope ${kind.label.lowercase()} source asset"
            },
            origin = when {
                managed != null -> AsmSemanticChangeOrigin.MANAGED_RELOCATION
                links.isNotEmpty() -> AsmSemanticChangeOrigin.VISUAL_EDITOR
                else -> AsmSemanticChangeOrigin.AUTHORED_ASSET
            },
            originDetail = if (managed != null) {
                "SMEDIT owns the manifest, authored asset and source marker as one transaction."
            } else if (links.isEmpty()) {
                "The project stores exact asset bytes, but this review has no proven Visual Editor owner or last-writer provenance."
            } else {
                "This exact incbin asset is shared by ASM and its reciprocal Visual Editor. The project does not retain which view made the last edit."
            },
            editorLinks = links,
        )
    }

    fun baseline(file: AsmBaselineFileUpdate): AsmSemanticChangeSummary {
        val origin = when {
            file.baselineChanged && file.projectEdited -> AsmSemanticChangeOrigin.MIXED
            file.baselineChanged -> AsmSemanticChangeOrigin.BASELINE_UPDATE
            else -> AsmSemanticChangeOrigin.AUTHORED_SOURCE
        }
        val combinedAfter = buildString {
            append(file.projectText)
            if (file.updatedText != file.projectText) append('\n').append(file.updatedText)
        }
        return source(
            fileId = file.fileId,
            before = file.baseText,
            after = combinedAfter,
            projectModule = file.fileId.startsWith(ASM_PROJECT_MODULE_PREFIX),
            origin = origin,
        )
    }

    private fun changedText(before: String, after: String): String = AsmThreeWayMerge.compare(before, after)
        .lines.asSequence()
        .filter { it.kind == AsmDiffLineKind.ADDED || it.kind == AsmDiffLineKind.REMOVED }
        .joinToString("\n", transform = AsmDiffLine::text)

    private fun kindFromText(text: String, projectModule: Boolean): AsmSemanticChangeKind? {
        if (projectModule) return AsmSemanticChangeKind.HOOK_OR_MODULE
        val lower = text.lowercase()
        return when {
            Regex("\\b(room|door|plm|leveldata|stateheader|roomstate|roomheader)\\b").containsMatchIn(lower) ->
                AsmSemanticChangeKind.ROOM_RESOURCE
            Regex("\\b(palette|colour|color)\\b").containsMatchIn(lower) -> AsmSemanticChangeKind.PALETTE
            Regex("\\b(typewriter|message|introtext|itemname|areaname|text)\\b").containsMatchIn(lower) ->
                AsmSemanticChangeKind.TEXT
            Regex("\\b(minimap|pausemap|maptiles)\\b").containsMatchIn(lower) -> AsmSemanticChangeKind.MINIMAP
            Regex("\\b(tiles|tilemap|metatile|graphics|gfx|sprite)\\b").containsMatchIn(lower) ->
                AsmSemanticChangeKind.GRAPHICS
            Regex("\\b(jml|jsl|warnpc|freecode|freedata|hook)\\b").containsMatchIn(lower) ->
                AsmSemanticChangeKind.HOOK_OR_MODULE
            else -> null
        }
    }

    private fun kindFor(link: AsmEditorLink): AsmSemanticChangeKind = when {
        "palette" in link.detail.lowercase() -> AsmSemanticChangeKind.PALETTE
        else -> when (val target = link.target) {
        is AsmEditorTarget.Room -> AsmSemanticChangeKind.ROOM_RESOURCE
        is AsmEditorTarget.Tileset -> if (target.palette) AsmSemanticChangeKind.PALETTE else AsmSemanticChangeKind.GRAPHICS
        is AsmEditorTarget.Sprite -> AsmSemanticChangeKind.GRAPHICS
        is AsmEditorTarget.Map -> AsmSemanticChangeKind.MINIMAP
        is AsmEditorTarget.Text -> AsmSemanticChangeKind.TEXT
        is AsmEditorTarget.CodePatch -> AsmSemanticChangeKind.HOOK_OR_MODULE
        else -> AsmSemanticChangeKind.SOURCE
        }
    }

    private fun kindForManaged(kind: String): AsmSemanticChangeKind = when (kind) {
        "tileset-palette" -> AsmSemanticChangeKind.PALETTE
        "tileset-graphics", "native-sprite-graphics", "cinematic-map" -> AsmSemanticChangeKind.GRAPHICS
        "typewriter-text" -> AsmSemanticChangeKind.TEXT
        else -> AsmSemanticChangeKind.ALLOCATION
    }

    private fun kindFromAssetPath(path: String): AsmSemanticChangeKind {
        val lower = path.lowercase()
        return when {
            "palette" in lower -> AsmSemanticChangeKind.PALETTE
            "text" in lower || "message" in lower -> AsmSemanticChangeKind.TEXT
            "minimap" in lower || "maptiles" in lower -> AsmSemanticChangeKind.MINIMAP
            "tile" in lower || "gfx" in lower || "graphic" in lower || "sprite" in lower -> AsmSemanticChangeKind.GRAPHICS
            else -> AsmSemanticChangeKind.BINARY_DATA
        }
    }

    private fun originDetail(origin: AsmSemanticChangeOrigin): String = when (origin) {
        AsmSemanticChangeOrigin.AUTHORED_SOURCE ->
            "The project stores exact source, but does not retain whether ASM typing or a reciprocal Visual Editor produced each line."
        AsmSemanticChangeOrigin.AUTHORED_ASSET ->
            "The project stores exact asset bytes, but no narrower editor origin was proven."
        AsmSemanticChangeOrigin.VISUAL_EDITOR -> "The exact resource is shared with a reciprocal Visual Editor."
        AsmSemanticChangeOrigin.MANAGED_RELOCATION -> "SMEDIT created this checked relocation transaction."
        AsmSemanticChangeOrigin.CONTAINS_MANAGED_RELOCATION ->
            "This file contains a checked managed source marker; any other authored lines still retain no per-line ASM/Visual provenance."
        AsmSemanticChangeOrigin.PROJECT_MODULE -> "This whole file is an authored project module."
        AsmSemanticChangeOrigin.BASELINE_UPDATE -> "This side of the comparison comes from the candidate ASM version."
        AsmSemanticChangeOrigin.MIXED -> "Both authored project source and the candidate ASM version affect this file."
        AsmSemanticChangeOrigin.GENERATED_BUILD_INPUT -> "This is a disposable generated build input, not authored source."
    }
}
