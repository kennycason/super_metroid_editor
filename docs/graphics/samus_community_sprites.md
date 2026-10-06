# Community Samus sprite compatibility

## Finding

The relevant standalone editor is
[SpriteSomething](https://github.com/Artheau/SpriteSomething). Map Rando vendors both
SpriteSomething and the community catalog
[MapRandoSprites](https://github.com/blkerby/MapRandoSprites), then its sprite build
script passes catalog PNGs to SpriteSomething's command-line injector and produces
IPS patches. The source image—not the generated IPS—is therefore the useful community
interchange format for SMEDIT.

Map Rando also carries a pinned copy of SpriteSomething's Samus sheet layout for its
own Rust tooling. This gives us an explicit, versionable schema instead of asking
users to import an anonymous tile sheet and guessing its cells.

## Format contract observed in the current repositories

Research snapshot: MapRandomizer `b243223ba3bafdb3223fe482aafbf4ca554b46c0`,
MapRandoSprites `91fdbf43a4ccf41fc0bd4153eb98c5189bd38a25`, and SpriteSomething
`f3428d26c4299ed1f7f4e648de69d0e8d05f746a`. Map Rando's checked-in layout also
attributes that SpriteSomething revision.

| Property | Community contract |
|---|---|
| Source file | PNG |
| Canvas | 876 x 2543 pixels |
| Layout | 52 named rows |
| Named image regions | 637 |
| DMA-sequence entries | 570 |
| Catalog currently inspected | 130 sheets |
| Catalog metadata | Display name, authors, version, optional credits name/category |

The large sheet includes more than ordinary gameplay poses: palettes, gun-port
graphics, death, file-select, and ship regions also live in the layout. A valid
importer must use the named layout and its import rules; slicing a uniform grid would
silently corrupt data.

## Implemented decoder foundation

SMEDIT now bundles the pinned SpriteSomething layout schema (with its CC BY-SA 4.0
notice) and has a non-mutating JVM decoder in
`shared/src/jvmMain/kotlin/com/supermetroid/editor/rom/SamusCommunitySheet.kt`.
It reproduces SpriteSomething's row centering, inherited image definitions, shifts,
extra areas, scaling, alpha mask, palette intervals, and palette quantization. The
scaled palette block also matches Pillow's premultiplied-alpha bicubic behavior and
22-bit coefficient rounding byte-for-byte.

The decoder produces all 637 named logical images with transparent index zero and an
ARGB preview for each region. It reports invalid canvas/layout/region/palette data
without changing a project or ROM. A completely transparent 876 x 2543 sheet is
accepted as a valid edge case with a `FULLY_TRANSPARENT` warning rather than rejected.

Four real conformance sheets are provisioned into the ignored workspace:

| Fixture | Why it is useful |
|---|---|
| `samus_vanilla.png` | Baseline SpriteSomething extraction |
| `samus_invisible.png` | TarThoron's real Invisible Samus; unusual content but a valid structured sheet |
| `samus_outline.png` | Sparse/outline art and palette behavior |
| `samus_zero-mission.png` | Broad visual replacement across the full layout |

Run:

```bash
./gradlew :shared:communitySamusTest
```

The task clones/fetches pinned MapRandoSprites commit
`91fdbf43a4ccf41fc0bd4153eb98c5189bd38a25`, verifies the fixture PNG hashes, and
compares Kotlin's complete 637-region semantic fingerprint for each sheet with an
independent SpriteSomething/Pillow extraction. It also checks representative standing,
running, morph-ball, death, and file-select regions individually. The resulting files
are available for manual testing under
`parity/work/community/MapRandoSprites/samus_sprites/`; they are not committed.

Invisible Samus is not the same test as a blank PNG: its sheet retains intentional
layout/palette scaffolding even though the in-game character is invisible. The pure
unit suite separately constructs and accepts an entirely transparent sheet.

SpriteSomething can inject the decoded sheet into a ROM. Its Super Metroid path is
not merely a same-size overwrite: it can expand the ROM and relocate/rewrite graphics,
DMA tables, tilemaps, palettes, and supporting control data. SMEDIT should therefore
not pretend that a custom sheet maps one-to-one onto the vanilla 435 payloads.

## Recommended SMEDIT implementation

### Stage A — validate and preview

Add `Import community Samus sheet…` to the Samus workspace. The first version should:

- accept a PNG and require the supported sheet dimensions/layout version;
- decode all named regions through a checked-in, attributed schema;
- report missing/invalid regions by community name;
- preserve supplied metadata separately from pixels;
- preview Power/Varia/Gravity poses, special screens, and palettes before mutation;
- make no ROM or project change until the user explicitly confirms.

Stage A is complete. `SamusSpriteViewer` exposes **Open Community Sheet…** without
changing the project or ROM. A valid sheet opens in a clearly marked read-only
workspace with catalog name/category/authors when a sibling manifest is present,
compact validation metrics, searchable named-region groups, pixel previews, palette
intervals, the complete seven-row master palette, and explicit transparent-region
signals. Invalid files remain visible with guided errors and a one-click retry; the
normal ROM animations remain available through **ROM Animations**.

This is the safest first milestone and can be tested independently against the 130
catalog sheets without redistributing them.

### Stage B — native project source and round trip

Store the imported logical images plus their layout version as a project-owned Samus
source. Export a SpriteSomething-compatible PNG and require an import/export/import
semantic round trip. The editor can then provide pixel editing using community names
while keeping the vanilla ROM graph and the custom expanded graph distinct.

### Stage C — ROM injection/export

Implement a versioned writer compatible with the pinned SpriteSomething rules, with
expected-before checks and an exact ROM-diff allowlist. This likely becomes a custom
Samus graphics patch layered into SMEDIT's normal export, not hundreds of independent
vanilla in-place writes. Validate the resulting ROM in the emulator and retain the
original sheet as the editable source of truth.

## Compatibility decisions

- **PNG first.** It is what Map Rando's build consumes and what community authors
  actually publish.
- **Do not use IPS as the editing format.** IPS loses image names, authorship, layout
  version, and semantic ownership; it is a deployable result.
- **Do not make the editor depend on a live clone.** Pin/import the small schema and
  attribution needed for compatibility. Development conformance tests may provision
  ignored upstream checkouts under `parity/work/community/`.
- **Do not bundle the catalog by default.** Import user-selected files. Revisit a
  downloadable browser only after per-sprite authorship and redistribution terms are
  represented clearly.
- **Keep vanilla and expanded ownership explicit.** The new Samus parity manifest is
  the exact vanilla baseline; a community sheet may require a different output graph.

## Reproducible research references

- MapRandomizer: <https://github.com/blkerby/MapRandomizer>
- MapRandomizer submodules: <https://github.com/blkerby/MapRandomizer/blob/main/.gitmodules>
- Map Rando sprite build: <https://github.com/blkerby/MapRandomizer/blob/main/python/scripts/process_sprites.py>
- Map Rando's pinned layout copy: <https://github.com/blkerby/MapRandomizer/blob/main/rust/data/samus_spritesheet_layout.json>
- MapRandoSprites: <https://github.com/blkerby/MapRandoSprites>
- SpriteSomething: <https://github.com/Artheau/SpriteSomething>

The inspected development checkouts stay ignored under `parity/work/community/`;
none of their PNGs or generated patches belong in this repository.
