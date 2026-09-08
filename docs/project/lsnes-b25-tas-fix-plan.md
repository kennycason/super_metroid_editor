# Plan — Finish lsnes rr2-beta25 TAS in SMEDIT

## Current direction

lsnes rr2-beta25 is an **optional Linux extra**, not a bundled emulator. It is
not shipped in GitHub releases and is not built by default
`./gradlew :desktopApp:run` or `packageDistributionForCurrentOS`. The worker is
GPL, heavy to compile, and not needed for ROM editing.

Tracked as **PR #31** on `feature/lsnes-b25-tas`. Users who want native
TASVideos `.lsmv` playback install the extra themselves:

```bash
git submodule update --init --recursive tools/lsnes
./gradlew buildLsnesWorker installLsnesWorker
```

Install location: `~/.smedit/lsnes/smedit-lsnes-worker`. Filename must be
`smedit-lsnes-worker`. Stock `lsnes` / `lsnes-bsnes.exe` is rejected.
Windows/macOS extra install is unsupported in this PR.

See [lsnes-extra.md](lsnes-extra.md) for the user-facing extra-install guide.
The sections below are the historical bring-up plan; packaging/CI/git bullets
that still said “bundle the worker” are updated in place.

---

Codex session `01a06a2c-c48a-72f1-a8c3-19984da7032b` (2026-09-04) started `feature/lsnes-b25-tas`, wrote files, then hit the usage limit mid-turn. The last `apply_patch` on `EmulatorWorkspaceState.kt` **did land**. Nothing is committed. There is no PR. Tests were not run. The Euler snapshot in the original paste is a different project and is unrelated.

User request that this branch still has to satisfy:

- TAS runs inside SMEDIT, no popups, Windows/macOS/Linux
- Lua scripting
- **Import and play TASVideos `.lsmv` movies natively** (same boot path as publication)
- Test fixture: Sniq 100% tape from `sm_ceres`

Do not treat BizHawk `sniq_100p.bk2` as the authoring movie. Do not convert LSMV buttons into `EmulatorInput` and feed them through `step()`. That is the snes9x/harness desync class `sm_ceres` already documented.

---

## Verdict

Codex got the skeleton on disk and then stopped. The Kotlin adapter, Lua mailbox, and a custom headless C++ entry point exist as uncommitted work. They are not wired through settings UI, they do not build or discover a worker under the names they claim, they have no tests, and they have not been proven against a TASVideos LSMV.

Safe next action: fix the worker name/build/discovery mismatch, vendor the Sniq LSMV fixture, then write tests that boot that LSMV the TASVideos way. Only after that, finish UI, the optional extra-install path (not bundling the worker), and PR #31.

---

## What Codex actually wrote

Local branch: `feature/lsnes-b25-tas` (still pointing at `origin/main`, HEAD `aff26f5`). Dirty tree, no lsnes commit.

| Path | Status | Role |
|------|--------|------|
| `tools/lsnes` | new submodule at `lsnes-rr2-beta25` (`96e46fff`) | pinned upstream source |
| `tools/lsnes-smedit/` | untracked | headless worker (`lsnes-smedit.cpp`, Makefile, `options.build`, gcc shim) |
| `desktopApp/.../LsnesBackend.kt` | untracked | mailbox RPC adapter |
| `desktopApp/.../LsnesDiscovery.kt` | untracked | worker path search |
| `desktopApp/.../DesktopEmulatorBackend.kt` | untracked | `FrameProvidingBackend` / `StateDirectoryBackend` / `AudioControllableBackend` |
| `desktopApp/.../resources/lsnes/lsnes_worker.lua` | untracked | Lua mailbox + joyset |
| `EmulatorRegistry.kt` | modified | registers `"lsnes-b25"` |
| `EmulatorModels.kt` | modified | `moviePath`, `luaScriptPaths`, `applyButtons`, capability flags |
| `AppConfig.kt` | modified | `lsnesPath` / `lsnesMoviePath` / `lsnesLuaScriptPath` + env overrides |
| `EmulatorWorkspaceState.kt` | modified | last Codex patch; movie/lua forwarded into `SessionConfig` |
| `LibretroBackend.kt` / `RetroArchBackend.kt` | modified | implement the new desktop capability interfaces |

`sm_ceres` inspection (Codex subagent, still valid):

- Reuse mailbox + Lua callback model, not the wx `lsnes-bsnes.exe` binary.
- Stock wx lsnes always opens windows. Mac is `#error` only in `tools/lsnes/src/platform/wxwidgets/main.cpp`.
- Publication boot is: `--rom-a=<rom> --lua=<script> <movie.lsmv>` with the movie present **before** core construction. Loading a blank ROM then `load-movie` desyncs.

Codex already launches with that argv shape when `moviePath` is set. Keep that. Prove it with the Sniq LSMV.

---

## Hard requirement: TASVideos LSMV, not a button dump

Publication: [TASVideos #4010M](https://tasvideos.org/4010M) — Sniq Super Metroid 100%, authored on **lsnes rr2-β23 / bsnes v085 Compatibility**. File in `sm_ceres`:

```
/home/v/01_projects/11_games/sm_ceres/super_metroid/tas/ref/sniq_100_4010M.lsmv
SHA256 1bd065d89b70c16efb6f9276e82b1c07fd57ec40095f030cc6efe6663458929c
size   98K (zip; uncompressed input table ~4.3MB)
frames 222788
```

`sm_ceres` already proved this tape on the authoring core:

| Marker | Frame | Notes |
|--------|-------|-------|
| Ceres elevator `0xDF45` | 8319 | energy 99 |
| first control `gs=8` | 8538 | pose 0 at (128, 0) |
| Ceres Ridley `0xE0B5` | 9979 | energy 99→24 |
| Landing Site `0x91F8` | 15198 | Zebes, energy 99 |

Correct SMEDIT launch (must stay this shape):

```text
smedit-lsnes-worker \
  --rom-a=/path/to/SuperMetroid.smc \
  --lua=/tmp/.../lsnes_worker.lua \
  /path/to/sniq_100_4010M.lsmv
```

Env: `LSNES_WORKER_DIR` for the mailbox. Host input must be `applyButtons=false` for the whole movie (workspace already does this via `isTasMoviePlayback`).

Do **not**:

- Import `sniq_100p.bk2` (BizHawk converter copy; not the lsnes movie)
- Parse LSMV `input` into SNES-12 and poke it through `step()`
- Boot ROM first, then `load-movie` / `load-readonly` from Lua
- Use `--rom=` for this movie (`sm_ceres` hit a β23/β25 single-file type-check bug; `--rom-a=` + positional LSMV is the working path)

Live (no-movie) sessions are a second mode. `construct_rom_nofile()` ignores `--rom-a=` unless `--rom-type=` is also set, so the current worker command will fail with “No ROM was specified” when `moviePath` is empty. Fix that separately with `--rom=<file>` or `--rom-type=<snes-type> --rom-a=<file>`. TASVideos playback is the acceptance test; live mode is not a substitute.

---

## Defects to fix (in order)

### 1. Worker identity is inconsistent — launch is dead on arrival

Three different names/paths, none of which match:

| Producer | Name / path |
|----------|-------------|
| Makefile `EXE` | `build/lsnes-smedit/lsnes-smedit` |
| `LsnesDiscovery` | `tools/lsnes-smedit/bin/smedit-lsnes-worker` (`.exe` on Windows) |
| `LsnesBackend.connect()` | refuses any file whose name is not `smedit-lsnes-worker[.exe]` |
| Error text | `./gradlew buildLsnesWorker` — **no such Gradle task exists** |

Even a successful `make` in `tools/lsnes-smedit` produces a binary the Kotlin side will not start.

**Fix:** pick one name (`smedit-lsnes-worker` / `.exe`) and one output dir (`tools/lsnes-smedit/bin/`). Point the Makefile `EXE` there. Add `:desktopApp` Gradle `Exec` task `buildLsnesWorker` that runs that Makefile. Keep the filename guard (stock wx `lsnes` must never be launched).

### 2. Vendor the Sniq 100% LSMV as the test fixture

Copy, do not submodule, the 98K movie:

```bash
mkdir -p desktopApp/src/jvmTest/resources/lsnes
cp /home/v/01_projects/11_games/sm_ceres/super_metroid/tas/ref/sniq_100_4010M.lsmv \
   desktopApp/src/jvmTest/resources/lsnes/sniq_100_4010M.lsmv
```

Assert SHA256 `1bd065d8…` in the test. `.gitignore` already ignores `*.smc`/`*.sfc` but not `.lsmv`, so the movie can be committed. The ROM stays local (`test-resources/Super Metroid (JU) [!].smc`); tests `assumeTrue` it exists, same as `TestRomHelper`.

Expected ROM hashes from `sm_ceres` (skip if mismatch, do not “fix up” the movie):

- SHA1 `DA957F0D63D14CB441D215462904C4FA8519C613`
- SHA256 `12b77c4bc9c1832cee8881244659065ee1d84c70c3d29e6eaf92e6798cc2ca72`

Optional companion (not the movie): `sm_ceres/super_metroid/tas/bodies/sniq_100_ceres_lsnes_hops.json` as expected room/frame markers.

### 3. Tests — prove LSMV import and playback

Add under `desktopApp/src/jvmTest/kotlin/.../emulator/`:

**Always-on (no worker, no ROM):**

- `LsnesDiscoveryTest` — candidate paths, Windows vs Unix names, reject `lsnes` / `lsnes-bsnes.exe`
- `LsnesBackendMailboxTest` — fake `Process` that speaks the inbox/outbox protocol; cover `hello`, `step` with `applyButtons=false`, `load_script`, `quit`, stale-id, worker-exit-during-wait
- Registry lists `"lsnes-b25"`
- Fixture SHA256 of `sniq_100_4010M.lsmv`

**Worker + ROM present (`assumeTrue`, skip otherwise):**

Class `LsnesSniq100LsmvTest`. This is the acceptance test.

1. `connect()` finds `smedit-lsnes-worker`
2. `startSession(SessionConfig(romPath=…, moviePath=…sniq_100_4010M.lsmv))`
3. Worker argv is `--rom-a=…` plus the `.lsmv` as a **positional** argument (assert via a recording `processStarter` or the worker log)
4. `applyButtons=false` on every `step`
5. Fast-forward with `includeFrame=false` to known frames; assert WRAM:
   - frame 8319 → room `0xDF45`
   - frame 15198 → room `0x91F8` (Landing). Cap timeout generously; this is ~15k frames, not the full 222788
6. Confirm a frame PNG appears in the mailbox / `FrameHolder` at least once (headless screenshot path)
7. Extra Lua: drop a tiny script via `luaScriptPaths` and assert it ran (`load_script`) without replacing the worker callbacks (`callback.register`, not `on_frame =`)

Do not run the full 222788-frame movie in CI.

### 4. Settings UI is missing

`SettingsPanel` backend chips will show `lsnes-b25` because they iterate `EmulatorRegistry.availableBackends()`, but the `when` has only `"retroarch"` and `"libretro"`. There are no fields for worker path, LSMV path, or Lua path, so a user cannot point at a TASVideos movie from the UI.

Add an `"lsnes-b25"` branch mirroring RetroArch:

- Worker path (`updateLsnesPath`) with “Browse…” and a note that stock lsnes is rejected
- Movie path (`updateLsnesMoviePath`) — file picker filtered to `*.lsmv`
- Optional extra Lua (`updateLsnesLuaScriptPath`)
- Persist on change (today `selectedBackendName = backend` also does not call `persistConfig()`; fix that for all backends while touching this)

Status line should say when a movie is attached (“Playing sniq_100_4010M.lsmv (readonly)”).

### 5. Headless worker build still has holes

`tools/lsnes-smedit/options.build` sets `GRAPHICS=WXWIDGETS` even though the Makefile only links `__all_common__.files` (dummy graphics in `core/dummygraphics.cpp`). That is OK **only if** `src/platform/` is never built. Guard the Makefile so `make` cannot pull wx.

Known follow-ups while bringing the worker up:

- `dummygraphics.cpp` sets `pausing_allowed = false`; `lsnes-smedit.cpp` sets it back to `true` after `platform::init()`. Keep that order or `pause-emulator` from Lua is a no-op.
- `gui.screenshot` goes through `emu_framebuffer`, not wx, so dummy graphics can still write PNGs. Verify in the Sniq test.
- gcc shim (`-include gcc-compat.hpp`) is the right place; do not patch the submodule.
- Linux is the first bring-up and the **only** extra install in this PR. Windows/macOS worker recipes are out of scope; do not invent them. Stock wx lsnes is still rejected on every OS.
- Do **not** package the worker next to the Compose app. Default run/package must not require `tools/lsnes` or `smedit-lsnes-worker`. Discovery may still look at `compose.application.resources.dir` and `tools/lsnes-smedit/bin/`, but the supported install is `./gradlew buildLsnesWorker installLsnesWorker` → `~/.smedit/lsnes/smedit-lsnes-worker` (or `SMEDIT_LSNES_PATH`).

### 6. Kotlin adapter polish after the LSMV test is green

- Session dirs under `~/.smedit/lsnes/sessions/run-*` are never deleted.
- WRAM dump is only `0x2000` bytes. Enough for the Sniq markers above; document the limit or expand if later tests need higher WRAM.
- `closeProcessBlocking` can block the UI thread via `EmulatorBackend.close()`.
- `LsnesBackend` reads `AppConfig.load().lsnesPath` itself instead of taking the path from `SessionConfig` / the workspace field. After settings UI exists, pass the path in so tests do not depend on `~/.smedit/config.json`.
- `load_script` uses Lua `dofile`. Extra user scripts must not overwrite `on_frame` / `on_idle`. Keep `callback.register` in the worker (already done) and add a regression test.

### 7. Git / PR / CI (only after tests)

Codex was asked to push a PR. The extra-plugin work is **PR #31** from
`feature/lsnes-b25-tas` against `main`. Do not treat default clone/package as
an lsnes deliverable.

1. `tools/lsnes` stays an **optional** submodule at `lsnes-rr2-beta25`. Default clone inits `tools/snes9x` and `tools/snes_spc` only. `git submodule update --init --recursive` is fine if someone already used `--recurse-submodules`, but CI/package must not require that tree.
2. Do not commit ROM files
3. Do commit `sniq_100_4010M.lsmv`
4. Run `./gradlew :shared:jvmTest :desktopApp:jvmTest` without building the worker. Gate the Sniq frame markers on `SMEDIT_LSNES_IT=1`.
5. Do not copy `smedit-lsnes-worker` into `.dmg` / `.msi` / `.deb` / `.rpm`. `installLsnesWorker` writes `~/.smedit/lsnes/smedit-lsnes-worker` only.

---

## Implementation order

1. Copy `sniq_100_4010M.lsmv` into `desktopApp/src/jvmTest/resources/lsnes/` and lock SHA256.
2. Unify worker filename/output dir; add `buildLsnesWorker`; build it on this machine.
3. Unit tests for discovery + mailbox (fake process).
4. LSMV acceptance test: native `--rom-a` + positional movie, `applyButtons=false`, Ceres elev + Landing Site frames.
5. Fix no-movie `--rom=` / `--rom-type` so Play without an LSMV still boots.
6. Settings UI for worker / `.lsmv` / Lua.
7. Document and keep the extra as Linux-only: `buildLsnesWorker` + `installLsnesWorker`, no worker in Compose distributions, no Windows/macOS extra recipes in this PR.
8. PR #31 from `feature/lsnes-b25-tas` against `main`.

Stop and reassess if step 4 cannot reach `0x91F8` at frame 15198. That means the worker is not on the publication boot path, and UI/packaging will not save it.

---

## Out of scope

- Watching or encoding the TASVideos YouTube dump
- BizHawk `sniq_100p.bk2`
- Full 222788-frame CI replay
- Using snes9x/libretro to “approximate” the LSMV
- Shipping stock wx lsnes
- Shipping `smedit-lsnes-worker` in GitHub releases or OS packages
- Windows/macOS worker install
- The unrelated Project Euler snapshot from the Codex paste
