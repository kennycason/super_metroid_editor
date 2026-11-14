package com.supermetroid.editor.asm

internal enum class AsmIntegrationCapability(val displayName: String) {
    INSPECT("Inspect"),
    ASM_EDIT("ASM edit"),
    VISUAL_EDIT("Visual edit"),
    LIVE_REFRESH("Live refresh"),
    ALIASES("Aliases"),
    RESTORE("Restore"),
    RELOCATION("Relocation"),
    COLD_BUILD("Cold build"),
}

internal enum class AsmCapabilitySupport { SUPPORTED, NOT_SUPPORTED, NOT_APPLICABLE }

internal data class AsmCapabilityDecision(
    val support: AsmCapabilitySupport,
    val detail: String,
)

internal enum class AsmConformanceScenario(val displayName: String) {
    NATIVE_PARITY("Native parity"),
    ASM_TO_VISUAL("ASM → Visual"),
    VISUAL_TO_ASM("Visual → ASM"),
    ALIASES("Aliases"),
    GROWTH("Growth / fixed-envelope refusal"),
    INVALID_DRAFT("Invalid draft"),
    CONFLICT("Newer-edit conflict"),
    RESTORE("Restore / Undo / Redo"),
    REOPEN("Save / reopen"),
    COLD_BUILD("Cold build / emitted bytes"),
}

internal enum class AsmConformanceStatus { COVERED, NOT_APPLICABLE }

internal data class AsmConformanceDecision(
    val status: AsmConformanceStatus,
    val evidence: Set<String> = emptySet(),
    val detail: String,
)

internal data class AsmIntegrationContract(
    val id: String,
    val title: String,
    val authority: String,
    val supportedShape: String,
    val unsupportedBoundary: String,
    val reciprocal: Boolean,
    val capabilities: Map<AsmIntegrationCapability, AsmCapabilityDecision>,
    val conformance: Map<AsmConformanceScenario, AsmConformanceDecision>,
    val fastProofSuites: Set<String>,
    val coldBuildProofSuites: Set<String>,
)

/** The checked inventory behind docs/asm/capability_matrix.md. A new reciprocal
 * editor is not complete until it has one row and explicit decisions for every
 * capability and conformance scenario. */
internal object AsmIntegrationContracts {
    private const val ASM_TEST = "com.supermetroid.editor.asm."
    private const val UI_TEST = "com.supermetroid.editor.ui."

    val all: List<AsmIntegrationContract> = listOf(
        reciprocal(
            id = "room-records",
            title = "Room headers, states, properties, and selectors",
            authority = "Typed declarations in bank_8F.asm",
            shape = "Supported symbolic room/state declarations and bounded predicates",
            boundary = "Arbitrary state code and untagged predicates remain ASM only",
            aliases = true,
            relocation = true,
            fast = setOf(
                "${ASM_TEST}AsmRoomPropertiesSourceTest",
                "${ASM_TEST}AsmRoomSelectorsSourceTest",
                "${ASM_TEST}AsmRoomGenerationSourceTest",
            ),
        ),
        reciprocal(
            id = "room-layouts",
            title = "Room layout, BTS, layer 2, and themes",
            authority = "Typed room layout asset plus bank_8F declarations",
            shape = "Decoded room grids with stable room/state identity",
            boundary = "Unknown compression/source declarations remain ASM only",
            aliases = true,
            relocation = true,
            fast = setOf("${ASM_TEST}AsmRoomLayoutSourceTest", "${ASM_TEST}AsmRoomThemeSourceTest"),
        ),
        reciprocal(
            id = "placed-objects",
            title = "Placed objects and PLM populations",
            authority = "Typed bank_8F PLM records and managed object macros",
            shape = "Six-byte entries, state aliases, and supported relocatable macro envelopes",
            boundary = "Custom handwritten population shapes remain ASM only",
            aliases = true,
            relocation = true,
            fast = setOf("${ASM_TEST}AsmPlmSourceBridgeTest", "${ASM_TEST}AsmRoomObjectSourceTest"),
        ),
        reciprocal(
            id = "doors-arrivals-saves",
            title = "Doors, arrivals, entrance FX, and save spawns",
            authority = "Symbolic DoorDef/arrival/save declarations",
            shape = "Stable door identities with supported arrival adapters and ordinary save rows",
            boundary = "Indexed/special loads and arbitrary arrival code remain ASM only",
            aliases = true,
            relocation = true,
            fast = setOf(
                "${ASM_TEST}AsmDoorSourceTest",
                "${ASM_TEST}AsmDoorArrivalsSourceTest",
                "${ASM_TEST}AsmSaveStationSourceTest",
            ),
        ),
        reciprocal(
            id = "room-fx",
            title = "Room FX records",
            authority = "Typed bank_83 FX declarations",
            shape = "Ordinary fixed FX rows and state copy-on-write aliases",
            boundary = "Custom indexed FX programs remain ASM only",
            aliases = true,
            relocation = true,
            fast = setOf("${ASM_TEST}AsmFxSourceTest"),
        ),
        reciprocal(
            id = "room-enemies",
            title = "Enemy populations and graphics sets",
            authority = "Typed bank_A1/B4 population and graphics declarations",
            shape = "Supported population records, terminators, parameters, and shared graphics sets",
            boundary = "Custom record shapes and runtime AI code remain ASM only",
            aliases = true,
            relocation = true,
            fast = setOf("${ASM_TEST}AsmEnemySourceTest"),
        ),
        reciprocal(
            id = "room-scrolls",
            title = "Initial scroll grids and scroll commands",
            authority = "Typed bank_8F scroll declarations and command records",
            shape = "Room-sized grids and supported command argument forms",
            boundary = "Arbitrary scroll PLM code remains ASM only",
            aliases = true,
            relocation = true,
            fast = setOf("${ASM_TEST}AsmInitialScrollSourceTest", "${ASM_TEST}AsmScrollCommandSourceTest"),
        ),
        reciprocal(
            id = "minimap",
            title = "Pause-map tilemaps and reveal data",
            authority = "Exact bank_B5 map/reveal source arrays",
            shape = "Native area tilemaps and map-station reveal records",
            boundary = "Pause-map graphics tiles remain inspect-only",
            aliases = false,
            relocation = false,
            fast = setOf("${ASM_TEST}AsmMinimapSourceBridgeTest", "${UI_TEST}MinimapEditorStateTest"),
        ),
        reciprocal(
            id = "tileset-graphics",
            title = "Tileset graphics and metatiles",
            authority = "Exact incbin asset or managed source asset",
            shape = "Area/CRE graphics and metatiles with proven pointer consumers",
            boundary = "Animated-tile programs and unknown asset declarations remain ASM only",
            aliases = true,
            relocation = true,
            fast = setOf("${ASM_TEST}AsmTilesetGraphicsBridgeTest", "${UI_TEST}AsmTilesetGraphicsEditorStateTest"),
            cold = setOf("${ASM_TEST}AsmManagedAssetCompilerTest", "${ASM_TEST}AsmManagedAssetPointerTest"),
        ),
        reciprocal(
            id = "tileset-palettes",
            title = "Tileset and CRE palettes",
            authority = "Exact incbin palette asset or managed source asset",
            shape = "SNES palette rows with shared-alias identity",
            boundary = "Runtime palette effects remain ASM only",
            aliases = true,
            relocation = true,
            fast = setOf("${ASM_TEST}AsmTilesetPaletteBridgeTest", "${UI_TEST}AsmTilesetPaletteEditorStateTest"),
            cold = setOf("${ASM_TEST}AsmManagedAssetCompilerTest"),
        ),
        reciprocal(
            id = "native-sprite-graphics",
            title = "Native sprite graphics",
            authority = "Pinned incbin asset or managed compressed asset",
            shape = "Exact native pixel sheets with proven graphics consumers",
            boundary = "OAM composition, animation, and DMA programs remain read-only",
            aliases = true,
            relocation = true,
            fast = setOf("${ASM_TEST}AsmNativeSpriteAssetBridgeTest", "${UI_TEST}AsmNativeSpriteEditorStateTest"),
            cold = setOf("${ASM_TEST}AsmNativeSpriteCompilerTest"),
        ),
        reciprocal(
            id = "native-sprite-palettes",
            title = "Native sprite palettes",
            authority = "Strict contiguous literal-dw palette lens",
            shape = "Exact 16-colour native rows",
            boundary = "Expressions, macros, runtime effects, and ambiguous rows remain ASM only",
            aliases = true,
            relocation = false,
            fast = setOf("${ASM_TEST}AsmNativeSpritePaletteBridgeTest", "${UI_TEST}AsmNativeSpriteEditorStateTest"),
            cold = setOf("${ASM_TEST}AsmNativeSpriteCompilerTest"),
        ),
        reciprocal(
            id = "cinematic-maps",
            title = "Title, intro, ending, and cinematic maps",
            authority = "Frame-aware exact source map asset",
            shape = "Supported Mode 7 byte maps and SNES BG word maps",
            boundary = "Cinematic programs, regional branches, and flattened rendered pixels remain read-only",
            aliases = true,
            relocation = true,
            fast = setOf("${ASM_TEST}AsmCinematicMapAssetBridgeTest", "${UI_TEST}AsmCinematicMapEditorStateTest"),
            cold = setOf("${ASM_TEST}AsmCinematicMapCompilerTest"),
        ),
        reciprocal(
            id = "fixed-text",
            title = "Fixed tilemap text",
            authority = "Exact literal-dw source fields",
            shape = "Area labels, message rows, and item-name fields",
            boundary = "Custom expressions/macros and regional encodings remain ASM only",
            aliases = true,
            relocation = false,
            fast = setOf("${ASM_TEST}AsmTextSourceBridgeTest", "${UI_TEST}AsmTextEditorStateTest"),
            cold = setOf("${ASM_TEST}AsmTextCompilerTest"),
        ),
        reciprocal(
            id = "typewriter-text",
            title = "Ceres and Zebes typewriter streams",
            authority = "Typed text/control stream in bank_A6",
            shape = "Preserved timer, VRAM, end controls, and bank-local relocation",
            boundary = "Unknown controls and regional encodings remain ASM only",
            aliases = true,
            relocation = true,
            fast = setOf("${ASM_TEST}AsmTypewriterTextBridgeTest"),
            cold = setOf("${ASM_TEST}AsmTypewriterTextCompilerTest"),
        ),
        reciprocal(
            id = "intro-story-text",
            title = "English NTSC intro story pages",
            authority = "Strict symbolic-glyph fixed-slot source lens",
            shape = "Six pages with preserved timing, position, wait, movie, and finish controls",
            boundary = "Structural changes and Japanese/regional paths remain ASM only",
            aliases = false,
            relocation = false,
            fast = setOf("${ASM_TEST}AsmIntroStoryTextBridgeTest"),
            cold = setOf("${ASM_TEST}AsmIntroStoryTextCompilerTest"),
        ),
        reciprocal(
            id = "gameplay-tuning",
            title = "Enemy, projectile, boss, environment, and patch values",
            authority = "Strict literal source lenses in their native banks",
            shape = "Recognized fixed fields, beam/projectile tables, and shared records with stable semantic keys",
            boundary = "Computed expressions and custom record shapes remain ASM only",
            aliases = true,
            relocation = false,
            fast = setOf(
                "${ASM_TEST}AsmEnemyTuningSourceTest",
                "${ASM_TEST}AsmBeamDamageSourceTest",
                "${ASM_TEST}AsmBossStatsSourceTest",
                "${ASM_TEST}AsmEnvironmentalDamageSourceTest",
                "${ASM_TEST}AsmDataPatchSourceTest",
            ),
        ),
        asmOnly(
            id = "sound-music",
            title = "Sound and music source",
            authority = "Authored ASM/assets for build; visual tools are independent inspectors/exporters",
            shape = "Navigation and safe project-source compilation",
            boundary = "Reciprocal sound/music authoring is intentionally not supported",
            inspect = true,
            restore = true,
            build = true,
            fast = setOf("${UI_TEST}MusicBankTransitionsTest"),
        ),
        asmOnly(
            id = "custom-source",
            title = "Arbitrary handwritten ASM and project modules",
            authority = "Authored source",
            shape = "Normal source editing, diagnostics, modules, checked hooks, and compilation",
            boundary = "SMEDIT does not decompile arbitrary code into Visual Editor models or auto-relocate native routines",
            inspect = true,
            restore = true,
            build = true,
            fast = setOf(
                "${ASM_TEST}AsmProjectWorkspaceTest",
                "${ASM_TEST}AsmCheckedHookTest",
                "${ASM_TEST}AsmLibraryTest",
            ),
        ),
    )

    val fastProofSuites: Set<String> get() = all.flatMapTo(linkedSetOf()) { it.fastProofSuites }
    val coldBuildProofSuites: Set<String> get() = all.flatMapTo(linkedSetOf()) { it.coldBuildProofSuites }

    fun validationErrors(): List<String> = buildList {
        if (all.map { it.id }.distinct().size != all.size) add("Capability IDs must be unique")
        all.forEach { contract ->
            val missingCapabilities = AsmIntegrationCapability.entries - contract.capabilities.keys
            if (missingCapabilities.isNotEmpty()) add("${contract.id}: missing capabilities $missingCapabilities")
            val missingScenarios = AsmConformanceScenario.entries - contract.conformance.keys
            if (missingScenarios.isNotEmpty()) add("${contract.id}: missing conformance decisions $missingScenarios")
            if (contract.authority.isBlank() || contract.supportedShape.isBlank() || contract.unsupportedBoundary.isBlank()) {
                add("${contract.id}: authority, source shape, and unsupported boundary are required")
            }
            contract.capabilities.forEach { (capability, decision) ->
                if (decision.detail.isBlank()) add("${contract.id}: ${capability.name} needs an explanation")
            }
            contract.conformance.forEach { (scenario, decision) ->
                if (decision.detail.isBlank()) add("${contract.id}: ${scenario.name} needs an explanation")
                if (decision.status == AsmConformanceStatus.COVERED && decision.evidence.isEmpty()) {
                    add("${contract.id}: ${scenario.name} needs focused test evidence")
                }
                if (decision.evidence.any { it !in contract.fastProofSuites && it !in contract.coldBuildProofSuites }) {
                    add("${contract.id}: ${scenario.name} cites evidence outside its proof suites")
                }
            }
            if (contract.reciprocal) {
                for (capability in listOf(
                    AsmIntegrationCapability.INSPECT,
                    AsmIntegrationCapability.ASM_EDIT,
                    AsmIntegrationCapability.VISUAL_EDIT,
                    AsmIntegrationCapability.LIVE_REFRESH,
                    AsmIntegrationCapability.RESTORE,
                    AsmIntegrationCapability.COLD_BUILD,
                )) if (contract.capabilities.getValue(capability).support != AsmCapabilitySupport.SUPPORTED) {
                    add("${contract.id}: reciprocal bridge must support ${capability.name}")
                }
                AsmConformanceScenario.entries.forEach { scenario ->
                    if (contract.conformance.getValue(scenario).status != AsmConformanceStatus.COVERED) {
                        add("${contract.id}: reciprocal bridge must decide and cover ${scenario.name}")
                    }
                }
            } else if (contract.capabilities.getValue(AsmIntegrationCapability.VISUAL_EDIT).support == AsmCapabilitySupport.SUPPORTED) {
                add("${contract.id}: a non-reciprocal row cannot claim Visual editing")
            }
        }
    }

    private fun reciprocal(
        id: String,
        title: String,
        authority: String,
        shape: String,
        boundary: String,
        aliases: Boolean,
        relocation: Boolean,
        fast: Set<String>,
        cold: Set<String> = setOf("${ASM_TEST}AsmProjectCompilerTest"),
    ): AsmIntegrationContract {
        val evidence = fast + cold
        return AsmIntegrationContract(
            id, title, authority, shape, boundary, reciprocal = true,
            capabilities = AsmIntegrationCapability.entries.associateWith { capability ->
                when (capability) {
                    AsmIntegrationCapability.ALIASES -> AsmCapabilityDecision(
                        if (aliases) AsmCapabilitySupport.SUPPORTED else AsmCapabilitySupport.NOT_APPLICABLE,
                        if (aliases) "Stable source identity preserves every supported shared consumer."
                        else "This format has one native owner; the no-alias case is still tested.",
                    )
                    AsmIntegrationCapability.RELOCATION -> AsmCapabilityDecision(
                        if (relocation) AsmCapabilitySupport.SUPPORTED else AsmCapabilitySupport.NOT_APPLICABLE,
                        if (relocation) "Growth uses checked managed placement and validated consumers."
                        else "The format is intentionally fixed-envelope; growth refusal is tested.",
                    )
                    else -> AsmCapabilityDecision(AsmCapabilitySupport.SUPPORTED, when (capability) {
                        AsmIntegrationCapability.INSPECT -> "Source and ownership are visible without adoption."
                        AsmIntegrationCapability.ASM_EDIT -> "Saved supported source updates the semantic model."
                        AsmIntegrationCapability.VISUAL_EDIT -> "Visual edits rewrite the one supported source authority."
                        AsmIntegrationCapability.LIVE_REFRESH -> "Both directions refresh without reopening the project."
                        AsmIntegrationCapability.RESTORE -> "Restore and source/model history are guarded and atomic."
                        AsmIntegrationCapability.COLD_BUILD -> "Saved authored inputs reproduce validated emitted bytes."
                        else -> error("Handled above")
                    })
                }
            },
            conformance = AsmConformanceScenario.entries.associateWith { scenario ->
                AsmConformanceDecision(
                    AsmConformanceStatus.COVERED,
                    evidence,
                    when (scenario) {
                        AsmConformanceScenario.NATIVE_PARITY -> "Decoder/source lens agrees with native data."
                        AsmConformanceScenario.ASM_TO_VISUAL -> "Saved source refreshes the visual model without reopen."
                        AsmConformanceScenario.VISUAL_TO_ASM -> "Visual edits publish supported authored source."
                        AsmConformanceScenario.ALIASES -> if (aliases) "Shared consumers retain one identity." else "The single-owner invariant is proven."
                        AsmConformanceScenario.GROWTH -> if (relocation) "Growth relocates through checked placement." else "Unsupported growth fails closed without mutation."
                        AsmConformanceScenario.INVALID_DRAFT -> "Invalid/unsupported drafts remain ASM only and protect visual state."
                        AsmConformanceScenario.CONFLICT -> "Newer source/assets block stale visual writes."
                        AsmConformanceScenario.RESTORE -> "Undo/Redo and baseline restore preserve complete ownership."
                        AsmConformanceScenario.REOPEN -> "Durable authored inputs survive save and reopen."
                        AsmConformanceScenario.COLD_BUILD -> "A cache-free compiler proof checks emitted bytes/pointers."
                    },
                )
            },
            fastProofSuites = fast,
            coldBuildProofSuites = cold,
        )
    }

    private fun asmOnly(
        id: String,
        title: String,
        authority: String,
        shape: String,
        boundary: String,
        inspect: Boolean,
        restore: Boolean,
        build: Boolean,
        fast: Set<String>,
    ): AsmIntegrationContract = AsmIntegrationContract(
        id, title, authority, shape, boundary, reciprocal = false,
        capabilities = AsmIntegrationCapability.entries.associateWith { capability ->
            val supported = when (capability) {
                AsmIntegrationCapability.INSPECT -> inspect
                AsmIntegrationCapability.ASM_EDIT -> true
                AsmIntegrationCapability.RESTORE -> restore
                AsmIntegrationCapability.COLD_BUILD -> build
                else -> false
            }
            AsmCapabilityDecision(
                if (supported) AsmCapabilitySupport.SUPPORTED
                else if (capability in setOf(AsmIntegrationCapability.ALIASES, AsmIntegrationCapability.RELOCATION))
                    AsmCapabilitySupport.NOT_APPLICABLE else AsmCapabilitySupport.NOT_SUPPORTED,
                if (supported) "Supported as authored ASM." else when (capability) {
                    AsmIntegrationCapability.VISUAL_EDIT, AsmIntegrationCapability.LIVE_REFRESH -> boundary
                    AsmIntegrationCapability.ALIASES -> "No reciprocal semantic alias contract is claimed."
                    AsmIntegrationCapability.RELOCATION -> "Arbitrary authored source is never auto-relocated."
                    else -> "Not part of this integration boundary."
                },
            )
        },
        conformance = AsmConformanceScenario.entries.associateWith { scenario ->
            AsmConformanceDecision(
                AsmConformanceStatus.NOT_APPLICABLE,
                detail = "No reciprocal Visual Editor contract is claimed; ${scenario.displayName} is not a reciprocity gate.",
            )
        },
        fastProofSuites = fast,
        coldBuildProofSuites = emptySet(),
    )
}
