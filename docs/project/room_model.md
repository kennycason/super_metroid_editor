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
generator-to-new-room output, and cross-project room import. Core project-owned room creation,
automatic pause-map ownership, and guarded deletion are implemented as the next layer on this model.

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

Existing-room selectors now support reciprocal typed AND/OR/NOT predicates and
source order, including atomic state add/duplicate/delete across all owned room
resource banks. GUI and ASM history restore membership, resources, sharing and
selection together; source values are not duplicated in project JSON. Invalid
drafts retain an explicitly stale last-valid preview when one exists, otherwise
report the selector mapping unavailable. Visual condition/state edits and
simulation lock until repaired; ASM editing and draft saving remain available.
This bounded typed format is not arbitrary executable-ASM recognition. A separate
explicit ASM-only backend preserves authored bank-`$8F` routines and generates
a carry/RTS adapter from verified assembler labels. Inline managed bodies now have
automatic packed placement and generated entry/end labels; explicitly placed
routines remain available for advanced source. Opaque conditions have no GUI
predicate: selector controls/simulation show a neutral “Managed in ASM” notice while
known room resources remain editable. Restoring supported typed source unlocks the
controls automatically. Full source envelopes, free-space/ABI checks and build parity protect
the interface, not arbitrary code behavior or hidden references. See the lifecycle contracts and
recorded UX questions in [the ASM workspace guide](../asm/reference_workspace.md#source-owned-state-lifecycle-and-projection-status).

Existing-room enemy populations and their GFX lists are reciprocal in ASM mode.
The first actor edit or **Room Info → Enemy Actors / Enemy Graphics → ASM** adopts
all effective state lists, retaining their ordering, eight per-actor words and
required kill quota. Both resource types live in one authored `bank_A1.asm` buffer
so an edit has one source/undo boundary; checked placements still target `$A1`
and `$B4` separately. GUI actor changes are selected-state edits and detach shared
lists independently. Raw shared-table edits affect every state referencing that
table. Existing transfer-only entries and intentional actors without GFX entries
are preserved rather than inferred from visible sprites. GUI additions add needed
species transfers subject to the four-entry/palette/staging-buffer checks.
Equal `GRAPHADR` pointers alone do not authorize sharing: the engine matches GFX
entries by species ID. Invalid drafts remain saveable, retain the last valid live
preview and block stale GUI writes/builds. Original source table, pointer, selector
or loader changes cannot be silently hidden by the authored replacements.
Project-created rooms and mixed old-ROM/source undo remain
explicit guarded boundaries. This is byte/ownership parity, not simulation of AI
or proof that arbitrary companion arrangements form a playable encounter.

Room Info's **Edit Objects** dialog is a normal visual field editor, not an ASM
mode selector. A separate right-aligned **ASM** action opens source only with
Project ASM enabled and the ASM source build selected. All editor-to-source links
use that same boundary; ROM mode keeps the standalone ASM reference browser but
does not imply that relocated room data matches reference addresses. ROM-mode field
edits retain the object's observed/selected state scope and use one undoable delta
transaction; station, gate and scroll compound changes stay in their dedicated map
controls. Source-owned dialogs retain their existing shared-list/expression rules
without ASM-specific titles. The Rooms bottom tab is an immediate global preference,
not project content; its four viewport scroll states survive main-view/subtab
navigation in the current session. Only the tab selection is persisted across app
restarts. Scroll offsets clamp naturally when content/viewport size changes.

Functional pattern strokes also use the reciprocal object source in ASM mode:
tiles and PLMs preview together, then publish one source/layout transaction on
release. Undo from either editor restores both, and private list scope stays
independent of layout sharing. Gate pairs and save-spawn resources participate in
the same operation; slots are reserved across the entire drag. Invalid/stale
source, exhausted slots and incompatible prospective All-states gate/door-cap
geometry cannot leave partial patterns behind. Source undo refuses to overwrite
newer layout/state edits. ROM mode retains its delta backend. Tile patterns do
not carry named scroll commands: use Add scroll trigger rather than stamping a
scroll trigger, and do not infer object movement from a tile selection. See
[compound pattern strokes](../asm/reference_workspace.md#compound-tile--object-pattern-strokes).

Ordinary room FX now uses the same reciprocal authority in ASM projects.
**Room Info → Default FX → ASM** or a door row's **ASM** link adopts effective
values into `bank_83.asm`; `asmFxSourceVersion = 1` retains ownership only and
common/state/door deltas retire with Undo/Redo. Complete ordered tables preserve
incoming-door branches, Default and the two-byte no-FX stop. State-local visual
edits detach shared resources; raw edits affect all aliases. New states inherit
their template. Typed preview does not depend on the full IDE index, invalid
drafts cannot be overwritten by GUI, and Build checks whole bank-$83 allocations,
state `+$06` pointers and actual Asar bytes alongside existing ROM patches.
The map previews Default, not entrance-specific or later runtime changes.
Mother Brain's after-Default runtime-indexed FX records, custom header/dispatch
layouts and added-room adoption are explicit guarded
boundaries. See [room FX authoring](../asm/reference_workspace.md#reciprocal-room-fx).

Initial Screen Scrolls are also reciprocal in ASM projects. A grid change or its
**ASM** link adopts that room's effective starting scrolls, including legacy common
and state overrides, into `bank_8F.asm`. `asmInitialScrollSourceVersion = 1` stores
ownership only; JSON scroll deltas are retired atomically. Adoption does not require
PLM adoption. Each state wrapper references a named `db` table; visual edits detach
the selected state, while raw edits update every alias of that table. New conditions
clone their template's table, and reorderings retain stable state identities.
Invalid drafts remain saveable but block GUI writes; last-good previews are marked
with the problem. Checked allocations, symbolic state pointers and compiled-byte
parity compose with the existing PLM/save/trigger and ordinary ROM-patch paths.
Room generation now combines geometry, initial grids and scroll-trigger cleanup
in one source/model transaction, including any needed adoption. Acknowledged
sharing updates camera data for the same layout's states; a private layout limits
the action to that state. Shared generation protects the union of all target
states' object and actor placements while retaining their separate lists; only
conflicting private geometry requires an independent layout.
Source undo restores retired GUI inputs as well as geometry; stale/malformed
inputs change nothing. Bulk Generate/Reset skips source-owned rooms. See
[compound room generation](../asm/reference_workspace.md#compound-room-generation).

Room resize now applies to every state's layout, authored initial grid and scroll
trigger commands in one source/model transaction. Shared and independent resource
identities and private geometry survive; new cells use air/Blue and cropped screens
are removed from command lists. Incoming Door ASM follows the final door graph at
export. Shrinks that strand objects, actors, entrances, incoming scroll writes or
save cameras are rejected before mutation. GUI and ASM undo restore dimensions,
source and prior ownership together, with guards against newer room, actor-source
and incoming-door edits. The existing 50-screen grid limit and added-room source
identity boundary remain. See [compound room resize](../asm/reference_workspace.md#compound-room-resize).

Themed generation now stages tileset changes, palette recolors and authored
Default FX together with geometry and camera data. The same layout-sharing scope
governs all targets, and FX subsets detach once without changing siblings or
entrance variants. One cross-bank undo restores source, room identity, geometry
and prior palette bytes/effect tag. Newer source, model or shared-palette edits
block the entire transition. Palette overrides remain global to a tileset and
still use the existing metadata/export backend; full reciprocal graphics assets
remain separate work. See [compound biome themes](../asm/reference_workspace.md#compound-biome-themes).

Single-room reset now retires every source ownership marker and restores native
header/state/layout/resource data through one cross-bank source/model transaction.
The clean record retains only native state identities. Undo from the canvas or
any participating bank restores the original room object, added states, sharing,
authored declarations and previous editor history. Newer source/model/dependency
edits block the entire undo, never just one bank. Custom references retain their
helpers; global assets and other rooms stay untouched. Added-room projects and
bulk reset remain guarded. See [compound room reset](../asm/reference_workspace.md#compound-room-reset).

ASM projects automatically recognize supported source-only PLM edits against the
immutable baseline; no per-room linking is required. For that bounded pilot the
Bank `$8F` declaration is the sole authority: GUI scalar fields and source edits
use one buffer, while the room canvas is a decoded preview. Earlier explicit
bindings remain supported; automatic authority is derived from source, not saved
as duplicate PLM deltas. When source is unchanged, existing room-editor edits and
state graphs remain authoritative and retain the established build path. Both
edited is an explicit conflict, not an implicit merge.

For supported GUI-managed rooms, **item → ASM** now migrates the effective PLM lists
to authored macros in `bank_8F.asm`. `asmPlmSourceVersion = 1` records ownership;
room/state PLM delta streams are retired, not replayed after compilation. Stable
state wrappers preserve shared/private lists. Every list uses direct `%PLMPopEntry`
rows, including multi-state rooms; **All states** writes each affected list once,
retaining the same collection ID in its copies. GUI edits and Save expand legacy
Common calls and prune unreferenced owned helpers in the same undo step, preserving
comments, live list identities, and definitions still referenced by custom ASM.
Save also checks already-saved managed banks, so old helper indirection is removed
without requiring a new placement edit; invalid drafts are preserved for repair.
Equal-valued independent objects are never implicitly linked. GUI placement edits
and ASM edit those same values; a state-local placement forks a shared list. A raw
row/scalar edit affects only that list and its whole-list consumers, not equal
copies elsewhere. The All states indicator reports current item membership rather
than an implicit link between independent rows. New
conditions inherit their template's complete list, including common changes and
local exceptions. The shared planner
allocates whole variable-sized resources, and the generated build input invokes
the authored macros at checked addresses. Actual Asar output must match the plan.

Source-owned object previews load independently of the full ASM IDE index. Verified
typed snapshots under generated `smedit/asm/projections/` accelerate reopening; they
are not a second persisted authority and never supply the ROM build's object values.
Relevant source, definitions, ROM identity, and state membership invalidate them;
unrelated editor changes do not. Missing/corrupt snapshots rebuild from source,
and invalid drafts cannot borrow an old cache as a current editable model.

Scroll triggers also have reciprocal named command resources in Bank `$8F`.
**Edit commands** edits this trigger in this state with copy-on-write isolation;
**ASM commands** opens its declaration, where shared-source edits affect all
references. Legacy custom command maps migrate with undo; cache snapshots retain
names, not allocation addresses. Build resolves pointers and checks command bytes
against actual Asar output. Initial room scroll tables and Door ASM remain
separate resources. See [scroll-trigger authoring](../asm/reference_workspace.md#reciprocal-scroll-trigger-commands).

Save stations now also pair PLM placement with source-owned fourteen-byte load
records. GUI spawn fields and raw authored words are reciprocal, with one Apply/
undo action and complete build claims. The area/slot record is shared across room
states; only the final GUI removal releases it. Native engine-owned entries and
foreign owners cannot be repurposed. Old GUI spawn overrides retire per adopted
slot with metadata-aware source undo. Cross-area save migration remains guarded;
see [save-station authoring](../asm/reference_workspace.md#reciprocal-save-station-spawns).

Original fixed-address links still have fixed-size restrictions. Managed lists
and named command streams support growth/shrinkage; changed resource links,
added-room projects and mixed source/GUI operations are guarded boundaries. Only
objects, named scroll commands, adopted save spawns, initial scroll grids,
ordinary FX tables, paired enemy/GFX tables, decoded layouts, ordinary header/state
scalars, typed selectors and existing fixed-identity DoorDefs have migrated.
Other resources keep their existing model/backend. This does **not** change the ordinary unlinked/ROM
editing model described below. See the [source/GUI ownership contract](../asm/reference_workspace.md#reciprocal-sourcegui-ownership--first-authoring-slice).

Door connections are room-wide source resources in bank `$83`, independent of
state ownership and of their separate executable arrival ASM. Ownership uses
`asmDoorSourceVersion`, not persisted decoded door values. Ordinary field edits retain native list order,
count and DoorDef addresses; GUI/source edits share exact literal
fields and reciprocal history. Door ASM opens as an editor-only staged bank;
the first actual edit adopts ownership and text atomically, without a separate
opt-in or dirty/build marker on inspection. Save/build inputs exclude staged
declarations. Explicit arrival creation also adopts DoorDefs in its single
cross-bank action when necessary. Full record/list/header-pointer claims include
unchanged bytes. Invalid drafts lock only door editing/building, not unrelated
room resources. Native exceptional geometry is preserved, while new geometry is
validated. Shared native DoorDefs use one canonical global declaration with explicit
GUI scope acknowledgement; all aliases remain at the same native address. A single
room Reset deliberately leaves that global resource intact. Private copies and
compound shared geometry still need review. Added rooms now have project-ID build
symbols, read-only Room Data navigation and reciprocal header/state scalars under
durable project-ID declarations. Other editable resource families and joint
new-room Resize/Reset/themes remain guarded. Incoming DoorDefs and proven scroll-only arrivals now participate
in compound resize/Reset, with symbolic private routines, checked byte/word
remapping and preserved native code/CPU context. Opaque geometry and externally
shared canonical arrivals require ASM review; unrelated editing stays available.
Cross-area moves now use
the coordinated source/map/save transaction described below. See
[door connection authoring](../asm/reference_workspace.md#reciprocal-door-connections).

Structural lists have no second JSON copy of their order or values.
An optional source list refers to stable Door_N identities; native records keep
their addresses and new records remain unresolved until checked allocation at
build time. Completed GUI Add/Remove operations automatically adopt the required
door/layout source and share source/canvas/map history. Removal checks every
state and modeled pointer consumer, remaps higher BTS indices while preserving
the elevator bit, and retains surviving identities/arrival names. ASM-first lists
need no layout opt-in; builds inspect every final runtime state's doorway indices.
Native Reset restores the original list. Symbolic state conditions, FX and save
spawns are covered below; stable added-room source identities remain separate work.
See [structural door lists](../asm/reference_workspace.md#structural-door-lists).

Symbolic incoming-door state conditions link to `(sourceRoomId, declarationId)`
rather than BTS position or an allocated pointer. The source argument is the
corresponding `SmeditDoors_*_Door_*_Entry` label; the condition picker lists live
incoming connections, including new/unbuilt records, and the simulator uses their
semantic identities. Typed AND/OR/NOT conditions remain reciprocal. Build planning
allocates the full door graph before resolving conditions, so address movement
and room iteration order do not change behavior. Only active connections leading
to that room are accepted. Retired or retargeted references show a stale-selector
warning, disable selector writes and block building without locking unrelated
room resources. GUI removal and external-owner Reset refuse referenced identities.
No additional Enable ASM prompt or build is needed to choose a new connection.
Arbitrary executable-code references remain separate work.

Entrance FX selectors and ordinary save spawns use the same stable entry labels.
The FX Add entrance picker and save Change entrance picker accept live, unbuilt
doors without an additional adoption prompt. FX edits/removal preserve independent
state tables; spawn/camera edits, Reset Auto and cross-area save migration preserve
the incoming identity. Zero placeholders cannot encode as Default FX or save doors.
The build resolves private copies after all DoorDefs are allocated; cached previews
revalidate cross-bank links, and missing/retired/retargeted bindings fail safely.
Numeric native pointers remain supported, with duplicate FX aliases rejected.
See [symbolic entrance data](../asm/reference_workspace.md#symbolic-entrance-fx-and-save-spawns).

Checked symbolic arrival references use marked bank-$8F routine bodies
and native `DoorASM_*` labels resolve through actual assembler symbols, never a
persisted preview/allocation address. Managed arrivals and predicates share one
packed, fully claimed code pool. Creation preserves known native dispatch context
and behavior with a tail jump, publishing the body and DoorDef word as one source
history group. Only the arrival address is managed in ASM; ordinary connection
fields remain reciprocal. Initial-scroll diagnostics explicitly omit authored
arrival execution, and room-only JSON export refuses to discard its source/code
dependencies. See [managed arrival ASM](../asm/reference_workspace.md#managed-arrival-asm-and-symbolic-connections).

Existing-room area moves are coordinated in ASM source mode.
Room Info and minimap area actions automatically adopt supported headers and
affected DoorDefs, then publish header/map placement, visible/reveal map cells,
incoming/outgoing cross-area flags and ordinary save migration as one shared
source/canvas/minimap Undo/Redo action. Save PLMs in every state retain their
independent lists and high parameter bytes; spawn records retain all seven words.
All-zero historical save releases may remain in earlier areas, but live records
must match the authored current area and every slot still passes native ownership
checks. Cold exports and projection caches derive that area from source, never
from transient GUI projections. No duplicate decoded values are persisted.
Custom arrival code, other connection fields, room index and state logic remain
unchanged. Added rooms, shared DoorDefs, special load records, slot expansion
and arbitrary geometry/code transformations remain separate work.
Reset after relocation now restores native room data/location, owned current/native
map/reveal cells, save declarations and affected cross-area flags in one
source/canvas/minimap transaction, including after reopen. Newer unrelated edits
and neighboring arrival code survive; occupied original locations, reused slots
and pending gestures block the whole reset. Required outgoing area corrections
remain if neighboring rooms are still moved. Raw header ASM edits do not trigger
hidden map/save migrations. See
[coordinated ASM area moves](../asm/reference_workspace.md#coordinated-asm-area-moves).

Same-area room drags now use the same atomic source/map history. An applied move
automatically adopts a supported header if needed and changes Map X/Y, visible
map cells and station-reveal cells together, leaving doors, saves, dimensions and
code untouched. Preview/cancel/no-motion operations never adopt or dirty source.
Authored positions are read before hit testing; stale geometry/map/build-mode
previews, occupied destinations and pending strokes are checked before commit.
Private state metadata and handwritten source are preserved. Source/canvas/map
Undo/Redo composes with area reassignment without duplicate JSON header values.
See [same-area map dragging](../asm/reference_workspace.md#same-area-map-dragging).

Ordinary header/state-scalar ASM links now open directly in the editable source
pane, just like DoorDefs. Before first typing, planned declarations are an
editor-only view: inspecting, selecting, saving, searching and building do not
adopt them. The first text change and room-wide scalar ownership/GUI override
retirement share one source/canvas Undo/Redo boundary. Invalid first drafts stay
saveable with last-good previews; ownership Undo restores the legacy tileset,
scrolling and selected-state preview without reloading the canvas. ASM-first
edits preserve unrelated canvases/history and undo absent room identities too.
Complete scalar byte claims also retain native-valued/comment-only ownership in
headless builds and reject competing ROM patches, including on unchanged fields.
Selector conditions/code, structural dimensions/pointers and map migrations
remain independent workflows; no source values are duplicated in JSON. See
[header/state fields](../asm/reference_workspace.md#reciprocal-room-headerstate-fields).

**Room Info → Layout → ASM** adopts effective L1/BTS/L2 into editable decoded
`dw`/`db` macros, retiring JSON tile deltas with one undo boundary. Painting and
raw code edit one asset; state wrappers retain complete shared/private resources.
The source pane's **Edit room** link selects a state using that layout. Build
validates and compresses it into checked placements. Generation/themes, functional
patterns, resize and reset remain reciprocal. See
[room layout source](../asm/reference_workspace.md#reciprocal-room-layouts).

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

Room PLMs have an explicit authoring scope. **Add new PLMs to → All states** is the default
for items, stations, gates, door caps and scroll triggers/extensions; **This state** is opt-in.
This preference only governs new placements. Existing objects keep their observed scope until
the object's membership menu selects **Edit this state**, **Edit all states**, or **Apply to all states**.
The badge reports actual membership, including partial membership, not the next-add preference.
Deleting a shared object removes its matching copies; the menu also offers explicit selected-state
and all-state removal. Distinct sibling variants/commands and incompatible gate/door-cap collision
geometry block a broad operation instead of being silently overwritten.

In ROM mode, **This state** writes the PLM
delta to the selected `RoomStateEdits`; **All states** writes a canonical remove/add pair to the
top-level `RoomEdits.plmChanges` stream and clears same-item overrides that would mask it in an
existing branch. Because this is room-wide data rather than a snapshot of state IDs, it also applies
to conditions created later. Every copy keeps one collection-bit parameter, so the game still treats
the item as one pickup. A conflicting different item at the same coordinates in any state blocks the
operation instead of being silently replaced. Authored ASM writes direct rows to each affected list;
new conditions clone their template's list, including explicit exceptions. Equal rows in independent
ASM lists remain independently editable in raw source. Save placements share one area/slot spawn;
last-reference removal alone releases it. Scroll placement and commands are one undo transaction,
and membership compares their commands, not an unresolved zero pointer. GUI shot-gate additions
pair their body and shot-block rows at one anchor in one operation, preserving existing open/closed
bodies. ROM-mode undo snapshots also retain save spawns and room/state command dictionaries.
ROM gate-pattern stamps compare cross-state collision geometry before the stroke modifies it;
their tiles still follow the layout scope, while PLMs follow their placement scope. Compound
tile-plus-PLM stamps in source-owned rooms remain guarded until their joint source transaction exists.
The serialized `itemScope`/`ItemStateScope` names remain historical storage names, not pickup-only semantics.

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

The room view's **Tile Meta** visibility choices are global SMEDIT preferences, not
project/room edits. Every toggle updates app config immediately and survives view
changes and restarts. Stable overlay names are stored rather than labels/ordinals;
old configs keep the existing defaults, while an explicit empty selection stays
all-off. A forced Layer 2 editing preview does not change the stored visibility choice.

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
- Separate live scope summaries for tiles/BTS and collectible items.
- `This state` / `All states` item placement and removal, plus `Apply to all` for an existing item.

`ELSE Default` is only the final fallback condition. It is not a base layer inherited by earlier
branches. The inspector and help text state this directly; always-present tiles use a shared layout,
while always-present collectible items use the room-wide `All states` scope.

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

ASM-source projects can author a new room's header/state scalars and decoded layouts
independently. Layout declarations use durable project IDs, not preview addresses;
Layout ASM opens as an editor-only preview with no enable confirmation or build
marker. First text editing atomically adopts all state layouts in memory; Save
writes the source. This retires GUI tile deltas while retaining the creation seed as the revert/clone
baseline. Painting, shared/private state layouts and source edits then use one authority.
Build resolves source layouts to the checked physical graph without saving those
placements or duplicating authored values in JSON. New-room compound generation,
Resize/Reset/themes and structural door-list changes after layout adoption remain
guarded until they can publish joint resource/source transactions. See the
[editable new-room layout contract](../asm/reference_workspace.md#editable-new-room-layout-data).

Creation also owns the room's pause-map footprint. Blank rooms receive an enclosed rectangle made
from vanilla minimap tiles; clones copy the source footprint when it exists and otherwise receive
the same generated outline. A requested footprint must be clear of room rectangles, map art, and
Map Station reveal data. Moving an older room with no visible map tiles generates an outline at its
new location rather than preserving an invisible map entry.

Whole-room deletion is intentionally narrower than room-state deletion. Only project-created rooms
can be deleted; core ROM rooms remain editable in place. Before deletion, SMEDIT previews the room,
map footprint, outgoing doors, authored states, placed objects/enemies, and save overrides that will
be removed. It refuses the operation while another room door, AreaSave override, incoming-door
condition, or door-specific FX entry references the room or one of its DoorDefs. SMEDIT never
cascades those changes silently. Intentional one-way doors and unrelated orphan rooms remain valid
warning-level topology rather than deletion blockers.

Current follow-ups are generator output into a new room, semantic room import, existing-ROM-room
door-list growth/removal, and managed ROM expansion when verified free space is exhausted. A
separate static template library is deferred because Clone current state already supplies the
practical template workflow.

## Development Project Format

The named `smedit-project` format is currently version 1. It includes the complete
current room/resource model, not one format revision per implemented feature.
Only the current explicit format is accepted; missing metadata, unknown fields
and invalid enum values are not silently replaced with defaults. Optional domain
settings still default normally. Project IDs are created once and stored explicitly.
The separate `versionMajor` and `versionMinor` fields label exported ROM builds.

Development projects are updated explicitly when the saved contract changes, rather
than maintaining automatic beta migrations and per-edit version stamping. The
checked-in active projects are normalized; historical archives/backups are preserved
as records, not silently reopened as current projects. See
[project format and offline normalization](project_format.md#project-format-and-versioning).

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
- The explicit named format/version and stable project identity are validated by one desktop/CLI codec.
- Offline normalization previews and verifies changes, with recovery copies on explicit apply.
- Active checked-in projects use the current model; old/markerless formats are rejected on Open.
- Serialization and byte/semantic equivalence tests cover current-format round trips.

### 2. State-scoped editing — complete for the current milestone

- Stable state IDs are carried through canvas/property edits and undo/redo.
- Copy-on-write export is implemented for level, enemy, enemy-GFX, PLM, scrolling, and FX data.
- Whole-layout ownership, shared/unique resource operations, copy/share/revert, and selection-to-state
  workflows are implemented without sharing state-owned PLMs, enemies, effects, or scroll changes.
- Collectible items can be promoted to a room-wide scope that covers current and future states while
  preserving one pickup bit; cross-state coordinate conflicts fail closed.
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

### 4. New rooms — lifecycle complete

- Blank and clone-current-state entry points, automatic minimap tiles, occupied-footprint checks,
  and guarded project-room deletion are implemented; generator/import output remains.
- Stable project identity, isolated workspace materialization, schema round trip, and physical
  room/door/state/resource allocation are implemented.
- Project-room door-list add/remove, semantic destinations, forward/cyclic resolution, BTS-safe
  removal, and existing reciprocal diagnostics are implemented.
- Header map coordinates and pause-map ownership are authored together. World-route/orphan
  discovery remains optional tooling; intentional one-way and disconnected hack content is not an error.

The prerequisite existing-door UX is now implemented: semantic room/entrance selection, bounded
screen coordinates, project-aware connection diagnostics, and non-destructive reciprocal/facing
warnings. The project-room connection editor reuses these diagnostics and assigns door-list
identities without asking the user to enter a BTS index, DoorDef pointer, or room address.

### 5. Symbolic ASM mode

- Known routine, project symbol, opaque pointer, and no-routine code references.
- Base-ROM/profile symbol tables and assembler/linker integration.
- Bounded, explicitly marked handwritten predicate import is implemented: control-flow/flag
  recognition produces a review proposal, and explicit conversion authors typed AND/OR/NOT selectors
  while retaining original code as comments. Recognition alone does not permit builds or visual edits.
  See `../asm/reference_workspace.md` for the supported numeric RAM/16-bit instruction contract.
- Executable ASM-only predicates have inline managed placement or an advanced explicit
  label/end-label binding, both using the tagged carry/RTS adapter backend. They are not typed GUI conditions, and unsupported import drafts
  are not silently built from stale projections. General custom selector-table schemas and
  arbitrary engine-format changes remain unsupported.

## Test Boundary

The completed existing-room milestone is covered by selector encoding/decoding, malformed-input,
full-ROM inspection, copy-on-write, shared/unique whole-layout ownership, layout relinking, add/delete/reorder,
simulator, graph-allocation, semantic round-trip, and instruction-level predicate/interpreter tests.
Core new-room coverage includes
schema save/reopen, blank and cloned workspace editing, semantic and cyclic doors, surviving-door
identity after deletion, normal delta/state export, and native ROM reparse.
Reciprocal new-room layout coverage adds non-mutating inspection/unchanged saves,
automatic first-edit ownership with reciprocal canvas/history restoration, raw/GUI
edits, independent states, invalid drafts, source history after preview relocation,
cold typed export, and mixed native/new real-Asar relocation parity.

The following remain acceptance requirements for their corresponding future features, not for the
completed state editor or core new-room creation:

- Migration tests for any future incompatible project-schema change.
- Emulator smoke tests for event, boss, equipment, incoming-door, and default branches.
