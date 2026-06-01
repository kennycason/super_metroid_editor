plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "1.9.0"
}

// ─── Native libspc build ────────────────────────────────────────────────
// Compiles blargg's snes_spc (tools/snes_spc submodule) into a shared
// library and places it in the JNA resource path for the current platform.
//
// macOS: builds a universal (fat) binary covering arm64 + x86_64 so that
// the packaged .app works on both Apple Silicon and Intel Macs.  The same
// fat dylib is written to both darwin-aarch64/ and darwin-x86-64/ because
// JNA selects the resource directory based on the JVM's reported arch.

fun detectJnaPlatformDir(): String {
    val osName = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val os = when {
        osName.contains("mac") || osName.contains("darwin") -> "darwin"
        osName.contains("win") -> "win32"
        osName.contains("linux") -> "linux"
        else -> error("Unsupported OS: $osName")
    }
    val cpu = when {
        arch == "aarch64" || arch == "arm64" -> "aarch64"
        arch == "amd64" || arch == "x86_64" -> "x86-64"
        else -> error("Unsupported arch: $arch")
    }
    return "$os-$cpu"
}

fun detectLibName(): String {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.contains("mac") || osName.contains("darwin") -> "libspc.dylib"
        osName.contains("win") -> "spc.dll"
        else -> "libspc.so"
    }
}

val spcSourceDir = rootProject.file("tools/snes_spc")
val jnaPlatform = detectJnaPlatformDir()
val libName = detectLibName()
val nativeOutputDir = file("src/jvmMain/resources/$jnaPlatform")

val buildNativeSpc = tasks.register("buildNativeSpc") {
    description = "Compile libspc shared library for the current platform"
    group = "build"

    inputs.dir(spcSourceDir.resolve("snes_spc"))
    inputs.file(spcSourceDir.resolve("Makefile"))
    // On macOS we output to both arch dirs (fat binary)
    val osNameCfg = System.getProperty("os.name").lowercase()
    if (osNameCfg.contains("mac") || osNameCfg.contains("darwin")) {
        outputs.file(file("src/jvmMain/resources/darwin-aarch64/$libName"))
        outputs.file(file("src/jvmMain/resources/darwin-x86-64/$libName"))
    } else {
        outputs.file(nativeOutputDir.resolve(libName))
    }

    doLast {
        if (!spcSourceDir.resolve("Makefile").exists()) {
            logger.warn("snes_spc submodule not checked out — skipping native build")
            return@doLast
        }

        val osName = System.getProperty("os.name").lowercase()
        val isMac = osName.contains("mac") || osName.contains("darwin")
        val dynlibExt = when {
            isMac -> ".dylib"
            osName.contains("win") -> ".dll"
            else -> ".so"
        }

        exec {
            workingDir = spcSourceDir
            commandLine("make", "clean")
            isIgnoreExitValue = true
        }

        exec {
            workingDir = spcSourceDir
            val args = mutableListOf("make", "DYNLIB_EXT=$dynlibExt")
            if (isMac) {
                // Inject -arch flags by overriding CXX directly.  Make expands
                // $(CXX) by simple text substitution so this injects the flags
                // into both compile and link steps without any Makefile changes
                // (the submodule Makefile is unmodified in CI).
                args.add("CXX=c++ -arch arm64 -arch x86_64")
            }
            if (osName.contains("win")) {
                args.add("DYNLIB_PREFIX=")
            }
            commandLine(args)
        }

        val builtLib = spcSourceDir.resolve("${if (osName.contains("win")) "" else "lib"}spc$dynlibExt")

        if (isMac) {
            // Copy the fat binary into both JNA platform resource dirs so
            // JNA finds it regardless of whether the JVM reports aarch64 or x86-64.
            val arm64Dir = file("src/jvmMain/resources/darwin-aarch64")
            val x64Dir   = file("src/jvmMain/resources/darwin-x86-64")
            arm64Dir.mkdirs()
            x64Dir.mkdirs()
            builtLib.copyTo(arm64Dir.resolve(libName), overwrite = true)
            builtLib.copyTo(x64Dir.resolve(libName), overwrite = true)
            logger.lifecycle("Built universal $libName -> darwin-aarch64/ and darwin-x86-64/")
        } else {
            nativeOutputDir.mkdirs()
            builtLib.copyTo(nativeOutputDir.resolve(libName), overwrite = true)
            logger.lifecycle("Built $libName -> $nativeOutputDir/$libName")
        }
    }
}

kotlin {
    jvm {
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
            }
        }
        
        val jvmMain by getting {
            dependencies {
                implementation("net.java.dev.jna:jna:5.14.0")
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

tasks.named("jvmProcessResources") {
    dependsOn(buildNativeSpc)
}

tasks.register<JavaExec>("verifyReplay") {
    description = "Verify a .smreplay bundle replays deterministically"
    group = "verification"
    dependsOn("jvmMainClasses", rootProject.tasks.named("buildLibretroCore"))
    mainClass.set("com.supermetroid.editor.emulator.ReplayVerifyKt")
    val jvmTarget = kotlin.targets.getByName("jvm")
    val mainCompilation = jvmTarget.compilations.getByName("main")
    classpath = mainCompilation.output.allOutputs + mainCompilation.runtimeDependencyFiles!!
    workingDir = rootProject.projectDir
    listOf("SMEDIT_VERIFY_REPLAY", "SMEDIT_ROM_PATH", "SMEDIT_LIBRETRO_CORE").forEach { key ->
        System.getenv(key)?.let { environment(key, it) }
    }
}
