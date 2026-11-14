# ASM Integration Roadmap

This is the ordered implementation plan for SMEDIT's Project ASM workflow. It
exists separately from the feature reference so unfinished work is not lost in a
long description of shipped behavior.

Work proceeds from the top down. Do not start the next numbered item while the
current item has an incomplete completion gate. If a deliberately deferred case
is discovered, record the exact boundary and reason here rather than calling the
feature complete implicitly.

## Engineering rules

- There is one authority for every editable value. Project metadata may retain
  stable identities and ownership, but it must not retain a second copy of an
  ASM-owned value.
- Supported source shapes are reciprocal. Unsupported/custom shapes fail closed
  as **ASM only** with a concrete explanation; they never fall back to stale ROM
  or project values.
- Opening or inspecting a resource does not adopt it or dirty the project.
- Visual edits, source edits, aliases, Undo/Redo, restore, Save/reopen, cold
  builds, baseline updates and conflicts must agree.
- Allocation and pointer changes are planned and validated before any authored
  file or project model is published.
- Generated/cache files are disposable projections. Authored source/assets and
  the `.smedit` project are the durable inputs.
- Prefer a complete vertical slice over broad partial coverage.

## Definition of done for every numbered feature

A feature is complete only when all applicable items below are satisfied:

1. The authority, supported source shape, aliases and unsupported boundary are
   explicit in code and user-facing language.
2. Source-to-visual and visual-to-source changes update live without reopening
   the project, while unsaved source drafts are protected.
3. Undo/Redo and restore are atomic across source, assets, ownership and project
   state, and refuse to overwrite newer edits.
4. Save/reopen and a cold headless build use durable authored inputs rather than
   a preview/cache.
5. Build validation checks emitted bytes, resolved pointers, allocation bounds
   and overlapping ownership.
6. Project ASM Changes and ROM comparison explain the resulting changes.
7. Focused tests cover native parity, both editing directions, aliases, growth,
   invalid drafts, conflicts, restore, reopen and cold build behavior.
8. User workflow and the intentional safety boundary are documented.

## 1. Managed ASM assets and relocatable segments

Remove the fixed-original-envelope ceiling for project-owned source assets and
establish the placement model later code/data features can reuse.

### 1A. Relocatable source-asset pilot: tilesets

**Status: complete (2026-11-13).** The pilot covers all five resource kinds,
direct and shared aliases, explicit move/cancel, guarded restore, live source/visual
refresh, durable cold builds, exact payload/symbol/consumer validation, checked
module exclusion, 3 MiB no-space failures, Project ASM Changes and ROM Space
Planner reporting. Focused bridge, UI-state, compiler, pointer, planner and
managed-palette theme Undo/Redo tests enforce the completion gates.

- Introduce a stable managed-asset identity independent of its current path or
  compiled address.
- Keep in-place edits when the encoded payload fits the frozen source envelope.
- When it grows, create an authored project asset, place it through the checked
  allocator and update the supported symbolic source declaration/pointer.
- Validate every pointer consumer, pointer width/bank constraint, compressed
  round trip, complete allocation claim and emitted byte.
- Preserve shared tileset/CRE aliases explicitly; relocation must not silently
  fork or merge an asset.
- Show current encoded size, in-place capacity and planned destination. Offer a
  clear **Move to project space** action rather than failing with only a size
  error.
- Restore returns the source declaration and asset to the frozen ASM baseline and
  releases the managed allocation atomically.
- Cover area graphics, area metatiles, CRE graphics, CRE metatiles and palettes.

### 1B. Shared managed-segment model

**Status: complete (2026-11-13).** Stable typed requests now carry logical ID,
owner, kind, exact size, alignment, allowed banks and constraints without storing
a durable address. One shared allocator session covers generated rooms/patches,
managed source assets and code plus editor-planned music, graphics and custom ASM;
compiled placements are adopted by final export. Canonical alignment survives
headered ROMs, 3/4 MiB capacity stays explicit, failures report compatible space
and known blockers, and the ROM Space Planner renders the same requests and exact
placements used by Export.

- Generalize the pilot into typed data/code segments with stable identity, size,
  alignment, allowed banks, pointer constraints and ownership.
- Make one layout-aware allocation registry authoritative for generated room
  data, source assets, checked hooks/helpers, generated patches and expansion
  banks. Retire independent free-space scanners as each owner migrates.
- Persist allocation intent, not a stale compiled address. Resolve placement in a
  checked build and retain symbols for navigation/reporting.
- Extend ROM Space Planner with requested size, chosen range, fragmentation,
  largest compatible block and the owner preventing a placement.
- Support the existing 3 MiB/4 MiB project capacity choices without silently
  expanding a project or moving fixed native code.

### 1C. Managed helper code

**Status: complete (2026-11-13).** Generated checked hooks use v2 placement
intent: the native long-jump envelope remains fixed while each helper is measured
in an independent single-bank probe and allocated/repacked through the shared
registry. Final symbols, detour bytes, displaced prefix, continuation tail,
complete envelope, free-space membership and exclusive output ownership are
validated. Adjacent helpers retain separate identities, growth changes the exact
request, saved seed/preview addresses are non-authoritative, and cache-free builds
reproduce deterministic placements. The ASM Library documents the workflow and
Space Planner reporting.

- Move checked-hook helpers onto managed code segments while leaving each proven
  native detour fixed.
- Repack/relocate a helper safely when it grows, re-resolve its symbols and check
  the exact detour, displaced instructions, continuation and complete helper
  envelope.
- Never auto-relocate arbitrary native routines or infer that an arbitrary source
  selection is hook-safe.

## 2. Complete remaining ASM / Visual Editor reciprocity

Reuse the managed-asset and ownership contracts rather than creating per-editor
override systems.

### 2A. Native sprite graphics and palettes

**Status: complete (2026-11-13).** Native pixel sheets now use exact pinned
`incbin` assets as their sole ASM-mode authority, including guarded in-place
saves, managed relocation for supported compressed growth, live previews,
legacy-override retirement, Restore and atomic ownership-aware Undo/Redo. Native
16-colour rows use a strict fixed-source lens over contiguous literal `dw` data;
custom expressions/macros and ambiguous rows fail closed as ASM only. Saved
source edits refresh without reopening, Project ASM Changes reports the authored
asset/bank, ASM builds preserve source ownership over stale visual overrides, and
cache-free compiler tests verify emitted pixels/palettes and restore. Composite
OAM placement, animation/DMA programs and runtime palette effects remain
intentionally read-only.

- Move existing native sprite pixel/palette edits from ROM/project overrides to
  the exact project source assets consumed by Asar.
- Resolve direct source assets and aliases, refresh from saved ASM changes, guard
  unsaved buffers, support managed relocation for compressed growth and restore
  the frozen ASM baseline.
- Keep composite placement/animation data read-only where ownership is not proven.

### 2B. Background, title/menu and cinematic assets

**Status: complete (2026-11-13).** Six exact compressed source assets are now
reciprocal through the Scenes workspace: title Mode 7, frame-aware Ceres/gunship
Mode 7, the four-frame intro BG1 source, intro Samus-head BG2, ending Samus BG2
and the post-credits icon. Direct and managed assets support guarded Apply,
live Scenes/Intro preview, shared title aliases, Restore and source-aware
Undo/Redo. Cache-free Asar tests validate emitted LZ5 bytes, managed allocations
and real split bank/low-word consumers; Project ASM Changes and ROM Space Planner
reuse the generic managed-asset transaction. Pinned-ROM/disassembly tests prove
the native decoded frame boundaries, visual dimensions and relocation shapes.
OAM composition, cinematic instruction/frame programs, graphics tiles and
regional menu/text/credits layouts remain explicit read-only inventory until
their own exact semantic models are proven.

- Give each supported scene map one stable source identity shared by every
  visual alias. Resolve only one direct manifest-backed `incbin`; custom source
  shapes fail closed as **ASM only**.
- Add an exact frame-aware map-cell editor for Mode 7 byte maps and SNES BG word
  maps. Preserve the Ceres/gunship and intro BG1 runtime frame boundaries plus
  tile number, palette, priority and flip bits; never flatten the rendered image
  back into guessed tiles.
- Reuse managed source-asset placement when compressed growth exceeds the native
  envelope. Save, Restore and Undo/Redo must cover the data asset, source
  declaration and direct/managed ownership atomically.
- Refresh the Scenes viewer and Intro Sequence previews from saved source without
  reopening. Guard unsaved ASM drafts and newer disk edits.
- Prove Save/reopen, aliases, compressed growth, relocation, restore, cache-free
  compile, emitted map bytes, Project ASM Changes and ROM-space reporting.
- Maintain an explicit regional inventory for the options screen PAL branch,
  English/Japanese controller and special-settings maps, Japanese intro font/text
  path and other regional cinematic data. Do not route those through an NTSC
  editor or claim reciprocal support until their active-branch and encoding
  contracts are modeled.

- Add reciprocal source-asset ownership only for formats with an exact decoder,
  encoder and semantic visual selection.
- Include regional/Japanese variants explicitly instead of assuming NTSC English
  layouts or encodings.

### 2C. Text assets

**Status: complete (2026-11-13).** The Text
Editor now reads and writes the exact area-label, message-row and item-name
literal words assembled by the project. It refreshes in both directions without
reopen, retires stale JSON ownership, guards drafts/newer source, supports
source-aware Undo/Redo and Restore, and fails custom shapes closed as ASM only.
Save/reopen, real pinned-source resolution, cache-free Asar output/restore and
export-precedence tests cover all 35 current fixed fields. Ceres/Zebes typewriter
streams are also complete: timer/VRAM/end controls are typed and preserved,
native-slot edits stay fixed-size, and longer messages use shared checked
allocation constrained to bank `$A6` with validated low-word consumers, history,
Restore, cold reopen/build, Project ASM Changes and ROM-space evidence. The six English NTSC intro
pages now use a strict symbolic-glyph lens over their fixed timing/position
records: visual/source changes refresh live, page/line geometry and every
cinematic control remain intact, stale JSON ownership is retired, and guarded
history, Restore, reopen, cold Asar output and export precedence are covered.
Custom page shapes and the Japanese/regional text paths fail closed as ASM only.

1. **Complete.** Fixed tilemap text: area labels, message-box rows and item-name fields become
   exact literal-`dw` source lenses. Retire matching project JSON overrides,
   refresh from saved ASM, guard drafts/newer source, and cover Restore,
   Undo/Redo, reopen and cold output.
2. **Complete (2026-11-13).** Typewriter streams: model Ceres/Zebes control words and quoted text as one
   typed stream. Fixed-size edits preserve every control value. Longer streams
   may relocate only inside their required implicit bank, with checked low-word
   consumers and allocator evidence; never place a low-word-only pointer in an
   arbitrary bank.
3. **Complete (2026-11-13).** Intro story scripts preserve timing, position,
   page/movie/wait/finish controls and symbolic glyph routines. English NTSC
   pages are a separate fixed-slot contract from the Japanese font/text path;
   custom macros/expressions and structural changes remain ASM only.

- Make supported text/control streams project source assets with checked encoding,
  relocation and pointer updates.
- Preserve control codes and regional encodings; unsupported/custom streams remain
  ASM only with repair navigation.

Sound/music reciprocal authoring is intentionally not part of this sequence.
Existing navigation and safe export remain supported.

## 3. Unified ROM-space allocation

**Status: complete (2026-11-13).** Every desktop and headless production
reservation now carries a typed request through one shared registry; final builds
reject anonymous allocations. Music, graphics, text, minimap, custom ASM,
generated patches and fixed/configured hooks publish direct planned writes. The
large room encoder runs against an isolated ROM plus a forked view of the same
allocator state, then atomically publishes its complete allocation envelopes and
non-allocation writes before the new placements are adopted. ROM Space Planner
therefore consumes the exact export requests/placements without owner inference.
Failures retain requested size, alignment, banks, compatible totals, largest
block, fragmentation, blockers, constraints and safe next steps. Combined
per-frame patches declare their shared ROM hook and exact WRAM words. Focused
allocator, planner, room, music, intro, headless and export tests enforce the
completion gate.

This item is complete when every production writer participates in the shared
registry introduced by 1B.

- Migrate remaining captured/legacy writers to direct planned writes.
- Declare ROM, WRAM, VRAM, hook, ID and other runtime resources for complex
  patches where meaningful.
- Make planner results and export/build ownership reports derive from the same
  plan; the UI must not estimate from a different scanner.
- Provide actionable failures: required size/alignment/banks, largest compatible
  block, conflicting owner and safe next actions.

## 4. Compiled Output Inspector

**Status: complete (2026-11-13).** Editable project source now follows the caret
through the retained WLA line map and shows exact emitted SNES/PC ranges,
baseline/loaded/result bytes, compiled symbols, export owners, managed placement
and allocator headroom. Incoming/outgoing references navigate to authored
source; ROM Comparison, Space Planner, Project ASM Changes and proven Visual
Editor owners are one click away. Unchanged statements remain inspectable,
label-only lines retain symbols/references, and edits mark captured output stale
instead of relabeling it current. Authored and generated diagnostics show the
exact failing statement; generated room/patch/managed-code/managed-asset inputs
are retained only as disposable evidence with their real editing authority
called out. Focused resolver, baseline-preview, WLA-order and build-artifact
tests cover the contract.

Connect authored source to actual output before adding live execution control.

- Map a selected source statement to emitted bytes, SNES/PC range, symbols,
  allocation owner and bank headroom.
- Compare baseline, loaded ROM and current SMEDIT result at that range.
- Navigate incoming/outgoing references and jump to ROM Comparison, ROM Space
  Planner, Project ASM Changes or the Visual Editor owner.
- Map assembler/build diagnostics to the exact source statement, including
  generated source without presenting generated files as authored authority.
- Keep views useful for unchanged source and explicitly mark stale results.

## 5. Source-level runtime debugger

**Status: complete (2026-11-13).** SMEDIT's bundled Snes9x core now exposes a
versioned optional debugger ABI with exact one-opcode stepping, bounded
run-to/breakpoints, CPU registers/flags/widths/timing and side-effect-free mapped
bus reads. Stock cores remain usable and fail closed for debugger capability.
The project-source panel provides session-only breakpoints, Run to Cursor,
resume/pause, exact current-PC highlighting, authored/generated symbol evidence,
raw native/emulation-mode stack windows, selected memory and conservative
RTS/RTL candidates. Controller input remains live during breakpoint-aware runs;
unsafe I/O/coprocessor reads are shown as unavailable without losing CPU state.
The loaded emulator ROM retains its exact build-artifact identity, so a rebuild
cannot apply newer symbols to older executing bytes: navigation and address
controls go stale until restart. A clean native-core build plus 56 focused
native, resolver, artifact, library and workspace/UI-state tests cover the
completion boundary.

Build on retained project symbols and the Compiled Output Inspector.

- Prove an emulator/core integration capable of deterministic pause, resume,
  single-step or run-to-address and CPU-state reads before promising the UI.
- Add Run to Cursor, execution breakpoints and current-PC source highlighting.
- Show A/X/Y, D/DB/PB, S, processor flags/widths, nearby stack and selected memory.
- Symbolize call/return context and crash/invalid-execution reports where the
  emulator exposes sufficient evidence.
- Keep breakpoints session state unless the user explicitly saves a debug setup.
- Treat generated helpers and relocated managed segments as first-class symbols.

## 6. Semantic Project ASM Changes

**Status: complete (2026-11-13).** Project ASM Changes now classifies proven
room resources, palettes, graphics/metatiles, map data, text, hooks/modules and
current managed allocations while retaining the exact three-layer raw diff.
Origins distinguish modules, managed relocations, proven reciprocal assets,
ordinary authored source/assets and candidate-version changes without inventing
per-line last-writer provenance. Proven Visual Editor owners link directly to
their editor. Restore selected operates only on complete guarded bank, module,
direct-asset or managed-asset transactions; managed source markers, unsaved
buffers, newer disk/asset data, changed module metadata, changed baselines and
cross-project reviews fail closed. Source/module/direct-asset repository restores
now roll back on validation failure, and all restore paths invalidate retained
build/ROM evidence. Update ASM Version reuses the same semantic classifier above
its existing whole-region conflict resolution and atomic candidate validation.
The ASM Library/reference docs plus 58 focused semantic, repository, managed
asset and Compose-dialog tests cover the completion boundary.

Extend the existing selectable text/data diff without replacing it.

- Identify the semantic resource and origin where known: direct source edit,
  Visual Editor edit, managed relocation, module, baseline update or generated
  build input.
- Render type-aware summaries for room resources, palettes, graphics/metatiles,
  text, hooks/modules and allocations while retaining an exact raw diff.
- Allow safe selected restore/apply only when the complete ownership transaction
  can be preserved; never apply half of a shared resource change.
- Reuse the same semantic changes during ASM version conflict review.

## 7. ASM editor assistance

**Status: complete (2026-11-13).** One shared fail-closed analyzer now powers
live, saved-source and cold-build checks. Its exact constant-expression model
handles literal/define arithmetic, bit operators and common address-part/LoROM
functions; an unknown symbol, function, macro expansion, conditional shape or
cursor size stops inference. It checks expression data width, fixed-cursor bank
crossing and `warnpc`, direct unresolved control-flow targets, uniquely proven
JSR/JSL versus RTS/RTL returns, and immediate width after a straight-line
REP/SEP state. Errors block unsafe known output; review warnings remain visible
without blocking Asar. The pinned source corpus has no new blocking diagnostic,
and digit-leading scoped labels such as `.2x1` are now indexed correctly.

Completion includes documented directive forms, exact define values and common
expression functions with caret-aware insertion, while preserving the existing
instruction, suffix, macro and scoped-symbol behavior. **Rename Symbol…** is a
mandatory-preview refactor for ordinary global labels: it proves every
declaration/use (including proven flattened descendants), revalidates the plan,
and applies every file as one guarded Undo/Redo transaction. It refuses stale or
unsaved indexes, locals, defines, macro/function parameters, namespaces,
collisions, ambiguous or unclassified occurrences; comments and strings are
never rewritten. There is intentionally no whole-file formatter. The ASM
Library/reference docs and 135 focused analyzer, completion, refactor, project
history, parser, baseline-transform and pinned-source tests cover this boundary.

- Add richer expression-aware Asar diagnostics and completion.
- Add conservative inspections for bank crossing, `warnpc`, unresolved symbols,
  near/long call-return mismatches and immediate-width ambiguity when state is
  provable.
- Add symbol rename/refactor only after references can be resolved without
  rewriting comments, strings, local labels or macro tokens incorrectly.
- Do not introduce a whole-file formatter that normalizes authored expressions,
  literal forms, suffixes or comments.

## 8. Ongoing integration contract and fast verification

**Status: complete (2026-11-13).** A checked Kotlin registry now inventories 19
integrations: 17 reciprocal source/Visual Editor contracts plus the intentional
sound/music and arbitrary-source boundaries. Every row has explicit decisions
for inspect, ASM edit, Visual edit, live refresh, aliases, restore, relocation
and cold build, together with the same ten conformance scenarios. Validation
rejects missing decisions/evidence, incomplete reciprocal rows, duplicate IDs,
documentation drift, missing/nested test suites and differences between the
registry and Gradle task lists. `asmFastTest` runs the focused source/UI contract
as the normal loop; `asmReciprocityTest` adds the declared cache-free compiler
proofs. The human-readable capability matrix, ASM reference link and bundled ASM
Library guide explain the supported shapes and intentional boundaries. The fast
gate currently covers 497 tests with no failures (40 pinned-ROM skips when its
optional fixture is unavailable).

Build the shared contract alongside items 1–7 rather than postponing it.

- Maintain a capability matrix for inspect, ASM edit, visual edit, live refresh,
  aliases, restore, relocation and build support.
- Give every reciprocal bridge the same focused conformance scenarios from the
  global definition of done.
- Keep fast targeted tests as the normal development loop. Run heavyweight
  full-source/parity suites at milestone boundaries or when their underlying
  compiler/export contracts change.

## 9. Projectile damage reciprocity

**Status: complete (2026-11-13).** The proven bank-`$93` damage lens and Visual
Editor now cover the remaining active player-projectile damage records. The
implementation extends the existing source authority, history, conflict and
build contract rather than introducing a second patch backend.

- Add Hyper Beam, missile, super missile, super-missile link, bomb, Power Bomb,
  Plasma SBA, Wave SBA and Spazer SBA as exact symbolic source fields.
- Keep normal/charged beam legacy migration and shared-table behavior intact.
- Show every supported value in one **Projectile Damage** editor with per-field
  ASM navigation, live refresh, guarded editing, Restore and Undo/Redo.
- Resolve compiled destinations from retained symbols in ASM mode and validate
  every emitted damage word before allowing later patches to target it.
- Exclude beam/missile/bomb explosion words whose native source explicitly says
  damage is ignored; do not present inert values as gameplay tuning.
- Complete the normal reciprocity gates: source/visual edits, invalid drafts,
  disabled snapshots, conflicts, navigation, reopen, cold build, documentation
  and checked capability coverage.

The completed editor adds Hyper Beam, missile, super missile, super-missile
link, bomb, Power Bomb and the Plasma/Wave/Spazer special-beam attacks alongside
the existing normal/charged beams. ASM and visual edits refresh reciprocally,
per-field navigation and guarded Undo/Redo/Restore share the established source
transaction, and compiled symbol destinations are validated before export.
Ignored explosion damage words remain intentionally absent. Focused reciprocity,
symbol-coverage and real-ROM cold-build gates pass.

## Explicit non-goals

- Decompiling arbitrary handwritten ASM into visual editor models.
- Silently relocating arbitrary native routines or repairing unknown control flow.
- Treating successful assembly as proof that a custom routine is runtime-correct.
- Maintaining duplicate source and project values with last-writer-wins behavior.
- Automatic merging of arbitrary source during ASM baseline updates.

