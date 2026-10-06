# Samus sprite system

This page describes the source-backed Samus graphics path used by SMEDIT. The strict
oracle is the pinned `sm_disassembly` checkout provisioned by the parity harness, not
an inferred bank dump or a hand-maintained list of frame lengths.

The complete machine-readable graph is generated at
`parity/reports/samus.json` by `./gradlew paritySamus`. Generated reports, the clean
ROM, rebuilt ROM, and extracted assets stay ignored and are never distributed.

## Proven inventory

| Unit | Exact vanilla count |
|---|---:|
| Pose IDs | 253 (`$00..FC`) |
| Unique animation definitions | 156 |
| Unique definition frames | 1,143 |
| Frame occurrences through pose aliases | 1,982 |
| Unique animation-delay streams | 127 |
| Top/bottom DMA tables | 24 (13 top, 11 bottom) |
| Seven-byte DMA entries | 435 |
| Matching extracted `samus-tiles` assets | 435 / 130,464 bytes |
| Referenced spritemaps | 422 / 1,957 OAM entries |
| Intentional null half-lookups | 273 |
| Normal suit palettes | 3 |

The previous documentation count of 418 tile assets was stale. All 435 DMA entries
now have exactly one named extracted payload with the same source address and byte
count, and all 435 payloads have exactly one DMA owner.

## Runtime graph

```text
pose ID
  +-> animation-delay pointer               $91:B010
  +-> animation-definition pointer          $92:D94E
  |     `-> frame: top table/entry + bottom table/entry (4 bytes)
  |           `-> DMA definition            $92:D91E / $92:D938
  |                 `-> source bytes in banks $9B..9F
  +-> top/bottom spritemap indices          $92:9263 / $92:945D
        `-> frame pointer in spritemap table $92:808D
              `-> count + five-byte OAM entries
```

“Pose ID” is the clearest term for the 253 outer entries. Several IDs alias the same
animation definition or delay stream, so calling all 253 independent animations is
misleading.

### Animation definitions and timing

Each pointer at `$92:D94E + pose*2` selects a four-byte-per-frame definition. The
four bytes are:

| Byte | Meaning |
|---:|---|
| 0 | Top DMA table index |
| 1 | Top DMA entry index |
| 2 | Bottom DMA table index |
| 3 | Bottom DMA entry index |

Definition length is the exact distance to the next named source definition; the
last definition ends at `$92:ED24`. It is not a terminated byte stream. The old
SMEDIT fallback that looked for a byte `>= $E0` could truncate valid definitions or
invent frames and has been removed.

Timing is separate. `$91:B010` is a 253-entry pointer table selecting one of 127
source-labelled delay streams. The parity manifest pins every stream's address,
size, and bytes. Interpreting all bank-$91 timing control semantics for a fully
faithful playback engine remains later work.

### DMA definitions and VRAM

`$92:D91E` contains 13 top-table pointers and `$92:D938` contains 11 bottom-table
pointers. Each selected entry is seven bytes:

| Bytes | Meaning |
|---:|---|
| 0..2 | 24-bit source address |
| 3..4 | First-row byte count |
| 5..6 | Second-row byte count |

The first bottom chunk targets tile `$08`, the second tile `$18`; the top chunks
target tiles `$00` and `$10`. SMEDIT starts with the default Samus VRAM population,
adds weapon tiles, applies bottom DMA, then top DMA—the same order used by the
production decoder and parity hashes.

Seven top selections intentionally index past the source label used as their named
table boundary and into the next contiguous definition. The engine performs raw
pointer arithmetic, so the manifest records these as `topCrossesNamedTable` instead
of rejecting valid vanilla behavior.

### Spritemaps

The top and bottom index tables begin at `$92:9263` and `$92:945D`. A frame lookup
selects a 16-bit pointer from the table at `$92:808D`; zero means that half has no
spritemap for that frame. A nonzero structure starts with a 16-bit entry count and
then five-byte OAM entries:

| Field | Meaning |
|---|---|
| Position word bit 15 | 16x16 when set, otherwise 8x8 |
| Position word bits 8..0 | Signed 9-bit X offset |
| Byte 2 | Signed 8-bit Y offset |
| Attribute bits 8..0 | Tile number |
| Attribute bits 11..9 | Palette |
| Attribute bits 13..12 | Priority |
| Attribute bits 14/15 | X/Y flip |

Production collects bottom entries, then top entries, and reverses the combined
list for draw order. Parity compares this exact result for all 1,982 pose-frame
occurrences, as well as the fully reconstructed VRAM hash for each occurrence.

## Palettes

The normal 16-color BGR555 suit palettes begin at:

| Suit | Address |
|---|---:|
| Power | `$9B:9400` |
| Varia | `$9B:9520` |
| Gravity | `$9B:9800` |

These correct the old `$9B:9820/$9B:9C40` values. Heat, charge, speed boost,
shinespark, hurt, death, Crystal Flash, X-Ray, file-select, and other runtime palette
programs are separate state machines and are not yet claimed complete by S-06.

## What strict parity proves

`SamusSourceParityTest` verifies the pinned totals and aggregate hashes, all source
anchors, every DMA definition and extracted payload, every production-decoded frame's
tilemap geometry and VRAM contents, all normal suit palette bytes, and that editor
pose-family IDs remain in range. Death is deliberately absent from ordinary pose
families because it uses a separate graphics path; `$E7/$E8` are not a generic death
pair.

This is strong structural coverage, not yet an independently reviewed visual atlas.
The remaining work is:

1. Interpret exact animation-delay control behavior in playback.
2. Freeze reviewed Power/Varia/Gravity pixel goldens for every valid pose.
3. Model special runtime palette programs.
4. Add representative emulator captures for dynamic states.
5. Store community sheets as project-owned sources, prove PNG round trips, and build
   safe expanded-ROM injection. Read-only validation/preview is complete.

See [Community Samus sprites](samus_community_sprites.md) for the SpriteSomething and
Map Rando interchange contract and staged SMEDIT importer plan.

## Code and evidence

- Production decoder: `shared/src/commonMain/kotlin/com/supermetroid/editor/rom/SamusSpriteDecoder.kt`
- Source manifest: `parity/samus_manifest.py`
- Strict JVM parity: `shared/src/jvmTest/kotlin/com/supermetroid/editor/rom/SamusSourceParityTest.kt`
- Pinned totals/hashes: `parity/reference.properties`
- Harness usage: `parity/README.md`
