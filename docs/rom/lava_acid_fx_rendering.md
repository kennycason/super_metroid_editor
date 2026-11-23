# Native Lava and Acid FX Rendering

This note records how Super Metroid renders lava and acid so SMEDIT can emulate
the native effect accurately in the room canvas. It also defines the canonical
meaning of the FX header's `fxBitC` byte for the GUI and semantic API.

## FX header options (`fxBitC`)

The native bank `$88` code gives these meanings to the byte at FX-header offset
`+12`:

| Mask | SMEDIT name | Native behavior |
|---|---|---|
| `0x01` | Flow Left | Reverses the liquid surface's horizontal flow direction. |
| `0x02` | BG Animate | Applies the vertical BG2 shimmer table. |
| `0x04` | BG Liquid | Applies the horizontal BG2 shimmer table. |
| `0x08`–`0x20` | Unused / unknown | No supported semantic meaning. Preserve these bits when an edit does not explicitly replace the byte. |
| `0x40` | Large Tide | Oscillates the liquid surface by approximately ±16 pixels. |
| `0x80` | Small Tide | Oscillates the liquid surface by approximately ±4 pixels. This branch wins when both tide bits are set. |

Historical SMILE-derived labels assigned these effects to the wrong masks.
SMEDIT must use the table above everywhere: room properties, previews,
documentation, validation, and AI/MCP payloads.

## BG2 shimmer is not the Layer 3 liquid graphic

Lava and acid use two related but separate visual systems:

1. Layer 3 draws the animated liquid surface/body tiles.
2. HDMA-style per-scanline offsets displace BG2 and make the room background
   look as though it is refracting through heat or liquid.

`BG Animate` and `BG Liquid` control the second system. They do not select a
different Layer 3 texture. A faithful editor preview therefore has to warp the
already-rendered BG2 image per scanline; drawing another translucent Layer 3
overlay cannot reproduce the native result.

SMEDIT's `FX Layer (L3)` display toggle controls only the first system. For
lava, acid, and water it is positioned and clipped at the effective liquid
surface. A signed/offscreen liquid height such as `$FFFF` suppresses L3 while
leaving the independently configured BG2 shimmer visible.

The vertical `BG Animate` displacement repeats:

```text
0, +1, +1, 0, 0, -1, -1, 0
```

Its phase advances every four frames. The horizontal `BG Liquid` displacement
repeats:

```text
0, 0, +1, +1, +1, +1, 0, 0, -1, -1, -1, -1, 0, 0, 0, 0
```

Its phase advances every six frames. Lava and acid share this BG2 warp engine.

## Liquid tile animation

The native liquid tile animator has five frames for each material:

- Lava holds each frame for 13 frames.
- Acid holds each frame for 10 frames.

The source graphics replace the relevant Layer 3 liquid tiles. The room canvas
already renders Layer 3 liquids; the missing fidelity is primarily the BG2
per-scanline displacement and native animation timing.

## Tide behavior and `$FFFF` heights

The tide flags modify the liquid surface position independently of the BG2
shimmer. They are not required for the heat-wave effect.

A safe, invisible-liquid heat shimmer is normally:

```text
FX type: Lava or Acid
Liquid start:  $FFFF
Liquid target: $FFFF
Liquid options: BG Animate (`0x02`)
Tides: disabled
```

Combining a real tide flag (`0x40` or `0x80`) with `$FFFF` start/target heights
can wrap the computed liquid position through the room, periodically covering
and uncovering the screen. Semantic mutation tools should reject that
combination unless a future expert-only operation makes the wrap explicit.

## SMEDIT preview implementation

The room canvas now emulates the native BG2 shimmer in this order:

1. Render BG2 independently using the existing tileset and effective room
   state.
2. For lava or acid with `BG Animate`, copy BG2 scanlines with the vertical
   displacement table and the native four-frame phase cadence.
3. For `BG Liquid`, apply the horizontal displacement table and six-frame phase
   cadence.
4. Composite the warped BG2 behind the unchanged BG1 and the existing overlays.

This runs only while Layer 2 is visible and only for FX types Lava (`0x02`) and
Acid (`0x04`). When both shimmer flags are set, `BG Animate` takes precedence,
matching the branch order in bank `$88`. Pixel lookup wraps at room boundaries
so the one-pixel displacement does not expose an empty seam.

Remaining native-fidelity work is separate from the BG2 shimmer:

- Animate lava and acid Layer 3 tiles with their native 13- and 10-frame holds.
- Derive tide position from `0x40`/`0x80`; if both are present, emulate the
  native small-tide precedence.
- Add a preview animation pause/control so screenshots can select a known
  phase.

The pixel warp and mode selection are isolated in `NativeLiquidBg2Shimmer.kt`
and covered by deterministic phase-zero tests.

## Native source references

The local disassembly used for this research is
`~/.smedit/asm/sm_disassembly`:

- `src/bank_88.asm`, approximately lines 5482–5515: lava/acid setup.
- `src/bank_88.asm`, around line 5519: tide selection and surface math.
- `src/bank_88.asm`, approximately lines 5822–6002: BG2 displacement tables
  and update handlers.
- `src/bank_87.asm`, approximately lines 555–586: lava and acid tile animation.

Line numbers are navigation hints for the current local revision; bank labels
and behavior are the durable references.
