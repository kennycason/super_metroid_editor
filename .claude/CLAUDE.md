# Super Metroid Editor (SMEDIT) - Claude Code Guidelines

## Project Overview
A native cross-platform Super Metroid ROM editor built with Kotlin/Compose Desktop.
Four Gradle modules: `shared` (domain/ROM parsing), `desktopApp` (Compose UI), `cli` (headless export), `smedit-service` (Ktor HTTP API).
`smedit-service-app` is a companion TypeScript/Vite web frontend (not a Gradle module).

## Build & Run
```bash
./gradlew :desktopApp:run                              # Run the editor
./gradlew :shared:jvmTest :desktopApp:jvmTest          # Run all tests
./gradlew :desktopApp:packageDistributionForCurrentOS   # Package installer
./gradlew :smedit-service:runService                   # Run the HTTP API service
```
Requires JDK 17+ and a C++ compiler for the native SPC audio library.

## Architecture
- **shared/commonMain** - Pure domain: ROM parsing, tile graphics, LZ5 compression, SPC audio, data models
- **shared/jvmMain** - JVM-specific: JNA native SPC emulator, WAV rendering
- **desktopApp** - Compose Desktop UI: EditorState manages all editor state with undo/redo
- **cli** - Command-line ROM export (rooms, graph, images)
- **smedit-service** - Ktor HTTP wrapper around the headless build engine (POST /patch, GET /metadata)
- **smedit-service-app** - TypeScript/Vite web UI for the service (in `smedit-service-app/`)

Data flow: ROM File → RomParser (immutable) → EditorState (tracked changes) → Project JSON / Patched ROM

## Code Conventions
- Kotlin official style (`kotlin.code.style=official` in gradle.properties)
- PascalCase classes, camelCase functions/properties
- Compose state via `mutableStateOf()` delegation
- One top-level class per file
- Test names use backticks: `` `test descriptive name` ``
- ROM addresses and data formats are documented inline with SNES hex references

## Key Files
- `desktopApp/.../Main.kt` - App entry point, window setup, tab navigation
- `desktopApp/.../ui/EditorState.kt` - Central editor state (undo/redo, brush, project I/O)
- `desktopApp/.../ui/MapCanvas.kt` - Interactive room editor canvas
- `shared/.../rom/RomParser.kt` - Core ROM reader (LoROM address conversion, room headers)
- `shared/.../rom/TileGraphics.kt` - 2bpp/4bpp tile decompression and rendering
- `shared/.../data/EditState.kt` - Serializable edit operations

## Testing
- JUnit 5 (Jupiter) across shared and desktopApp modules
- Tests may require a ROM file at specific paths; they skip gracefully if missing
- Always run tests before submitting: `./gradlew :shared:jvmTest :desktopApp:jvmTest`

## Documentation
All ROM format knowledge, sprite data, boss specs, and engine internals live in `docs/`.
- `docs/CONTEXT.md` - **Master index** — start here for any ROM data question
- `docs/rom/` - ROM data format, limits, engine internals, hex edits
- `docs/bosses/` - Per-boss deep dives (Phantoon, Kraid)
- `docs/graphics/` - Tile pipeline, sprite system, OAM/BG2 formats
- `docs/reference/` - SMILE editor references, rogue doors analysis
- `docs/project/` - Roadmap, SMILE parity plan
- `docs/code/` - Python ROM analysis scripts (run against test-resources/*.smc)
- Use explicit imports, not wildcard imports (e.g. `import androidx.compose.runtime.remember` not `import androidx.compose.runtime.*`)

## Important Notes
- ROM data uses SNES LoROM addressing; `snesToPc()` converts to file offsets
- The `tools/snes_spc` git submodule must be initialized for audio features
- Config/patterns persist to `~/.smedit/`
- Do not commit ROM files (.smc, .sfc) - they are copyrighted
