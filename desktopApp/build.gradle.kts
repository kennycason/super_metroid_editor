import java.security.MessageDigest

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "1.9.0"
    id("org.jetbrains.compose")
}

val asarVersion = "1.81"
val asarTag = "v1.81"
val asarCommit = "a8538ca8582cdc81de6941223b358aa851e3b7b1"
val asarRepositoryUrl = "https://github.com/RPGHacker/asar.git"
val hostOs = System.getProperty("os.name").lowercase()
val hostArch = System.getProperty("os.arch").lowercase()
val hostIsWindows = hostOs.contains("win")
val hostIsArm64 = hostArch == "aarch64" || hostArch == "arm64"
val appResourcePlatformDir = when {
    hostOs.contains("mac") && hostIsArm64 -> "macos-arm64"
    hostOs.contains("mac") -> "macos-x64"
    hostIsWindows && hostIsArm64 -> "windows-arm64"
    hostIsWindows -> "windows-x64"
    hostIsArm64 -> "linux-arm64"
    else -> "linux-x64"
}
val asarExecutableName = "asar-standalone" + if (hostIsWindows) ".exe" else ""
val asarToolchainRoot = layout.buildDirectory.dir("toolchains/asar-$asarVersion")
val asarSourceDirectory = asarToolchainRoot.map { it.dir("source") }
val asarBuildDirectory = asarToolchainRoot.map { it.dir("build") }
val asarRuntimeOutputDirectory = asarToolchainRoot.map { it.dir("runtime") }
val asarBuiltExecutable = asarRuntimeOutputDirectory.map { it.file(asarExecutableName) }
val platformAppResourcesDirectory = layout.buildDirectory.dir("appResources/$appResourcePlatformDir")
val packagedAsarDirectory = platformAppResourcesDirectory.map { it.dir("tools/asar/$asarVersion") }

val macGestureJvmArgs = listOf(
    "--add-exports", "java.desktop/com.apple.eawt.event=ALL-UNNAMED",
    "--add-opens", "java.desktop/com.apple.eawt.event=ALL-UNNAMED",
)

val appLoggingJvmArgs = listOf(
    "-Dorg.slf4j.simpleLogger.defaultLogLevel=info",
    "-Dorg.slf4j.simpleLogger.showThreadName=false",
    "-Dorg.slf4j.simpleLogger.showDateTime=false",
    "-Dorg.slf4j.simpleLogger.showLogName=false",
    "-Dorg.slf4j.simpleLogger.showShortLogName=false",
    "-Dorg.slf4j.simpleLogger.logFile=System.out",
)

kotlin {
    jvm {
        jvmToolchain(17)
        withJava()
        testRuns["test"].executionTask.configure {
            useJUnitPlatform { excludeTags("community-samus-rom", "asm-reference-network", "asm-toolchain") }
            systemProperty("smedit.realItFixture", System.getProperty("smedit.realItFixture", ""))
        }
    }
    
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(project(":shared"))
                
                // Material3 for Compose
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                
                // JSON parsing
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

                // Runtime logging
                implementation("io.github.oshai:kotlin-logging-jvm:5.1.0")
                implementation("org.slf4j:slf4j-simple:2.0.9")

                // JNA (for libretro core loading)
                implementation("net.java.dev.jna:jna:5.14.0")

                // Jamepad (SDL2-based gamepad support)
                implementation("com.badlogicgames.jamepad:jamepad:2.30.0.0")
            }
        }
        val jvmTest by getting {
            kotlin.srcDir(rootProject.file("parity/test-support/src/main/kotlin"))
            dependencies {
                implementation(kotlin("test"))
                implementation("org.junit.jupiter:junit-jupiter:5.10.0")
            }
        }
    }
}

val regularJvmTest = tasks.named<org.gradle.api.tasks.testing.Test>("jvmTest")

tasks.register<org.gradle.api.tasks.testing.Test>("asmReferenceIntegrationTest") {
    group = "verification"
    description = "Download the pinned ASM source and derive all assets from SMEDIT_TEST_ROM"
    dependsOn(tasks.named("jvmTestClasses"))
    testClassesDirs = regularJvmTest.get().testClassesDirs
    classpath = regularJvmTest.get().classpath
    useJUnitPlatform { includeTags("asm-reference-network") }
    systemProperty("smedit.requireParityFixtures", "true")
    outputs.upToDateWhen { false }
}

tasks.register<org.gradle.api.tasks.testing.Test>("communitySamusRomTest") {
    group = "verification"
    description = "Compare project Samus ROM export with pinned Map Randomizer IPS output"
    dependsOn(
        tasks.named("jvmTestClasses"),
        rootProject.tasks.named("communitySamusFixtures"),
    )
    testClassesDirs = regularJvmTest.get().testClassesDirs
    classpath = regularJvmTest.get().classpath
    useJUnitPlatform { includeTags("community-samus-rom") }
    systemProperty("smedit.requireParityFixtures", "true")
    systemProperty(
        "smedit.communitySamusDir",
        rootProject.file("parity/work/community/MapRandoSprites/samus_sprites").absolutePath,
    )
    systemProperty(
        "smedit.communitySamusPatchDir",
        rootProject.file("parity/work/community/MapRandomizerPatches").absolutePath,
    )
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("benchmark") {
    description = "Run emulator backend benchmark (libretro/snes9x)"
    group = "verification"
    dependsOn("jvmMainClasses")
    mainClass.set("com.supermetroid.editor.benchmark.EmulatorBenchmarkKt")
    val jvmTarget = kotlin.targets.getByName("jvm")
    val mainCompilation = jvmTarget.compilations.getByName("main")
    classpath = mainCompilation.output.allOutputs + mainCompilation.runtimeDependencyFiles!!
    workingDir = rootProject.projectDir
    // Forward benchmark env vars
    listOf("SMEDIT_ROM_PATH", "SMEDIT_LIBRETRO_CORE", "BENCH_BACKENDS", "BENCH_FRAMES", "BENCH_WARMUP_FRAMES").forEach { key ->
        System.getenv(key)?.let { environment(key, it) }
    }
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(macGestureJvmArgs + appLoggingJvmArgs)
}

// Ensure the libretro core is built before running or packaging
tasks.named("jvmMainClasses") {
    dependsOn(rootProject.tasks.named("buildLibretroCore"))
}

// Wire the copy into packaging tasks so the core is bundled in the app
afterEvaluate {
    tasks.matching { it.name.startsWith("prepareAppResources") }.configureEach {
        dependsOn(copyLibretroToAppResources, copyAsarToAppResources, copyLegalNoticesToAppResources)
    }
}

// ── Copy libretro core into app resources for packaging ───────────────
val copyLibretroToAppResources by tasks.registering(Copy::class) {
    dependsOn(rootProject.tasks.named("buildLibretroCore"))

    val ext = when {
        hostOs.contains("mac") -> ".dylib"
        hostIsWindows -> ".dll"
        else -> ".so"
    }
    val coreFile = rootProject.file("tools/snes9x/libretro/snes9x_libretro$ext")

    from(coreFile)
    into(project.layout.buildDirectory.dir("appResources/$appResourcePlatformDir"))
    onlyIf { coreFile.exists() }
}

// ── Build and bundle the pinned Asar compiler ─────────────────────────────

val provisionAsarSource by tasks.registering {
    group = "build"
    description = "Fetch the exact Asar $asarVersion source used by Project ASM"
    inputs.property("asarCommit", asarCommit)
    val marker = asarSourceDirectory.map { it.file(".smedit-commit") }
    outputs.file(marker)
    doLast {
        val source = asarSourceDirectory.get().asFile
        val current = marker.get().asFile.takeIf { it.isFile }?.readText()?.trim()
        if (current != asarCommit || !source.resolve("src/CMakeLists.txt").isFile) {
            if (source.exists()) source.deleteRecursively()
            source.parentFile.mkdirs()
            project.exec {
                commandLine(
                    "git", "clone", "--filter=blob:none", "--no-checkout", "--depth=1",
                    "--branch", asarTag, asarRepositoryUrl, source.absolutePath,
                )
            }
            project.exec {
                workingDir(source)
                commandLine("git", "checkout", "--detach", asarCommit)
            }
            marker.get().asFile.writeText("$asarCommit\n")
        }
    }
}

val configureAsarStandalone by tasks.registering(Exec::class) {
    group = "build"
    description = "Configure pinned Asar $asarVersion for the current platform"
    dependsOn(provisionAsarSource)
    inputs.property("asarCommit", asarCommit)
    inputs.file(asarSourceDirectory.map { it.file("src/CMakeLists.txt") })
    outputs.file(asarBuildDirectory.map { it.file("CMakeCache.txt") })
    doFirst {
        asarBuildDirectory.get().asFile.mkdirs()
        asarRuntimeOutputDirectory.get().asFile.mkdirs()
    }
    commandLine(
        "cmake",
        "-Wno-dev",
        "-Wno-deprecated",
        "-S", asarSourceDirectory.get().asFile.resolve("src").absolutePath,
        "-B", asarBuildDirectory.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DASAR_GEN_EXE=ON",
        "-DASAR_GEN_DLL=OFF",
        "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=${asarRuntimeOutputDirectory.get().asFile.absolutePath}",
        "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY_RELEASE=${asarRuntimeOutputDirectory.get().asFile.absolutePath}",
    )
    if (hostOs.contains("mac")) {
        args(
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=11.0",
            "-DCMAKE_CXX_FLAGS=-Wno-deprecated-declarations -Wno-unknown-warning-option",
        )
    }
}

val buildAsarStandalone by tasks.registering(Exec::class) {
    group = "build"
    description = "Build pinned Asar $asarVersion for the current platform"
    dependsOn(configureAsarStandalone)
    inputs.dir(asarSourceDirectory.map { it.dir("src") })
    outputs.file(asarBuiltExecutable)
    commandLine(
        "cmake", "--build", asarBuildDirectory.get().asFile.absolutePath,
        "--target", "asar-standalone", "--config", "Release", "--parallel",
    )
    doLast {
        val executable = asarBuiltExecutable.get().asFile
        require(executable.isFile) { "Asar build did not produce ${executable.absolutePath}" }
        if (!hostIsWindows) require(executable.setExecutable(true, false) || executable.canExecute()) {
            "Could not make bundled Asar executable: ${executable.absolutePath}"
        }
    }
}

val copyAsarToAppResources by tasks.registering(Copy::class) {
    group = "build"
    description = "Bundle pinned Asar $asarVersion and its license with this platform's app resources"
    dependsOn(buildAsarStandalone)
    from(asarBuiltExecutable)
    from(asarSourceDirectory.map { it.file("license-gpl.txt") }) {
        rename { "LICENSE-GPL-3.0.txt" }
    }
    from(rootProject.file("docs/licenses/asar.md")) {
        rename { "NOTICE.md" }
    }
    into(packagedAsarDirectory)
    doLast {
        val executable = packagedAsarDirectory.get().file(asarExecutableName).asFile
        require(executable.isFile) { "Packaged Asar is missing: ${executable.absolutePath}" }
        if (!hostIsWindows) require(executable.setExecutable(true, false) || executable.canExecute()) {
            "Could not preserve bundled Asar executable permissions: ${executable.absolutePath}"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        executable.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val sha256 = digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
        packagedAsarDirectory.get().file("SHA256").asFile.writeText("$sha256  $asarExecutableName\n")
    }
}

val copyLegalNoticesToAppResources by tasks.registering(Copy::class) {
    group = "build"
    description = "Bundle SMEDIT and third-party license notices with the desktop application"
    into(platformAppResourcesDirectory.map { it.dir("legal") })
    from(rootProject.file("LICENSE")) {
        rename { "LICENSE-APACHE-2.0.txt" }
    }
    from(rootProject.file("NOTICE"))
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    from(rootProject.file("tools/snes9x")) {
        into("snes9x")
        include("LICENSE", "**/LICENSE*", "**/license*", "**/*-license.*")
    }
    from(rootProject.file("tools/snes_spc/license.txt")) {
        into("snes_spc")
        rename { "LICENSE-LGPL-2.1.txt" }
    }
    from(rootProject.file("docs/licenses/maprandomizer-MIT.txt")) {
        into("maprandomizer")
        rename { "LICENSE-MIT.txt" }
    }
    from(rootProject.file("shared/src/jvmMain/resources/samus-community/NOTICE.md")) {
        into("spritesomething")
    }
}

tasks.register<org.gradle.api.tasks.testing.Test>("asarToolchainSmokeTest") {
    group = "verification"
    description = "Run the packaged-layout Asar compiler against a copyright-free synthetic ROM"
    dependsOn(tasks.named("jvmTestClasses"), copyAsarToAppResources)
    testClassesDirs = regularJvmTest.get().testClassesDirs
    classpath = regularJvmTest.get().classpath
    useJUnitPlatform { includeTags("asm-toolchain") }
    systemProperty("smedit.testPackagedResourcesDir", platformAppResourcesDirectory.get().asFile.absolutePath)
    outputs.upToDateWhen { false }
}

compose.desktop {
    application {
        mainClass = "com.supermetroid.editor.MainKt"
        // macOS trackpad pinch-to-zoom (magnification gesture)
        jvmArgs(*(macGestureJvmArgs + appLoggingJvmArgs).toTypedArray())
        nativeDistributions {
            includeAllModules = true
            appResourcesRootDir.set(project.layout.buildDirectory.dir("appResources"))

            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm
            )
            packageName = "Super Metroid Editor"
            packageVersion = "1.0.0"
            description = "Super Metroid ROM editor — tile, PLM, enemy, and patch editing"
            copyright = "© 2025-2026 Kenny Cason and Lucas \"Luke\" Vinze"

            macOS {
                bundleID = "com.supermetroid.editor"
                iconFile.set(project.file("src/jvmMain/resources/macos/app_icon.icns"))
            }

            windows {
                iconFile.set(project.file("src/jvmMain/resources/windows/app_icon.ico"))
                menuGroup = "Super Metroid Editor"
                upgradeUuid = "b3a7f2c1-8d4e-4a9f-b6c5-1e3d7f8a2b9c"
            }

            linux {
                packageName = "supermetroideditor"
                iconFile.set(project.file("src/jvmMain/resources/linux/app_icon.png"))
                shortcut = true
                menuGroup = "Games"
            }
        }
    }
}
