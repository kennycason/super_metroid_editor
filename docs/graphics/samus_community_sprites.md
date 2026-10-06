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
| Animation groups / variants | 41 / 202 |
| Timed animation frames | 1,747 |
| Positioned image placements | 2,714 |
| Catalog currently inspected | 130 sheets |
| Catalog metadata | Display name, authors, version, optional credits name/category |

The large sheet includes more than ordinary gameplay poses: palettes, gun-port
graphics, death, file-select, and ship regions also live in the layout. A valid
importer must use the named layout and its import rules; slicing a uniform grid would
silently corrupt data.

## Implemented decoder foundation

SMEDIT now bundles the pinned SpriteSomething layout and animation schemas (with their
CC BY-SA 4.0 notice) and has a non-mutating JVM decoder in
`shared/src/jvmMain/kotlin/com/supermetroid/editor/rom/SamusCommunitySheet.kt`.
It reproduces SpriteSomething's row centering, inherited image definitions, shifts,
extra areas, scaling, alpha mask, palette intervals, and palette quantization. The
scaled palette block also matches Pillow's premultiplied-alpha bicubic behavior and
22-bit coefficient rounding byte-for-byte.

The decoder produces all 637 named logical images with transparent index zero and an
ARGB preview for each region. It reports invalid canvas/layout/region/palette data
without changing a project or ROM. A completely transparent 876 x 2543 sheet is
accepted as a valid edge case with a `FULLY_TRANSPARENT` warning rather than rejected.

`SamusCommunityAnimationCatalog` maps those named regions through SpriteSomething's
matching animation manifest instead of inferring animation order from filenames. It
preserves the source timing, displacement, cropping, flipping, and reversed layer order
used for multi-part poses and cannon-port overlays. Each variant is rendered onto one
union canvas so pose movement stays stable while playing. The only four unresolved
manifest images are `optional_ship_*` pieces supplied by SpriteSomething outside the
community PNG; the UI marks that WIP ship preview explicitly.

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
running, morph-ball, death, and file-select regions individually. The vanilla fixture
is then assembled through all 202 manifest variants; every sheet-owned animation must
render non-transparent pixels, with only the WIP external-ship variants exempted. The
resulting files are available for manual testing under
`parity/work/community/MapRandoSprites/samus_sprites/`; they are not committed.
The same bootstrap downloads four small, hash-pinned Map Randomizer IPS oracle files
into `parity/work/community/MapRandomizerPatches/`; it does not clone Map Randomizer or
copy any patch into the application.

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

Stage A is complete. `SamusSpriteViewer` exposes **Import Local PNG…** without changing
the project or ROM. A valid sheet opens directly into the familiar animation-first
workspace, with 41 named groups, human-readable direction/variant controls, exact
SpriteSomething timing/composition, and the same SMEDIT animation player used by ROM
Samus, including frame PNG, animation GIF, and sprite-sheet export. **All Frames** is a
responsive visual grid of the 637 decoded regions; selecting
a card opens its dimensions, palette interval, and larger pixel preview without
distorting its aspect ratio. Raw validation metrics, search, palettes, and named-region
diagnostics live under **Source Details** instead of dominating the normal workflow.
The sheet name, author, category, and project/read-only state remain visible above the
workspace. Invalid files remain visible with guided errors and a one-click retry; the
normal ROM animations remain available through **ROM Animations**.
When previewing a different catalog sheet, its identity and the current project Samus
share one contextual header instead of stacking two source banners. The project name,
ROM-ready state, and **View** action remain visible without competing with the preview.
When a project already owns a community Samus source, opening the Samus workspace
loads that source and its mapped animations automatically; **ROM Animations** remains
an explicit comparison view rather than the misleading default.

All Samus animation, community preview, gallery, catalog, status, and action text uses
SMEDIT's semantic font tokens. The global Small/Medium/Large/Larger setting therefore
updates this entire workspace rather than leaving imported-sheet controls at fixed
pixel sizes.

This is the safest first milestone and can be tested independently against the 126
manifest-listed sheets (130 PNGs are present in the pinned checkout) without
redistributing them.

### Stage B — native project source and round trip

Stage B is complete. A validated local or catalog PNG can be promoted with **Use in
Project**. The project embeds the original PNG bytes, SpriteSomething layout ID,
SHA-256, display name, authors, category, catalog name/version/revision, and upstream
source URL. Logical images remain derived through the exact decoder rather than being
duplicated as 637 separately serialized blobs. This keeps the project portable and
makes **Export Source PNG…** byte-for-byte lossless; decode/export/decode therefore
also preserves the complete semantic sheet.

The Samus workspace shows the active project source independently from the current
preview. **Restore Base ROM Samus** removes only this project layer after confirmation;
it never edits the imported file or reverse-patches a ROM. Because normal export starts
from the project's immutable base ROM, a later export naturally returns to the base
ROM's Samus graphics.

### Downloadable catalog

The Samus workspace also exposes a searchable **Community Catalog** backed by
MapRandoSprites' existing manifest. SMEDIT resolves upstream `main` to an exact commit,
loads its names/categories/authors/versions, and downloads a PNG only after the user
chooses **Download & Preview**. Every download must pass the same 876 x 2543 / 637-region
validation before it enters the cache.

Catalog metadata and selected PNGs are cached under
`~/.smedit/cache/community-samus/`. An unavailable network falls back to the last valid
manifest and any already-downloaded sheets. Projects do not depend on that cache after
**Use in Project**, because their chosen source is embedded with its hash. Local PNG
import remains available beside the catalog.

Downloaded catalog entries also show a sixteen-pose pixel-art lineup directly in the
catalog card, with the sprite name and artist beneath it. The showcase uses fixed-size
image cards—four columns by four rows at normal widths and additional rows when the
panel narrows—so resizing the editor never compresses, inflates, or squashes the
sprites. Only those sixteen small decoded images are retained for the card; the complete
637-region decode is released after building the showcase.
Intentionally invisible sheets receive an explicit transparent-pose message. Catalog
headings, list names, artist lines, metadata, badges, and actions all use SMEDIT's
shared semantic font sizes and react immediately to the global
Small/Medium/Large/Larger setting.

Catalog selection is intentionally staged: **Download Preview** validates and caches
the sheet while remaining on the catalog page; after the sixteen-pose showcase loads,
**Use in Project** installs it without leaving the catalog. **Open Detailed View** is a
separate opt-in action for the complete animation workspace.

No community PNG is bundled with SMEDIT, and the UI keeps each sheet's artist credit
visible. Public submission/upload is intentionally separate from local import and will
require an explicit authorship/permission contribution flow.

### Stage C — ROM injection/export

Stage C is complete for the pinned 126-entry catalog. When **Use in Project** promotes
a catalog sheet, SMEDIT also downloads that sheet's generated IPS from exact
MapRandomizer commit `b243223ba3bafdb3223fe482aafbf4ca554b46c0`. The project embeds
the IPS bytes, their SHA-256, provider revision, source-sheet SHA-256, exact clean-ROM
SHA-256, and the 3 MiB → 4 MiB size contract. It therefore remains buildable offline
and does not depend on the global download cache.

Export accepts the artifact only when all hashes and sizes match. It expands a clean
base ROM to 4 MiB, parses the IPS strictly, and stages the bytes that actually differ
from that clean base through the normal `RomWritePlan`. Untouched gaps and unchanged
padding carried inside broad IPS records remain available to compatible hacks. Other
patches/graphics cannot silently overwrite an
actual Samus IPS byte; a real collision aborts the export. **Restore Base ROM Samus**
removes the PNG and artifact together, so the next build naturally starts from the
unchanged base ROM.

SpriteSomething's new fourth MiB uses zero-filled banks `$E0–$FF`, unlike vanilla's
`$FF`-filled free regions. SMEDIT recognizes that convention only while exporting a
verified catalog artifact and can place later project-owned graphics into the unused
trailing portion of those expanded banks. Vanilla banks keep their existing `$FF`
allocation rules, and all resulting allocations still pass through the write plan.

Directional and hold-Aim-Down Spider Ball remain compatible with a catalog Samus.
Those patches normally add a vanilla-derived active-state tint in Samus-owned graphics
banks; when a catalog character is active, export deliberately keeps the catalog's own
morph-ball art and applies the Spider Ball movement, item, menu, and persistence records.
Its vanilla pose-table guard is also replaced with an adaptive hook into the catalog
injector's shared pose routine. The export log calls out this compatibility path rather
than allowing either feature to overwrite the other.

Custom item placements keep their patch dependency regardless of which Samus source is
active. In particular, `$F200/$F204/$F208` are Spider Ball PLMs implemented by either
Spider Ball patch; they are not self-contained room data. Disabling both Spider Ball
variants while one of those pickups remains placed can freeze new-game room setup at
game state `$1F`, which appears as a black screen after Start. The patch toggle now
refuses to disable the final owner of a placed custom item, and export preflight blocks
older already-invalid projects with the exact room, PLM, and patch choice. Catalog
Samus and Spider Ball are intended to remain enabled together—the adaptive export path
above resolves their former graphics collision.

Run the exact four-sample injection oracle with a clean unheadered ROM:

```bash
export SMEDIT_TEST_ROM='/path/to/clean/unheadered/Super Metroid.sfc'
./gradlew :desktopApp:communitySamusRomTest
```

Vanilla, Invisible, Outline, and Zero Mission exports must byte-match independently
applied pinned Map Randomizer patches and their hard-coded 4 MiB output hashes.

Arbitrary local PNGs deliberately remain **SOURCE ONLY**. A native image-to-ROM writer
is still needed before a local/private sheet (or future pixel editing) can become
ROM-ready without a catalog-generated artifact. This distinction is visible in the UI
and source-only projects fail export instead of silently keeping base-ROM Samus.

## Compatibility decisions

- **PNG first.** It is what Map Rando's build consumes and what community authors
  actually publish.
- **Do not use IPS as the editing format.** The original PNG and metadata remain the
  editable source of truth. A pinned IPS is stored only as the reproducible deployable
  result for a verified catalog entry.
- **Do not make the editor depend on a live clone.** Pin/import the small schema and
  attribution needed for compatibility. Development conformance tests may provision
  ignored upstream checkouts under `parity/work/community/`.
- **Do not bundle catalog artwork.** The browser downloads user-selected files directly
  from the attributed upstream catalog and caches them locally. Private/local sheets
  never need to be published.
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
