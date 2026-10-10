# ASM Reference Workspace

SMEDIT's **ASM** tab begins with a deliberately read-only reference layer and can
now opt an editable project into an isolated **Project ASM** workspace. The
reference turns the exact annotated Super Metroid disassembly used by the parity
suite into an IDE-like knowledge browser without making either an ASM checkout
or a second ROM-selection step a prerequisite for ordinary ROM editing. Project
ASM establishes safe source ownership and editing and makes that source the build
base as soon as it is enabled. Ordinary projects remain on the existing loaded-ROM
patch path. A clean Project ASM workspace can temporarily return to **Loaded ROM**;
once a saved or unsaved source edit exists, source mode is required so the edit can
never be silently omitted from an export.

## User workflow

1. Open a ROM normally in SMEDIT.
2. Open the **ASM** tab and choose **Download ASM Reference**.
3. SMEDIT downloads the pinned `sm_disassembly` source revision and extracts all
   1,130 NTSC binary assets from the ROM already open in memory.
4. Browse banks as chapters, expand a bank into its authored `;;; $address:
   description ;;;` sections, search bank names, labels, addresses, or literal
   source text—including instructions, values, and comments—and click a label operand
   to follow it to its definition. Only the active bank chapter is expanded by
   default, and selecting it again collapses it. Back/forward navigation
   preserves the trail. At a definition, the compact **References** bar reports
   every resolved use; **Prev**, **Next**, and **Show all** navigate callers and
   data references without losing the active symbol. Definition names and source
   gutters are clickable, so reference browsing also works from search/address
   landings instead of only from an operand. Literal-text results land on the
   exact matching column, and ASM back/forward history retains that caret target.
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
    The reverse bridge covers room headers and complete runtime-state resources,
    door definitions, library-background programs and payloads, tileset graphics,
    metatiles, palettes and CRE, enemy/boss and Samus graphics, pause-map tilemaps
    and graphics, editable text entries, and music song sets. Intentional aliases
    produce explicit destinations instead of silently choosing one owner.
11. Use the persistent workspace control at the bottom of the sidebar and choose
    **Enable Project ASM** to create an isolated source workspace beside the
    `.smedit` file. Its collapsed summary always reports whether Project ASM is
    disabled, enabled, or needs repair; expand it for provenance and asset-sync
    details. Enabling Project ASM automatically selects the **ASM source** build and
    opens **Project (editable)**. Switch between **Original (read-only)** and
    **Project (editable)** at any time; the original view also provides an explicit
    shortcut back to editable source. Project source offers syntax-aware
    editing, a compact `Save *` dirty indicator, `Cmd/Ctrl+S`, buffer
    revert, and a confirmed **Restore original** action. Enter inherits the
    current instruction indentation and opens an instruction column after a
    label. **Tab** advances a caret to the next four-column stop or indents the
    selected rows; **Shift+Tab** removes up to one indentation level. Paste and
    multi-line replacement are deliberately never reformatted.
    Saving persists both the source override and the `.smedit` project, reparses
    the complete source index, and immediately checks the complete source tree
    for unsafe data literals. Label navigation and references therefore reflect
    the saved project text. Asar `!defines` such as `!SPF` are colored and
    navigate to their declarations like ordinary source symbols; use
    **Cmd/Ctrl + click** while editing so an ordinary click remains available for
    caret placement and selection.
    With literal source text in the Find field, **Replace in project…** opens an
    explicit project-wide preview. It reports every occurrence and affected file,
    shows before/after rows, and offers a case-sensitive option. Applying the
    preview only stages unsaved bank buffers; it does not touch the source tree,
    project file, or ROM until the normal Save/Build boundary. Replacement text
    is literal, so Asar punctuation such as `!`, `$`, and `\\` is never treated
    as a regular-expression or replacement escape.
    An **EDITED** badge in the bank tree means the source intentionally differs
    from the immutable vanilla snapshot; only `Save *` or an explicit
    **unsaved** count means a buffer or project still needs to be written.
    Use **+ Module** above the banks for new standalone project code. Modules are
    immediately portable authored files, can be renamed or deleted from their
    editor bar, and use the sidebar arrows for explicit include order. Their
    empty template is safe by default: adding emitted code still requires an
    intentional `org`/free-space target and a hook from existing engine code.
12. **ASM source** is now the active ROM build base. A project with no source
    edits may choose **Loaded ROM** to use the established patch-only path. The
    first new source edit selects ASM source again; Loaded ROM is unavailable
    while any saved or unsaved source edit exists, and export independently rejects
    stale projects that would otherwise ignore a saved override. In source mode
    SMEDIT compiles both the
    immutable snapshot and project tree in isolated temporary directories,
    measures the authored delta, registers it as `asm-source:project`, and then
    plans the normal semantic-edit transaction. Fixed-range editor writes that
    fit wholly inside one known `incbin` are staged into temporary project
    `data/` overrides and assembled into the source base. They appear in ROM
    ownership as `asm-asset:<path>`. Inline ASM data, ambiguous aliases, and
    relocated/allocated writes remain in the normal post-compile transaction.
    SMEDIT requires the staged and unstaged routes to produce the same final ROM
    hash before export succeeds. Patches, conflict checks, validation, and
    atomic output remain shared with Loaded ROM mode.
    The ROM preview uses the same selection and performs the same build without
    writing an output file.

The application-level **Back** and **Forward** buttons beside **EMU** preserve
the workspace and selected resource across these jumps. For example, opening a
room from bank `$8F`, pressing Back, and then Forward returns to the exact ASM
bank/line and the same room respectively. Ordinary tab, room, tileset, sprite,
sound-track, map-area, and text-entry context joins the same bounded history.
ASM's own compact history remains available for fine-grained
source/asset/Library browsing.

When **Project** source is selected, the source pane is immediately editable;
there is no second per-file edit mode. **Save**, **Revert**, and
**Restore original** provide the explicit write and recovery boundaries. Large
banks are presented in bounded line pages so syntax highlighting, selection,
and scrolling remain reliable; one continuous right scrollbar addresses the
whole bank, the bottom scrollbar exposes long lines, and edits from every page
are merged into the same whole-file project buffer. The paging implementation
is intentionally hidden from the editing workflow; the toolbar does not expose
internal page ranges. Search, symbol, and diagnostic
navigation highlights the complete destination row with surrounding context;
the highlight then follows the editing caret. **Save** is deliberately a fast
persistence boundary: it atomically writes the source override, reparses its
index, saves the `.smedit` project, and performs fast source-literal validation.
It does not wait for Asar. The button becomes `Build*` whenever unsaved or
newly saved source has not produced a successful build; an explicit **Build**,
EMU launch, preview, or export performs the complete validated build. This keeps
ordinary source-saving responsive without allowing execution to use stale
bytes. Unsafe source fails before Asar is launched. Asar still
assembles its include tree as one program rather than incrementally compiling an
individual bank, but SMEDIT reuses an unchanged compiled base and unchanged
generated inputs. Successful builds add only a compact status to the existing
source header. Source-validation and Asar diagnostics use one collapsible strip
below the source editor, where it cannot obscure a linked line; located problems
open the exact source file with several lines of surrounding context rather than
adding a permanent Problems tool window. Every located error row is also shaded
red with an edge marker directly in source; warning rows use the corresponding
amber treatment, while the ordinary caret/navigation row remains blue. Failed
builds expand the drawer automatically, successful builds leave a compact status,
and its top divider can be dragged to resize a scrollable multi-problem list; the
chosen height is retained in global SMEDIT layout settings. Build diagnostics do
not open the unrelated ASM workspace/download controls in the sidebar.

Editable banks use a bounded 250-line text window over the whole saved buffer;
the continuous scrollbar hides that implementation detail. Caret movement does
not reparse the complete bank, and an open Source search is refreshed when its
query or saved source index changes rather than rescanning every bank on each
keystroke.

The **Library** and **Original (read-only)** source views are read-only. To turn a Library
example into a real edit, switch to **Source**, select **Project (editable)**, open or search
for the referenced bank/label, click into the source text, and save with
`Cmd/Ctrl+S` or **Save**. Enabling Project ASM creates the editable tree and selects
it as the build base. Loaded ROM remains available only while that tree has no
source edits; beginning an edit returns the project to ASM source automatically.

Source and hex text are selectable and copyable without giving up clickable
labels, mnemonics, or assets. The source tree, asset tree, source canvas, and hex
canvas expose draggable scrollbars for fast navigation. Mouse wheels and
two-axis trackpads scroll normally; **Shift + mouse wheel** scrolls editable
source horizontally, and holding the middle mouse button and dragging pans both
axes in read-only canvases. Mouse back/forward buttons, `Alt+Left` / `Alt+Right`, and
`Cmd/Ctrl+[` / `Cmd/Ctrl+]` follow navigation history, including transitions
between source and Library pages. `Cmd/Ctrl+F` moves focus to the current
Source/Assets/Library search field.

The Library is bundled and remains available before the external disassembly is
downloaded. Its nine SMEDIT-authored lessons are intentionally a field guide,
not a general computer-science course. Their selectable, syntax-highlighted
examples progress from literal formats, strings, tables, and bit masks through
width state, pointers, comparisons, calls, and SNES runtime patterns; mnemonics
inside the examples open their instruction pages. A capstone walkthrough traces
Samus's jump from `Make_Samus_Jump` to its paired fixed-point physics tables,
makes a reversible project-owned source edit, and defines the exact compilation
and ROM-diff checks that will eventually prove it safe. The complete 92-mnemonic index
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

An opted-in project's files live beside the project at
`<project-name>_smedit/asm/`. `original/` is an immutable, offline snapshot of
the pinned source and ROM-derived assets at opt-in time; `workspace/` is the
editable tree that future compilation will consume. Both generated trees are
ignored by the sidecar's own `.gitignore`; intentionally saved source changes are
mirrored under `overrides/src/`, which is small, reviewable, and suitable for
source control. A recreated local tree reapplies those overrides automatically.
Standalone project-authored routines and tables live under `modules/`, beside an
explicit `.order` file and, when needed, a small `.disabled` manifest. They
appear first in the Project Source tree and can be created, renamed, disabled,
re-enabled, deleted, and reordered without editing the pinned `main.asm`.
At build time SMEDIT creates a reserved gateway that includes those modules after
the vanilla source and before generated patch backends. Disabled modules remain
editable and retain their position, but are omitted from the gateway and source
validation until re-enabled. Both `modules/` and
`overrides/src/` are portable authored inputs; only their generated mirrors under
`workspace/src/project/` are ignored.
The ignored `build/` directory retains the latest Asar log and WLA symbol map so
compiled addresses and diagnostics remain available to the ASM workspace. These
are reproducible local products, never project-authored source.
The `.smedit` JSON records that ASM mode is enabled, its source revision, and the
ROM build-base choice. Existing non-ASM projects default to **Loaded ROM**; enabling
Project ASM selects **ASM source**. A clean workspace may switch back, but saved
source overrides make Loaded ROM invalid until they are restored.
Initial creation is staged and validated before activation. Repairing an
incomplete sidecar preserves the prior tree as a recoverable hidden backup rather
than deleting it.

Installation uses a staging directory and activates it only after the archive,
entry point, asset ranges, and counts validate. Refreshing assets uses the same
staged replacement. Archive paths, expanded size, entry count, and extraction
destinations are bounded before writes occur.

No Git or Python installation is required to download/browse source or extract
assets. A checked-in, data-only range manifest lets Kotlin perform extraction
directly. Source compilation requires exact Asar 1.81. SMEDIT release packages
carry a native build for their own OS/architecture; on first use it is copied
from the read-only application resources into
`~/.smedit/asm/toolchains/asar/1.81/<platform>/`, given executable permissions
where required, checked against the platform package's SHA-256 manifest, and
version-verified before use. Users therefore do not need
Git, CMake, or a C++ compiler. `SMEDIT_ASAR`/`smedit.asar` remains the highest
priority developer override, and source checkout/build into
`~/.smedit/asm/asar/` remains a final development fallback.

Each release runner builds Asar from the pinned revision used by SMEDIT, bundles
the GPLv3 license and distribution notice, then runs
`./gradlew :desktopApp:asarToolchainSmokeTest`. That ROM-free test resolves Asar
through the packaged-resource path, installs it into an isolated home directory,
verifies the 1.81 banner and native executable permission, assembles a synthetic
LoROM, checks its emitted bytes, and confirms WLA symbol output. It runs on the
macOS, Windows, and Linux GitHub Actions jobs before application packaging.
That manifest is regenerated from the pinned upstream `tools/rip_assets.py` with:

```bash
python3 tools/generate_asm_asset_manifest.py \
  parity/work/sm_disassembly \
  desktopApp/src/jvmMain/resources/asm-reference/asset-manifest.tsv
```

The manifest records paths and ROM ranges only; it contains no extracted bytes.

## Accuracy boundary

The global workspace is an annotated **vanilla-source reference**, not a general
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
door lists, library-background command streams, placed-object definitions,
pause-map tables, decoded text entries, discovered tileset catalog, enemy
species headers, and discovered music pointers. Source lines only offer an
editor destination for an **exact** address anchor; nearby context never
inherits a potentially false visual-editor link. Composite boss, Samus, and
shared pause-map asset names are used only where the engine has no single
semantic pointer for the complete visual source.

The ROM comparison has a different boundary from the extracted reference assets.
It does not infer changes from project JSON. It invokes the same transactional
export builder used by **Export ROM**, including preflight validation, patch
preconditions, write-conflict detection, generated hooks, allocation/relocation,
post-build verification, and write ownership. The resulting arrays remain in
memory; building or refreshing the preview writes neither a ROM nor the project
file. An explicit refresh keeps the snapshot model honest when the user continues
editing.

In **ASM source** mode, source compilation is not an untracked pre-step. The
immutable and edited trees are assembled separately; only their byte delta is
claimed as project source. Asar's generated SNES checksum/complement bytes are
excluded from source ownership because later deterministic writers may replace
them. Any patch, community sprite injection, or semantic exporter that tries to
write a different value over an authored source byte fails through the same
`RomWritePlan` conflict used by loaded-ROM builds.

Emulator launch and restart run this build on a worker dispatcher so Compose can
continue painting and accepting window events. While it runs, the toolbar,
bottom status bar, and emulator viewport show the current compiler/export phase;
duplicate Play/Restart requests are disabled. This removes the operating-system
wait cursor but does not weaken the clean-build contract. The current safe path
may still perform an immutable-baseline compile, a project compile for ownership
and planning, and a final project compile when generated source assets or patch
modules are present. The final pass reuses the immutable ROM compiled by the
first pass instead of assembling that same baseline twice.

Compiled bases also use bounded, session-local content-addressed caches. Their
keys contain the SMEDIT compiler-contract version, loaded-ROM identity, exact
Asar binary and version, immutable or compiled reference input, editable
`src/`/`data/` trees, and every generated source-asset and patch claim. An exact
project/source match can reuse the already prepared base and avoid both Asar and
the preliminary materialization-planning pass. A change handled only by the ROM
patch backend misses that whole-project shortcut but still reuses both compiled
bases, so Asar runs zero times and only planning plus post-compile export remain.
A saved ASM edit invalidates the project base, while a GUI edit mapped into an
`incbin` asset or a source-backed patch invalidates the generated-input base.
Cache entries are copied before use and are never accepted as the final ROM:
every Play/Restart still runs the current export transaction, conflict checks,
and final-ROM/parity validation. The caches are deliberately memory-only for
now, so restarting SMEDIT establishes a fresh trust boundary without adding
generated build artifacts to the project.

Editor-owned source assets follow an equally strict boundary. SMEDIT first runs
the complete validated export plan against the compiled source, then maps only
fixed writes wholly contained by one manifest asset into an in-memory override
of that project `data/` file. Allocator-owned ranges, the community-Samus
injection, inline source data, crossings, and intentional aliases are never
guessed. The working sidecar is not rewritten: overrides exist only in the clean
temporary build tree. Generated ranges are excluded from authored-source
ownership, reintroduced as `ASM_ASSET`/`asm-asset:<path>` claims, and the final
ROM must match the pre-materialization plan byte for byte. Unsupported domains
therefore retain the proven post-compile behavior instead of blocking source
mode or being silently reclassified.

### Authority and patch backends

SMEDIT does not try to reverse-edit arbitrary handwritten assembly. Each value
has one authority for a given build:

- Saved files under project `overrides/src/` are user-authored ASM.
- Saved files under project `modules/` are user-authored standalone ASM, compiled
  in the explicit order stored beside them when enabled. Disabled module source
  is preserved without contributing bytes or blocking the build. Module bytes receive an
  `asm-module:<name>` owner when Asar's source map identifies their range.
- Structured room, graphics, music, map, and patch settings in the `.smedit`
  model are GUI-authored data.
- Source generated from GUI data is a temporary, reserved build product. It is
  deliberately absent from the editable workspace, which effectively freezes
  that generated region against a second source of truth.

If user-authored ASM and a GUI feature target the same byte, the ownership plan
reports a conflict instead of choosing one silently. For reciprocal structures
such as room data, the safe future path is a shared typed codec—decode source or
ROM into the semantic model, edit that model, then encode a generated asset—not
text replacement inside a bank file.

Both build modes ultimately produce the same kind of output: a binary SNES ROM.
The difference is where an edit enters the pipeline:

```text
Loaded ROM mode
  loaded ROM
    → SMEDIT semantic edits and ROM-byte/IPS patches
    → validation
    → final ROM

ASM source mode
  ASM source + extracted data assets
    → generated assets and supported ASM patch modules
    → Asar compilation into a ROM
    → remaining post-compile ROM-byte/IPS patches
    → validation and parity proof
    → final ROM
```

In other words, ASM is an earlier build stage rather than a different final
format. ASM mode does not require every existing patch to acquire a source
implementation before it can be used.

Terminology matters at this boundary:

- An extracted **`.bin` source asset** is input to the disassembly, normally
  consumed by an `incbin` directive. Graphics and compressed room data are
  common examples.
- A **ROM-byte patch** is a set of writes to offsets in an already compiled ROM.
  IPS records and SMEDIT's fixed hex writes are examples.
- A generated **ASM overlay/module** is source input compiled by Asar before the
  remaining ROM-byte patches run.

All three contain or produce bytes, but they belong to different stages. The
documentation avoids calling ROM-byte patches “`.bin` patches” so `.bin` remains
unambiguous shorthand for disassembly source assets.

A patch can advertise a ROM implementation, an ASM implementation, or both,
but one build selects exactly one implementation. **Loaded ROM** uses direct ROM
writes. **ASM source** prefers an explicitly registered source implementation;
patches that have not migrated yet continue through the post-compile ROM backend.
Migration is atomic per patch family: if any required record cannot be represented
safely, the complete patch stays on the ROM backend for that build.
The two current ASM implementation levels are:

1. **Generated overlay** — validated write intents become a readable temporary
   Asar `org`/`db` module. This establishes source ownership without pretending
   byte patches are already symbolic source.
2. **Curated module** — a future patch-specific implementation can use labels,
   mnemonics, macros, and source-level allocation while keeping the same backend
   and parity contract.

Samus Physics is the first dual-backend patch. Its GUI remains authoritative;
Loaded ROM mode uses the established writer, while ASM mode compiles the
generated overlay and records those bytes as `ASM_PATCH`. The pre-materialized
ROM plan remains the oracle, so the final output must be byte-identical. This
lets patch families migrate incrementally rather than forcing an unsafe all-at-
once conversion.

## Architecture

- `AsmReferenceRepository` owns download, archive safety, transactional cache
  activation, ROM normalization, exact range extraction, and metadata.
- `AsmProjectWorkspaceRepository` owns transactional project-sidecar creation,
  immutable original/working-tree separation, bounded atomic source writes,
  trackable source overrides, modified-file detection, offline restore, and
  recoverable repair of incomplete workspaces.
- `AsmProjectCompiler` performs clean out-of-tree reference/project builds,
  preserves optional copier headers, captures Asar diagnostics, requires a valid
  emitted symbol map, applies size-checked staged data overrides, and returns
  separate minimal authored-source, generated-asset, and generated-patch ranges
  rather than an opaque ROM replacement.
- `AsmBuildArtifactRepository` retains the latest local Asar log and WLA map;
  `AsmBuildOutputParser` turns compiler messages into file/line diagnostics and
  `AsmWlaSymbolParser` indexes emitted labels and exact source-line addresses.
  Every retained report includes a fingerprint of the source tree that produced
  it, so repaired source can never inherit stale errors after reopening SMEDIT.
- `AsmSourceLinter` catches data literals that exceed their explicit
  `db`/`dw`/`dl`/`dd` storage width before Asar can silently keep only the low
  bits. Intentional truncation remains expressible with an explicit mask.
- `AsmSourceAssetMaterializer` projects eligible write-plan bytes into exact
  manifest-owned `data/` files, excludes allocations and ambiguous ownership,
  and leaves the project workspace unchanged.
- `AsmPatchBackendRegistry` explicitly declares which patch families have an
  ASM implementation; unregistered patches retain the ROM backend.
- `AsmSourcePatchMaterializer` converts validated writes for a selected generated
  overlay backend into temporary Asar source and separately owned `ASM_PATCH`
  ranges. Overlapping or out-of-ROM records fall back instead of being guessed.
- `AsmToolchain` pins and verifies Asar 1.81, preferring an explicitly configured
  executable, then the release-bundled native compiler, and only then using or
  provisioning a managed developer compiler.
- `AsmSourceParser` reads `main.asm` include order and descriptions, then indexes
  every source file, authored section, global label, scoped local label,
  cross-reference, and extracted asset. Reference resolution uses the same
  local-scope rules as label navigation and ignores comments and quoted strings;
  it is not a raw text search.
- `AsmAddressAtlas` is the shared bidirectional address layer. It converts
  canonical LoROM SNES/PC coordinates and resolves exact source anchors,
  contextual anchors, and extracted asset ownership without decoding assembler
  output heuristically.
- `AsmSemanticBridge` indexes exact addresses consumed by SMEDIT's production
  decoders and maps recognized source/assets back to Rooms, Tiles, Sprites, and
  Sound. The index is built once per loaded ROM so browsing assets does not
  repeatedly scan the room and species catalogs.
- `AsmWorkspaceState` owns reference/project selection, source edit buffers and
  dirty state, guarded multi-bank replacement staging, source/asset/library mode,
  cross-mode history, ROM freshness, preview state, progress, and safe navigation.
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

The separation between editable source and executable output is intentional.
Follow-up work can now be incremental:

1. Continue conservative editing assistance with source completion and explicit
   statement formatting that preserves expressions, comments, literal forms,
   and `.B`/`.W`/`.L` suffixes. Literal project-wide find/replace now has a
   reviewed, buffer-only preview, and Tab/Shift-Tab provide selection-scoped
   indentation without a whole-file formatter.
2. Add artifact sinks for semantic editors so room, graphics, map, text, and
   sound changes can materialize into project-owned `data/`/source before Asar.
   Today those edits still run safely after compilation through the shared
   transaction; moving each stable encoder before compilation is the next
   incremental step toward fully source-native assets.
3. Extend the reverse semantic bridge to remaining specialized title/menu,
   cinematic, and generated-runtime assets as those editors gain stable semantic
   selection entry points.
4. Use retained project symbols for emulator run-to-address, breakpoints,
   registers/stack inspection, and source-mapped crash diagnostics.

Arbitrary-hack disassembly and automatic source merge/conflict resolution are not
implied by the current editable workspace.
