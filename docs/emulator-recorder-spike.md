# Emulator Recorder Spike

This spike implements a minimal room-attempt recorder at the libretro frontend
boundary. It does not use OS timers, sleep loops, background polling, or
wall-clock sampling. The emulator frame loop is the only clock.

## Frame Loop Hook

`LibretroEmulatorFrameStepper.runOneFrame(inputBits)` is the instrumentation
point:

1. Normalize the SNES controller bitmask.
2. Push that bitmask into the loaded libretro core input callback state.
3. Call `retro_run()` exactly once through `LibretroCore.run()`.
4. Copy `RETRO_MEMORY_SYSTEM_RAM`.
5. Return a `FrameRecord` for that emulated frame.

The recorder calls this once per input entry, so the attempt log has one record
per emulated frame.

## Input Injection

SNES input is represented by `SnesInputBits`, with bits in libretro joypad
button order:

`B, Y, SELECT, START, UP, DOWN, LEFT, RIGHT, A, X, L, R`.

`LibretroEmulatorFrameStepper` converts the bitmask to the existing
`LibretroCore.setInput(port = 0, buttons = ...)` list before each `retro_run()`.
Tests use a synthetic input sequence; no live keyboard state participates in
recording or replay.

## RAM Read And Hashing

After each frame, the stepper reads `RETRO_MEMORY_SYSTEM_RAM` through
`retro_get_memory_data()` / `retro_get_memory_size()` as exposed by
`LibretroCore.readWram()` and `LibretroCore.systemRamSize()`.

Each `FrameRecord.systemRamHash` is a SHA-256 hash of the copied system RAM for
that frame. `FrameRecord.frameState` also stores typed Super Metroid WRAM values
such as room id, game state, Samus position, speed, health, and a door transition
flag.

## Savestate Hashing

`AttemptLogRecorder` captures the initial state with `FrameStepper.saveState()`
before running any attempt frames. The raw savestate bytes are hashed with
SHA-256 and stored as `AttemptLog.initialStateHash`.

Replay verifies the emulator core, ROM hash, and initial savestate hash before
loading the state. It then replays the recorded input bitmask for each frame and
compares the resulting frame record, including final system RAM hash and typed
frame state.

## Test Controls

Pure JVM tests always run and do not need a ROM.

The native emulator determinism test is opt-in:

```bash
SMEDIT_TEST_ROM=/path/to/super-metroid.sfc \
SMEDIT_TEST_STATE=/path/to/start.state \
SMEDIT_LIBRETRO_CORE=/path/to/snes9x_libretro.so \
./gradlew :shared:jvmTest --tests com.supermetroid.editor.emulator.DeterministicReplayTest
```

`SMEDIT_LIBRETRO_CORE` is optional when core discovery can find a SNES libretro
core. The test skips clearly when the ROM or savestate variables are missing.

## Current Limits

- No UI has been built for recording, browsing, or replaying attempts.
- The log format is intentionally small and does not include timelines,
  heatmaps, route analytics, or overlays.
- Replay compares frame hashes and selected WRAM values, not instruction-level
  CPU or memory-write traces.
- snes9x core internals were not modified. Event-perfect traces such as exact
  CPU-cycle WRAM writes would still require deeper core or bus-layer hooks.
- Linux native execution was validated locally. macOS should work at the
  Kotlin/JNA boundary, but still depends on having a compatible libretro SNES
  core dylib available and discoverable.
