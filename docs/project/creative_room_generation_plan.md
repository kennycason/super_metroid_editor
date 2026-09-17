# Creative room and world generation plan

**Status:** Proposed replacement for the current learned room generator  
**Last updated:** 2026-09-14

## Decision

Do not spend another training cycle tuning the current masked-tile checkpoint as though it can
become a room designer. Keep it as an experiment and possible local-detail helper, but move room
generation to a hierarchical pipeline that plans structure first, validates traversal, and dresses
the result with coherent multi-tile motifs.

The immediate product target is **Generate a new room while keeping the current room's dimensions,
tileset, and doors**. Random-door rooms and complete-world generation follow only after this mode is
visually interesting and mechanically reliable.

## Honest schedule

A couple of hours is enough for a meaningful structural spike because SMEDIT already has seeded
cave, chamber, shaft, gallery, facility, settlement, and maze algorithms; door preservation; a
tileset profile; and the proposal gallery. It is not enough for the complete creative system.

| Deliverable | Focused engineering estimate | Expected result |
| --- | ---: | --- |
| Structural spike | 2-4 hours | Six visibly different collision blueprints using fixed doors and existing dressing; useful for proving direction, not art quality |
| Fixed-door vertical slice | 1-3 days | Graph-first layouts, archetype controls, basic traversal checks, diversity ranking, coherent foreground preview |
| Good fixed-door room generator v1 | 1-2 weeks | Extracted multi-tile motifs, stronger movement validation, themed foregrounds, visual regression suite, several feedback/tuning passes |
| Backgrounds and environmental composition | 3-7 additional days | Layer 2/background motifs and deliberate decorative zones; initial plants/props and compatible object suggestions |
| Random doors and new-room integration | 3-7 additional days | Planned door positions plus safe door records, caps/PLMs, reciprocal connections, and room allocation, subject to editor infrastructure |
| Complete-world generator | 3-8+ weeks | Area/room graph, progression locks, map placement, paired doors, role-conditioned room generation, validation, and substantial playtesting |

These are scope estimates, not promises. They assume one focused implementation stream, reuse of
the existing procgen/editor infrastructure, and quick visual feedback. The biggest uncertainty is
not writing the algorithms; it is evaluating and iterating until the rooms consistently look
authored across many dimensions and tilesets.

## What the current model actually learned

The current system is a local tile-restoration model, not a level designer:

- The dataset has 262 exported rooms but only 241 unique contents, spread over 23 tilesets. Seven
  tilesets have only one room. At world scale, vanilla Super Metroid provides one authored world.
- Training samples are random 32x32 crops. Of the 262 rooms, 139 are larger than 32 blocks on at
  least one axis, so training often omits their complete composition.
- The network predicts raw collision type, metatile/flips, and BTS with per-cell cross-entropy. It
  has no labels or objectives for a chamber, shaft, route, landmark, plant cluster, visual rhythm,
  or room purpose.
- A fully masked room gives the convolutional model coordinates, dimensions, area, tileset, and
  any preserved doors, but no global layout plan or latent design concept. Different seeds sample
  the same local distribution rather than selecting fundamentally different compositions.
- The postprocessor weights patterns from the source room eight times more heavily than general
  tileset patterns. This deliberately pulls even a full-room result toward the source aesthetic.
- Candidate ranking gives novelty only 3 points while density and familiar detail adjacency receive
  44 points. The highest-ranked result is therefore usually the safest, most familiar result.
- Connectivity repair carves generic tunnels after generation. That can make a flood fill succeed,
  but it creates missing corners and visually unplanned corridors.
- The training export contains Layer 1, raw/resolved collision, BTS, and type-9 door groups. It
  deliberately excludes Layer 2/backgrounds, door machinery, destinations, PLMs, enemies, FX,
  scrolling, and room states. The model cannot intentionally generate information it never sees.
- Four-neighbor flood-fill reachability is not Super Metroid playability. It ignores Samus's body
  clearance, jumps, falls, morph passages, hazards, and ability requirements.

More epochs, a larger convolutional network, a higher temperature, or more random seeds will not
fix those representation and objective problems.

## Product modes

Generation should expose distinct operations instead of treating them as strengths of one model.

| Mode | Hard constraints | Generated content |
| --- | --- | --- |
| Repair or remix | Existing room composition and gameplay objects | Local geometry, seam, or artwork alternatives |
| New room, fixed doors | Dimensions, tileset/theme, door anchors, protected fixtures | Entire blueprint, collision geometry, foreground art, and eventually background/decorations |
| New room, planned doors | Dimensions and optional theme/door count | Door positions, blueprint, geometry, artwork, and editor-owned door records |
| New world | Progression policy, size, area themes, required landmarks | World graph, room roles/sizes/locations, paired doors, then each conditioned room |

A numeric seed makes a chosen plan reproducible. It does not create creativity by itself; the
generator needs explicit plan variables that the seed can choose.

## Target architecture

```text
World or room intent
        |
        v
Coarse semantic blueprint
        |
        v
Movement-aware validation
        |
        v
Collision geometry and slopes
        |
        v
Multi-tile foreground motif dressing
        |
        v
Background, decoration, and object suggestions
        |
        v
ROM/editor safety validation and preview
```

Each stage owns one kind of decision. A later stage may reject or locally revise an earlier result,
but it must not silently redesign the room through broad repair carving.

### 1. Generation request and semantic blueprint

Introduce a source-independent request and intermediate `RoomBlueprint`. The request should contain:

- dimensions and seed;
- fixed door anchors or a door-planning policy;
- archetype: cavern, warren, shaft, gallery, arena, ruins, facility, settlement, puzzle,
  connector, item room, or surprise;
- traversal ability profile;
- openness, verticality, branching, platform density, hazard intensity, and decoration density;
- tileset/theme and optional landmark intent.

The blueprint should use semantic cells or regions rather than metatile IDs: solid mass, open space,
platform, slope boundary, hazard, breakable, door pocket, protected fixture, foreground-decoration
zone, and background zone. It should also carry a route graph, named chambers, landmarks, and the
reason each required connection exists.

### 2. Graph-first structural planner

Plan required paths before drawing terrain:

1. Convert fixed doors into entry pockets and route endpoints.
2. Choose an archetype-specific graph containing a primary path, optional branches, chambers, and
   one or more landmarks.
3. Embed that graph spatially at coarse resolution.
4. Expand edges into corridors, platforms, or shafts and nodes into shaped regions.
5. Add optional structure only when it cannot invalidate required traversal.

The existing `StructureAlgorithms` implementations are useful shape generators, but they need to
become graph-conditioned instead of generating a field and connecting it afterward. Existing
`BiomeGenerator`, `DoorPreservation`, `BiomeRules`, and `TilesetProfile` code should be reused where
its contracts fit.

### 3. Movement-aware validator

Replace passable-cell flood fill with a progressively richer movement graph:

- standing and morph-ball clearance;
- supported walking/running surfaces;
- falls and landing space;
- conservative jump and wall-jump transitions;
- slope and platform handling;
- hazards and destructible blocks;
- configurable ability profiles for bombs, high jump, space jump, grapple, speed booster, and
  other traversal mechanics.

The first version can be intentionally conservative. Every required door pair must have a valid
route for the selected profile. Optional advanced routes can be annotated rather than treated as
mandatory. Emulator-assisted checks may later supplement, but not replace, deterministic analysis.

### 4. Dataset v2 and motif atlas

Export the information needed by the planner and artist while preserving the current lossless raw
arrays:

- all available foreground and embedded Layer 2 data;
- structural door descriptors plus door entries, caps, and destinations;
- PLMs/items, enemies, FX, scroll data, room states, and protected gameplay fixtures;
- derived semantic collision and surface-orientation maps;
- room size, tileset, area, topology descriptors, and inferred archetype tags;
- diagnostic rendered images for human inspection and regression, not as a substitute for ROM data.

Extract variable-size motifs rather than only pairwise adjacencies. Candidate motifs include door
frames, convex/concave corners, ledges, arches, columns, pipes, vines, plants, rubble, slope
transitions, repeated wall bands, and larger background structures. Each motif needs semantic
placement requirements and edge sockets so it can join neighboring motifs legally.

Geometry should be learned across every room independent of tileset. Tileset-specific examples
should teach only how a shared semantic surface is rendered. This avoids asking a tileset with one
room to teach both level design and art style.

### 5. Constraint-based foreground artist

Dress complete surfaces and regions using motifs from the selected tileset/theme:

1. Place large landmarks and rare motifs.
2. Tile long surfaces with compatible bands.
3. Resolve corners, intersections, and slope transitions as coherent units.
4. Fill remaining interiors.
5. Repair only a narrow invalidated band around any structural edit.

Use a bounded backtracking/constraint solver for sockets and collision requirements. The existing
learned adjacency grammar and masked model may provide priors or tie-breaking, but they should not
choose the structural plan. Single-cell fallback is acceptable only for seams that cannot be filled
with a motif.

### 6. Background and environment composer

Handle visual storytelling as a separate stage conditioned on the completed room:

- divide the room into foreground, open gameplay space, distant background, and decoration zones;
- select compatible Layer 2/background motifs with deliberate density and focal points;
- place plants and props as grouped motifs on valid attachment surfaces;
- suggest enemies and PLMs only after geometry is stable;
- preserve or warn about room-state, FX, palette, VRAM, and engine limits.

The first release should preview suggestions without automatically writing risky gameplay objects.
Automatic object placement should be introduced per category with explicit validators.

### 7. Ranking, diversity, and evaluation

Validity is a hard gate, not an artistic score. Rank valid candidates with separate, visible axes:

- traversal validity and required-door coverage;
- repair amount;
- motif/socket validity;
- archetype adherence;
- visual rhythm and landmark presence;
- distance from the source room;
- pairwise diversity among the six candidates;
- distance from the nearest training blueprint;
- decoration/background coherence.

Select a gallery as a diverse set, not simply the six highest values of one score. A candidate that
is excellent but nearly identical to another should be replaced by the best candidate from a
different structural cluster. Keep snapshot renders and metric reports for a representative room
matrix spanning dimensions, door arrangements, and tilesets.

Cross-entropy validation loss is useful for local prediction but is not the release metric. Human
visual review and short playtests remain required until the automatic metrics correlate with room
quality.

### 8. Editor controls

The Generate panel should eventually expose:

- generation mode;
- archetype or Surprise Me;
- seed and reroll;
- creativity, openness, verticality, branching, hazards, and decoration;
- traversal ability profile;
- preserve/select/generate doors;
- foreground/background toggles;
- candidate metrics and warnings explaining why a result passed or failed.

The existing persistent proposal gallery and undo history remain the application boundary.

## Delivery phases

### Phase 0: structural spike (2-4 hours)

Goal: prove that the next pipeline creates meaningfully different room concepts before expanding
the dataset or training another model.

- Add an experimental fixed-door planner that consumes only dimensions, door pockets, archetype,
  controls, and seed—not the source silhouette.
- Reuse existing structure algorithms and tileset dressing.
- Create a required route skeleton first and condition structure around it.
- Generate more candidates internally and choose six from different structural clusters.
- Emit collision thumbnails and diagnostics for at least a horizontal room, vertical room, and
  multi-door room.
- Keep PLMs, enemies, and current backgrounds protected and unchanged.

Acceptance criteria:

- all six blueprints are visibly distinct from the source and from one another;
- the same request and seed reproduce the same gallery;
- existing door cells and protected fixtures are unchanged;
- all required door pockets belong to the intended passable network before any broad repair pass;
- tests cover determinism, door constraints, source independence, and gallery diversity;
- documentation labels foreground art as provisional and does not claim full playability.

### Phase 1: blueprint foundation (1-2 days)

- Define versioned generation-request and `RoomBlueprint` schemas.
- Refactor structure algorithms behind a graph-conditioned planner interface.
- Add archetype-specific route graphs, regions, landmarks, and parameters.
- Separate hard validity failures from aesthetic scoring.
- Add a diagnostic command that exports blueprint JSON, metrics, and rendered previews.

### Phase 2: fixed-door vertical slice (2-4 days)

- Implement the conservative movement graph and required-route validator.
- Generate structure by construction instead of relying on connectivity tunnels.
- Add structural diversity and nearest-candidate clustering.
- Integrate controls and explanations into the existing preview gallery.
- Exercise a representative matrix of room sizes, doors, and tilesets.

### Phase 3: dataset v2 and motif foregrounds (3-7 days)

- Extend the lossless exporter and manifest.
- Build semantic maps and a browsable motif-atlas diagnostic.
- Implement motif sockets and constraint-based surface dressing.
- Retile edited boundaries as complete motifs rather than individual cells.
- Add visual snapshots and motif-validity tests.

This is the phase expected to create the largest improvement in authored visual quality.

### Phase 4: backgrounds and environmental composition (3-7 days)

- Export and model available Layer 2/background information.
- Extract and place background/decorative motifs.
- Add plant/prop attachment rules and safe preview-only object suggestions.
- Validate engine and room-state constraints before enabling writes.

### Phase 5: learned global priors (optional, after the hybrid baseline)

- Train on semantic macro-cells or motif tokens rather than raw metatile IDs.
- Use whole-room context and an explicit latent/style code.
- Condition on doors, route graph, archetype, dimensions, abilities, and theme.
- Compare generated output against the procedural baseline using the same validity and diversity
  suite.
- Ship the learned planner only if it measurably improves composition or variety.

Potential models include a transformer over coarse tokens or a diffusion-style semantic-grid model.
With the vanilla dataset's size, retrieval and procedural augmentation will still be important.
Training more raw-tile denoisers is explicitly out of scope.

### Phase 6: planned doors and new rooms (3-7 days after editor prerequisites)

- Plan legal boundary door positions and orientations.
- Create paired door records, caps/PLMs, BTS indices, destinations, and reciprocal edits.
- Allocate or clone room headers and dependent data through the editor's transactional writer.
- Place rooms on the area map and validate overlap, reachability, pointers, and engine limits.

This phase depends on robust new-room creation and shared allocation work already listed in the
project roadmap.

### Phase 7: world generation (3-8+ weeks)

- Generate an area and progression graph with required abilities, locks, items, bosses, saves, and
  escape constraints.
- Embed variable-size rooms on the map without overlap.
- Assign each room a role, theme, dimensions, and paired doors.
- Generate rooms from those contracts.
- Validate progression reachability and absence of softlocks for every relevant inventory state.
- Perform batch emulator/playtest passes and iterative quality tuning.

Vanilla Super Metroid is one world-level example, so this should begin as a constraint solver with
designer-authored distributions. A user-supplied corpus of compatible ROM hacks could later expand
the learned world prior, but it is not required for the deterministic baseline.

## Immediate implementation boundary

Begin with Phase 0 and stop after producing the diagnostic gallery. Review those images before
committing to Dataset v2 or another model. The spike succeeds if the silhouettes look like six
different room ideas and all fixed doors are integrated intentionally. It does not need finished
backgrounds, object placement, or complete movement physics.

If the spike is promising, proceed through Phases 1-3 as the fixed-door v1 milestone. Do not begin
random-door or world work until fixed-door candidates consistently satisfy both visual review and
movement validation.

## Existing components to reuse

- `shared/.../procgen/BiomeGenerator.kt`: pipeline and protection boundary.
- `shared/.../procgen/StructureAlgorithms.kt`: initial shape algorithms.
- `shared/.../procgen/DoorPreservation.kt`: fixed-door detection and pockets.
- `shared/.../procgen/BiomeRules.kt`: seeded controls and style envelopes.
- `shared/.../procgen/TilesetProfile.kt`: learned legal tile vocabulary.
- `shared/.../procgen/TileDresser.kt`: provisional dresser and future fallback.
- `room_model`: local learned priors, dataset tooling, proposal format, and experiment harness.
- learned proposal bundle/gallery: candidate preview, comparison, apply, and undo workflow.
- transactional ROM writer: eventual door/new-room allocation safety boundary.

## Non-goals for the first vertical slice

- generating an entire world;
- creating new room headers or allocating ROM structures;
- automatically wiring newly generated doors;
- placing items or enemies automatically;
- claiming emulator-proven playability;
- training a larger raw-tile model;
- replacing source Layer 2/background data before Dataset v2 and its validators exist.

