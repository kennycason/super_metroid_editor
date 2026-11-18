package com.supermetroid.editor.asm

import com.supermetroid.editor.ui.AsmIntegrationCoverageFilter
import com.supermetroid.editor.ui.AsmCinematicMapEditorStateTest
import com.supermetroid.editor.ui.AsmNativeSpriteEditorStateTest
import com.supermetroid.editor.ui.AsmTextEditorStateTest
import com.supermetroid.editor.ui.AsmTilesetGraphicsEditorStateTest
import com.supermetroid.editor.ui.AsmTilesetPaletteEditorStateTest
import com.supermetroid.editor.ui.MinimapEditorStateTest
import com.supermetroid.editor.ui.MusicBankTransitionsTest
import com.supermetroid.editor.ui.asmIntegrationCoverageRows
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AsmIntegrationContractTest {
    @Test
    fun `every integration has complete capability and conformance decisions`() {
        assertEquals(emptyList(), AsmIntegrationContracts.validationErrors())
        assertEquals(20, AsmIntegrationContracts.all.size)
        assertEquals(18, AsmIntegrationContracts.all.count { it.reciprocal })
    }

    @Test
    fun `every declared proof suite is compiled and callable`() {
        val suites = AsmIntegrationContracts.fastProofSuites + AsmIntegrationContracts.coldBuildProofSuites
        assertTrue(suites.isNotEmpty())
        fun hasFocusedTestEvidence(type: Class<*>): Boolean =
            type.declaredMethods.any { method ->
                method.annotations.any { annotation ->
                    annotation.annotationClass.qualifiedName == "org.junit.jupiter.api.Test"
                }
            } || type.declaredClasses.any(::hasFocusedTestEvidence)

        val typedEvidence = PROOF_TYPES.associateBy(Class<*>::getName)
        assertEquals(suites, typedEvidence.keys, "Runtime proof IDs must exactly match compiler-checked test types")
        typedEvidence.forEach { (className, type) ->
            assertTrue(hasFocusedTestEvidence(type), "$className must retain focused JUnit evidence")
        }
    }

    @Test
    fun `documented capability rows exactly match checked contract IDs`() {
        val root = File(checkNotNull(System.getProperty("smedit.repositoryRoot")))
        val documentation = File(root, "docs/asm/capability_matrix.md").readText()
        val documented = Regex("^\\| `([a-z0-9-]+)` \\|", RegexOption.MULTILINE)
            .findAll(documentation).map { it.groupValues[1] }.toSet()
        assertEquals(AsmIntegrationContracts.all.map { it.id }.toSet(), documented)
        assertTrue("./gradlew :desktopApp:asmFastTest" in documentation)
        assertTrue("./gradlew :desktopApp:asmReciprocityTest" in documentation)
    }

    @Test
    fun `where-to-work documentation and implementation files cannot drift from the registry`() {
        val root = File(checkNotNull(System.getProperty("smedit.repositoryRoot")))
        val documentation = File(root, "docs/asm/capability_matrix.md").readText()
        val where = documentation.substringAfter("## Where to work").substringBefore("## Shared reciprocity gate")
        val implementationRoot = File(root, "desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/asm")

        AsmIntegrationContracts.all.forEach { contract ->
            val row = checkNotNull(
                Regex("^\\| `${Regex.escape(contract.id)}` \\|.*$", RegexOption.MULTILINE).find(where),
            ) { "${contract.id} is missing from Where to work" }.value
            contract.location.visualEditors.forEach {
                assertTrue(it.displayName in row, "${contract.id} visual location drifted: ${it.displayName}")
            }
            contract.location.sourceFiles.forEach { assertTrue("`$it`" in row, "${contract.id} source location drifted: $it") }
            contract.location.implementationFiles.forEach { file ->
                assertTrue("`$file`" in row, "${contract.id} implementation documentation drifted: $file")
                assertTrue(File(implementationRoot, file).isFile, "${contract.id} implementation file is missing: $file")
            }
        }
    }

    @Test
    fun `every ASM bridge implementation belongs to a checked integration`() {
        val root = File(checkNotNull(System.getProperty("smedit.repositoryRoot")))
        val implementationRoot = File(root, "desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/asm")
        val bridgeDeclaration = Regex("\\binternal\\s+(?:class|object)\\s+(Asm[A-Za-z0-9]+Bridge)\\b")
        val discovered = implementationRoot.listFiles().orEmpty()
            .filter { it.extension == "kt" }
            .flatMap { file -> bridgeDeclaration.findAll(file.readText()).map { it.groupValues[1] }.toList() }
            .toSortedSet()
        val registered = AsmIntegrationContracts.all
            .flatMapTo(sortedSetOf()) { contract ->
                contract.location.implementations.map(AsmIntegrationImplementation::className)
            }

        assertEquals(
            emptySet(),
            discovered - registered,
            "Every reciprocal/navigation bridge must name its checked integration; add a registry row or explicit ownership",
        )
    }

    @Test
    fun `coverage search finds data source code and intentional boundaries`() {
        assertEquals(
            listOf("minimap"),
            asmIntegrationCoverageRows("bank_B5.asm", AsmIntegrationCoverageFilter.ALL).map { it.id },
        )
        assertTrue(
            asmIntegrationCoverageRows("AsmNativeSpritePaletteBridge", AsmIntegrationCoverageFilter.ALL)
                .any { it.id == "native-sprite-palettes" },
        )
        assertEquals(
            listOf("fast-doors"),
            asmIntegrationCoverageRows("project/fast_doors.asm", AsmIntegrationCoverageFilter.ALL).map { it.id },
        )
        assertEquals(
            setOf("sound-music", "custom-source"),
            asmIntegrationCoverageRows("", AsmIntegrationCoverageFilter.ASM_ONLY).map { it.id }.toSet(),
        )
        assertEquals(18, asmIntegrationCoverageRows("", AsmIntegrationCoverageFilter.RECIPROCAL).size)
    }

    @Test
    fun `Gradle fast and cold loops cannot drift from registry proof suites`() {
        val root = File(checkNotNull(System.getProperty("smedit.repositoryRoot")))
        val build = File(root, "desktopApp/build.gradle.kts").readText()
        fun declared(name: String): Set<String> {
            val body = checkNotNull(Regex("val $name = listOf\\((.*?)\\n\\)", RegexOption.DOT_MATCHES_ALL)
                .find(build)) { "Missing $name in desktopApp/build.gradle.kts" }.groupValues[1]
            return Regex("\"(com\\.supermetroid\\.editor\\.[^\"]+)\"")
                .findAll(body).map { it.groupValues[1] }.toSet()
        }
        assertEquals(
            AsmIntegrationContracts.fastProofSuites + AsmIntegrationContractTest::class.qualifiedName!!,
            declared("asmFastProofSuites"),
        )
        assertEquals(AsmIntegrationContracts.coldBuildProofSuites, declared("asmColdBuildProofSuites"))
    }

    @Test
    fun `intentional ASM-only rows cannot be mistaken for reciprocal editors`() {
        for (id in listOf("sound-music", "custom-source")) {
            val contract = AsmIntegrationContracts.all.single { it.id == id }
            assertTrue(!contract.reciprocal)
            assertEquals(
                AsmCapabilitySupport.NOT_SUPPORTED,
                contract.capabilities.getValue(AsmIntegrationCapability.VISUAL_EDIT).support,
            )
            assertTrue(contract.unsupportedBoundary.contains("not", ignoreCase = true) ||
                contract.unsupportedBoundary.contains("does not", ignoreCase = true))
        }
    }

    private companion object {
        val PROOF_TYPES: Set<Class<*>> = setOf(
            AsmRoomPropertiesSourceTest::class.java,
            AsmRoomSelectorsSourceTest::class.java,
            AsmRoomGenerationSourceTest::class.java,
            AsmRoomLayoutSourceTest::class.java,
            AsmRoomThemeSourceTest::class.java,
            AsmPlmSourceBridgeTest::class.java,
            AsmRoomObjectSourceTest::class.java,
            AsmDoorSourceTest::class.java,
            AsmDoorArrivalsSourceTest::class.java,
            AsmSaveStationSourceTest::class.java,
            AsmFxSourceTest::class.java,
            AsmEnemySourceTest::class.java,
            AsmInitialScrollSourceTest::class.java,
            AsmScrollCommandSourceTest::class.java,
            AsmMinimapSourceBridgeTest::class.java,
            MinimapEditorStateTest::class.java,
            AsmTilesetGraphicsBridgeTest::class.java,
            AsmTilesetGraphicsEditorStateTest::class.java,
            AsmTilesetPaletteBridgeTest::class.java,
            AsmTilesetPaletteEditorStateTest::class.java,
            AsmNativeSpriteAssetBridgeTest::class.java,
            AsmNativeSpritePaletteBridgeTest::class.java,
            AsmNativeSpriteEditorStateTest::class.java,
            AsmCinematicMapAssetBridgeTest::class.java,
            AsmCinematicMapEditorStateTest::class.java,
            AsmTextSourceBridgeTest::class.java,
            AsmTextEditorStateTest::class.java,
            AsmTypewriterTextBridgeTest::class.java,
            AsmIntroStoryTextBridgeTest::class.java,
            AsmEnemyTuningSourceTest::class.java,
            AsmBeamDamageSourceTest::class.java,
            AsmBossStatsSourceTest::class.java,
            AsmEnvironmentalDamageSourceTest::class.java,
            AsmDataPatchSourceTest::class.java,
            AsmFastDoorsModuleTest::class.java,
            MusicBankTransitionsTest::class.java,
            AsmProjectWorkspaceTest::class.java,
            AsmCheckedHookTest::class.java,
            AsmLibraryTest::class.java,
            AsmProjectCompilerTest::class.java,
            AsmManagedAssetCompilerTest::class.java,
            AsmManagedAssetPointerTest::class.java,
            AsmNativeSpriteCompilerTest::class.java,
            AsmCinematicMapCompilerTest::class.java,
            AsmTextCompilerTest::class.java,
            AsmTypewriterTextCompilerTest::class.java,
            AsmIntroStoryTextCompilerTest::class.java,
            AsmFastDoorsCompilerTest::class.java,
        )
    }
}
