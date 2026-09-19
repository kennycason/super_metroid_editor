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

// ── Optional TAS extra: popup-free lsnes rr2-beta25 worker ─────────────────
// Not a dependency of run / test / package. Users install the worker themselves.

val buildLsnesWorker by tasks.registering(Exec::class) {
    group = "build"
    description = "Compile optional TAS extra smedit-lsnes-worker (not part of default editor builds)"
    workingDir = file("tools/lsnes-smedit")
    val os = System.getProperty("os.name").lowercase()
    val exe = if (os.contains("win")) "smedit-lsnes-worker.exe" else "smedit-lsnes-worker"
    val outputFile = file("tools/lsnes-smedit/bin/$exe")
    inputs.dir("tools/lsnes-smedit")
    val lsnesSourceDir = file("tools/lsnes")
    if (lsnesSourceDir.isDirectory && file("tools/lsnes/Makefile").isFile) {
        inputs.dir(lsnesSourceDir)
    }
    outputs.file(outputFile)
    val cpuCount = Runtime.getRuntime().availableProcessors()
    commandLine("make", "-j$cpuCount")
    doFirst {
        val lsnesSource = file("tools/lsnes/Makefile")
        if (!lsnesSource.isFile) {
            throw GradleException(
                "lsnes extra source is missing. This is an optional TAS extra, not part of the default editor. " +
                    "Run: git submodule update --init --recursive tools/lsnes"
            )
        }
    }
}

val installLsnesWorker by tasks.registering(Copy::class) {
    group = "build"
    description = "Install optional TAS extra smedit-lsnes-worker to ~/.smedit/lsnes/ (not part of default editor builds)"
    dependsOn(buildLsnesWorker)
    val os = System.getProperty("os.name").lowercase()
    val exe = if (os.contains("win")) "smedit-lsnes-worker.exe" else "smedit-lsnes-worker"
    from(file("tools/lsnes-smedit/bin/$exe"))
    into(File(System.getProperty("user.home"), ".smedit/lsnes"))
}

tasks.register("cleanLsnesWorker", Exec::class) {
    group = "build"
    description = "Clean optional TAS extra lsnes worker build artifacts (not part of default editor builds)"
    workingDir = file("tools/lsnes-smedit")
    commandLine("make", "clean")
}
