# Optional lsnes TAS extra (Linux)

lsnes rr2-beta25 is an **optional plugin extra**. It is not part of the SMEDIT
editor binary, not a default Gradle dependency, and not a reason to fetch the
large `tools/lsnes` tree when you only want to edit rooms.

This extra is **Linux-only** in this version. There is no Windows or macOS
worker recipe here.

## Why it is an extra

- **Heavy build deps.** The worker needs Boost, Lua 5.1, libcurl, zlib, a C++
  toolchain, and related `-dev` packages. Those are unrelated to ROM editing.
- **GPL worker.** `tools/lsnes` is GPL. SMEDIT does not ship that binary in
  GitHub releases or in `.dmg` / `.msi` / `.deb` / `.rpm` packages.
- **Linux-first.** The popup-free worker is brought up on Linux only. Stock
  wxWidgets `lsnes` / `lsnes-bsnes.exe` always opens windows and is rejected.
- **Not needed for editing.** Default `./gradlew :desktopApp:run` and
  `packageDistributionForCurrentOS` use snes9x via libretro.

If you already cloned with `--recurse-submodules`, lsnes source may be on disk.
That still does not build or package the extra.

## What SMEDIT ships vs what you install

| SMEDIT ships | You install (optional) |
|--------------|------------------------|
| Editor UI, ROM parser, snes9x libretro core, SPC audio | `tools/lsnes` submodule (source) |
| Kotlin adapter that *can* talk to a worker if present | Headless `smedit-lsnes-worker` binary |
| Settings fields and env overrides for the extra | System packages to compile that worker |

The installed extra lives at:

```text
~/.smedit/lsnes/smedit-lsnes-worker
```

The filename **must** be `smedit-lsnes-worker`. Stock `lsnes` and
`lsnes-bsnes.exe` are rejected (wx popups).

## Linux dependencies

Exact package names are distro-dependent and installing them may need sudo.

The worker Makefile / `options.build` expect:

- `g++`, `make`, `pkg-config`, `python3`
- Boost (`-lboost_iostreams`, `-lboost_filesystem`)
- Lua **5.1** via pkg-config (`lua51` by default; override with `LSNES_LUA_PKG`)
- libcurl (`curl-config`)
- zlib (`-lz`)

Do **not** use Lua 5.5 (or other non-5.1 Lua) as the pkg-config Lua. The default
is `lua51`.

Likely package names (examples only; verify on your distro):

- Debian/Ubuntu: `g++`, `make`, `pkg-config`, `python3`,
  `libboost-iostreams-dev`, `libboost-filesystem-dev`, `liblua5.1-0-dev`,
  `libcurl4-openssl-dev`, `zlib1g-dev`. Debian's pkg-config id is often
  `lua5.1`, so you may need `LSNES_LUA_PKG=lua5.1`.
- Fedora: `gcc-c++`, `make`, `pkgconf`, `python3`, `boost-devel`,
  `compat-lua-devel` (Lua 5.1), `libcurl-devel`, `zlib-devel`. Fedora's default
  `lua-devel` is often newer than 5.1 — do not use that.

Building the extra may need extra packages beyond this list if your distro
splits Boost, curl, or Lua further.

## Build and install

From the repo root:

```bash
git submodule update --init --recursive tools/lsnes
./gradlew buildLsnesWorker installLsnesWorker
```

- `buildLsnesWorker` compiles `tools/lsnes-smedit/bin/smedit-lsnes-worker`.
- `installLsnesWorker` copies it to `~/.smedit/lsnes/smedit-lsnes-worker`.

Neither task runs during `:desktopApp:run` or packaging.

## Settings and environment

After install, pick the extra in **Settings → Emulator** (`lsnes-b25`):

- Worker path — Browse to `smedit-lsnes-worker` if discovery does not find it
- Optional TASVideos `.lsmv` movie
- Optional extra Lua script

Environment overrides (win over `~/.smedit/config.json`):

| Variable | Purpose |
|----------|---------|
| `SMEDIT_LSNES_PATH` | Absolute path to `smedit-lsnes-worker` |
| `SMEDIT_LSNES_MOVIE` | Absolute path to a `.lsmv` movie |
| `SMEDIT_LSNES_LUA` | Optional extra Lua script |

You can skip Browse by pointing `SMEDIT_LSNES_PATH` at the worker.

## Discovery paths

The editor looks for an executable named `smedit-lsnes-worker`, in order:

1. Settings worker path
2. `SMEDIT_LSNES_PATH`
3. `~/.smedit/lsnes/smedit-lsnes-worker` (install location)
4. `~/.smedit/extras/lsnes/smedit-lsnes-worker`
5. `tools/lsnes-smedit/bin/smedit-lsnes-worker` under the current working directory
6. `/usr/local/bin` and `/usr/bin`
7. `PATH`
8. Compose app resources dir last (only if a user copied the extra there; default packages do not ship it)

A file named `lsnes`, `lsnes-bsnes.exe`, or `lsnes-smedit` is never accepted, including when set via Settings or `SMEDIT_LSNES_PATH`.

## TASVideos playback

Native TASVideos LSMV on lsnes rr2-beta25 / bsnes v085 compatibility.

Publication boot (must stay this shape):

```text
smedit-lsnes-worker \
  --rom-a=/path/to/SuperMetroid.smc \
  --lua=/tmp/.../lsnes_worker.lua \
  /path/to/movie.lsmv
```

The movie is a **positional** argument and must be present **before** core
construction.

Do **not**:

- Convert LSMV buttons into `EmulatorInput` and feed them through `step()`
- Boot the ROM first, then `load-movie` / `load-readonly` from Lua
- Import a BizHawk `.bk2` as the authoring movie
- Point Settings at stock wx `lsnes`

Host input stays `applyButtons=false` for the whole movie. Live (no-movie)
sessions are a second mode and use `--rom=`, not `--rom-a=`.

The floating emulator cycles watch speed **1× / 2× / 4× / 8× / 16×** (button
or `=` with the viewport focused). **SKIP** seeks Sniq 100% to Ceres elevator
(frame 8319). 16× is sent to the worker, but live playback tops out around
400–450 emulated fps (~7× NTSC) because **bsnes v085 Compatibility is the
limiter**, not the speed control. 8× and 16× both hit that ceiling. A faster
core would desync TASVideos movies.

Reference publication: [TASVideos #4010M](https://tasvideos.org/4010M)
(Sniq Super Metroid 100%, authored on lsnes rr2-β23 / bsnes v085 Compatibility).

## Developer notes (acceptance markers)

These are for worker bring-up, not a user checklist.

Sniq 100% (`sniq_100_4010M.lsmv`) on the TASVideos Super Metroid dump:

| Marker | Frame | Room |
|--------|-------|------|
| Ceres elevator | 8319 | `0xDF45` |
| Landing Site | 15198 | `0x91F8` |

The gated integration test is `LsnesSniq100LsmvTest`. It does **not** run in
default `./gradlew :desktopApp:jvmTest`. Enable with `SMEDIT_LSNES_IT=1` when
the worker, the fixture `.lsmv`, and the matching ROM are present. Do not run
the full 222788-frame movie in CI.

## Non-goals

- Windows/macOS worker install or packaging
- Shipping `smedit-lsnes-worker` inside `.dmg` / `.msi` / `.deb` / `.rpm`
- Shipping or launching stock wx `lsnes` / `lsnes-bsnes.exe`
- Fetching `tools/lsnes` for a default clone or editor run
