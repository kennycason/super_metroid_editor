plugins {
    kotlin("multiplatform") version "1.9.0" apply false
    id("org.jetbrains.compose") version "1.5.11" apply false
}

tasks.wrapper {
    gradleVersion = "8.5"
}

// Tests must never read or write the developer's real ~/.smedit state.
subprojects {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        val isolatedTestHome = temporaryDir.resolve("user-home")
        systemProperty("user.home", isolatedTestHome.absolutePath)
        systemProperty("smedit.repositoryRoot", rootProject.projectDir.absolutePath)
        listOf(
            "smedit.testRom",
            "smedit.disassemblyDir",
            "smedit.requireParityFixtures",
            "smedit.testOutputDir",
            "smedit.kaizoTestRom",
        ).forEach { propertyName ->
            providers.systemProperty(propertyName).orNull?.let { value ->
                systemProperty(propertyName, value)
            }
        }
        doFirst { isolatedTestHome.mkdirs() }
    }
}

val parityPython = providers.environmentVariable("PYTHON").orElse("python3")

tasks.register<Exec>("parityBootstrap") {
    group = "verification"
    description = "Clone/fetch the pinned Super Metroid disassembly into parity/work"
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/bootstrap.py")
    onlyIf {
        System.getenv("SMEDIT_DISASSEMBLY_DIR").isNullOrBlank() &&
            providers.systemProperty("smedit.disassemblyDir").orNull.isNullOrBlank()
    }
}

tasks.register<Exec>("parityCheck") {
    group = "verification"
    description = "Strictly validate the configured vanilla ROM and pinned disassembly fixtures"
    dependsOn("parityBootstrap")
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/check_fixtures.py")
    providers.systemProperty("smedit.testRom").orNull?.let { value ->
        environment("SMEDIT_TEST_ROM", value)
    }
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityBuildReference") {
    group = "verification"
    description = "Extract private assets and rebuild the pinned disassembly byte-identically"
    dependsOn("parityBootstrap")
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/build_reference.py")
    providers.systemProperty("smedit.testRom").orNull?.let { value ->
        environment("SMEDIT_TEST_ROM", value)
    }
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
    providers.systemProperty("smedit.asar").orNull?.let { value ->
        environment("SMEDIT_ASAR", value)
    }
}

tasks.register<Exec>("paritySymbols") {
    group = "verification"
    description = "Generate the searchable source-symbol catalog under parity/reports"
    dependsOn("parityBuildReference")
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/symbol_catalog.py", "--json")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityAssets") {
    group = "verification"
    description = "Generate and validate the source-backed asset range manifest"
    dependsOn("parityBuildReference")
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/asset_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityLz5Oracle") {
    group = "verification"
    description = "Independently classify and decode every extracted LZ5 stream"
    dependsOn("parityAssets")
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/lz5_oracle.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityTilesets") {
    group = "verification"
    description = "Generate the source-derived tileset pointer and CRE ownership manifest"
    dependsOn("paritySymbols", "parityLz5Oracle")
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/tileset_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityReport") {
    group = "verification"
    description = "Run strict source/ROM parity and write JSON/Markdown reports"
    dependsOn("parityCheck", "paritySymbols", "parityAssets", "parityLz5Oracle", "parityTilesets", ":shared:parityTest")
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/report.py")
}

// ── Build snes9x libretro core from submodule ──────────────────────────────

val buildLibretroCore by tasks.registering(Exec::class) {
    group = "build"
    description = "Compile snes9x libretro core from tools/snes9x submodule"

    val coreDir = file("tools/snes9x/libretro")
    workingDir = coreDir

    val os = System.getProperty("os.name").lowercase()
    val ext = when {
        os.contains("mac") -> ".dylib"
        os.contains("win") -> ".dll"
        else -> ".so"
    }
    val outputFile = file("$coreDir/snes9x_libretro$ext")

    inputs.dir("tools/snes9x/libretro")
    inputs.dir("tools/snes9x/apu")
    inputs.dir("tools/snes9x/filter")
    outputs.file(outputFile)

    val cpuCount = Runtime.getRuntime().availableProcessors()
    commandLine("make", "-j$cpuCount")

    onlyIf { !outputFile.exists() }
}

tasks.register("cleanLibretroCore", Exec::class) {
    group = "build"
    description = "Clean snes9x libretro core build artifacts"
    workingDir = file("tools/snes9x/libretro")
    commandLine("make", "clean")
}
