plugins {
    kotlin("multiplatform") version "1.9.0" apply false
    id("org.jetbrains.compose") version "1.5.11" apply false
}

tasks.wrapper {
    gradleVersion = "8.5"
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

// ── Build popup-free lsnes rr2-beta25 worker ───────────────────────────────

val buildLsnesWorker by tasks.registering(Exec::class) {
    group = "build"
    description = "Compile popup-free lsnes rr2-beta25 worker (smedit-lsnes-worker)"
    workingDir = file("tools/lsnes-smedit")
    val os = System.getProperty("os.name").lowercase()
    val exe = if (os.contains("win")) "smedit-lsnes-worker.exe" else "smedit-lsnes-worker"
    val outputFile = file("tools/lsnes-smedit/bin/$exe")
    inputs.dir("tools/lsnes-smedit")
    inputs.dir("tools/lsnes")
    outputs.file(outputFile)
    val cpuCount = Runtime.getRuntime().availableProcessors()
    commandLine("make", "-j$cpuCount")
}

tasks.register("cleanLsnesWorker", Exec::class) {
    group = "build"
    description = "Clean popup-free lsnes rr2-beta25 worker build artifacts"
    workingDir = file("tools/lsnes-smedit")
    commandLine("make", "clean")
}
