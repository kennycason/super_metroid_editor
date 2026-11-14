package com.supermetroid.editor.asm

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AsmIntegrationContractTest {
    @Test
    fun `every integration has complete capability and conformance decisions`() {
        assertEquals(emptyList(), AsmIntegrationContracts.validationErrors())
        assertEquals(19, AsmIntegrationContracts.all.size)
        assertTrue(AsmIntegrationContracts.all.count { it.reciprocal } >= 17)
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

        suites.forEach { className ->
            val type = Class.forName(className)
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
}
