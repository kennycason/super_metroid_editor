# ASM Reference Workspace

SMEDIT's **ASM** tab is the first, deliberately read-only layer of the future
assembly workflow. It turns the exact annotated Super Metroid disassembly used
by the parity suite into an IDE-like reference inside the editor, without making
either an ASM checkout or a second ROM-selection step a prerequisite for ordinary
ROM editing.

## User workflow

1. Open a ROM normally in SMEDIT.
2. Open the **ASM** tab and choose **Download ASM Reference**.
3. SMEDIT downloads the pinned `sm_disassembly` source revision and extracts all
   1,130 NTSC binary assets from the ROM already open in memory.
4. Browse banks as chapters, expand a bank into its authored `;;; $address:
   description ;;;` sections, search labels/functions, and click a label operand
   to follow it to its definition. Only the active bank chapter is expanded by
   default, and selecting it again collapses it. Back/forward navigation
   preserves the trail.
5. Open **Library** for a practical learning path covering source/data syntax,
   registers and banks, width state, `.B`/`.W`/`.L`, addressing modes, flags and
   loops, calls and stack discipline, and common SNES runtime patterns.
6. Click any 65C816 mnemonic in Source to open its full Library page. A sized
   spelling such as `LDA.W` retains its suffix context, and each instruction page
   shows common forms, a concise example, editing cautions, and direct usages in
   the downloaded Super Metroid source that navigate back into the relevant bank.
   The Library index automatically reveals the opened instruction.
7. Click a source `incbin` path, or switch to **Assets**, to inspect the derived
   file, its PC/SNES provenance, and a read-only hex view.
8. Search a full SNES address (`$8F:805A`, `$8F805A`, `8F805A`, or
   `SNES:8F805A`) or an unheadered PC offset (`0x07805A`, `PC:07805A`, or
   `07805A`) to find exact source anchors, a containing extracted asset, and the
   nearest authored source context. Full 24-bit address operands in source are
   clickable. The source gutter displays exact addresses independently of line
   numbers.
9. Open **ROM** and choose **Build SMEDIT Result** for a non-writing snapshot of
   the real export transaction. **Loaded ROM** shows the bytes from the opened
   file, **SMEDIT Result** shows the fully validated bytes SMEDIT would export,
   and **Diff** groups only changed bytes with their write owner, mutation kind,
   label, PC/SNES range, and a link back into ASM context. Refresh the snapshot
   after making more edits. Loading a different ROM invalidates the old snapshot
   automatically.
10. When a source anchor or extracted asset has an exact semantic owner, use the
    compact **Open in SMEDIT** actions to return to the existing visual editor.
    The first reverse bridge covers room headers and layouts, tileset graphics,
    metatiles, palettes and CRE, enemy/boss and Samus graphics, and music song
    sets. Intentional aliases produce explicit destinations instead of silently
    choosing one owner.

The application-level **Back** and **Forward** buttons beside **EMU** preserve
the workspace and selected resource across these jumps. For example, opening a
room from bank `$8F`, pressing Back, and then Forward returns to the exact ASM
bank/line and the same room respectively. Ordinary tab, room, tileset, sprite,
and sound-track navigation joins the same bounded history. ASM's own compact
history remains available for fine-grained source/asset/Library browsing.

Source and hex text are selectable and copyable without giving up clickable
labels, mnemonics, or assets. The source tree, asset tree, source canvas, and hex
canvas expose draggable scrollbars for fast navigation. Mouse wheels and
two-axis trackpads scroll normally; holding the middle mouse button and dragging
pans both axes. Mouse back/forward buttons, `Alt+Left` / `Alt+Right`, and
`Cmd/Ctrl+[` / `Cmd/Ctrl+]` follow navigation history, including transitions
between source and Library pages. `Cmd/Ctrl+F` moves focus to the current
Source/Assets/Library search field.

The Library is bundled and remains available before the external disassembly is
downloaded. Its eight SMEDIT-authored lessons are intentionally a field guide,
not a general computer-science course. Their selectable, syntax-highlighted
examples progress from literal formats, strings, tables, and bit masks through
width state, pointers, comparisons, calls, and SNES runtime patterns; mnemonics
inside the examples open their instruction pages. The complete 92-mnemonic index
is validated against undisbeliever's **65816 Opcodes** reference (CC BY-SA 4.0),
the official Asar manual, and Western Design Center processor documentation.
Attribution and source links are visible both at the bottom of Library pages and
under **Settings → Credits**.

**Sync assets** re-extracts data from the currently loaded ROM without a network
request. **Redownload** transactionally refreshes the pinned source and derives
the assets again. These controls live in a compact, expandable status bar at the
bottom of the sidebar so the bank chapters remain the primary navigation. A ROM
fingerprint status makes stale assets visible after the loaded ROM changes.

## Source, ROM, and cache boundary

The immutable source contract is shared with the parity harness:

- Repository: `https://github.com/InsaneFirebat/sm_disassembly.git`
- Commit: `11c906f547edc1b57f5a5923cf977fe7b50a3694`
- NTSC asset ranges: 1,130

The managed workspace lives at `~/.smedit/asm/sm_disassembly/`. It contains the
downloaded source plus `data/` files derived from the active ROM. SMEDIT does not
copy the complete ROM into this directory, store its path in the reference, add
the reference to a `.smedit` project, or commit downloaded source/assets to this
repository. Metadata retains only the ROM display name, normalized size, and
SHA-256 fingerprint.

Installation uses a staging directory and activates it only after the archive,
entry point, asset ranges, and counts validate. Refreshing assets uses the same
staged replacement. Archive paths, expanded size, entry count, and extraction
destinations are bounded before writes occur.

No Git or Python installation is required in the desktop workflow. A checked-in,
data-only range manifest lets Kotlin perform the extraction directly.
That manifest is regenerated from the pinned upstream `tools/rip_assets.py` with:

```bash
python3 tools/generate_asm_asset_manifest.py \
  parity/work/sm_disassembly \
  desktopApp/src/jvmMain/resources/asm-reference/asset-manifest.tsv
```

The manifest records paths and ROM ranges only; it contains no extracted bytes.

## Accuracy boundary

This first workspace is an annotated **vanilla-source reference**, not a general
binary decompiler. For a normal layout-preserving hack, the data files reflect
the bytes at the loaded ROM's known vanilla ranges and are useful for comparison.
If a hack relocates a resource or adds new custom assembly, the vanilla source
cannot automatically describe that new code and a fixed source range may no
longer own the resource suggested by its filename. The browser therefore stays
read-only and identifies its pinned revision and asset ROM at all times.

The same distinction applies to editor changes that have not yet become a ROM:
**Sync assets** derives from the current in-memory parser image, but it does not
turn project deltas into new symbolic source.

The Address Atlas follows the same accuracy rule. `org` directives, authored
section headings, and recorded six-digit disassembly comments are exact anchors.
A label inherits an address only when it is directly attached to one of those
anchors. SMEDIT does not guess instruction sizes through macros or assembler
conditionals. When an address falls after an anchor but has no exact record, the
UI says **Near** and shows the byte delta; that is navigation context, not a
claim that the selected source line assembled at that address.

The editor provides semantic bridges into the Atlas rather than making users
retype addresses. Room headers, door lists, room-state records, layouts,
backgrounds, FX, PLM/object sets, enemy sets and graphics, special X-Ray data,
room ASM, and scroll data expose compact **ASM** links wherever the ROM stores a
valid address. The tile inspector also links a selected door to its `$83`
DoorDef and every item/object to its `$84` PLM header. Expanded enemy stats link
to the `$A0` species header, and the selected enemy/boss in the sprite catalog
offers the same source jump. A link may land on exact source, the extracted
asset that owns the byte, or clearly labeled nearby source context.
Project-created data without a pinned vanilla anchor remains honest about that
boundary and is not assigned a fictional vanilla address.

The tileset toolbar exposes its detected area metatiles, area graphics,
palette, CRE metatiles, and CRE graphics as one compact **ASM** menu. The sound
editor links the selected track's effective song-set table entry and transfer
data, including when a compatible ROM has relocated the table. Those links use
the same addresses the active decoders consume; they do not assume vanilla
locations when the parser has already discovered a valid relocation.

The reverse bridge applies that rule in the other direction. It builds one
semantic index for the loaded ROM from the room catalog, decoded room states,
discovered tileset catalog, enemy species headers, and discovered music
pointers. Source lines only offer an editor destination for an **exact** address
anchor; nearby context never inherits a potentially false visual-editor link.
Composite boss and Samus asset names are used only where the engine has no
single species-owned pointer for the complete visual source.

The ROM comparison has a different boundary from the extracted reference assets.
It does not infer changes from project JSON. It invokes the same transactional
export builder used by **Export ROM**, including preflight validation, patch
preconditions, write-conflict detection, generated hooks, allocation/relocation,
post-build verification, and write ownership. The resulting arrays remain in
memory; building or refreshing the preview writes neither a ROM nor the project
file. An explicit refresh keeps the snapshot model honest when the user continues
editing.

## Architecture

- `AsmReferenceRepository` owns download, archive safety, transactional cache
  activation, ROM normalization, exact range extraction, and metadata.
- `AsmSourceParser` reads `main.asm` include order and descriptions, then indexes
  every source file, authored section, global label, scoped local label, and
  extracted asset.
- `AsmAddressAtlas` is the shared bidirectional address layer. It converts
  canonical LoROM SNES/PC coordinates and resolves exact source anchors,
  contextual anchors, and extracted asset ownership without decoding assembler
  output heuristically.
- `AsmSemanticBridge` indexes exact addresses consumed by SMEDIT's production
  decoders and maps recognized source/assets back to Rooms, Tiles, Sprites, and
  Sound. The index is built once per loaded ROM so browsing assets does not
  repeatedly scan the room and species catalogs.
- `AsmWorkspaceState` owns selection, source/asset/library mode, cross-mode
  history, ROM freshness, preview state, progress, and safe navigation.
- `RomExporter.build()` is the single disk-independent export transaction.
  `RomExporter.export()` only adds the atomic output-file write around that
  validated result, so preview and export cannot drift into separate pipelines.
- `AsmRomPreview` compares canonical unheadered PC bytes, retains the write-plan
  owners and labels for each changed range, handles optional copier headers and
  expanded results, and supplies the three deliberately named read-only views.
- `AsmLibrary` owns the original guided lessons, complete mnemonic catalog,
  practical forms/examples, categories, and stable page identifiers.
- `AsmWorkspaceSidebar` and `AsmWorkspaceCanvas` provide the bank tree, search,
  syntax-colored source, navigable Library, real-source instruction examples,
  and binary inspector. Typography is taken from SMEDIT's global UI setting.
  Shared selectable, two-axis text panes keep source and hex navigation behavior
  consistent.

The install contract is covered by normal synthetic archive/range tests. The
real pinned download plus all 1,130 byte-exact extractions can be exercised with:

```bash
export SMEDIT_TEST_ROM='/path/to/clean/unheadered/Super Metroid.sfc'
./gradlew :desktopApp:asmReferenceIntegrationTest
```

## Next layers

The read-only boundary is intentional. Follow-up work can now be incremental:

1. Extend the reverse semantic bridge to remaining specialized assets and
   surfaces such as pause-map text and background programs as those editors gain
   stable semantic selection entry points.
2. Create a project-owned writable ASM workspace with explicit dirty files,
   compile diagnostics, and symbol output.
3. Feed semantic editor changes into generated assets/source, assemble with the
   pinned toolchain, and run the existing ownership/preflight checks over the
   resulting ROM delta so patch-mode features remain compatible.

Writable source, arbitrary-hack disassembly, merge/conflict handling, and ASM
compilation are not implied by the current browser.
