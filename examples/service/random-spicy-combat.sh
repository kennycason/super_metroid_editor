#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: examples/service/random-spicy-combat.sh [--colorize <effect>] <base-rom.smc> [output-rom.smc]

Environment:
  SMEDIT_SERVICE_URL  Service base URL. Defaults to http://localhost:8080

Example:
  ./gradlew :smedit-service:runService
  examples/service/random-spicy-combat.sh path/to/base.smc build/random-spicy-combat.smc
  examples/service/random-spicy-combat.sh --colorize psychedelic path/to/base.smc build/random-spicy-combat.smc
EOF
}

COLORIZE=""
POSITIONAL=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --colorize)
      if [[ $# -lt 2 ]]; then
        usage
        exit 2
      fi
      COLORIZE="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      POSITIONAL+=("$@")
      break
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage
      exit 2
      ;;
    *)
      POSITIONAL+=("$1")
      shift
      ;;
  esac
done

if [[ ${#POSITIONAL[@]} -lt 1 || ${#POSITIONAL[@]} -gt 2 ]]; then
  usage
  exit 2
fi

for tool in curl jq; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Missing required tool: $tool" >&2
    exit 1
  fi
done

ROM="${POSITIONAL[0]}"
OUTPUT="${POSITIONAL[1]:-random-spicy-combat.smc}"
SERVICE_URL="${SMEDIT_SERVICE_URL:-http://localhost:8080}"

if [[ ! -f "$ROM" ]]; then
  echo "ROM file not found: $ROM" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUTPUT")"

BUILD_JSON="$(jq -nc --arg colorize "$COLORIZE" '{
  schemaVersion: 1,
  patches: {
    skip_intro_and_ceres: {
      enabled: true
    },
    fanfares: {
      enabled: true,
      config: {
        item_fanfare_frames: 16
      }
    }
  }
} + (if $colorize == "" then {} else {
  colorize: {
    effect: $colorize
  }
} end)')"

RANDOMIZE_JSON="$(jq -nc '{
  preset: "spicy",
  excludeEnemies: ["metroid"],
  includeBeams: ["power", "ice", "wave", "plasma"]
}')"

headers="$(mktemp)"
body="$(mktemp)"
trap 'rm -f "$headers" "$body"' EXIT

if ! status="$(
  curl -sS \
    -X POST \
    -D "$headers" \
    -o "$body" \
    -w '%{http_code}' \
    -F rom=@"$ROM" \
    -F "build=$BUILD_JSON" \
    -F "randomize=$RANDOMIZE_JSON" \
    "$SERVICE_URL/patch"
)"; then
  echo "Failed to call SMEDIT service at $SERVICE_URL/patch" >&2
  exit 1
fi

if [[ "$status" -lt 200 || "$status" -ge 300 ]]; then
  echo "SMEDIT service returned HTTP $status" >&2
  cat "$body" >&2
  echo >&2
  exit 1
fi

mv "$body" "$OUTPUT"

seed="$(awk -F': ' 'tolower($1) == "x-smedit-randomization-seed" { gsub(/\r/, "", $2); print $2 }' "$headers")"
configs="$(awk -F': ' 'tolower($1) == "x-smedit-randomized-config-types" { gsub(/\r/, "", $2); print $2 }' "$headers")"

echo "Wrote patched ROM: $OUTPUT"
if [[ -n "$COLORIZE" ]]; then
  echo "Colorize effect: $COLORIZE"
fi
if [[ -n "$seed" ]]; then
  echo "Randomization seed: $seed"
fi
if [[ -n "$configs" ]]; then
  echo "Randomized configs: $configs"
fi
