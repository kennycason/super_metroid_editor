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

tasks.register<Exec>("communitySamusFixtures") {
    group = "verification"
    description = "Provision pinned MapRandoSprites sheets and Map Randomizer IPS oracle files"
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/community_samus_bootstrap.py")
}

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

tasks.register<Exec>("parityTileFormats") {
    group = "verification"
    description = "Generate independent source-backed tile pixel and metatile semantics"
    dependsOn("parityTilesets")
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/tile_format_oracle.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityAnimatedTiles") {
    group = "verification"
    description = "Generate source-backed animated-tile objects, frames, and DMA destinations"
    dependsOn("parityAssets")
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/animated_tiles_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityItemPlmGraphics") {
    group = "verification"
    description = "Generate source-backed item PLM graphics, IDs, tables, and VRAM placement"
    dependsOn("parityAssets")
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/item_plm_graphics_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityEnemyHeaders") {
    group = "verification"
    description = "Generate source-backed enemy species headers and GRAPHADR ownership"
    dependsOn("parityAssets")
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/enemy_header_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityEnemyOam") {
    group = "verification"
    description = "Generate source-backed standard, extended, and tilemap enemy OAM structures"
    dependsOn("paritySymbols")
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/enemy_oam_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityEnemyInstructions") {
    group = "verification"
    description = "Measure named enemy instruction-list records and production preview coverage"
    dependsOn("parityEnemyOam")
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/enemy_instruction_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityEnemyVerticalSlices") {
    group = "verification"
    description = "Generate source-backed Zoomer, Sidehopper, and Space Pirate slices"
    dependsOn("parityEnemyHeaders", "parityEnemyInstructions")
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/enemy_vertical_slice_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityKraid") {
    group = "verification"
    description = "Generate Kraid's source-backed BG2, palette, and linked-OAM recipe"
    dependsOn(
        "parityTilesets",
        "parityEnemyHeaders",
        "parityEnemyInstructions",
    )
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/kraid_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityPhantoon") {
    group = "verification"
    description = "Generate Phantoon's source-backed BG2, palette, and four-part animation recipe"
    dependsOn(
        "parityTilesets",
        "parityEnemyHeaders",
        "parityEnemyInstructions",
    )
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/phantoon_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityDraygon") {
    group = "verification"
    description = "Generate Draygon's source-backed BG2, OAM, palette, and four-slot animation recipe"
    dependsOn(
        "parityTilesets",
        "parityEnemyHeaders",
        "parityEnemyInstructions",
    )
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/draygon_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityMotherBrain") {
    group = "verification"
    description = "Generate Mother Brain's source-backed phase 1/2 graphics, OAM, palette, and animation recipe"
    dependsOn(
        "parityTilesets",
        "parityEnemyHeaders",
        "parityEnemyInstructions",
    )
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/mother_brain_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityRidley") {
    group = "verification"
    description = "Generate Ridley's shared Norfair/Ceres graphics, OAM, DMA, palette, and animation recipe"
    dependsOn(
        "parityEnemyHeaders",
        "parityEnemyInstructions",
    )
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/ridley_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityCrocomire") {
    group = "verification"
    description = "Generate Crocomire's split BG2, OBJ, melting, skeleton-DMA, palette, and animation recipe"
    dependsOn(
        "parityTilesets",
        "parityEnemyHeaders",
        "parityEnemyOam",
        "parityEnemyInstructions",
    )
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/crocomire_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("paritySporeSpawn") {
    group = "verification"
    description = "Generate Spore Spawn's cross-bank body, stalk, projectile, palette, and animation recipe"
    dependsOn(
        "paritySymbols",
        "parityAssets",
        "parityEnemyHeaders",
        "parityEnemyOam",
        "parityEnemyInstructions",
    )
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/spore_spawn_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityBotwoon") {
    group = "verification"
    description = "Generate Botwoon's head, history-following body, projectile, palette, and animation recipe"
    dependsOn(
        "paritySymbols",
        "parityAssets",
        "parityEnemyHeaders",
        "parityEnemyOam",
        "parityEnemyInstructions",
    )
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/botwoon_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityTorizo") {
    group = "verification"
    description = "Generate Bomb/Golden Torizo shared OBJ, runtime overlay, projectile, palette, and animation ownership"
    dependsOn(
        "paritySymbols",
        "parityAssets",
        "parityEnemyHeaders",
        "parityEnemyOam",
        "parityEnemyInstructions",
    )
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/torizo_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityMetroid") {
    group = "verification"
    description = "Generate the normal Metroid's insides, shell, electricity, palette, and timing ownership"
    dependsOn(
        "paritySymbols",
        "parityAssets",
        "parityEnemyHeaders",
        "parityEnemyOam",
        "parityEnemyInstructions",
    )
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/metroid_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityOrdinaryEnemyAnimations") {
    group = "verification"
    description = "Generate exact helper-selected ordinary-enemy animation routes"
    dependsOn(
        "paritySymbols",
        "parityAssets",
        "parityEnemyHeaders",
        "parityEnemyOam",
        "parityEnemyInstructions",
    )
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/ordinary_enemy_animation_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("paritySamus") {
    group = "verification"
    description = "Generate Samus pose, DMA, spritemap, palette, and asset-ownership parity"
    dependsOn("paritySymbols", "parityAssets")
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/samus_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityRooms") {
    group = "verification"
    description = "Generate complete room, state, level-data, and state-resource parity"
    dependsOn("paritySymbols", "parityAssets", "parityLz5Oracle")
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/room_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityScrollRuntime") {
    group = "verification"
    description = "Generate source-backed scroll PLMs, door overrides, load order, and writer inventory"
    dependsOn("paritySymbols", "parityRooms")
    workingDir = rootProject.projectDir
    commandLine(parityPython.get(), "parity/scroll_runtime_manifest.py")
    providers.systemProperty("smedit.disassemblyDir").orNull?.let { value ->
        environment("SMEDIT_DISASSEMBLY_DIR", value)
    }
}

tasks.register<Exec>("parityReport") {
    group = "verification"
    description = "Run strict source/ROM parity and write JSON/Markdown reports"
    dependsOn(
        "parityCheck",
        "paritySymbols",
        "parityAssets",
        "parityLz5Oracle",
        "parityTilesets",
        "parityTileFormats",
        "parityAnimatedTiles",
        "parityItemPlmGraphics",
        "parityEnemyHeaders",
        "parityEnemyOam",
        "parityEnemyInstructions",
        "parityEnemyVerticalSlices",
        "parityKraid",
        "parityPhantoon",
        "parityDraygon",
        "parityMotherBrain",
        "parityRidley",
        "parityCrocomire",
        "paritySporeSpawn",
        "parityBotwoon",
        "parityTorizo",
        "parityMetroid",
        "parityOrdinaryEnemyAnimations",
        "paritySamus",
        "parityRooms",
        "parityScrollRuntime",
        ":shared:parityTest",
        ":shared:communitySamusTest",
        ":desktopApp:communitySamusRomTest",
    )
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
