#!/usr/bin/env bash
# Launch the editor with the libretro backend and auto-start a session.
#
# Usage:
#   ./tools/run_libretro.sh                              # use default ROM path
#   ./tools/run_libretro.sh /path/to/rom.sfc             # explicit ROM
#   ./tools/run_libretro.sh /path/to/rom.sfc replay.smreplay
#
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

ROM="${1:-custom_integrations/SuperMetroid-Snes/rom.sfc}"
REPLAY="${2:-}"
if [[ "$ROM" != /* ]]; then
    ROM="$ROOT/$ROM"
fi
if [[ -n "$REPLAY" && "$REPLAY" != /* ]]; then
    REPLAY="$ROOT/$REPLAY"
fi
CORE_SEARCH_DIRS=(
    ./tools/snes9x/libretro
    /usr/lib/libretro
    /usr/lib64/libretro
    /usr/local/lib/libretro
    "$HOME/.config/retroarch/cores"
    "$HOME/.var/app/org.libretro.RetroArch/config/retroarch/cores"
    ./cores
    ../cores
)

if [[ ! -f "$ROM" ]]; then
    echo "ROM not found: $ROM"
    echo "Usage: $0 [path/to/rom.sfc]"
    exit 1
fi

# Check for libretro core (|| true: head may close the pipe before find exits)
CORE=$(
    find "${CORE_SEARCH_DIRS[@]}" 2>/dev/null \
        \( -name 'snes9x_libretro.so' -o -name 'bsnes_libretro.so' -o -name 'mesen-s_libretro.so' \) \
        | head -1 || true
)

if [[ -z "${SMEDIT_LIBRETRO_CORE:-}" && -z "$CORE" ]]; then
    echo "No SNES libretro core found."
    echo "Install one:  sudo pacman -S libretro-snes9x"
    echo "Or set:       export SMEDIT_LIBRETRO_CORE=/path/to/snes9x_libretro.so"
    exit 1
fi

echo "=== Launching SMEDIT with libretro backend ==="
echo "ROM:  $ROM"
echo "Core: ${SMEDIT_LIBRETRO_CORE:-$CORE}"
echo ""

export SMEDIT_EMULATOR_BACKEND=libretro
export SMEDIT_ROM_PATH="$ROM"
export SMEDIT_OPEN_EMU=1
export SMEDIT_AUTO_START=1
if [[ -n "$REPLAY" ]]; then
    export SMEDIT_REPLAY_PATH="$REPLAY"
fi

# Preload system libstdc++ to prevent symbol conflicts between
# the libretro core's C++ iostream and Compose/Skia's bundled natives.
for STDCXX in /usr/lib/libstdc++.so.6 /usr/lib64/libstdc++.so.6 /usr/lib/x86_64-linux-gnu/libstdc++.so.6; do
    if [[ -f "$STDCXX" ]]; then
        export LD_PRELOAD="${LD_PRELOAD:+$LD_PRELOAD:}$STDCXX"
        break
    fi
done

exec ./gradlew --console=plain :desktopApp:run
