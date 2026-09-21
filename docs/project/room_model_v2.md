# Stateful Room Editing

> The filename is retained for existing links. This is not a second map or project type: it is an
> in-place evolution of the existing `.smedit` project schema and `RoomEdits` model.

## Goal

Make rooms, runtime room states, shared resources, and newly created rooms first-class project data.
ROM addresses are build output, not project identity. V2 must preserve vanilla behavior, surface
unsupported custom behavior explicitly, and reject unsafe builds rather than guessing.

This work is intentionally staged: read and explain state logic first, then persist state-scoped
changes, then author selectors and new rooms.

## Milestone Status

As of 2026-09-20, **existing-room state editing is feature complete for the current product
milestone**. This scope includes ordered inspection and preview, state-scoped editing, branch
add/duplicate/delete/reorder, typed and compound conditions, first-match simulation, safe
copy-on-write, selector-graph relocation, transactional export, validation, and semantic reopening.

The following are intentionally separate future features rather than incomplete parts of this
milestone: explicit re-linking of resources after copy-on-write, actions that mutate persistent
state during gameplay, separate-background authoring, opaque/custom room-code authoring, automatic
materialization of early development-only project formats, and creation of entirely new rooms.

## Verified Runtime Model

When a room loads, the game reads its shared 11-byte header and evaluates the state-selector list
from top to bottom. The first condition that succeeds selects a 26-byte state record. The final
selector is `$E5E6`, whose default state record follows inline and always succeeds.

The room header owns the area/index, map position, dimensions, scroller thresholds, special-GFX
flag, and door-list pointer. Each state independently references level data, tileset, music, FX,
enemy population, enemy GFX, Layer 2 scrolling, scroll data, special X-Ray blocks, main ASM,
PLMs, background data, and setup ASM.

Resources may be shared. Two states pointing at the same level-data address intentionally share one
layout. An editor must preserve that relationship until the user explicitly makes one state
independent.

Verified inputs:

- Vanilla machine code at `$8F:E5D2-$8F:E689`.
- All 263 cataloged vanilla rooms: 324 state records, each ending in one default state.
- Existing parser format tests for Landing Site, Bomb Torizo, Mother Brain, and other multi-state
  rooms.
- The reviewed expanded project ROM: all 297 XML rooms and all 371 ROM states are now discovered
  with zero state-parse issues. `$E60F` support recovers the previously omitted Elevator to Hell
  room and produces the ordered logic: incoming door, never, Speed Booster, default. Supporting
  the engine's area `$07` debug/unused slot recovers the project's Debug Room; that slot has load
  stations but no normal pause-map area.

The machine code uses an indirect jump to the selector address. The smaller switch in the C
decompilation only lists entry points encountered by that decompiler; it is not a runtime whitelist.
Vanilla contains callable entry points for incoming door, Morph Ball, and Speed Booster selectors,
even though vanilla room data does not use them. `$E60F` enters the final `INX; INX; RTS` path and
is conventionally used as an always-false selector that skips its state pointer.

## Current Boundary

The project remains a set of deltas keyed by an existing physical room-header address. `RoomEdits`
now optionally contains an ordered manifest of stable `RoomStateEdits` identities and explicit
resource links. Existing room-wide deltas remain valid common edits while older projects are
upgraded.

Selected-state tiles, PLMs, enemies, scrolls, tileset/music/Layer 2 motion, and FX are now persisted
and exported against that state. Both the default FX entry and existing incoming-door-specific FX
entries are editable. Shared level, PLM, enemy, enemy-GFX, scroll, and FX resources use copy-on-write
so editing one state does not silently mutate a linked sibling.

Existing-room selector graphs are now authorable. The UI can add/duplicate/delete/reorder conditional
branches, edit every vanilla condition plus generated equipment/beam, capacity, current health/ammo,
exact item/door/Chozo-block, escape, and cross-area boss conditions, and keeps the one mandatory
default branch fixed last. A visual builder creates nested `AND`, `OR`, and `NOT` expressions.
Size-changing conditions and changed state counts rebuild the selector graph in bank `$8F`. The room
header remains at its original address and contains a four-byte SMEDIT bridge to the relocated graph,
so DoorDefs, AreaSave entries, load stations, and hardcoded engine comparisons continue to use the
same room ID. Export round trips the relocated graph through the normal parser and remains inside the
transactional ROM write plan.

The room-state simulator now accepts only inputs used by the current graph and shows the first branch
that would win plus later branches that also match. A duplicated state copies the selected state's
effective project deltas; unedited ROM resources stay linked and later state-local edits use
copy-on-write. Explicit re-linking, state-triggered actions, separate-background/custom-code
authoring, legacy behavioral materialization, and new-room creation are deferred extensions outside
the completed existing-room milestone.

## Proposed Project Model

Names are illustrative; the serialized schema should be finalized with round-trip fixtures before
shipping it.

```text
existing RoomEdits
  origin: Existing(room header source) | New
  header: shared room header fields
  doors: ordered door definitions
  states: ordered RoomStateEdits list

RoomStateEdits
  id: stable project ID
  condition: typed selector condition
  level: ResourceRef<LevelData>
  tileset / music
  fx: ResourceRef<FxTable>
  enemies: ResourceRef<EnemyPopulation>
  enemyGfx: ResourceRef<EnemyGfxSet>
  scrolling: ResourceRef<ScrollData>
  plms: ResourceRef<PlmSet>
  background: ResourceRef<BackgroundProgram>
  mainAsm / setupAsm: CodeRef
  specialXrayBlocks: ResourceRef<SpecialXrayBlockTable>?
```

Stable IDs are not SNES addresses. New content has no address until the build planner allocates
it, and existing content can move during copy-on-write or ROM expansion.

`ResourceRef` makes sharing explicit. The default state can share a level with several conditional
states while each state owns different enemies or PLMs. Editing a linked resource should offer:

- Edit the shared resource and affect every linked state.
- Make this state independent, copy the resource, and edit only that copy.

The safe default for an edit initiated from one state is copy-on-write.

## Conditions

Known conditions should be typed rather than exposed only as routine addresses:

- Default
- Incoming door
- Main area boss defeated
- Never
- Event bit set
- Per-area boss-bit mask set
- Morph Ball collected
- Morph Ball and at least one missile collected
- At least one Power Bomb collected
- Speed Booster collected

The order is semantic. V2 validation requires exactly one default, requires it to be last, detects
duplicate or shadowed predicates where possible, and preserves unknown custom selectors as opaque
code only when their encoded argument layout is known. It must never guess an unknown selector's
entry size.

## GUI

The initial inspector uses an ordered decision list because that is the runtime structure and is
usually clearer than a free-form graph:

```text
IF event $0E is set             -> Escape state
ELSE IF Power Bombs collected   -> Awakened state
ELSE IF event $00 is set        -> Post-intro state
ELSE                            -> Default state
```

Selecting a branch previews that state's complete resource set. Pointer values remain available in
an advanced view, while the normal view uses compact summaries such as `same in all 4 states` or
`same in 3 of 4 states`. A help dialog lists the matching states by condition name instead of
exposing state ordinals.

The editable state UI includes:

- Add, duplicate, delete, and reorder state.
- A typed condition builder.
- Compare the selected state with Default and highlight differing fields/resources.

Deferred extensions:

- Explicit duplicate-as-linked/independent choices and per-resource link/unlink controls.
- Visual authoring for actions that set events or otherwise mutate persistent state.

The door/world graph is related but separate. Door edges connect rooms; a room's state list is an
ordered decision tree that determines the content loaded at a node. The graph can eventually
annotate a room with state-dependent door caps or traversal changes without conflating the models.

## New Rooms

New-room creation should build on this same model rather than introduce another address-keyed delta format. A
new room starts with a header, one mandatory default state, blank level/BTS data, scroll data, empty
PLM/enemy/GFX sets, an FX table, and a door list. Blank, cloned, templated, and generated rooms all
produce the same semantic model.

At build time, the planner allocates the room header, selector list, state records, and referenced
resources in their required banks, then resolves door destinations and code symbols transactionally.

## Project Versioning And Migration

Project schema versioning must use a new `projectFormatVersion`. The existing `versionMajor` and
`versionMinor` fields control exported ROM filenames and are not schema versions.

Load behavior:

1. A missing `projectFormatVersion` means legacy format 1.
2. The application offers an explicit format-1 upgrade only for legacy projects.
3. The original project is backed up before writing the current format.
4. New projects use the current format by default.
5. Legacy loading can be removed after the migration window; current projects must never silently downgrade.

The safest migration of old room-wide state behavior is behavioral materialization:

1. Build the V1 project onto a copy of its base ROM using the existing exporter.
2. Parse each affected room and its complete state/resource graph from that result into the current schema.
3. Preserve sharing by grouping identical resulting pointers/resources.
4. Copy non-room project features through their typed migration paths.

This preserves what V1 actually exported—including its room-wide multi-state behavior—instead of
reimplementing that behavior and risking a subtly different conversion.

## Build Invariants

- Exactly one default state, always last.
- Every selector's encoded argument width matches its routine contract.
- Every state pointer remains in bank `$8F` and targets a complete 26-byte record.
- Room dimensions agree with every state's level and scroll data.
- Shared resources remain shared unless explicitly forked.
- Mutating a resource shared outside the edited room uses copy-on-write.
- All bank-constrained allocations are planned before any ROM bytes are committed.
- Doors resolve to project room IDs before addresses are emitted.
- Unknown/opaque ASM pointers are preserved or reported as blocking; never rewritten speculatively.

## Delivery Chunks

### 0. Inspection and truthfulness — complete

- Structured parser result with exact selector arguments and diagnostics.
- Full vanilla-ROM regression scan plus synthetic malformed/extended-selector tests.
- Ordered GUI state logic, resource-sharing display, and selected-state comparison against Default.
- Complete selected-state rendering for layout, PLMs, enemy actors, scrolls, Layer 2 background,
  Layer 3 FX/liquids, tileset, and the preserved special X-Ray block-table field.
- Multi-state fields remained read-only until state-scoped persistence was proven.

### 1. Schema and project format — complete for current-format projects

- Serializable room/state/resource identities are implemented inside the existing `RoomEdits`.
- Explicit `projectFormatVersion` and legacy-format detection are implemented.
- A one-time pre-upgrade backup is implemented.
- Automated behavioral materialization of early development-only projects is deferred; it is not a
  blocker for current-format projects.
- Serialization and byte/semantic equivalence tests cover current-format round trips.

### 2. State-scoped editing — complete for the current milestone

- Stable state IDs are carried through canvas/property edits and undo/redo.
- Copy-on-write export is implemented for level, enemy, enemy-GFX, PLM, scrolling, and FX data.
- State-targeted level, enemy, PLM, scrolling, default/door-specific FX, music, tileset, and Layer 2 motion export is implemented.
- All known typed condition changes are implemented, including encoded-size changes through graph relocation.
- Explicit link/unlink controls and separate-background authoring are deferred extensions.

### 3. State authoring — compound editor, allocator, and simulator complete

- Add/delete/duplicate/reorder are implemented with stable state IDs and a fixed final default.
- The condition builder supports every verified vanilla selector, SMEDIT's typed runtime predicates,
  and nested `AND`/`OR`/`NOT`; duplicate predicates are blocked in the UI and diagnosed during validation.
- The selector-list/state-record allocator preserves the physical room-header address and round trips
  its tagged redirect format through `RomParser`.
- The condition-selection simulator proves first-match behavior from editable load-time inputs.
- Canonical expression decoding, semantic evaluation, real-ROM graph round trips, and an
  instruction-level 65816 interpreter matrix are unit tested.

### 4. New rooms

- Blank/clone/template/generator entry points.
- Room/door/state/resource allocation.
- Map placement, room graph, reciprocal-door tooling, and route validation.

### 5. Symbolic ASM mode

- Known routine, project symbol, opaque pointer, and no-routine code references.
- Base-ROM/profile symbol tables and assembler/linker integration.
- Custom selector schemas with explicit encoded argument contracts.

## Test Boundary

The completed existing-room milestone is covered by selector encoding/decoding, malformed-input,
full-ROM inspection, copy-on-write, add/delete/reorder, simulator, graph-allocation, semantic
round-trip, and instruction-level predicate/interpreter tests.

The following remain acceptance requirements for their corresponding future features, not for the
completed state editor:

- V1 migration tests comparing old exported behavior with V2 output.
- New-room round trip: model -> ROM -> parser -> equivalent model.
- Emulator smoke tests for event, boss, equipment, incoming-door, and default branches.
