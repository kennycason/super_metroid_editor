plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "1.9.0"
    id("org.jetbrains.compose")
}

kotlin {
    jvm {
        jvmToolchain(17)
        withJava()
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
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

                // JNA (for libretro core loading)
                implementation("net.java.dev.jna:jna:5.14.0")

                // Jamepad (SDL2-based gamepad support)
                implementation("com.badlogicgames.jamepad:jamepad:2.30.0.0")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.junit.jupiter:junit-jupiter:5.10.0")
            }
        }
    }
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

// Ensure the libretro core is built before running or packaging
tasks.named("jvmMainClasses") {
    dependsOn(rootProject.tasks.named("buildLibretroCore"))
}

// Wire the copy into packaging tasks so the core is bundled in the app
afterEvaluate {
    tasks.matching { it.name.startsWith("prepareAppResources") }.configureEach {
        dependsOn(copyLibretroToAppResources)
    }
}

// ── Copy libretro core into app resources for packaging ───────────────
val copyLibretroToAppResources by tasks.registering(Copy::class) {
    dependsOn(rootProject.tasks.named("buildLibretroCore"))

    val os = System.getProperty("os.name").lowercase()
    val ext = when {
        os.contains("mac") -> ".dylib"
        os.contains("win") -> ".dll"
        else -> ".so"
    }
    val coreFile = rootProject.file("tools/snes9x/libretro/snes9x_libretro$ext")

    from(coreFile)
    into(project.layout.buildDirectory.dir("appResources"))
    onlyIf { coreFile.exists() }
}

compose.desktop {
    application {
        mainClass = "com.supermetroid.editor.MainKt"
        // macOS trackpad pinch-to-zoom (magnification gesture)
        jvmArgs(
            "--add-exports", "java.desktop/com.apple.eawt.event=ALL-UNNAMED",
            "--add-opens", "java.desktop/com.apple.eawt.event=ALL-UNNAMED"
        )
        nativeDistributions {
            includeAllModules = true
            // appResourcesDir.set(project.layout.buildDirectory.dir("appResources")) // TODO: requires Compose plugin update

            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName = "Super Metroid Editor"
            packageVersion = "1.0.0"
            description = "Super Metroid ROM editor — tile, PLM, enemy, and patch editing"
            copyright = "© 2025 Super Metroid Editor"

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
