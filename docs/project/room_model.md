# Stateful Room Editing

This is the one room model used by native `.smedit` projects. The schema grew in place during beta;
there is no separate V1/V2 room type for callers or users to select.

## Goal

Make rooms, runtime room states, shared resources, and newly created rooms first-class project data.
ROM addresses are build output, not project identity. The model must preserve standard game behavior,
surface unsupported custom behavior explicitly, and reject unsafe builds rather than guessing.

This work is intentionally staged: read and explain state logic first, then persist state-scoped
changes, then author selectors and new rooms.

## Milestone Status

As of 2026-09-22, **existing-room state editing is feature complete for the current product
milestone**. This scope includes ordered inspection and preview, state-scoped editing, branch
add/duplicate/delete/reorder, typed and compound conditions, first-match simulation, safe
copy-on-write, intentional shared-layout editing and relinking, selector-graph relocation,
transactional export, validation, and semantic reopening.

The following are intentionally separate future features rather than incomplete parts of this
milestone: explicit relinking controls for non-layout resources, actions that mutate persistent
state during gameplay, separate-background authoring, opaque/custom room-code authoring,
whole-room deletion, template libraries, and generator-to-new-room output. Core project-owned room
creation is implemented as the next layer on this same model.

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
- The reviewed expanded project ROM: all 297 discovered room headers and all 371 ROM states are
  parsed from the built ROM with zero state-parse issues. `$E60F` support recovers the previously
  omitted Elevator to Hell
  room and produces the ordered logic: incoming door, never, Speed Booster, default. Supporting
  the engine's area `$07` debug/unused slot recovers the project's Debug Room; that slot has load
  stations but no normal pause-map area.

The machine code uses an indirect jump to the selector address. The smaller switch in the C
decompilation only lists entry points encountered by that decompiler; it is not a runtime whitelist.
Vanilla contains callable entry points for incoming door, Morph Ball, and Speed Booster selectors,
even though vanilla room data does not use them. `$E60F` enters the final `INX; INX; RTS` path and
is conventionally used as an always-false selector that skips its state pointer.

## Current Boundary

Existing source rooms remain deltas keyed by their physical room-header address. Project-owned
rooms have stable semantic identities and complete initial payloads, then use the same `RoomEdits`
overlay after workspace materialization. `RoomEdits` optionally contains an ordered manifest of
stable `RoomStateEdits` identities and explicit resource links. Top-level room-wide deltas remain
valid common edits that apply across states.

Selected-state layouts, PLMs, enemies, scrolls, tileset/music/Layer 2 motion, and FX are now persisted
and exported against their actual owning resources. Both the default FX entry and existing
incoming-door-specific FX entries are editable. A state never owns a partial tile override: it
references one complete level-layout resource, which may be shared by several states or unique to
one. The first tile-changing action on an unacknowledged shared layout asks whether to edit the
shared layout or make the active state's complete layout unique first. PLM, enemy, enemy-GFX,
scroll, and FX resources continue
to use copy-on-write so editing one state does not silently mutate a linked sibling.

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
copy-on-write. Layouts can be made unique, copied between states, applied from a selection to several
states, explicitly shared, or reverted as one whole resource. Relinking for non-layout
resources, state-triggered actions, and separate-background/custom-code authoring remain deferred
extensions. Project-owned room creation now builds on the completed
existing-room milestone.

## Current Project Representation

```text
SmEditProject.rooms[room-header ID] -> RoomEdits
  common room operations / PLM / enemy / scroll / FX edits
  levelResourceOperations: tile/BTS/embedded-Layer-2 operations owned by a complete layout identity
  optional shared room-header changes
  states: ordered RoomStateEdits list

SmEditProject.newRooms[stable project ID] -> ProjectNewRoom
  complete initial header and default-state payload
  semantic ordered doors (`rom:*` / `project:*` destinations)
  rebuildable workspace room/DoorDef pointers (preview adapters only)

RoomStateEdits
  id: stable project ID
  source/template state identity
  condition: typed selector condition
  resources: explicit level / FX / enemy / enemy-GFX / scroll / PLM / background / X-Ray links
  state-specific non-layout operations and property changes
```

State IDs and resource-link IDs are project identities, not SNES addresses. Existing ROM addresses
remain source locations and can change during copy-on-write relocation.

Resource links make sharing explicit. The default state can share a level with several conditional
states while each state owns different enemies or PLMs. A layout identity always represents the
complete Layer 1, BTS, and embedded Layer 2 payload; partial per-state layout overlays are not part
of the current model. The canvas exposes both layout choices:

- Edit the shared layout, changing every state linked to that resource.
- Make this state's whole layout unique, then edit its independent resource.

The first tile-changing action never guesses between them. The editor pauses that action, asks for
its scope, and then replays it after the choice. Tile/BTS/embedded-Layer-2 data are the layout;
PLMs, enemies, effects, scroll settings, and other state resources are not accidentally included in
a layout operation. The toolbar also provides explicit make-unique, copy, share,
selection-to-states, and revert commands. Legacy beta files containing per-state tile overlays are
migrated to complete unique layout resources when opened, preserving their visible result without
retaining ambiguous partial ownership.

Project-owned rooms extend this representation with a stable room identity, an origin of `BLANK` or
`CLONED`, a complete shared header/default state, and ordered door definitions. Their workspace
addresses are disposable parser adapters; new content has no exported ROM address until the build
planner allocates it.

## Conditions

Conditions are typed rather than exposed only as routine addresses. The engine-native baseline is:

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

The order is semantic. Validation requires exactly one default, requires it to be last, detects
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
- A compact shared/unique layout indicator and menu for make-unique, copy, share, and revert.

Deferred extensions:

- Explicit duplicate-as-linked/independent choices and link/unlink controls for non-layout resources.
- Visual authoring for actions that set events or otherwise mutate persistent state.

The door/world graph is related but separate. Door edges connect rooms; a room's state list is an
ordered decision tree that determines the content loaded at a node. The graph can eventually
annotate a room with state-dependent door caps or traversal changes without conflating the models.

## New Rooms

Project-owned room creation is implemented in the native model. The Rooms list `+` action can create
a blank room or snapshot the currently previewed state. A blank room receives a complete header,
one mandatory default state, editable Layer 1/BTS/embedded Layer 2 data, blue scrolls, empty PLM,
enemy, and enemy-GFX sets, a default FX entry, and an empty terminated door list. A clone snapshots
the visible layout, scrolls, PLMs, enemies, enemy GFX, FX, state properties, and optionally copies
outgoing connections into independently allocated DoorDefs. It intentionally clones the selected
state as the new room's default; additional conditions use the normal state editor.

`ProjectNewRoom.id` is stable project identity. Its workspace preview address is rebuilt from an
untouched copy of the input ROM so the normal parser, renderer, and editing tools operate on the
same native structure they use for existing rooms. Saving preserves the semantic model; building
allocates fresh physical addresses and returns the stable-ID-to-room-ID mapping.

Every door tile uses the same two-stage destination picker in the tile-properties panel: search for
a room by name, ID, or area, then select one of the real compatible doorway openings found in that
room's effective tile layout. The picker includes unsaved project edits and unlinked openings in new
rooms; it does not guess from existing DoorDefs. If the source doorway has no DoorDef, choosing the
destination opening creates one behind the scenes. If a freshly painted doorway still shares another opening's BTS, choosing its
destination privately forks the connection and assigns the entire contiguous doorway pattern,
preventing mixed BTS indices across the opening. The doorway shape supplies the initial horizontal
or vertical direction, and only destination edges compatible with that direction are selectable. Room Info remains
the overview for inspecting and shrinking the complete door list; new connections always begin from
doorway tiles. Connections use named destinations, direction, and bounded one-based entrance screens.
Door destinations are stored as `rom:91F8` or
`project:room-1` references and resolved only after all new headers exist, including forward and
cyclic links. Removing a connection is blocked while doorway tiles reference its BTS index; higher
indices are shifted safely. Removal is also blocked while a room-state condition, door-specific FX
override, or save-station spawn references that exact DoorDef. Existing
reciprocal/facing/opening diagnostics remain advisory.
Reciprocity is checked against the connection index attached to the selected physical destination
opening, not merely any DoorDef elsewhere in that room. Healthy connections stay quiet; the tile
panel surfaces only actionable warnings and errors.

Room-local screen and tile coordinates are one-based everywhere in the editor UI. `(1, 1)` is the
top-left, columns increase to the right, and rows increase downward. ROM/project fields remain
zero-based internally and are converted only at the UI boundary. Door connections are likewise
displayed as Connection 1 through N; their zero-based BTS indices are an implementation detail.

At build time, SMEDIT allocates and validates the native graph in its required banks: compressed
level data in `$C0-$CE`, FX and DoorDefs in `$83`, enemy populations in `$A1`, enemy GFX in `$B4`,
and the room header/default state, PLMs, scrolls, and door list in `$8F`. The materialized output is
reopened through `RomParser` in tests. Normal room deltas and relocatable multi-state authoring are
then applied to the allocated room through the same exporter used for existing rooms.

Current follow-ups are whole-room deletion/reference cleanup, automatic minimap-tile drawing,
rebuildable template libraries, generator output into a new room, existing-ROM-room door-list
growth/removal, and managed ROM expansion when verified free space is exhausted.

## Project Schema During Beta

`projectFormatVersion` is an internal serialization-safety marker. It does not select a room model,
a ROM layout, or an export mode. The existing `versionMajor` and `versionMinor` fields only label
exported ROM builds.

New projects and checked-in beta projects use the current schema. Markerless early beta files can
still be read; if saving would cross a schema boundary, SMEDIT writes one untouched pre-upgrade
backup first. We do not maintain parallel public room models during beta. A future incompatible
schema change must ship with a tested, one-way migration into this model and must never silently
downgrade a project.

Top-level room edit lists remain intentional common edits: they apply across the room's states.
Entries inside `RoomStateEdits` are state-specific non-layout changes. Layout edits live in
`levelResourceOperations`, keyed by the complete layout identity referenced by one or more states.
This is a resource-ownership distinction in one model, not old-versus-new storage.

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
- Full standard-ROM regression scan plus synthetic malformed/extended-selector tests.
- Ordered GUI state logic, resource-sharing display, and selected-state comparison against Default.
- Complete selected-state rendering for layout, PLMs, enemy actors, scrolls, Layer 2 background,
  Layer 3 FX/liquids, tileset, and the preserved special X-Ray block-table field.
- Multi-state fields remained read-only until state-scoped persistence was proven.

### 1. Schema and project format — complete for current-format projects

- Serializable room/state/resource identities are implemented inside the existing `RoomEdits`.
- Explicit `projectFormatVersion` and markerless beta-file detection are implemented.
- A one-time pre-upgrade backup is implemented.
- Checked-in beta projects are normalized to the current schema; markerless external beta files
  remain readable.
- Serialization and byte/semantic equivalence tests cover current-format round trips.

### 2. State-scoped editing — complete for the current milestone

- Stable state IDs are carried through canvas/property edits and undo/redo.
- Copy-on-write export is implemented for level, enemy, enemy-GFX, PLM, scrolling, and FX data.
- Whole-layout ownership, shared/unique resource operations, copy/share/revert, and selection-to-state
  workflows are implemented without sharing state-owned PLMs, enemies, effects, or scroll changes.
- State-targeted level, enemy, PLM, scrolling, default/door-specific FX, music, tileset, and Layer 2 motion export is implemented.
- All known typed condition changes are implemented, including encoded-size changes through graph relocation.
- Link/unlink controls for non-layout resources and separate-background authoring are deferred extensions.

### 3. State authoring — compound editor, allocator, and simulator complete

- Add/delete/duplicate/reorder are implemented with stable state IDs and a fixed final default.
- The condition builder supports every verified vanilla selector, SMEDIT's typed runtime predicates,
  and nested `AND`/`OR`/`NOT`; duplicate predicates are blocked in the UI and diagnosed during validation.
- The selector-list/state-record allocator preserves the physical room-header address and round trips
  its tagged redirect format through `RomParser`.
- The condition-selection simulator proves first-match behavior from editable load-time inputs.
- Canonical expression decoding, semantic evaluation, real-ROM graph round trips, and an
  instruction-level 65816 interpreter matrix are unit tested.

### 4. New rooms — core creation complete

- Blank and clone-current-state entry points are implemented; templates and generator output remain.
- Stable project identity, isolated workspace materialization, schema round trip, and physical
  room/door/state/resource allocation are implemented.
- Project-room door-list add/remove, semantic destinations, forward/cyclic resolution, BTS-safe
  removal, and existing reciprocal diagnostics are implemented.
- Header map coordinates are authored at creation. Automatic minimap-tile drawing and whole-world
  route/orphan validation remain separate follow-ups.

The prerequisite existing-door UX is now implemented: semantic room/entrance selection, bounded
screen coordinates, project-aware connection diagnostics, and non-destructive reciprocal/facing
warnings. The project-room connection editor reuses these diagnostics and assigns door-list
identities without asking the user to enter a BTS index, DoorDef pointer, or room address.

### 5. Symbolic ASM mode

- Known routine, project symbol, opaque pointer, and no-routine code references.
- Base-ROM/profile symbol tables and assembler/linker integration.
- Custom selector schemas with explicit encoded argument contracts.

## Test Boundary

The completed existing-room milestone is covered by selector encoding/decoding, malformed-input,
full-ROM inspection, copy-on-write, shared/unique whole-layout ownership, layout relinking, add/delete/reorder,
simulator, graph-allocation, semantic round-trip, and instruction-level predicate/interpreter tests.
Core new-room coverage includes
schema save/reopen, blank and cloned workspace editing, semantic and cyclic doors, surviving-door
identity after deletion, normal delta/state export, and native ROM reparse.

The following remain acceptance requirements for their corresponding future features, not for the
completed state editor or core new-room creation:

- Migration tests for any future incompatible project-schema change.
- Emulator smoke tests for event, boss, equipment, incoming-door, and default branches.
