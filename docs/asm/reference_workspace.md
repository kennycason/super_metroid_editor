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

Editor-to-source **ASM** shortcuts appear only when **Project ASM** is enabled
and **ASM source** is the active build mode. This rule covers Room Info, selected
objects/doors/saves, scrolls, FX, tilesets, sprites, enemy stats, minimap, text and
sound. Downloading the reference alone does not enable these links; returning a
clean workspace to **Loaded ROM** hides them again. ROM edits can relocate data
without updating reference source, so those shortcuts must not imply a mapping in
patch-only projects. The main **ASM** tab, reference navigation and Library remain
available in either mode.

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
    Undo/Redo, and confirmed recovery actions under the source settings cog.
    **Discard unsaved changes** returns to the last save, while **Restore
    original bank ASM** removes the project override and returns to the pinned
    source snapshot. Enter inherits the
    current instruction indentation and opens an instruction column after a
    label. **Tab** advances a caret to the next four-column stop or indents the
    selected rows; **Shift+Tab** removes up to one indentation level. Paste and
    multi-line replacement are deliberately never reformatted.
    Saving persists both the source override and the `.smedit` project without
    waiting for a full index rebuild. The bottom status bar shows **Indexing ASM…**
    and then **Checking ASM…** while background work refreshes navigation and
    checks saved files for unsafe data literals. Editing remains available;
    newer typing stays unsaved, and obsolete jobs cannot replace a newer index
    or build report. Asar `!defines` such as `!SPF` are colored and
    navigate to their declarations like ordinary source symbols. Resolvable
    references retain syntax colors without persistent underlines; hovering any
    navigable token changes the text cursor to a hand. Use **Cmd+D** on macOS or
    **Ctrl+D** on Windows and Linux to open a definition. Cmd/Ctrl-click and
    middle-click remain convenient pointer equivalents, while ordinary click
    only moves the caret or changes the selection and never recenters the source.
    **Go to Definition** is also in the editor's right-click menu. Literal
    `incsrc` filenames (quoted or unquoted) open the included source in the current
    Project/Reference workspace, relative to the containing file. Nested paths,
    `..` segments within the source tree and unsaved include operands are supported;
    missing files and expression-based paths are never guessed by basename.
    Read-only source includes are clickable too. **Find Usages**
    uses **Cmd/Ctrl+U** and immediately expands the in-place result list; labels
    and macros share the same usage count, Prev/Next cycle, and navigation history.
    Editable-source pointer navigation retains its measured text layout across
    same-page history moves. If a genuinely new page is still being measured,
    the first definition gesture is replayed as soon as that layout is ready
    instead of being silently discarded.
    Completion opens automatically after two typed characters or explicitly with
    **Cmd/Ctrl+Tab** or **Show Completions** in that menu. **Cmd/Ctrl+B** invokes
    Build, and **Cmd/Ctrl+R** saves the active ASM source/project as needed and
    opens or resumes the emulator through the normal validated ROM build path.
    **Cmd/Ctrl+Left** and **Cmd/Ctrl+Right** move through ASM navigation history.
    Standard **Cmd/Ctrl+X**, **C**, **V**, and **A** text editing remains native.
    Size-qualified mnemonics preserve and complete `.B`, `.W`, and `.L`
    rather than collapsing back to a bare opcode. It offers 65C816 instructions,
    Asar directives, `!defines`, global labels, only the `.localLabels` valid in
    the current scope, and indexed `%macros` with their parameter signatures and
    source locations. Inside a call, a compact caret-adjacent signature tracks
    the active argument while respecting nested expressions and quoted commas.
    Accepting a macro completion inserts its parentheses and leaves the caret
    inside. Macro calls use the same definition navigation as labels.
    Disabled modules are excluded from active completion so the editor does not
    suggest source that the current build cannot see. Use
    Up/Down and Enter/Tab to accept or Escape to dismiss it. The completion
    engine reuses the saved project index rather than reparsing a large bank on
    every keystroke.
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
    immediately portable authored files. The source-header settings cog changes
    the module name and description; the description is a source-controlled,
    assembler-safe comment inside the `.asm` file. Destructive module deletion
    lives inside that settings dialog and still requires a separate confirmation,
    while the sidebar owns the single ON/OFF toggle and explicit include-order
    arrows. Their
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
    ownership as `asm-asset:<path>`. Inline ASM data and ambiguous aliases remain
    in the normal post-compile transaction. Existing-room edits now have an
    additional atomic generated-ASM backend: the proven room exporter first
    plans the complete final graph, including variable-sized allocations and
    every pointer update, and SMEDIT assembles that exact graph before export.
    Project-created rooms retain the ROM backend until they have stable source
    identities independent from their preview and allocated room IDs. SMEDIT
    requires the staged and unstaged routes to produce the same final semantic
    ROM hash before export succeeds. Patches, conflict checks, validation, and
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
there is no second per-file edit mode. Its compact gray source header combines
Back/Forward, file or module identity, **Save**, and the source or module
settings cog; it does not spend another row listing keyboard shortcuts. Build
is project-wide, so its single **Build** button lives in the application toolbar
beside **Run** and **EMU** whenever Project ASM is enabled.
Right-click a bank, bank section, search result, or non-module source entry and
choose **Open in Split Right** to inspect both locations side by side. Each
column retains its bank/section, caret, scroll position, and navigation history,
while both views edit one shared document buffer. Opening the same bank in both
columns is therefore safe and changes appear in each view. The editor menu also
provides **Split View** and **Close View**; source headers expose a close-view
button while split. Project modules remain single-view inputs. **Cmd/Ctrl+Z**
and **Cmd/Ctrl+Shift+Z** provide bounded, delta-backed Undo/Redo without retaining
whole copies of large banks.
The current address, PC offset, line count, and compact build state live in the
application's existing bottom-right status bar. **Restore original** provides
the recovery boundary for a modified pinned source file. Large
banks are presented in bounded line pages so syntax highlighting, selection,
and scrolling remain reliable; one continuous right scrollbar addresses the
whole bank, the bottom scrollbar exposes long lines, and edits from every page
are merged into the same whole-file project buffer. The paging implementation
is intentionally hidden from the editing workflow; the toolbar does not expose
internal page ranges. Search, symbol, and diagnostic
navigation highlights the complete destination row with surrounding context;
the highlight then follows the editing caret. **Save** is deliberately a fast
persistence boundary: it atomically writes the source override and saves a detached
snapshot of the `.smedit` project. Disk writes run off the UI thread. Indexing and
saved-file literal validation run afterward, coalescing multi-bank saves into one
index rebuild. Source Save does not regenerate optional graphics PNGs or refresh
the entire bundled-patch catalog; explicit Project Save retains graphics exports
and pattern-library saving. It does not wait for Asar. The button becomes `Build*` whenever unsaved or
newly saved source has not produced a successful build; an explicit **Build**,
**Run**, preview, or export performs the complete validated build. **EMU** only
opens or closes the emulator window; Run builds if needed and starts play. This keeps
ordinary source-saving responsive without allowing execution to use stale
bytes. Unsafe source fails before Asar is launched. Asar still
assembles its include tree as one program rather than incrementally compiling an
individual bank, but SMEDIT reuses an unchanged compiled base and unchanged
generated inputs. Successful builds update only that compact global status.
Source-validation and Asar diagnostics use one collapsible strip
below the source editor, where it cannot obscure a linked line; located problems
open the exact source file with several lines of surrounding context rather than
adding a permanent Problems tool window. Every located error row is also shaded
red with an edge marker directly in source; warning rows use the corresponding
amber treatment, while the ordinary caret/navigation row remains blue. Failed
builds expand the drawer automatically. Located error rows and the failed-build
heading link to source (the heading opens the first located error); the disclosure
arrow still collapses the drawer. Explicit file/line locations from room-data
validation are retained alongside Asar locations. The top Build button uses a
subtle error tint while the current build report is failed. Successful builds leave
a compact status, and the drawer's top divider can be dragged to resize a scrollable
multi-problem list; the chosen height is retained in global SMEDIT layout settings.
Build diagnostics do not open the unrelated ASM workspace/download controls in
the sidebar.

Clicking Build from another editor page in ASM mode opens ASM when the build
fails, revealing the first located error and the diagnostics drawer. General
patch/planning failures still open the drawer even when they have no source line.
This transition is recorded in global navigation history so Back returns to the
previous editor page; a successful Build leaves the current page selected.

Editable banks use a bounded 250-line text window over the whole saved buffer;
the continuous scrollbar hides that implementation detail. Tail targets retain
preceding context even when the file ends just after a window boundary. Wheel
and scrollbar navigation preserve the absolute viewport across overlapping windows,
with scroll restoration deferred until the new text is measured (not a competing
caret-recentering coroutine). The line-number gutter scales with the font and digit
count and never wraps. `AsmEditorViewportTest` exercises the rendered editor with
a synthetic 15,754-line bank, including reopening, large fonts, focused boundary
scrolling and end-to-end scrollbar dragging;
it needs no ROM and cannot modify a real project. Caret movement does
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
between source and Library pages. **Cmd/Ctrl+F** in a source pane opens its compact
**Find in this file** control; the magnifying glass in that pane's header does the
same. Search covers the whole document (including unsaved edits, comments, and
off-screen pages), highlights literal matches, and shows a match count.
**Enter / Shift+Enter** or the arrow buttons cycle matches with wraparound;
**Aa** toggles case sensitivity and **Escape** closes Find and returns focus to
the text. Each split pane keeps its own find session. Original source and Room
Data support the same control. **Cmd/Ctrl+Shift+F** from a source pane focuses the
broader sidebar search; **Cmd/Ctrl+F** while the sidebar is focused stays there.
Sidebar search includes room names/IDs as well as sources, labels, and addresses.
**Cmd+Left/Right** on macOS (**Ctrl+Left/Right** on Windows/Linux) follows ASM
Back/Forward history throughout the sidebar and canvas, including while Find,
sidebar search, or a Library/header control has focus. Shift-modified arrows
remain native text-selection commands. History handling sits above individual
text fields so adding a focused control cannot silently disable navigation.

The Library is bundled and remains available before the external disassembly is
downloaded. Its ten SMEDIT-authored lessons are intentionally a field guide,
not a general computer-science course. Their selectable, syntax-highlighted
examples progress from literal formats, strings, tables, and bit masks through
width state, pointers, comparisons, calls, and SNES runtime patterns; mnemonics
inside the examples open their instruction pages. A selectable Quick-reference
page adds native tables for equivalent decimal/Asar-hex/Asar-binary values,
data widths, NVMXDIZC flags, matching call/return forms, and Super Metroid's
source-verified native/emulation interrupt vectors. A capstone walkthrough traces
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

An opted-in project's authored files live in `asm/` at the project root; generated
files live in `smedit/asm/`. See [project layout and manual reorganization](../project/project_format.md#project-root-and-opening-workflow).
Within the generated cache, `original/` is an immutable, offline snapshot of
the pinned source and ROM-derived assets at opt-in time; `workspace/` is the
editable tree that future compilation will consume. Both generated trees are
ignored by the cache's own `.gitignore`; intentionally saved source changes are
mirrored under `asm/overrides/src/`, which is small, reviewable, and suitable for
source control. A recreated local tree reapplies those overrides automatically.
Standalone project-authored routines and tables live under `asm/modules/`, beside an
explicit `.order` file and, when needed, a small `.disabled` manifest. Names and
descriptions are portable metadata comments in the module itself. Modules appear
first in the Project Source tree and can be created, configured, disabled,
re-enabled, deleted, and reordered without editing the pinned `main.asm`.
New workspaces begin with the documented Objectives pause-screen module present
but disabled. It is a readable, buildable reference and contributes no bytes
until the user explicitly enables it.
At build time SMEDIT creates a reserved gateway that includes those modules after
the vanilla source and before generated patch backends. Disabled modules remain
editable and retain their position, but are omitted from the gateway and source
validation until re-enabled. Both `asm/modules/` and
`asm/overrides/src/` are portable authored inputs; their generated mirrors under
`smedit/asm/workspace/src/` are ignored and never treated as source authority.
The ignored `smedit/asm/build/` directory retains the latest Asar log and WLA symbol map so
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
valid address. The tile inspector links a selected door to its `$83`
DoorDef preview/authored declaration; the adjacent **Arrival ASM** link opens the
separate executable routine in `$8F`. For an item/object, **ASM** instead targets its selected room/state's
placement: the exact `%PLMPopEntry` line in editable Bank `$8F` source when
verified. The adjacent dropdown offers **Behavior** for the shared `$84` PLM
header (setup/instructions), independently of the placement. Expanded enemy stats link
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
Multiple reverse links are collapsed by default (**Used by · N rooms** for room
consumers), with a bounded scrollable list when expanded. They describe the
loaded-ROM index, not the current project's unbuilt changes. A single destination
remains a compact direct link. Read-only source gutters grow with font size so
five-digit line numbers and SNES addresses do not wrap.

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
`src/`/`data/` trees, and every generated source-asset, room, and patch claim. An exact
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
ROM must match the pre-materialization plan except for the derived checksum
bytes described below. Unsupported domains
therefore retain the proven post-compile behavior instead of blocking source
mode or being silently reclassified. Existing-room graphs graduate atomically
rather than write-by-write: generated source owns all planned room bytes or
none. The initial implementation is deliberately byte-faithful to the existing
exporter so it can prove identical semantic ROM output before individual
resources become typed label-driven tables. Asar's four derived internal
checksum/complement bytes are the only exclusion from that parity hash; all
semantic bytes remain exact.

The first typed resource is now a PLM list and its room-state references.
Reachable, edited lists in an existing selector graph emit readable `dw`/`db`
records under names such as `SmeditRoom_91F8_State1_Plms`. State pointers use
`dw SmeditRoom_91F8_State1_Plms&$FFFF`, so Asar resolves the relationship.
Shared pointers emit one table; equal contents at distinct pointers remain
distinct resources. Complete table/pointer ranges are claimed, including bytes
unchanged by the edit, and conflicting source/patch ownership blocks promotion.
The emitter verifies the zero terminator, field widths, and bank-$8F placement,
and asserts the compiled list size. Explicit data primitives avoid inheriting
an edited upstream macro's layout.

The existing room allocator still selects placements, and the original exporter
still provides the exact-output oracle. Other room resources, including rewritten
selector graphs, retain the atomic byte backend. Project-created rooms and
allocations beyond the compiled source ROM retain the ROM backend. This pilot
therefore establishes symbolic PLM references without claiming that Asar now
chooses all room allocations. The GUI model remains authoritative.

After compilation, `smedit/asm/build/generated-rooms.asm` retains the
generated input for inspection beside `symbols.sym`. It describes that compilation,
not unsaved/current GUI edits; rebuild to refresh it. It is a disposable build
artifact, not an editable project module. Cached prepared builds restore their
matching source and symbol report.

The project source sidebar lists **Custom ASM** (your modules, with **+ Module**),
then **Room Data**, followed by **Banks** and **Build & symbols**. Room Data uses
the same theme-colored section heading and retains its expand/collapse control.

In **ASM → Source → Project (editable) → Room Data**, a collapsible tree groups
read-only listings by the same room names used in the room list. Each room exposes
its annotated header, runtime-state fields and objects (PLMs), including object
names, tile coordinates, parameters, shared-list notes and terminators. These
listings are decoded from the **last successfully validated ROM build**, not
editable assembler inputs. They are published automatically to
`smedit/asm/build/room-data.asm` on Build/Run/export in ASM mode, even without GUI
room edits. Project-created-room support remains deferred; a successful build of
a project with added rooms clears this catalog rather than presenting old data
as that build's room list. A failed build retains the prior successful snapshot.
The room inventory comes from the loaded project's input catalog, while all
displayed values are decoded from the build. This retains stable room names and
IDs even when generated state redirects would hide a room from heuristic ROM
discovery (notably Landing Site). Build again to refresh older incomplete snapshots.
The hierarchy uses distinct indentation for Room Data, rooms, and state/object
sections; typing **Landing Site** or **91F8** in the sidebar narrows to that room.

**Build writes**, collapsed separately beneath Room Data, retains the actual
`generated-rooms.asm` input from the last successful source compilation. Typed PLM
sections group under their room names; unmapped raw writes appear under **Other
build writes** with SNES addresses, not meaningless numbered "Room bytes" entries.
No room ownership is inferred from proximity or a shared byte value. Source-state
numbers in generated PLM labels remain original source identities; runtime-state
order in the decoded catalog reflects the built ROM instead.

Both views have syntax colors, selectable multi-line text with real newline
characters for copy/paste, scrollbars, symbolic navigation and Back/Forward.
**Edit room** returns to the owning GUI room. Search includes room names, section
titles and generated labels. Generated text never enters editable buffers, source
replacement or modules. Expanding/collapsing the room tree does not modify source.

The compact **Format checks** action explains the supported six-byte PLM format
and validation limits. Structural/ownership failures still appear in the build
panel, including failures after Asar succeeds: planning/export failures retain
their actual reason instead of only a generic build-failed message. Existing
Asar errors keep their clickable source locations. No claim is made that
arbitrary engine-layout edits can be detected.
The **Build writes** snapshot belongs to a successful *source compilation*, not
necessarily a successful final export, and is not live GUI data. It remains
inspectable after source edits or a later failed compilation. A successful
compilation with no generated input clears it, including a preliminary pass or
ROM-backend fallback. Such a preliminary compilation does not clear the separate
last-successful-build **Room Data** catalog. `$00`/`$FF` in raw writes are data,
not error indicators; their meaning depends on the specific resource.

After GUI room data has been compiled, manifest preflight uses the retained
**pre-materialization source-built ROM**. Revalidating original state identities
against an already-rewritten selector graph falsely rejected condition edits and
reordered states. The initial planning pass, genuine source-identity checks,
ownership conflicts, exact final-ROM parity and postflight structural checks
remain in force; this is not a blanket validation bypass. Prepared cache copies
retain their own immutable validation input. Room-specific preflight errors also
include the room name/address for context.

The real-ROM tests create disposable projects from the explicit fixture contract;
they never read or modify a user's Sandbox. They cover all-state list growth,
selected-state growth and shrinkage with copy-on-write, empty lists, symbolic
addresses, combined room/graphics/patch output, and cache reuse. Synthetic tests
cover ownership conflicts, copier headers, bank boundaries, unsupported allocation
space, new-room fallback, and missing terminators. The packaged-Asar smoke test
also moves a synthetic table and verifies that both state pointers follow its
label; it needs no Nintendo data and runs in the existing cross-platform CI job.

Run the strict real-ROM check with the pinned checkout provisioned:

```sh
export SMEDIT_TEST_ROM='/path/to/clean/unheadered/Super Metroid.sfc'
./gradlew parityBootstrap
./gradlew :desktopApp:asmBuildIntegrationTest
```

`SMEDIT_DISASSEMBLY_DIR` optionally overrides the pinned checkout location. Missing
fixtures fail this strict task, while the ordinary JVM suite reports them as skipped.
The task stages the same pinned Asar executable as the desktop package (requiring
the normal CMake/native build toolchain on a fresh checkout).
Future placement changes that intentionally differ from the legacy allocator must
retain byte-exact tests where applicable and add decoded-resource, pointer/sharing,
and relocation-diff allowlist proofs.

### Extending engine data formats

Adding a field is different from changing its value or adding another record.
The original PLM population is `word ID, byte X, byte Y, word parameter`, followed
by a word-zero terminator. Bank `$82:EB74` reads the population pointer from state
offset `$14`; `$82:EB85` advances it by six bytes. Changing a macro to emit a seventh
byte can assemble successfully while making the engine interpret the next entry
at the wrong offset. Changing the 26-byte state record has the same issue across
its many consumers. Labels fix addresses, not record layouts or runtime contracts.

For small extensions, prefer an existing custom PLM parameter or a separate,
explicitly keyed metadata table when the design permits it. A true format change
requires coordinated engine readers, encoded data, GUI parsers/writers, validators,
and any affected import/export/patch paths. A future supported variant should name
its format/version and provide those codecs and compatibility checks. Unsupported
formats should remain source-owned rather than being rewritten using vanilla GUI
assumptions. Arbitrary ASM can disguise a layout change as plausible vanilla bytes;
current ownership/parity checks are not a general detector or runtime proof of such
changes. Automatic format detection/adapters are not implemented by this pilot.

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

The real-Asar combined-backend regression also includes an enabled legacy byte
patch and a disabled conflicting byte patch. It compares the enabled legacy and
Samus Physics results with Loaded ROM mode, checks unchanged-build cache reuse,
and verifies that a later invalid patch is still reported as a build failure.

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
  separate minimal authored-source, generated-asset, generated-room, and generated-patch ranges
  rather than an opaque ROM replacement.
- `AsmBuildArtifactRepository` retains the latest local Asar log and WLA map;
  `AsmBuildOutputParser` turns compiler messages into file/line diagnostics and
  `AsmWlaSymbolParser` indexes emitted labels and exact source-line addresses.
  Every retained report includes a fingerprint of the source tree that produced
  it, so repaired source can never inherit stale errors after reopening SMEDIT.
- `AsmSourceLinter` catches data literals that exceed their explicit
  `db`/`dw`/`dl`/`dd` storage width before Asar can silently keep only the low
  bits. It also resolves complete `%macro(...)` calls and checks their argument
  counts, including variadic minimums. The editor runs these conservative checks
  after a short typing pause and immediately feeds Problems plus red/yellow source
  rows; incomplete calls are ignored while being typed, and Asar remains the
  authority for the complete language. Intentional truncation remains expressible
  with an explicit mask.
- `AsmSourceAssetMaterializer` projects eligible write-plan bytes into exact
  manifest-owned `data/` files, excludes allocations and ambiguous ownership,
  and leaves the project workspace unchanged.
- `AsmSourceRoomMaterializer` atomically converts the complete validated
  existing-room write graph—including allocator-owned payloads and pointer
  changes—into a temporary ASM input. It cannot partially migrate a graph, and
  project-created rooms deliberately fall back until source IDs are defined.
- `AsmRoomPlmTable` encodes the first typed generated room resource. Its name
  follows a consuming room/state identity rather than its allocation address;
  all consuming state-pointer fields reference that label. Discovery decodes
  reachable lists from the validated plan and checks full-resource ownership.
- `AsmPatchBackendRegistry` explicitly declares which patch families have an
  ASM implementation; unregistered patches retain the ROM backend.
- `AsmSourcePatchMaterializer` converts validated writes for a selected generated
  overlay backend into temporary Asar source and separately owned `ASM_PATCH`
  ranges. Overlapping or out-of-ROM records fall back instead of being guessed.
- `AsmToolchain` pins and verifies Asar 1.81, preferring an explicitly configured
  executable, then the release-bundled native compiler, and only then using or
  provisioning a managed developer compiler.
- `AsmSourceParser` reads `main.asm` include order and descriptions, then indexes
  every source file, authored section, global label, arbitrarily nested dotted
  label, flattened local alias, struct member (including indexed forms such as
  `Enemy[1].properties`), Asar/WLA `Parent_Local` alias, cross-reference, and
  extracted asset. This lets
  a reference such as `DebugConst_DisableAudio` navigate to the `.DisableAudio:`
  declaration beneath `DebugConst:`. Reference resolution uses the same
  local-scope rules as label navigation and ignores comments and quoted strings;
  it is not a raw text search.
- `AsmSourceReferenceAuditor` compares every navigable reference in the pinned
  source with Asar's emitted WLA symbols. `:desktopApp:asmSymbolCoverageTest`
  fails if any emitted symbol used by source cannot reach a SMEDIT definition;
  the strict parity report runs that audit after rebuilding the reference.
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

## Reciprocal source/GUI ownership — first authoring slice

The generated room backend above proved compilation/export parity; it was **not**
yet reciprocal source editing. The target is one authoritative representation per
resource, with GUI and source/raw views acting on the same document. Source-owned
tables belong in authored ASM; graphical/level assets can instead own project
data files referenced by ASM. Neither requires a second editable `db` dump of the
built ROM. Room Data remains a last-successful-build inspection report.

In ASM source mode, supported PLM lists now resolve their authority automatically
against the immutable source/ROM baseline. There is no per-room **Link** step:

| Authored ASM list | Room-editor changes | Authority / behavior |
|---|---|---|
| Unchanged | None | Normal room editing remains available; opening the field dialog does not take ownership. |
| Unchanged | Objects, scroll commands, or state graph | Room editor. Existing additions, removals and state differences remain authoritative and are included by the established build backend. |
| Changed, supported fixed layout | None | ASM. The room preview follows source automatically, including after save/reopen. |
| Changed | Overlapping GUI resource/graph changes | Explicit conflict. Neither version is discarded; build stops until ownership is resolved. |

Comparison uses decoded values, not whitespace, comments or literal spelling.
Sharing is checked across every consumer, not only the currently selected room.
Automatic ownership is derived from the documents rather than saved as another
toggle. Source Undo back to the original values releases that automatic ownership.
Build-time checks run independently of the GUI, so visiting Room Info first is
not required for either protection or correct export.

**Room Info → State Data → Placed Objects → Edit objects…** opens compact field
controls for an unchanged or source-owned supported list. The dialog names the
rooms/states sharing the list and the current fixed-size boundary. Changing
a literal X/Y or parameter updates the actual `%PLMPopEntry` token in project
`bank_8F.asm`, retaining surrounding labels, comments, other literal spellings and
line endings. The **ASM** action opens that same editable declaration, not the
built-ROM report. Editing a supported source field updates the room's PLM preview
without building. GUI and source edits share the source document and undo/redo
history. Normal project Save (including Cmd/Ctrl+S outside ASM) saves pending source
buffers too; it does not trigger a build.

Until first opened as placement source, GUI-managed rooms show **Objects · Room
editor**. Their original Bank `$8F` list is a baseline, not a second edited version.
For example, Landing Site may have a rewritten state graph, common item additions
and state-specific exceptions—not merely a longer vanilla list. The migration
below preserves those differences instead of flattening them into one shared table.

### Selected-object navigation

Right-click an item/PLM's tile on the room canvas, find **Items / PLMs** in the
tile properties panel, then click **ASM** next to its name. In source
mode, navigation verifies the selected room/state's list identity and matches
the full ID/X/Y/parameter tuple against the **current buffer**, including unsaved
changes. It highlights the matching entry rather than stopping at the population
label or opening the shared behavior definition. Identical duplicate records,
invalid drafts, ownership conflicts and unmatched states are not guessed.

Original list identity follows the room state's symbolic `%PLMPop` reference,
verified against the base ROM's complete list. This recovers the missile-refill
and Big Pink lists despite their duplicated `$8F:8550` population annotations in
the pinned reference. Address-only lookup retains all candidates and requires a
unique full-list ROM match; conflicting state references remain unavailable.

For a supported GUI-managed room, this action now automatically migrates its
complete effective object lists into project source, then opens the selected entry.
This changes source/ownership **in memory**, without compiling, exporting or
touching the base ROM. Project Save persists both the source and state identities.

If safe migration is unavailable, ASM opens a compact **Placement** inspection: room,
selected condition, PLM type, tile X/Y and parameter, plus the reason source
navigation is unavailable. This is explicitly a read-only snapshot of the room
preview at selection, not the last build and not another editable ASM dump.
The actual migration refusal is shown; no guessed conversion occurs.
**Behavior** still opens the shared implementation. Back/Forward preserves the
captured placement context; snapshots are discarded when switching projects.
Navigation never uses the source lens's last-valid cached preview as if it were
current authored text.

### Fixed-layout authoring boundary

Earlier explicit links remain supported through the project's
`asmWorkspace.plmSourceBindings` identities (label, original pointer, record count),
**not another list of object values or PLM deltas**. No new binding is needed for
automatic source recognition or field edits.
The immutable original source supplies the initial identity/layout checks; the
project's authored source owns the values. The existing ROM-only workflow is
unchanged for unlinked resources. Linked resources require ASM mode.

This intentionally bounded pilot supports the standard six-byte records expressed
as single-line `%PLMPopEntry(ID, X, Y, Param)` calls and a zero-word terminator:

- Decimal, `$` hexadecimal and `%` binary literals are editable through the GUI.
  Known pinned source labels are decoded, but the GUI never silently replaces a
  symbolic field with a literal. Edit that expression in ASM explicitly first.
- Record count and source/state-pointer placement stay fixed. Add/remove,
  relocation, per-state detachment and arbitrary expressions are next steps, not
  implemented reciprocal capabilities. Existing unlinked room editing still
  supports its usual additions/removals through the established build backend.
- Invalid/incomplete source or a changed PLM macro keeps the session's last valid
  preview, marks it out of date and blocks visual writes to that resource. A stale
  object dialog cannot overwrite a newer source revision. On reopen there may be
  no last-valid session preview; the source problem is still explicit.
- For older explicit links, **Use room editor** requires original object values
  first. Automatic source ownership instead releases on restoring the source
  values. Neither action hides changed source behind a base-ROM preview.
- Saving checks both workspace and authored override text against the buffer's
  saved baseline. External changes are rejected, not overwritten. Automatic
  external-file merging/reloading is not implemented.

Builds verify the decoded model against actual Asar bytes **and** its emitted
label address, confirm every existing state reference, then claim the complete
table and state-pointer fields as source-owned, including unchanged bytes. Thus a
patch cannot overwrite an unchanged byte in a linked list, and the GUI exporter
cannot replay an independent PLM delta on top. Compiled source cache reuse retains
these ownership claims. The project compiler does not guess custom engine layouts.

Validation: `AsmPlmDocumentTest` covers lossless field edits and rejected formats;
`AsmPlmSourceBridgeTest` covers reciprocal buffers, shared undo, invalid/stale
drafts, ownership, persistence and external-edit rejection, plus exact placement
navigation, GUI-owned fallbacks, state mismatch and inspection history. The strict
`asmBuildIntegrationTest` includes a disposable real-ROM Landing Site test proving
GUI → source → GUI → Asar output **without linking**, retained sharing, undo/reopen
and patch-conflict rejection. Another fixture combines a rewritten Landing Site
with common/per-state item edits and an independently source-edited Wasteland,
then verifies that overlapping source/GUI edits fail without dropping either.
Tests never edit the user's Sandbox or other live project.

### Fast room projections and startup

The desktop opens a small room-source workspace (`main.asm`, `macros.asm`, and
`bank_8F.asm`) before the full IDE index. Room objects can render and be edited
while the remaining banks, references, and diagnostics initialize in the background.
The bottom status bar reports **Loading room objects…**, **Indexing ASM…**, and
**Checking ASM…** without adding another toolbar. A failed index retains the usable
room workspace and offers **Index unavailable · Retry** in that same bar. A late
global-reference load never locks project editing or replaces the project view.

`smedit/asm/projections/` contains disposable decoded views and immutable baseline
symbol lookups. Room snapshots include source/dependency SHA-256 keys, a codec version,
payload checksums, ROM identity, and stable room/state identities. Symbol lookups
are keyed to the complete immutable source snapshot. Cached scalar
tokens are checked against the current source. Missing, truncated, incompatible,
or checksum-invalid snapshots are rebuilt; cache write failures do not prevent
valid previews. Only a few generations per cache family are retained. No preview
cache is an editable authority or a ROM-build input. Builds retain their independent
source decoding, allocation, Asar output, and final-ROM parity checks.

The live object model is keyed to its relevant source, macro definitions, and state
membership rather than the global editor/index revision. Editing another bank or
changing unrelated room content therefore does not reparse the PLM view. ASM edits
invalidate the affected model; GUI edits still change those same source values.
Invalid drafts retain the session's last valid preview, mark it **out of date**, and
block visual mutations. With no valid source preview, the canvas does not silently
substitute vanilla ROM items.

Before source is indexed, generated bank mirrors are reconciled with their authored
overrides or immutable original snapshot. Differing old mirror contents are preserved
under `smedit/asm/recovery/`; authored files are never overwritten by this recovery.
Per-project synchronization serializes short mirror/save transactions, not the full
index rebuild. External helper consumers are read from authored sources and unsaved
buffers, including disabled modules, even while the IDE index is incomplete.

Future resource codecs can reuse `AsmVerifiedCache` with their own format version
and complete dependency keys. Their cached models must remain disposable, must be
validated, and must not bypass the resource's normal source/build ownership checks.

### Relocatable room-object source

`AsmRoomObjectSource` is the next, bounded step beyond fixed original lists. On
first **item → ASM** navigation for a supported GUI-owned room, the bridge:

1. Resolves its stable state manifest and computes complete effective PLM lists
   from the loaded ROM, common edits and state exceptions. Existing independent
   source edits, changed record layouts and unsupported resource changes refuse
   conversion without deleting either version.
2. Appends authored `SmeditObjects_<room>_…` macros to editable `bank_8F.asm`.
   State wrappers reference List macros; shared lists retain sharing. Every list
   contains direct `%PLMPopEntry` records, including multi-state rooms. There are
   no generated per-object Common helpers; state-local exceptions stay local.
   Original vanilla declarations and surrounding source are unchanged.
3. Verifies that decoding those macros reproduces every effective list, then
   retires only the room/state `plmChanges` streams. Project JSON stores
   `RoomEdits.asmPlmSourceVersion = 1`, plus existing state identities—not a second
   authoritative copy of the object values. Historical operations and unrelated
   tile/enemy/FX edits remain intact.

Both ASM edits and ordinary GUI object add/remove now change those macros. A
state-local edit first forks a shared List, while **All states** writes the actual
PLM row into each current list (once per physical list, not once per consumer).
Duplicated rows retain the same pickup flag ID: they represent one collectible
across room states, not multiple pickups.
Added conditions clone their template's list, inheriting common additions/removals
**and template exceptions**.
Editing a row directly in ASM or the scalar dialog changes that list and any
states sharing the whole list. Equal rows in different lists are independent;
editing one does not silently rewrite the others. Use **All states** in the room
view to deliberately apply a change to every list. The scope indicator describes
current membership: an identical object present once in every state shows All states,
not a hidden per-object link.

The inspector's **Add new PLMs to** preference defaults to **All states** and applies
to items, stations, gates, door caps and scroll triggers/extensions in both backends.
It is not an existing object's scope indicator. Each object's membership menu shows
its actual coverage and offers **Edit this state**, **Edit all states**, **Apply to all
states**, and explicit removal scopes. Normal edits preserve existing membership;
an intentional local edit detaches a shared list. A broad edit refuses distinct
objects/command streams or incompatible gate/door-cap collision geometry in another
state. Save rows keep one shared spawn; gate body/shot-block rows move together in
GUI transactions. Raw ASM edits still affect the list/resource explicitly edited,
not every independently duplicated row.

In single-state rooms, All states and This state have the same membership. Adding
a condition shares its template's existing List; a subsequent state-local placement
forks the List. This direct-row form also matches the pinned vanilla bank `$8F`
PLM declarations (`%PLMPopEntry`, followed by `dw $0000`). Identical bytes in independent lists
are **not** evidence of intentional sharing and are never automatically merged.

Migration, GUI placement/scalar edits, and source/project Save tidy the affected
room's owned helper graph in the same source undo step. Legacy Common calls expand
to direct rows in every active list, including shared and independent multi-state
lists. Unreferenced Common/List definitions and wrappers for deleted states are
removed without changing entry order, literal spelling, values, or live List
identities. Surviving list names are not renumbered. Handwritten comments are retained;
generated item-name comments and the old helper-based header are refreshed. Calls
elsewhere in the bank or other indexed project sources, including unsaved/disabled
custom modules, protect legacy definitions even though managed lists now contain
direct rows. Unknown helper bodies are conservatively retained. This is
a bounded source transformation, not an arbitrary Asar dead-code optimizer.
The decoded resource graph must match before/after cleanup or the edit is refused.

Malformed authored records block GUI placement/removal without queuing an edit or
overwriting the draft. The status bar reports **Object edit blocked** in error
color for 12 seconds; malformed PLM rows include `bank_8F.asm:<line>`. Correct the
source and retry the placement. A successful edit replaces the stale error status.

Older saved rooms receive this cleanup on their next GUI object edit or Save, even
if the bank was already saved and no GUI placement changed. Build/Run preparation
also saves managed source first. Merely opening or navigating does not rewrite
authored text. Invalid drafts remain saveable and are not cleaned until repaired;
Build still validates the complete tree. Cleanup is source-undoable, and newer
typing during Save remains unsaved. Redo, save/reopen, and item → ASM navigation use
the resulting document. Synthetic
tests cover repeated add/remove, single-to-multi-state transitions, state exceptions,
external consumers, comments/CRLF, and exact undo. The strict Asar integration test
also builds Bubble Mountain before and after adding a condition and compares every
compiled state list with the source model.

Authored PLM entries carry short `; PLM: <name>` comments, using
the editor's item/door/scroll names (including project custom items), plus a field
legend and list terminator comment. Unknown IDs remain identified by hex, not a
guessed item name. GUI object edits refresh these names in the same undo step;
handwritten comments, numeric spelling and original bank declarations are retained.
Additional notes can follow a second semicolon. Comments never determine object
values. Existing authored rooms acquire comments on their next GUI object edit or Save;
merely opening a source file or typing ASM does not automatically rewrite it.

At build time the shared room planner receives the decoded source lists. It
allocates variable-sized, whole resources in bank `$8F` and rewires every state
pointer. The generated placement plan calls the **authored macro**, not a copied
editable dump. Asar must emit exactly the planned bytes, table sizes and pointers;
whole-resource claims and final-ROM parity still protect against overlapping
patches. A missing source document, unknown format version or leftover independent
PLM deltas fails closed. ROM-only/headless builds cannot silently ignore the marker.
Allocation addresses remain build results, not persisted source identities.

Migration is one source-undo step that restores both text and the prior GUI PLM
streams. It refuses undo across changed state membership. GUI object Undo/Redo
uses source history and rejects stale revisions. A newly added state's declaration
cannot be undone while that state still references it. Source Save also persists
the project's room-state/ownership metadata. Ordinary Project Save includes all
dirty source banks and checks already-saved managed room blocks for cleanup.

Current boundaries: this conversion supports existing rooms in projects without
added rooms. Changed resource links, conflicting
original-source edits and arbitrary macro expressions still need review. Compound
tile/PLM operations and whole-room reset now use the transactions described below.
Original fixed-layout declarations retain their earlier count/address restriction;
the appended managed lists support growth/shrinkage and state-local detachment.
This is **object-source migration, not full room-header/selector/level-data source
authoring**. Room Data remains a read-only build report.

Validation adds synthetic `AsmRoomObjectSourceTest` coverage and migration/undo/
save/reopen bridge tests. The strict real-ROM integration compares migrated Landing
Site states with the established ROM exporter, then exercises GUI edits, shared
undo, a new condition, source-list growth, real Asar output and cache reuse. No test
reads or modifies a user's live Sandbox project.

Next: other source-aware compound edits, initial room scroll tables, a first asset-owned
graphics example, and stable source identities for project-created rooms; then
extend the same authority contract to the rest of the room graph.

### Reciprocal scroll-trigger commands

Source-owned room objects now support the referenced command resource of a `$B703`
scroll trigger. In a source-owned room, select its tile → **Edit commands** to use
the screen grid; **ASM commands** opens the exact command declaration. **ASM** on
the object still opens its placement row. Creating a scroll behavior writes the
placement and commands in one shared source/undo transaction.

```asm
macro SmeditObjects_ACB3_Scroll_1()
    db $00, $01 ; Screen (1, 1) → Blue (normal)
    db $03, $02 ; Screen (2, 2) → Green (open + lower view)
    db $80 ; End of scroll commands
endmacro

; Inside each affected state's object-list macro:
    %PLMPopEntry($B703, $04, $05, SmeditObjects_ACB3_Scroll_1&$FFFF)
```

Screen IDs in code are zero-based row-major indices; the GUI and generated comments
use one-based screen coordinates. Each command is a byte screen index plus a byte
scroll value (`0` red, `1` blue, `2` green); `$80` terminates the stream. This is
**runtime trigger behavior**, not the room's initial scroll table or a simulation
of the running camera. The grid uses the editor's configured font sizes and can
scroll horizontally instead of shrinking cells in wide rooms.

Command values are authoritative in the authored Bank `$8F` source. A named
reference has no physical pointer until Build: its numeric preview parameter is
an internal placeholder, not an address. The typed preview cache retains the
resource name and verifies its body and room bounds against current source.
Build allocates each referenced identity once in bank `$8F`, emits its label and
macro invocation, then assembles the referencing PLM lists. Exact command bytes,
terminators, list bytes, state pointers, complete allocation claims, and other
write owners are checked against the established planner. No command values are
duplicated in project JSON or reapplied as a second post-compile room patch.

GUI edits target the selected trigger in its chosen scope. New placement defaults
to **All states**; existing triggers retain their membership unless their scope
menu requests otherwise. **This state** copies shared lists/commands on write;
**All states** updates the affected trigger rows together with their command reference.
Another trigger is not changed implicitly. Editing a
shared command macro directly in ASM updates all its references. Independent
equal streams are not deduplicated by their contents. Unused supported owned
macros are pruned; helpers mentioned by custom source (including disabled modules)
remain protected. Source Save refreshes screen/value annotations and preserves
handwritten notes. Stale open GUI drafts refuse to overwrite newer source.

Existing custom room/state command maps migrate into named resources with their
sharing preserved; source undo restores their legacy metadata. Original numeric
ROM command pointers remain intact until a GUI edit creates a private named
resource. Verified `RoomPLM_*` declarations are read from current project source,
so supported literal ASM edits are visible immediately. Unknown hack pointers use
the loaded ROM; a changed or unsupported known declaration does **not** silently
fall back to old ROM bytes. Matching an address alone does not establish source
ownership: a hack's differing ROM commands are retained when the source baseline
is untouched, while changes on both sides require review. Build verifies original
fixed pointers against actual compiler labels and command bytes; a mismatch
blocks the build with guidance to import a named resource. Original declaration
resizing is not a new general room-source layout guarantee; use the relocatable
named form for GUI growth.

The bounded codec rejects missing/misplaced terminators, non-byte expressions,
invalid values, out-of-room screens, duplicate screen writes that the grid would
otherwise discard, cross-room resource references, and ambiguous placements.
An empty named stream is a valid no-op and remains visible as an empty-behavior
diagnostic. Invalid drafts remain saveable, but source-owned edits/builds fail
closed. The synthetic tests cover source/GUI round trips, shared-state isolation,
undo, save/reopen, cache validation, growth/removal, header handling and conflicts;
the strict real-Asar integration checks migration, growth, empty streams, actual
pointers and preservation of a ROM-only patch in a disposable project.

### Reciprocal save-station spawns

Source-owned existing rooms now support save-station PLMs **and their paired
load-station records**. In the tile inspector use **Add Station / Gate → Save
Station**, then **Apply Spawn**, **Reset/Auto**, or **Spawn ASM** on the station.
The ordinary **ASM** link still opens its placement. The spawn link adopts an
unchanged native record on demand, then opens the authoritative authored fields.
No extra toolbar or second editable generated-data page is introduced.

```asm
macro SmeditObjects_91F8_Save_0_2()
    dw $91F8 ; Room · bank $8F
    dw $8916 ; Incoming door · bank $83 (example: must actually enter this room)
    dw $0000 ; Door BTS
    dw $0000 ; Camera X
    dw $0000 ; Camera Y
    dw $0068 ; Samus Y · screen top
    dw $FFE0 ; Samus X · screen center (-32)
endmacro

; In a state's PLM list: this parameter is a slot, NOT the record's address.
    %PLMPopEntry($B76F, $08, $08, $8002) ; Save slot 2 in the room's area
```

The name encodes owning room, area, and slot. The seven words are one fourteen-byte
bank-`$80` record; authored values live beside the PLM macros in the same Bank
`$8F` source buffer, while the checked build placement invokes the macro at the
actual load-table slot. Its original Bank `$80` declaration is baseline data,
not a competing editable authority after adoption. Editing that baseline record
too produces an ownership conflict before the authored record can overwrite it.

The spawn is **shared across room states** because the engine indexes by area
and slot, not by state. State-local placement still detaches the PLM list. Removing
one state's station keeps its spawn while another state references it; removing
the last reference through the GUI writes an explicit seven-zero-word released
record. That declaration intentionally survives list cleanup, so reopening or
rebuilding cannot resurrect the original slot. Raw ASM can retain an independent
nonzero checkpoint record after removing its PLM; that is an explicit source
choice, not an automatic deletion. Released source slots remain claimed by their
room and may be reused there, not silently transferred to another room.

Ordinary source saves are limited to areas `0..5` and indices `0..7`. Allocation
reserves occupied native records, other rooms' authored records, and legacy GUI
overrides. A matching room pointer is insufficient to claim the gunship: an occupied
native record must also have a verified ordinary save PLM for that slot. Elevator,
Ceres, debug, and other engine-owned destinations are not spare save capacity.
The incoming door must enter the owning room; cross-area save migration is still
a separate compound operation. Duplicate owners, a PLM pointing at a released or
missing spawn, wrong rooms/doors, altered record widths and out-of-word values
fail closed. Invalid drafts remain saveable and a last-good preview is not an
editable current model.

Placement plus spawn authoring is one source/undo transaction. **Apply Spawn** also
updates camera and Samus fields in one undo step. Negative offsets use ordinary
sixteen-bit two's-complement values; out-of-range GUI input is rejected rather
than wrapped. Unchanged scalar spelling, CRLF, symbolic fields and handwritten
notes survive GUI edits. Changing a symbolic field visually requires first making
that expression a supported literal in ASM. Native door BTS is preserved when
creating/adopting a record, and remains directly editable in authored source.
Legacy GUI spawn overrides migrate into source with metadata restored by undo;
there is no duplicate JSON spawn value for an adopted slot.

Typed caches verify current save declarations, native ownership, area/state links
and the base ROM. Builds claim all fourteen record bytes (including unchanged
bytes), assemble the actual macro, check its byte count and exact planner output,
and preserve the post-compile ROM-patch path. The regression tests cover headers,
negative offsets, notes/symbols, slot protections, shared-state lifetime, stale and
invalid previews, undo/reopen and mixed ASM/ROM-patch output. Header coverage also
fixed a pre-existing parser bug: the load-station pointer-table read must include
the copier-header offset, just like its target-record reads.

#### Batch acceptance checks

1. In an already source-owned room, add a save station through the tile inspector.
   Select it, open **Spawn ASM**, change a Samus offset and verify the inspector.
2. Change both camera and spawn fields with **Apply Spawn**; one Undo restores both.
   Save/reopen and confirm the same values return without a full IDE index wait.
3. Duplicate the room state, remove the station from one state, then from its last
   state. Verify the first removal keeps the shared record and the last releases it;
   Undo restores the station and record together.
4. Build/Run with an unrelated existing ROM patch enabled. In the emulator, test
   saving and resuming from the station on a suitable floor (the PLM does not build
   room geometry for you).
5. Batch-check energy/missile refill stations, map stations, gate color/direction,
   door-cap variants and scroll extensions. These use the same direct PLM-row lens;
   only save and scroll triggers require the new linked-resource codecs.
6. Leave **Add new PLMs to → All states** selected and verify new placements appear
   in every branch. Use an object's membership menu to **Edit this state**, recolor
   a gate/door cap, and verify the sibling is unchanged. Undo restores the complete
   operation. Distinct sibling variants should block **Apply to all states**.

## Reciprocal initial screen scrolls

**Room Info → Screen Scrolls → Initial grid → ASM** opens the selected state's
actual authored table. Changing a grid cell automatically adopts the room in ASM
mode; there is no Link step and no dependency on adopting PLMs. ROM-patching mode
continues to use its existing state-scoped scroll edits.

Initial values live only in `bank_8F.asm`:

```asm
macro SmeditScrolls_91F8_Table_1()
    db $02 ; Screen (1,1) · Green
    db $01 ; Screen (2,1) · Blue
    ; ... exactly width × height bytes; no terminator
endmacro
macro SmeditScrolls_91F8_State_73746174652d31()
    %SmeditScrolls_91F8_Table_1()
endmacro
```

`asmInitialScrollSourceVersion = 1` is an ownership marker, not a stored second
grid. Adoption imports current GUI common/state overrides and retires those deltas
with metadata-aware Undo/Redo. Sharing follows original pointer identity **and**
effective values; independent equal-valued tables never become aliases. GUI edits
fork a shared table for the selected state. Raw edits of a shared table affect all
its wrappers. Added states clone their template; reordering uses stable IDs.
Private scalar edits preserve whitespace, CRLF and handwritten notes. Generated
coordinate/color comments refresh on GUI edits/Save; unreferenced owned tables and
deleted state wrappers are pruned, except those referenced elsewhere in project ASM.

The small typed lens is independent of the full IDE index and caches the macro
envelopes for the exact buffer revision. It accepts `db` color literals and verified
baseline symbols, validates every active wrapper and exact dimensions (at most
50 screens), and never guesses arbitrary expressions. Invalid drafts stay saveable;
the last-good grid is shown with an error and cannot overwrite that draft via GUI.
Build decodes current saved source, never a preview/cache, then allocates each named
table once in bank `$8F`. Asar invokes the authored macro and writes symbolic state
pointers at `+$0E`; full allocation/pointer claims and exact compiled-byte parity
protect the result. Original ASM pointer/table/header changes which conflict with
this adopted resource fail before replacement. The source-built native scroll loader
at `$82:EB20-$EB6B` must also match the loaded baseline; a custom byte layout/preset
needs a matching codec rather than silently reusing this lens. Ordinary ROM patches retain their
normal post-compile ownership checks. `$0000/$0001` presets are never treated as
addresses; adopted grids always receive concrete tables.

Engine correction: `$82:EB3D-$EB67` generates Green on upper rows and Blue on the
bottom row for `$0000`; only a one-row room is uniformly Blue. `$0001` remains all
Green. This preview correction applies to both build modes. Initial tables still
precede PLM setup, incoming Door ASM, room setup ASM and gameplay writers, so changing
one does not remove a trigger/door override. Trigger command macros are a distinct
format with an `$80` terminator.

Acceptance: use Landing Site, change a cell, open **Initial grid → ASM**, change
its `db` byte and return to the room. Switch states to check isolation, Undo/Redo,
save/reopen, then Build/Run with an unrelated patch enabled. Native multi-row preset
rooms should preview Green above/Blue bottom before editing. Synthetic tests cover
round trips, aliasing, migration, malformed drafts, notes, headered allocations and
ownership guards; `asmBuildIntegrationTest --tests '*initial screen scrolls*'`
checks real Asar output, coexistence with PLM authoring, sentinel materialization,
new-state graph relocation, ordinary patches and base-ROM immutability.

Current boundaries: project-created rooms are guarded rather than partially
translated. Room generation, resizing and reset now have joint
source/layout transactions (see below). Explicit re-sharing controls remain
future work.

## Reciprocal room FX

**Room Info → Default FX → ASM** opens the selected state's authored header in
`bank_83.asm`. Each existing door-specific row also has an **ASM** link. A visual
FX edit automatically adopts the room's supported tables in ASM mode, independently
of PLMs or initial scrolls. ROM-patching mode retains its normal FX overrides.

The authored table contains readable, commented `dw`/`db` fields: four words
(incoming door, liquid start, target, signed 8.8 speed), then eight bytes
(delay, type, A/B/C options, palette effects, animated tiles, palette blend).
State wrappers name the table; JSON records only `asmFxSourceVersion = 1`, not
a second copy of its values. Adoption imports existing common/state/door overrides
and retires them with metadata-aware Undo/Redo.

Generated type fields include the native effect name, for example
`db $06 ; FX Type - Water`. GUI edits and Save refresh those generated names while
preserving scalar values, CRLF and semicolon-separated handwritten notes. The
dropdown, decoded header and writer share `NativeFxTypes`; old SMILE labels such
as `$18 = Lightning` are not used as native handler names. `$0E-$1E` are explicitly
Unused/no-op-handler slots in this dispatch. Invalid drafts still save unchanged.

Ordered incoming-door headers remain intact. The engine chooses the first match
or the complete Default header (`door = $0000`); that header ends lookup itself.
A table without Default ends with the **two-byte** `dw $FFFF` stop, which also
represents an empty/no-FX table. Editing Default in such a table adds a complete
header without removing its door branches. FX type is an **even byte offset** into
the native dispatch table (`$00-$2C`), not a sequential enum. Palette blend is also
a byte offset, not an arbitrary palette ID.

GUI edits detach a shared table for the selected state. Raw edits affect every
state wrapper referencing that table. Room-wide FX operations update each distinct
resource once; new conditions inherit their template and later detach on editing.
Independent equal-valued tables never become aliases. Scalar edits preserve manual
notes and whitespace. Save/GUI cleanup removes unreachable owned declarations while
keeping references from custom ASM. The lightweight startup index includes both
banks `$8F` and `$83`, so FX adoption does not wait for the full IDE index.

The typed lens validates every active wrapper, field width, record layout, selector
order, unique selectors, incoming-door destination, native type offset, stop and
bank boundary. Invalid drafts remain saveable; the last-good preview is marked with
the problem, and GUI writes cannot overwrite it. Format failures retain an authored
file/line in the build drawer; an unsafe FX type links directly to its field.
The map previews **Default FX**,
not a simulated incoming-door choice or later runtime mutation. Door-specific
values remain editable in their separate rows.

Build decodes saved source, allocates whole tables in bank `$83`, invokes the
authored macros at checked placements, and emits symbolic state pointers at `+$06`.
Complete resource/pointer claims and final Asar-byte parity compose with PLM,
initial-scroll and save-spawn authoring, state-graph relocation and ordinary ROM
patches. Conflicting edits to original state pointers/FX bytes fail explicitly.
The initial source-built native loader at `$89:AB82-$89:ACC2` and dispatch pointers
at `$83:AC18-$83:AC45` must match the loaded baseline; custom layouts/dispatches need
a matching codec. These checks are bounded ABI checks, not proof of arbitrary hack
runtime behavior.

**Indexed FX is a separate boundary.** Mother Brain's table at `$83:A0A4` has
two additional headers *after* Default, selected by her AI through `Load_FX_Entry`
(`$89:AB02`). Moving only the door-visible prefix would break her acid transitions.
Adoption therefore blocks that native table without replacing source or metadata.
Supporting it needs explicit indexed records/consumers, not a relaxed door parser.
Project-created rooms remain guarded. Whole-room reset and biome themes have joint
source transactions (below). Arbitrary expressions are not interpreted
by this first FX lens.

Acceptance: in an ASM project use **Room Info → Default FX**, change water/height,
open **ASM**, edit a field, return and switch states. Check Undo/Redo, save/reopen,
and Build/Run with an unrelated patch enabled. Also check a room with incoming-door
FX: editing one branch must preserve Default and its siblings. `AsmFxSourceTest`
covers reciprocal edits, adoption, aliases, invalid drafts, startup-independent
preview, notes, strict reads, state cloning and headered allocation/ownership.
`asmBuildIntegrationTest --tests '*authored FX compose*'` verifies real Asar output
alongside PLMs, initial scrolls, door variants, empty tables, added states and ROM
patches; fixtures are isolated and the base ROM remains immutable.

## Reciprocal enemy populations and GFX

Existing-room actors now use the same source/GUI authority as objects and FX.
In an ASM-source project, **Room Info → Enemy Actors / Enemy Graphics → ASM**, or
an actor's **ASM** shortcut in tile properties, opens that room/state's authored
data rather than the species header. The first GUI add/remove/update also adopts
the effective lists automatically. ROM-only projects keep their established delta
path and do not show these source shortcuts.

One `bank_A1.asm` buffer contains the paired definitions, so population and GFX
changes have one atomic edit/save/undo boundary. The build planner places each
population macro in `$A1` and each graphics macro in `$B4`; their stable identities
do not encode allocated addresses. Per-state wrappers reference each list
independently: a GUI population edit forks only its shared population, and a new
species forks/adds graphics only when necessary. Raw edits to a shared declaration
affect every referring state. New conditions inherit the template's two references.
These paired state wrappers are planner recipes, not contiguous records to invoke
inline from a custom module; placement invokes the two tables in their own banks.
Saving removes unreachable owned helpers but retains definitions referenced by
other project source. Scalar GUI edits retain other literal spellings and notes;
source saves refresh only generated descriptions, leaving executable text intact.

```asm
macro SmeditEnemies_91F8_Population_1()
    ; species, pixel X, pixel Y, init, properties, extra properties, parameter 1, parameter 2
    dw $D13F, $0028, $0038, $0000, $2800, $0000, $0000, $0000
    dw $FFFF ; End of population
    db $00   ; Required kills
endmacro

macro SmeditEnemies_91F8_Graphics_1()
    dw $D13F, $0001 ; species, VRAM destination (palette offset 1 → OBJ palette 9)
    dw $FFFF
endmacro
```

All eight actor words, list ordering, trailing required-kill count, intentional
no-GFX actors and transfer-only GFX entries survive adoption. Zero-transfer headers
do not acquire invented transfers. Equal `GRAPHADR` addresses are not treated as
species aliases: `$A0:8BF3` matches the species ID itself. The native engine has
**32 runtime enemy slots**, regardless of the preview parser's 64-record safety
cap. Graphics validation checks four entries, palette offsets 0–7, raw LoROM source
bounds, nonzero/aligned transfer sizes and the `$7000..97FF` staging footprint;
special size-bit-15 quadrant offsets are retained. Native tables are audited with
the clean ROM fixture. Companion initialization and dynamic AI spawning still need
encounter-specific knowledge; these checks are not a gameplay simulation.

Source-only values are decoded from a revision-local typed lens, including during
progressive startup, without waiting for the full IDE index or invoking Asar.
Invalid drafts remain saveable, preserve the last valid live preview and block GUI
overwrites/builds. Project JSON stores `asmEnemySourceVersion = 1`, not another
population/GFX value list; common/state enemy deltas retire with metadata-aware
source undo. Header/population macro layout changes, native loader changes and
competing original tables/state pointers/selectors fail explicitly instead of
being hidden by generated output. Missing or partial paired placements fail before
a ROM is published. Allocations and every state pointer are checked against actual
Asar output and the same write-owner planner used by normal ROM patches.

Try updating an actor's X or parameter in the map, following **ASM**, editing the
same row back, switching states, then Undo/Redo and Save/reopen. A GFX-less actor
such as an existing elevator should retain its native arrangement. Build/Run with
an unrelated patch enabled. `AsmEnemySourceTest` covers these boundaries, raw
aliases, malformed/missing GFX, fifth transfers, macro layout changes, helper
cleanup, headered claims and the complete native-table audit.
`asmBuildIntegrationTest --tests '*authored enemy populations*'` proves real Asar
composition with PLMs, scrolls, FX, new conditional states and ordinary patches in
isolated projects. Project-created rooms remain guarded pending stable reciprocal
source identities; existing-room reset now spans all authored resource banks.

## Compound tile / object pattern strokes

Functional tile patterns (including floating pattern placement) now work in
existing-room ASM projects. Painting an item, door cap, refill, gate or save
station pattern automatically adopts its object lists when needed. The drag
previews tiles and objects in memory; releasing it validates and publishes all
PLM rows and save-spawn resources as **one** Bank `$8F` source edit. JSON retains
the layout edits, not a parallel stream of PLM values. There is no new toolbar or
special ASM-only placement workflow.

Undo/Redo from either the room editor or ASM reverses both halves together,
including when viewing a different state sharing that layout. The source-history
sidecar captures the layout identity and before/after operations, rather than
depending on whichever room happens to be selected later. Newer layout/state
changes must be undone first; a rejected undo changes neither source nor model.
Saving/reopening and compiling use the ordinary resource-owned layout exporter
and checked authored PLM placements.

Placement keeps the existing This state / All states semantics. Private object
edits detach their PLM list, independently of the tile layout's sharing choice.
Gate shot blocks retain their paired body. A final prospective-geometry check
prevents an All-states gate or door-cap pattern from leaving an incompatible
independent state layout. Save strokes reserve distinct free slots across the
whole drag; repainting retains the station's slot and customized spawn. Slot
exhaustion, invalid source and source changes during a drag never leave a
half-applied pattern. A rejected stamp rolls back that stamp while retaining
earlier valid stamps in the same drag; an abandoned drag discards its preview.
Objects can still be added when their decorative tile bytes already match.

Scroll-trigger patterns remain explicitly guarded: a tile-only pattern does not
carry a named command-list resource. Use **Add scroll trigger** for those. This
slice does not imply that selecting/moving tiles also selects or moves actors or
PLMs. Added-room source identities retain their existing guards; whole-room reset
and themed generation transactions are described below.

`AsmPlmSourceBridgeTest` exercises staging, rollback, scopes, gate pairs, slots,
cross-view undo, interrupted strokes, reopen and the ROM-only backend using
synthetic fixtures. The real-Asar `authored enemy populations` integration also
stamps patterns, undoes/redoes them, and checks the final geometry and PLMs in the
same compiled ROM alongside enemies/GFX, FX, scrolls, new states and ROM patches.

## Compound room generation

In ASM mode, **Generate** and applying a reviewed learned-room candidate now
combine layout changes, initial camera grids and scroll-PLM cleanup in one
source/model transaction. If objects or grids still use GUI deltas, both
adoptions are planned privately first. No source, ownership metadata or tiles
change until the complete plan validates. One Undo/Redo from Rooms or ASM reverses
the whole action, including adoption, legacy command maps and save-spawn overrides.
The same layout sidecar used by pattern strokes owns the geometry history.

Generation follows the existing layout-sharing choice. Choosing **This state**
forks its layout; choosing all sharing states updates those states' initial grids
and removes their old scroll triggers/extensions together. Unrelated layouts and
their camera data are untouched. The generated camera grid is Blue above / Green
on the bottom row, matching the existing generator policy. Target states retain
their shared list/grid identities where possible; a shared subset forks once,
and independent resources are never merged simply because their values match.

States sharing a generated layout may have different object and actor lists.
Generation protects the union of their placements, including state-specific
door caps and important-object approach areas, then writes one common maze.
Each state's non-scroll objects, actors, graphics, FX and scripts remain separate;
generation does not merge those lists. Private geometry overrides that disagree
with the shared layout still require **Make layout unique to this state…**.
Placements changed while the sharing prompt is open require a fresh generation.
Malformed drafts, changed inputs, custom unsupported initial presets,
or competing original PLM source block the entire operation. A later source undo
also refuses to overwrite newer layout/state changes. Existing handwritten notes,
custom references and save resources survive; only unreachable owned scroll
helpers are collected. Source plans check the project source revision so a newer
macro/custom reference cannot be overwritten by an older plan.

Generated rooms persist resource-owned tile edits until layout-source adoption;
adopted layouts instead update their authored `SmeditLayout_*` declarations.
Bank `$8F` PLM/grid values remain authored, not duplicate JSON values. Reopen, raw ASM edits, subsequent
GUI edits, and checked compiled placements use the same existing lenses. ROM-only
generation retains its delta backend. Bulk Generate/Reset skips source-owned
rooms: bulk reset must not remove only geometry while leaving generated camera
source behind. Use the single-room reset or Undo on the complete source transaction
instead. Added-room identities remain separate work; themes now participate in
the generation transaction described below.

`AsmRoomGenerationSourceTest` covers atomic adoption, shared subsets, invalid
drafts, stale plans, save preservation, cross-view undo, reopen, typed export,
ordinary Generate and the ROM-only backend. The real-Asar integration
`compound generated room data` builds the authored result with an ordinary ROM
patch, then undoes adoption and builds the restored legacy inputs as well.
The Flyway shared-maze integration builds generation, undo and redo across its
different native states, checking one layout pointer and unchanged state-specific
object lists, enemy populations/GFX, FX and scripts.

## Compound room resize

**Room Info → Size → Resize** now works with authored initial scroll grids.
Room dimensions apply to every state: all Layer 1/BTS/embedded Layer 2 layouts
and every physical initial grid resize together. Existing sharing and independent
resource identities survive. New geometry is air and new camera cells are Blue;
overlapping cells retain their values, literal/symbol spelling and handwritten notes.
Private layout differences are rebased onto the resized native resource so Save,
reopen and subsequent edits preserve the same result. Shrink followed by growth
does not resurrect cropped tiles.

The transaction adopts existing GUI object/grid values when needed, then remaps
each scroll command by its old screen coordinates. Writes to cropped screens are
removed. Named command sharing survives; original ROM streams become named room
resources instead of being modified where other rooms may still reference them.
ASM resizing now coordinates incoming DoorDefs and proven scroll-only arrivals
before publishing the edit; the ROM-only backend retains its final export remap.
Managed source arrivals are never remapped again by the byte exporter.

### Coordinated incoming doors and arrivals

Existing source-owned entrances no longer block every resize. Arrival screens
and closing-door caps must fit the new geometry; the editor does not clamp them
or discard objects/actors to make a shrink succeed. When row-stride changes move
scroll indices, unchanged verified native scroll routines are copied into managed
arrival macros and their stable DoorDefs bind symbolic entry labels. The native
routine and every unrelated connection field stay intact. Unowned neighboring
DoorDefs adopt only when a binding actually changes; already-owned participating
records join the same undo boundary even when their text is unchanged.

The recognizer is deliberately bounded: straight-line literal loads and long
scroll writes inside a balanced outer PHP/PLP/RTS envelope, with the known native
dispatch scaffold as an optional prefix. It handles byte/word stores, repeated
writes and row-boundary splitting, preserving return A, flags, stack balance,
native A/scratch context, authored values, comments and CRLF. It does not invent
the high accumulator byte after an eight-bit load. Native source must still
match its original routine, and its decoded writes must match the loaded ROM.
This is not a general decompiler or a proof of arbitrary engine modifications.

One routine shared only by affected entrances updates once. A binding to another
door's shared routine uses copy-on-write when its private identity is free.
An explicitly shared canonical routine with external consumers, retained identity
collisions, unknown native/source behavior, branches and mixed effects require
manual ASM review instead of a speculative rewrite. Opaque routines survive
height-only growth unchanged; width changes and shrinking remain guarded. Only
the unsafe geometry operation is blocked, not the rest of the room editor or ASM.

All banks, neighboring ownership and room geometry share source/canvas/minimap
Undo/Redo. New references cannot be left dangling by Undo. Pending map gestures,
stale source/ROM/build mode, changed entrances and newer dependent edits block
the entire restoration. Opening an untouched room requires no preparatory edit:
its manifest is staged privately, a failed resize creates nothing, and Undo of
its first resize removes the new edit record. Map art/reveal cells are not
automatically repainted or moved; the outline follows the new dimensions.

Reset composes this same door plan with its native room/relocation reset. It
remaps neighboring managed arrivals back to native dimensions while retaining
their newer authored values and comments. Opaque geometry blocks Reset before
room resources are retired. Raw JSON dimension changes do not authorize a silent
build-time replacement of source-owned arrival pointers.

Shrinking refuses to strand previously in-room objects, actors, incoming-door
destinations, incoming ASM scroll writes or save cameras outside the room. Mixed
incoming routines that cannot be rewritten safely, invalid source, stale previews,
unsupported dimensions (the existing initial-grid limit is 50 screens), and added
room projects fail before publishing source, dimensions, metadata or geometry.
An unsuccessful Apply leaves the resize controls open and reports the reason.

Source and GUI undo/redo restore dimensions, layouts, grid/command declarations
and any retired GUI inputs together. Newer room/layout edits, actor source changes,
incoming door changes and active strokes prevent stale source undo from overwriting
the current project. The base ROM remains unchanged. This adds no project schema or
second persisted copy of authored camera values.

`AsmRoomResizeSourceTest` covers sharing, independent layouts/commands, growth,
cropping, adoption, invalid/stale inputs, reciprocal undo, save/actor/entrance guards,
reopen and typed export. `asmBuildIntegrationTest --tests '*source room resize*'`
checks real Asar output, an ordinary ROM patch, byte-identical layout/grid previews,
remapped trigger commands and a subsequent build after undo.
`--tests '*coordinated resize authors*'` grows Landing Site from 9x5 to the
50-screen 10x5 limit with Gauntlet Entrance already source-owned, edits its arrival
in ASM, saves/reopens, resizes back, and verifies symbolic addresses, precise
compiled writes, unchanged native code/other DoorDefs/maps, source/map Undo/Redo,
patch conflicts and coordinated native Reset. Instruction-level tests also check
the preserved accumulator, flags, return stack and split-word writes.

Manual check: in ASM mode, open Map once to initialize its shared history, then
open Landing Site and resize **9x5 → 10x5** using **Room Info → Size → Resize**.
Gauntlet Entrance's door into Landing Site should
now bind a managed arrival; its scroll `$13` write becomes `$15`. Build, Undo in
`bank_83.asm`, then Redo in Map. Save/reopen and resize back to 9x5: the write
returns to `$13`, without patching `DoorASM_Scroll_13_Blue` in place.

## Compound room reset

**Generate → Reset room** now works for existing rooms in ASM-source projects.
It restores the native room header, dimensions, state graph, layouts/BTS/embedded
Layer 2, objects, initial scrolls, FX, enemies/GFX, doors and save-spawn records.
This room's resources and their connection dependencies are reset together. Native state identities remain as lightweight
metadata; source ownership and independent GUI overrides are retired together.
Global palettes, patches, unrelated map cells/room edits and handwritten original-bank
edits are deliberately not reset. Relocation and size-dependent connection data
are coordinated as described above and in the area-move section.

The reset removes unreachable generated declarations across banks `$8F`, `$83`
and `$A1`. Custom/disabled-module references pin helpers and their transitive
dependencies, even across banks; handwritten notes and unknown custom declarations
remain intact. Malformed macro envelopes fail before any transaction is published.
This is a reset of room-editor-owned data, not a checkout of all ASM affecting a room.

One shared undo boundary spans every participating bank and the complete room
model. Undo/Redo from the canvas or any participating ASM bank restores all of them,
including added states, resource sharing, prior ownership and the previously selected
state. Older room-editor history remains usable after undoing reset. Newer edits in
any participating bank, changed room metadata, external helper references, incoming
doors or unfinished strokes block a stale transition without changing part of it.
Incoming entrances on expanded screens must be moved before resetting dimensions.
Projects containing added rooms and bulk reset remain guarded.

`AsmRoomResetSourceTest` and `AsmProjectWorkspaceTest` cover cleanup, stale inputs,
metadata-only transactions and reciprocal cross-bank history. The isolated real-Asar
test `whole room source reset` builds the edited room, its native reset and its
restored authored result, checking all resource families, save records, state graphs,
an unrelated room/ROM patch and base-ROM immutability.

## Compound biome themes

**Generate → Theme → Generate room** now stages theme and layout changes together
in existing-room ASM projects. One transaction owns generated Layer 1/BTS geometry,
initial grids and obsolete scroll-trigger cleanup in bank `$8F`, Default FX in
bank `$83`, state tileset overrides and any palette recolor. Invalid FX drafts,
unsupported indexed FX, unavailable palettes, malformed inputs or stale previews
leave every part unchanged. Any layout-scope choice happens before publication.

The theme applies to the states sharing the generated layout; choosing a private
layout limits it to that state. FX keeps all incoming-door variants, unrelated
fields, handwritten notes and independent resource identities. Shared target subsets
fork once rather than modifying sibling states. Source values remain authored in
ASM, not duplicated as JSON FX overrides. Tileset selectors and palette overrides
still use the existing project metadata/export backend; this is not yet reciprocal
graphics-asset authoring.

Palette recolors are **global to the selected tileset**, as before. Other rooms
using that tileset see the same recolor. Undo restores the previous palette bytes
and effect tag, not a guessed vanilla palette, and refuses to overwrite a newer
edit to that palette. Unrelated palettes are untouched. Clearing a theme's palette
effect selection does not implicitly delete an existing custom override.

Canvas Undo/Redo or ASM Undo/Redo from a participating bank restores source,
ownership, geometry, tileset and palette together. Newer raw source or room-model
changes block the whole transition; prior GUI history remains usable after undo.
Raw edits through **Room Info → Default FX → ASM** continue to update the GUI.
Save/reopen and Build use the same projections. Added-room projects, bulk
generation of source-owned rooms, and Mother Brain's indexed after-Default FX
remain guarded.

`AsmRoomThemeSourceTest` covers adoption, scope, aliasing, palette guards,
reciprocal raw edits, save/reopen and the actual Generate entry point.
`asmBuildIntegrationTest --tests '*themed generation compiles*'` checks actual
Asar output for theme and layout data, the previous result after undo, redo,
ordinary ROM patches and base-ROM immutability.

## Reciprocal room layouts

**Room Info → Layout → ASM** first opens a read-only decoded preview without
changing project data, source buffers, undo history or a matching build. Choose
**Create editable source** to explicitly adopt the effective layouts into
project `bank_8F.asm`; that step writes source and legitimately requires a build.
An already-adopted layout opens directly without further mutation. This is
different from the read-only, last-build **Room Data** catalog. Navigation does
not require a build first. The source pane's **Edit room** link returns to a
state using that exact shared/private layout, including after the canvas reloads.

`SmeditLayout_<room>_Data_<identity>()` macros contain a `dw` L1 byte count,
row-major `dw` L1 tile words, row-major `db` BTS bytes, and any embedded L2/tail
data. Comments show zero-based row/column coordinates; rows are at most sixteen
cells long. The first word is the allocation header, not a tile. State wrappers
reference the same macro when their complete layouts are linked. Hidden allocated
blocks, embedded L2, odd tails and unused bytes survive adoption without
normalization. These are typed source assets: do not invoke an uncompressed
layout macro at a ROM `org`. SMEDIT's build encoder validates the literals and
LZ5-compresses them before Asar compiles the generated placement plan.

Painting, filling, selection-property edits and ordinary tile batches update
those same literals; raw source edits refresh the canvas. Existing handwritten
comments and literal spelling survive unrelated GUI edits. **Make layout unique**
clones a whole source asset; **Use another state's layout** relinks it explicitly.
Equal independent assets are not merged. Invalid drafts retain the last valid
preview (or show an unavailable-source warning on fresh load), remain saveable,
and block GUI writes and builds rather than falling back to native geometry.

Adoption retires legacy tile deltas with one reciprocal undo boundary. New source
edits prevent older GUI undo from overwriting them. Generation, themed generation,
functional tile/PLM patterns, resize and whole-room reset retain their joint
source/model history without reintroducing duplicate tile operations. Source
builds use validated resource inputs and checked allocations/pointers; ROM-only
export cannot ignore authored ownership. Project JSON records ownership and
resource identities only. Added-room projects and selection-to-multiple-state
application remain guarded; copy/share complete layouts instead.

Manual round trip:

1. Generate Flyway, then open **Room Info → Layout → ASM** and choose
   **Create editable source** if this is its first source adoption.
2. Find an `L1 tiles` row. Copy a visibly different nearby tile's word into one
   ordinary wall cell to see an appearance change. Changing only `$8xxx` to
   `$0xxx` changes collision to air but keeps the same graphic. Do not change
   the allocation header or door/trigger cells for this test.
3. Use **Edit room** to inspect the change. Paint a block in the canvas, then
   reopen **Layout → ASM** to see its authored value.
4. Check shared/private states and Undo/Redo. Build to refresh **Room Data**.

`AsmRoomLayoutSourceTest` covers adoption/retirement, raw and GUI edits, sharing,
invalid drafts, reopen, typed export, generation/themes, patterns, resize/reset,
retained allocation bytes and reverse navigation. The real-Asar integration
`authored layout code round trips` builds Flyway after raw edits, undo, a private
fork and restored sharing, checking decoded bytes and state-specific resources.

## Reciprocal room header/state fields

In ASM source mode, **Room Info → Header → ASM** opens ordinary header/state fields
directly in the editable source pane, without a **Create editable source** step.
**State fields → ASM** opens the selected state's declaration. Like doors, an
unowned room initially uses a staged full-bank editor view: opening, selecting,
finding, saving or navigating away does not adopt source or add `BUILD*`.
The first actual text change adopts the room's supported header/state-scalar
family and retires legacy scalar overrides in the same undoable source edit.
The staged declarations never leak into Save, Build or project-wide replacement
before that change. This is a general room-editing workflow;
generation is not required. The source pane's **Edit room** returns to the exact
state without needing a build.

`SmeditRoomInfo_<room>_Header()` contains seven labeled `db` scalars: room index,
area, map X/Y, up/down scrollers and CRE flags. Each
`SmeditRoomInfo_<room>_State_<identity>()` contains tileset, music data, music
track (`db`) and background scrolling (`dw`). GUI edits and raw source edits
share these literal declarations and preserve unrelated comments. Like decoded
layouts, these are typed input macros: the build validates them and uses the room
encoder's checked placement plan, rather than invoking them at an arbitrary `org`.

Project JSON records source ownership, not duplicate scalar values. Adoption
preserves earlier GUI overrides in the source, retires the JSON overrides and
supports reciprocal undo. Invalid drafts remain saveable, retain a last-valid
preview even when the first keystroke is incomplete, and block GUI writes and builds.
Undo of first typing restores legacy metadata, the earlier source, selected-state
tileset/scroll preview and GUI history together, then reopens an untouched editor
overlay with Redo available. ASM-first typing can attach an absent room without
loading a canvas or disturbing another room's state/history. Stale model/source,
project/mode/ROM or view changes reject adoption before any write; retained
declarations require review rather than being overwritten. Existing bank bytes,
notes, line endings, state identities and independent field values survive.
Builds claim all seven owned header bytes and five bytes per state, including
unchanged values, at their actual native/relocated record addresses. This keeps
comment-only first edits and native-valued ownership buildable and prevents a
ROM patch from silently overriding those source fields. Dimensions, pointers and
selector instructions are not included in scalar claims.
Fresh loads without a valid source
projection show an unavailable-source warning instead of presenting native room
data as the authored preview. The engine's debug/unused area 7 remains outside
the existing writable-room contract. Clone-state, themes, resize and reset preserve the
source ownership boundary.

Header/scalar ownership does **not** include dimensions or door pointers; selector
ownership is a separate layer described below. Use **Resize** for dimensions.
Minimap room drags now commit positions, map tiles and reveal cells together;
cross-area moves also coordinate doors and saves as described below. Changing raw map/area scalars alone
does not relocate minimap tiles or migrate area-specific records. Added-room
projects remain guarded pending stable new-room source identities.

Manual round trip:

1. Open an existing room such as Flyway, select a state in **Room Info**, and
   open **State fields → ASM**. It should already be editable, without changing
   the project or build status just by opening it.
2. Change its music-track `db` literal, for example `$05` to `$07`. Use
   **Edit room** and confirm that state's Music Track control changed.
3. Change Music Track back using the GUI, then reopen **State fields → ASM**
   and inspect the same literal. The other state's fields should stay unchanged.
4. Open **Header → ASM**, change the CRE-flags byte to `$01`, and return to
   Room Info to inspect the flags. Check Undo/Redo, save/reopen and Build.

`AsmRoomPropertiesSourceTest` covers pure first-edit overlays, reciprocal ownership
Undo/Redo, GUI/native preview restoration, inherited scalar overrides, CRLF/notes,
incomplete first drafts, stale/no-write guards, adoption, raw/GUI edits,
reopen/export, navigation, clone-state, resize/themes/reset
and structural-edit guards. The real-Asar `room header and state source fields`
integration types through the production automatic-header entry point, then
builds headlessly after GUI/raw edits and ownership Undo/Redo, checking all eleven scalar
fields and retaining state geometry, PLMs, enemies, FX and scripts.
The literal-format test also round-trips the native fields of every writable
vanilla catalog room.

## Reciprocal door connections

In ASM source mode, select an existing door tile and open its **DoorDef** ASM
link. Supported door data opens directly in the normal ASM editor; there is no
**Create editable source** step. Before the first edit this is a staged view of
the real bank plus the room's effective DoorDefs: opening it, moving the caret,
using Find, saving, or building does not adopt data or invalidate a successful
build. The first text change adopts the room's connections and that exact edit
in one undoable transaction. Undo restores the previous GUI overrides and source
ownership together, while keeping the door data visible and ready to edit.
A normal GUI connection edit also adopts automatically after preflight validation.
Source/Assets offers **Edit door data…** from a native DoorDef, without loading a visual room.
Connections are room-wide, not state-specific; intentional one-way links are valid.

The staged view is editor-only: project search/replace, saves, semantic writers
and build inputs continue to use actual source, not provisional declarations.
Stale room/project/ROM/source snapshots reject a first edit without publishing
partial changes; reopen the link to see the current data. Unsupported ownership,
shared native records and retained conflicting declarations still require review.
Incomplete first edits are saveable drafts, not rejected keystrokes; invalid
door data blocks GUI writes and builds until repaired, just like later edits.

Each `SmeditDoors_<room>_Door_<identity>()` macro contains the exact twelve bytes:
destination `dw`, flags/direction `db`, cap tile X/Y `db`, arrival screen X/Y `db`,
distance `dw`, and bank-$8F arrival ASM pointer `dw` (`$0000` means none). Cap
coordinates are **not** an ASM pointer. GUI edits replace literal tokens only,
preserving comments and line endings; raw edits refresh the GUI and **Edit room**
returns to the owning room immediately from the live buffer, before Save/Build.
The normal source editor preserves its typing caret across first-edit adoption.
Native escape-gate directions are supported alongside
ordinary door directions. Native exceptional entrance geometry is retained on
adoption/distance edits; changed geometry or destination dimensions must pass
bounds validation rather than silently inheriting that exception.
The inspector's **Advanced** controls distinguish None / Blue door / Escape gate
closure; travel and elevator edits preserve unrelated flag bits.

Project JSON stores ownership only, retiring old GUI door overrides with reciprocal
undo/redo. Source-only undo also restores ownership. Invalid drafts remain
saveable, show the last valid read-only preview (or an unavailable-source notice
on a cold load), and block door GUI writes/builds. Other supported room resources
stay editable. A stale visual edit cannot overwrite a newer ASM row. Older
minimap undo/redo snapshots cannot restore retired GUI door overrides over source
ownership, even before the map pane has refreshed its external-edit version.

Ordinary field-only builds preserve the native list order/count and every bank-$83 DoorDef address:
BTS indices, incoming-door state selectors, FX variants and saves retain their
identities. Checked generated macro invocations own each complete twelve-byte
record, plus the room's list pointer and pointer list, including unchanged bytes.
Competing patches/native-table edits fail. Known native `DoorASM_*` anchors must
still resolve to the declared arrival address; moved/removed anchors cannot leave
a silently stale pointer. This checks identities and ownership, not arbitrary
arrival-code behavior. Literal pointers to custom code remain the author's
responsibility. Managed arrival routines and symbolic references now extend this
codec as described below; numeric legacy pointers retain their fixed-address guard.

Explicit boundaries: shared native DoorDefs require a joint-owner workflow. Structural
lists use the checked workflow below; added-room source identities remain guarded.
Resizing a destination with authored incoming doors now
uses the coordinated scroll-only geometry transaction described above. Cross-area
reassignment is supported through the coordinated area-move action below.
Mother Brain exit retargeting that
would require automatic arrival-code cleanup is refused rather than replacing
custom code. Room reset already retires door ownership/source with compound undo.

Manual round trip: in Flyway select a door and open its DoorDef link directly.
Check that merely opening it does not add a build marker or project edit.
Change the distance word to `$1234`, use **Edit room**, and check the **Advanced →
Distance** control. Change that control to `$2345`, reopen the source, then Undo/Redo and
Build. This is a data-sync check, not a recommendation to play with arbitrary
spawn distances. **Arrival ASM**, when present, opens the separate routine.

`AsmDoorSourceTest` covers staged no-op/save/build isolation, atomic first-edit
adoption and history, invalid first drafts, stale snapshots, ASM/GUI/source history, stale
UI writes, invalid/cold drafts, literal preservation, native catalog formats,
shared-owner rejection, relocation guards, complete claims and reset. A Compose
source-action test selects/replaces a field in the real ASM text box and checks
caret preservation, live **Edit room**, and staged Undo/Redo. The shared
navigation suite exercises ASM-first adoption without a loaded canvas. Real Asar
integration builds Flyway with other owned room resources, checks exact bytes and
stable identities, save/reopen and undo/cache parity, and rejects both an
overlapping patch and competing original-bank edits.

## Structural door lists

Structural doors use an optional `SmeditDoors_<room>_List()` declaration in bank `$83`.
It contains ordered `dw SmeditDoors_<room>_Door_<identity>_Entry` references and a
final `dw $0000`. Position is the doorway BTS index; declaration identities are
independent and never renumbered or reused by GUI operations. Inactive declarations
retain their notes and reserve identities. Existing native DoorDefs keep their
addresses; new DoorDefs and the relocated bank-$8F list receive checked build-time
allocations. Live previews use an unresolved zero for a new DoorDef, never a
persisted or guessed physical address. The header displays **Symbolic · placed at
build** and navigates to the authored list.

In ASM mode, **Room Info → Door Connections → Add connection** lets you choose a
travel direction and an actual destination opening. Opening/canceling the chooser
is read-only. The first completed addition automatically adopts door fields and
all-state layouts in one cross-bank source/model action; there is no Enable ASM
prompt. Selecting a destination on an unlinked doorway or a shared connection in
the tile inspector creates and links a private connection in the same transaction.
The normal shared-layout scope choice still applies to those tile changes. A
standalone Room Info addition does not invent doorway tiles or a reciprocal door.
Use the tile inspector's **Use existing connection…** menu to link a source
opening to a previously added connection without entering a raw BTS index.
Both views have unbuilt **ASM** links; Go to Definition on a list entry opens its
current declaration before Save/Build.

**Remove** checks every state, not just the active preview. A doorway still using
the deleted index must be erased or relinked first. Higher indices are remapped
in every shared/private layout, preserving the BTS elevator flag and all other
tile/L2 data. Native/typed state conditions, door-specific FX, save/load records,
and explicit DoorDef symbol references block removal with an explanation until
retargeted. Surviving DoorDefs and managed arrival names keep their identities.
Lists allow zero through sixteen entries and always emit a zero terminator.

Source/canvas/initialized Map Undo/Redo restores both banks, ownership and tile
indices together. Pending gestures, changed room data, newer source and added
external references prevent partial restoration. Reset retires the structural
list and new declarations and restores the native list; explicit external DoorDef
symbol references must be retargeted before retiring their ownership.

ASM-first editing uses the same optional list grammar after ordinary automatic
door-source adoption. No layout adoption is required merely to author a list in
ASM: the build validates doorway indices in every final runtime state, including
legacy GUI tile deltas. An explicit source reorder deliberately changes index
meaning; only the GUI Remove action performs automatic BTS remapping. Invalid
drafts remain saveable with last-valid door previews and blocked door writes/builds.

The allocator is shared with other room resources. Generated output invokes the
authored list and DoorDef macros using checked symbols and exact size/bank claims,
including unchanged bytes and the terminator. Native-table competing edits and
overlapping patches fail before a ROM is returned. Save/reopen rebuilds identities
from source, not disposable preview addresses. `AsmDoorSourceTest` covers native
middle deletion, notes, hidden layouts, elevator bits, stable arrivals, no-write
failures, sixteen-entry limits, ASM-first runtime validation and reciprocal history.
`asmBuildIntegrationTest --tests '*structural doors allocate*'` checks cold real-Asar
placement, preserved native lists/records, managed arrivals, rebuilt Undo/Redo,
terminator patch conflicts and Reset.

Boundaries: project-created-room identities, joint ownership of shared native
DoorDefs and arbitrary ASM index dependencies are not inferred. Typed state
conditions now support symbolic DoorDefs as described below; FX/save codecs still
require verified native pointers.
Generated entry labels can be used by the authored list and navigated unbuilt,
but external handwritten references to build-generated DoorDef labels are not yet
supported by the initial compilation pass (unresolved references fail the build).

## Symbolic incoming-door conditions

The **Entered through a specific door** condition lists live incoming connections,
including new DoorDefs that have not been built. Entries identify their source
room, current connection number and stable declaration ID. Selecting one uses
the existing automatic selector-source workflow; there is no extra adoption prompt
and opening/canceling a picker remains read-only. Existing literal native pointers
remain valid and are not silently rewritten just by viewing a condition.

In a typed selector macro, the incoming-door argument may name the exact active
entry label:

```asm
    db $01, $00, $00 ; INCOMING_DOOR: kind, NOT, child count
    dw SmeditDoors_91F8_Door_5_Entry ; stable declaration, not BTS index 5
```

The same token works in bounded AND/OR/NOT trees and round-trips through GUI
edits without replacing handwritten notes. No arbitrary expression evaluation,
unknown labels, pointer arithmetic or bank truncation is accepted. The label must
refer to an active source-owned connection whose destination is the condition's
room. Retained macros for deleted doors are not link targets.

Live previews and the state simulator use semantic identities, never pointer zero
as a stand-in. The planner allocates every new DoorDef before resolving any room's
selectors. Its private build projection uses the actual bank-$83 pointers, then
the existing checked generated-ASM/parity pipeline owns the final bytes. Neither
resolved addresses nor source-owned condition projections become project JSON
values. Reordering/removing an earlier connection cannot retarget a surviving
condition, and occupying an old allocation in ASM causes both consumers to relink.

Missing, retired and wrong-destination references retain the last good selector
preview with a warning; selector writes and builds are blocked, while unrelated
room resources remain editable. Repair the reference in source (or undo the door
change). GUI connection removal refuses explicit symbolic users; resetting an
owner is blocked by other rooms' users, but resetting a room's own doors and own
conditions together is allowed. Direct ASM drafts may be temporarily invalid and
remain saveable. Arbitrary executable predicate references, FX/save references to
new records and stable project-created-room identities remain separate work.

The current project-format contract includes these authored tokens. Codec, semantic
linking, cross-room ordering, simulator, stale-draft, state lifecycle and reciprocal
history regressions live beside the existing selector/door tests. The structural
real-Asar scenario additionally reserves a former DoorDef address in source and
checks the nested runtime condition follows its new address after cold reopen.
Related export regressions exposed by this work are covered too: negated native
door/event/boss-bit leaves retain their compiled expression pointer and six-byte
ABI, rather than overwriting that pointer with a native argument or state word.

## Managed arrival ASM and symbolic connections

The door inspector offers **Create managed arrival ASM…** without a preliminary
door-data opt-in. This explicitly creates a marked routine in `bank_8F.asm`
and binds that connection's arrival word in `bank_83.asm`, in **one cross-bank
Undo/Redo transaction**, including automatic DoorDef adoption when needed. Unknown
native code or other preflight failures leave both banks and ownership untouched.
Opening an existing arrival link never creates code or
requires a build. The source pane's **Edit room** returns to the owning room.

When there is a known native arrival, the scaffold restores the native
dispatcher's A / `$12` scratch-pointer context and tail-jumps to its `DoorASM_*`
label, preserving its behavior and caller stack rather than discarding it or
copying shared code. A native symbol may move within bank `$8F`; both that jump
and symbolic DoorDef references follow the actual compiled label. An unknown
numeric target cannot be replaced automatically: review/preserve it in ASM first.
A connection with no previous arrival gets a minimal `RTS` body.

For example, an originally empty second Flyway arrival becomes:

```asm
; bank_8F.asm
macro SmeditArrival_9879_Door_1()
    ; SMEDIT managed arrival v1
    rts
endmacro

; Last word of SmeditDoors_9879_Door_1() in bank_83.asm:
    dw SmeditArrival_9879_Door_1_Entry ; Arrival ASM
```

Do not add `org`, placement/end labels or includes inside that body. The checked
gateway accepts instructions, local labels, data, bounded fill and assertions;
placement and entry/end symbols are generated out-of-tree. Each routine is
limited to 1,024 assembled bytes. Arrivals and managed state predicates share
one deterministic packed bank-$8F pool. Reference/current-source bytes and
explicit manual envelopes constrain placement; complete code ranges, including
unchanged `$FF` padding, are owned and excluded from room allocations. Growth
relinks subsequent routines and DoorDefs automatically. The generated-input pass
must retain the first pass's symbols, exact bytes and complete ROM parity.

Arrival entry uses the verified native dispatcher: native mode, 16-bit A/X/Y,
DB=`$8F`, an `RTS` return, and a balanced routine-owned stack. The compiler checks
that dispatcher ABI and actual symbol banks/bounds. These are **trusted code
contracts, not a proof of arbitrary behavior**, stack balance, termination or
side effects. Ordinary external helpers and advanced numeric fixed-address
dependencies remain the author's responsibility. Changing engine dispatch to an
incompatible ABI blocks symbolic arrivals rather than running them with guessed
assumptions.

The GUI shows **Managed in ASM · address resolved on build** and the target label,
not a fabricated allocation address. Only the numeric arrival-address control is
replaced by that source link; destination, screens, flags/cap and distance remain
reciprocal. GUI edits and destination-picker suggestions preserve the binding.
Use a known native `DoorASM_*` name or a managed `_Entry` symbol for the final
`dw`; expressions and unverified arbitrary labels are not silently interpreted.
Restoring a literal (including `$0000`) automatically restores the numeric control.
It does not delete the authored routine: declared managed bodies still assemble
and own space until explicitly removed/reset, even if currently unreferenced.
Go to Definition on generated entry/end symbols opens their current authored
macro body, including unsaved text before the first build.

An unfinished executable body remains saveable and does not lock connection-data
editing. Missing/ambiguous binding declarations are different: they make that
data projection stale/unavailable and block its writes/builds until repaired.
Incoming scroll diagnostics follow effective connection targets but do not show
base-ROM scroll writes as the execution of authored arrivals; they identify
source-managed effects as unsimulated while leaving the initial grid editable.
Room-only JSON cannot carry these code dependencies and is explicitly refused;
save the portable ASM project or build/export a ROM instead. This source-ownership
boundary is explicit; no labels, code or guessed addresses are copied
into persisted room deltas.

Room reset retires unreferenced arrival macros with the door declarations and
restores both through Undo. External symbolic references retain the code and its
transitive dependencies. Shared DoorDefs, structural door-list edits, added-room
identities remain guarded. Recognized scroll-only arrivals now join coordinated
resize/Reset; managed code placement does not make arbitrary routines safe to
rewrite for new geometry.

Manual smoke test: choose **Create managed arrival ASM…** directly
on its Bomb Torizo connection (the native empty arrival). Insert `nop` before
`rts`, Build, and use **Edit room**. Change **Advanced → Distance** and check its
DoorDef source still contains the same `_Entry` word. Test Undo from either bank
and the canvas. On the Parlor connection, the scaffold should preserve the
original native routine through a context-preserving tail jump instead.

Coverage includes codec/bank/ABI/envelope checks, headered builds, direct native
relink ownership handoff, instruction-level dispatcher/scaffold context parity,
unbuilt symbol navigation, reciprocal field/history/reset/draft flows, and a real
Asar scenario with two arrivals plus a predicate, growth/relocation, moved native
labels, headless reopen, unchanged-padding patch conflicts and ABI/oversize failures.

## Coordinated ASM area moves

In ASM source mode, **Room Info → Area** and the minimap's **Move to area** action
can move an existing room even when its header, outgoing/incoming DoorDefs or
save objects are already source-owned. No preliminary adoption or setup clicks
are needed. The action stages everything before publishing one shared source,
room-editor and minimap Undo/Redo transaction:

- The header's area and collision-free destination map position; other header
  scalars and every state's properties remain unchanged.
- The room-owned visible map cells and station-reveal mask. Nested/shared source
  rectangles keep their cells; the source-area view remains selected after Apply.
- Only the cross-area `$40` flag of affected incoming/outgoing connections.
  Other flags, geometry, identity, order and literal/symbolic arrival bindings survive.
- Ordinary AreaSave records and every state's save PLM parameter, retaining the
  high parameter byte and all seven spawn words, including door BTS and signed
  positions. Allocation uses genuinely available preallocated slots, not another
  room's live, reserved or source-claimed slot.

Supported unowned headers and changed DoorDefs adopt automatically. Objects/save
source adopts only when save migration is needed; independent state lists and
sharing are retained. Authored literals, handwritten notes, custom arrival code
and unrelated resources are preserved. The move does not interpret or rewrite
arbitrary arrival routines, state logic or area-specific engine graphics behavior.
Room index is preserved rather than guessed or renumbered.

Authored save resources permit explicit all-zero historical releases in earlier areas.
Live save records must still match the owning room's current area, and historical
releases must pass native slot ownership checks. The old and new fourteen-byte
records retain complete build claims. JSON stores existing ownership/identity
metadata only, not duplicate decoded values. Cold/headless readers and versioned
projection caches obtain the current area from authored source, not a missing or
stale GUI projection. Repeated moves preserve earlier releases without confusing
equal slot numbers in different areas.

Undo from either participating bank, the current room canvas, or the minimap
restores the entire action. A newer source, map, room/slot-consumer edit or pending
canvas/map stroke blocks restoration before any part changes. Source Undo also
updates the minimap's history/view without navigating away from ASM. Moving another
room leaves the active canvas state, geometry and session history untouched.
Opening source, inspecting, or selecting an already-current area remains a no-op.

Known boundaries: added-room projects, shared DoorDefs, engine-owned start/elevator
load records, save-table expansion, and destinations without a free map rectangle
remain guarded with an explanation. Recognized incoming scroll-only routines now
join the compound resize/Reset transaction; opaque geometry still needs ASM review.
**Reset** now returns a relocated room to its native header and map location in
the same transaction as its room resources. Current/native owned map cells and
reveal bits, incoming cross-area flags and room-owned save declarations return
together; historical save releases retire too. Unrelated later edits and
neighboring custom arrival code survive. This works after save/reopen, without
session move history. If a neighbor remains in another area, the reset room
retains only the required authored outgoing cross-area-bit corrections.
Repeated Reset of that result is a no-op. Native exceptional flags are retained
when the relevant room areas have not changed.

The original location must still be safe: a moved-in room, new map art/reveal
data, reused save slot or retained conflicting DoorDef reference blocks the
whole reset before publication. Native nested rooms/ambiguous cells remain
untouched. Pending map gestures block Reset and its Undo/Redo; source, canvas
and an open minimap share the reset's single history identity. Newer conflicting
source/model/map edits block the whole restoration. Reset restores native data,
not a snapshot of the room just before its last move, and does not guess arbitrary
arrival-code geometry transformations.
Raw ASM header edits intentionally do not trigger hidden map/save migrations;
authors making those changes directly must maintain their dependent data.

Manual check: select **Frog Savestation**, make a small ordinary ASM/GUI edit if
desired, then choose **Brinstar** from Room Info's Area selector or the minimap
area action. It should disappear from Norfair's map and appear at a free Brinstar
position, with its save index updated. Check the header/DoorDef/save source, Build,
then Undo from `bank_83.asm`; the room, both maps, reveal cells, flags and save
records should return together. Redo from the minimap should reproduce the build.

`AsmRoomAreaMoveTest` covers first-use/owned data, private states, notes, repeated
moves, cold source/cache/export, full rollback, source/map/GUI history, slot/map
exhaustion, stale headers, shared definitions and coordinated relocation reset. Real-Asar coverage
moves Frog Savestation with authored incoming doors and a managed arrival, checks
exact maps/flags/spawn words and stable identities, headless reopen/Undo/cache
parity, and rejects a patch that attempts to reclaim the released save bytes.
A separate reset scenario moves and saves Frog Savestation, reopens without
session history, resets it while retaining a newer neighboring DoorDef edit and
managed arrival, and verifies exact headless compiled Undo/Redo parity.

Manual reset check: move Frog Savestation to Brinstar, then use **Generate →
Reset room**. It should return to Norfair with its original save station and
native room data. Undo in ASM restores the moved room; Redo in Map returns it
home again. No earlier move Undo is required.

### Same-area map dragging

In ASM mode, the existing Select-tool drag, middle-button drag and arrow-key
Apply/Cancel controls work for both unowned and already-authored room headers.
Only an applied move adopts the header/state-scalar family if needed. Previewing,
cancelling, clicking without moving, and clamped no-motion drops do not write
source, create history or dirty the project. Existing authored scalar values,
private state data, notes and line endings survive.

Apply stages the header's Map X/Y literals, visible map cells and station-reveal
cells in the same source/model transaction used by area moves. Partial overlap
with the room's old footprint works; nested/ambiguous source cells stay with their
existing owners. Destination rooms, art and reveal cells cannot be overwritten,
and room positions stay within logical rows 0–30 rather than the padding row.
A room without pause-map art receives the existing automatic outline only on
Apply. Dimensions, area, room index, doors, PLMs, saves and executable code do not
change; a position-only action does not anchor the door bank's unrelated history.

Hit testing refreshes authored positions first. Apply also verifies the preview's
project/ROM/build mode, effective room rectangles and original map/reveal data.
Changed positions/dimensions, invalid header drafts or map contents refuse the stale
drop before any persistent write. Pointer rejection restores the current map;
an arrow-key preview remains available to Cancel. Pending canvas/map strokes
cannot be silently committed by a room move.

Source, current-room canvas and map Undo/Redo share one identity and compose with
cross-area moves. Newer conflicting edits block every part together; moving a
different room preserves the current canvas and its earlier history. Reset after
relocation now restores native room/map/save/connection data atomically as
described above. Raw ASM position edits still do not trigger an implicit tile migration.

Manual check: drag **Frog Savestation** to an empty position in Norfair (or select
it and use arrow keys then Apply), inspect its header Map X/Y, then Build. Undo
from `bank_8F.asm` and Redo from Map should move both the outline and its art/reveal
cells together. Check that its save index, spawn and arrival ASM stay unchanged.
Synthetic tests cover previews, stale/no-write failures, nested/occupied/bounded
drops, authored notes/private states and mixed move history; a real-Asar test
checks exact maps, every room's non-position fields/doors, save words, preserved
arrival code and headless build/Undo/Redo ROM parity.

## Room/source workflow safety contracts

Room source links share the production `RoomAsmNavigation` router. Layout,
selectors, initial scrolls, FX, enemies/GFX and object-source
adoption follow the same rule: inspection is read-only; **Create editable
source** is an explicit ownership/edit boundary. A verified native PLM placement
opens its existing code directly without creating wrapper declarations. Save-spawn
links preview before authoring a missing declaration or retiring legacy spawn
metadata. Scroll-command links already refer to existing owned source.
Door data and ordinary header/state scalar fields instead open in the normal
editor with adoption on the first text change; area/map moves also adopt their
supported dependencies within the one action. State-selector/code conversion
remains a separate workflow, not part of scalar ownership.

ASM-first authoring is available directly in **Source** and **Assets** through
**Preview / make editable…**, in either Original (read-only) or Project views when
project ASM authoring is enabled. Native room/state headers offer the supported
room-wide resource families; native resource addresses offer their matching family.
Whole-file layout `incbin` declarations resolve through the asset manifest, so
searching `LevelData_Flyway` in Source or `LevelData_Flyway.bin` in Assets leads to
the same preview/adoption workflow. Shared addresses list their room/state consumers
explicitly. Opening a preview never adopts data or dirties the build; Create editable
source performs explicit conversions for the remaining families. Header/state
scalars and doors use first-edit adoption; already-owned resources open directly.

No visual room needs to have been loaded. Preview/adoption of another room leaves
the active canvas, selected state and GUI history untouched; the source document
holds that adoption's undo step. The preview names its target room, and failed
adoption rolls back provisional identity metadata. Source actions are hidden while
displayed text differs from its indexed snapshot, and are never inferred from an
unrelated nearby label or asset filename. Raw graphics/unknown payloads are not
advertised as supported room resources. Station-specific save-spawn actions still
require a selected station rather than guessing ownership from a global save table.

Inspection must leave project JSON, ownership, source revisions, undo stacks,
dirty/build state and project files unchanged. Previews use private room snapshots
and the adoption planners, not a second preview format. Explicit adoption stages
one GUI/source undo boundary in the appropriate bank; reopening owned source is
idempotent. Preview callbacks reject changes to the room, selected state, source
revision, project/workspace, build mode or loaded ROM. Invalid actor/object/state
or door selections are rejected before migration.

Returning to the same room/parser/ROM preserves the active state and GUI history,
including from an unadopted preview. A different room, ROM or parser still reloads.
Read-only ASM shortcuts cannot undo hidden source, but semantic room-editor undo
remains usable after returning from a preview. Source-only ownership undo/redo
notifies the live editor; plain source edits need no redundant JSON dirty flag.
Project save prunes empty rooms only in its serialized snapshot, preserving live
room identities held by undo/redo.

`RoomAsmNavigationTest` exercises these contracts across all source families using
the same router and bridge wiring as Main, including mixed-resource undo, stale
callbacks, navigation history, actual file immutability and save/undo/save/redo.
Resource-specific suites retain the detailed raw/GUI edit, invalid-draft,
sharing/detachment, transaction and reopen tests. The real-Asar selector integration
also uses production navigation/adoption before combining all resource families,
changing conditions/order and verifying headless builds without GUI projections.
These contracts cover supported resources; the structural/added-room guards and
remaining integrations below still apply.

## Reciprocal conditions and selector order

**Room Info → Selectors → ASM** previews an existing room's branch conditions
and order without setting `BUILD*`. **Create editable source** adopts them into
`bank_8F.asm`. Existing owned selectors open directly. An ordinary GUI condition
edit/reorder also adopts source when necessary because that is an explicit edit.
The source pane's **Edit room** returns to the condition's exact state.

`SmeditSelectors_<room>_Order()` lists references to stable condition macros in
engine selection order. Reorder those references, or use the GUI arrows. Every
state must appear exactly once and the original mandatory default must remain
last. Resource identities are not reordered or merged: PLMs, actors, geometry,
FX and ordinary state fields remain attached to their own state IDs. Encoder
and symbolic pointer discovery use the same private source-order projection,
including on headless builds with no GUI preview caches.

Each `SmeditSelectors_<room>_Condition_<identity>()` is a prefix tree:

```asm
    db $04, $00, $00 ; EVENT_SET: stable kind ID, NOT (0/1), child count
    dw $0007         ; argument (event 7)
```

The declaration header lists the fixed kind-ID dictionary. No-argument and
compound nodes use argument zero. Compound nodes are followed by their children
in order; ALL/ANY, negation, the native predicates and existing extended typed
predicates use the same validated build encoders as the GUI. Arbitrary expressions,
unknown IDs, malformed trees, invalid argument widths and default replacement are
rejected. These are typed input macros, not tables to invoke at an arbitrary `org`.

Project JSON records `asmStateSelectorsSourceVersion = 1`; current predicate values
and branch order are authored only in source. JSON retains native conditions as
baseline provenance, not competing overrides. Invalid drafts remain saveable,
retain last-good previews and block selector writes/builds. Adoption, raw/GUI
edits, reordering, source undo and GUI undo preserve their shared history.
Resize, themes and whole-room reset retain this ownership; reset retires the
owned declarations and undo restores them. Add/duplicate/delete state commands now
stage selector membership, all owned resource wrappers and identity metadata as
one cross-bank transaction. Existing pre-adoption added states can also be adopted
with stable IDs.

### Source-owned state lifecycle and projection status

In **Room Info → Room States**, Add and Duplicate inherit the selected state's
effective content and existing sharing, then insert a conditional branch directly
after that state in the actual source selection order (or before ELSE when cloning
the default). Nested AND/OR/NOT conditions use the existing typed condition model.
No room resource is silently merged by equal contents. Existing-room source-owned
families participate together: selectors, header/state scalars, decoded layouts,
PLMs/save resources, initial scrolls, FX, and paired enemy populations/GFX. A state
change adopts selectors if another family was already owned, but does not adopt
unrelated native families. New state identities are never reused after deletion.

Planning validates each participating family before publishing anything. Invalid
drafts, active strokes/selections, stale project/ROM/source inputs, or inconsistent
resource sharing reject the entire operation. A single history group covers all
participating banks, including unchanged banks whose resources are dependencies.
Undo/Redo from ASM or the room editor restores the whole source/model transition
and selected state. Newer edits in any participating bank prevent partial undo.

Delete preserves shared or custom-referenced helpers and handwritten comments.
An authored save slot is released with an explicit zero record only when its last
state consumer disappears. If retained custom code still references that station's
list, deletion is blocked rather than clearing a potentially live save record.
The default state cannot be deleted. Resource/model metadata remains separate
from source-owned condition values, so serialized/headless builds do not depend
on transient GUI projections.

Selector projections distinguish **current**, **ASM-only** (valid executable binding), **recognized conversion proposal**,
**stale last-valid**, and **unavailable**. An unreadable draft keeps source editable/saveable, displays the
reason, and locks visual state creation/deletion, condition edits, reordering and
simulation. Known state identities remain inspectable; the panel follows the same
selection as the canvas and ASM navigation. Repairing source re-enables controls.
On a fresh reopen without a last-valid projection, the canvas must not present
native data as an authored selector result.

This is not a general handwritten-ASM decompiler. Explicit executable predicates
now have the separate source-only backend described below. Manually changing state membership still requires the matching
identity/resource declarations; the current bounded lens rejects unmatched edits
instead of inventing resources. Branch instructions inside condition macros remain
non-buildable drafts; the opt-in importer below can propose an explicit conversion
for a bounded subset. Arbitrary calls inside typed/import macros remain unsupported;
an explicitly bound executable routine may contain calls. Neither path ignores
lens failures or rebuilds stale GUI conditions as a fallback.

UX decisions recorded for follow-up:

- Duplicate currently requires a different condition before confirmation, retaining
  the existing protection against an exactly shadowed branch. Whether to allow an
  intentionally unreachable draft state is a product choice, not an automatic
  condition rewrite.
- Stale selector simulation is disabled; inspecting a specifically selected state's
  known resources is distinct from claiming that state wins at runtime.
- State-operation errors stay in the Add/Delete dialog so the user can repair a
  source dependency without losing the proposed condition.
- Opaque executable predicates now have an explicit binding/adapter authority
  boundary. Malformed typed declarations still block builds; arbitrary code
  behavior and hidden references are not claimed safe merely because it assembles.
- Recognized handwritten drafts require a reviewed, whole-room conversion before
  editing selectors visually or building. Conversion is not a claim that the
  original routine executes. All marked predicates must be recognizable and all
  typed siblings/order valid; there is no partial conversion or implicit fallback.
  Allowing simulation of an unconverted proposal, or per-predicate conversion,
  remains a UX choice. The current UI keeps those paths deliberately explicit.

Manual check: open Flyway's conditional state in **Selectors → ASM**. After
explicit adoption, set the node's first row to `db $04, $00, $00` and its argument
to `dw $0007`. **Edit room** should show “Event is set” with event 7. Change it to
event 8 in the GUI and inspect `dw $0008` in the same macro. Try Undo/Redo and
Build. For order testing, choose a room with multiple non-default branches.

`AsmRoomSelectorsSourceTest` covers raw/GUI edits, retirement/reopen, compiled
compound predicates, order/resource identity, invalid drafts, resize/reset,
navigation and every writable vanilla selector graph. The real-Asar selector
test combines source-owned layouts, headers, PLMs, initial scrolls, FX and enemy
tables, then builds reordered compound conditions and their undo states from a
serialized/headless project model while checking all state-specific resources.

### Bounded handwritten predicate import

This is an **explicit import/conversion workflow**, not automatic two-way rewriting
of arbitrary executable code. In an adopted room's non-default
`SmeditSelectors_<room>_Condition_<state>()` macro, replace the typed body with a
predicate draft containing the exact standalone marker `; smedit:predicate-v1`.
The input contract is 16-bit A and literal absolute reads of the supported game
RAM fields. `SEC`/`CLC` followed by `RTS` return true/false; this is **not** the
native room-selection handler's stack/pointer protocol.

For example, retain the existing macro name and `endmacro`, replacing only its body:

```asm
    ; smedit:predicate-v1
    lda.w $09A4       ; CollectedItems
    bit.w #$0004     ; Morph Ball
    beq .no
    lda.w $09C8       ; MaxMissiles
    cmp.w #$0019     ; 25, unsigned
    bcc .no
    sec
    rts
.no:
    clc
    rts
```

Return to **Room Info**. The panel shows a recognized conversion proposal for
“Morph collected AND maximum missiles at least 25” and offers **Review recognized
predicates…**. Inspect the proposed condition summaries and confirm **Convert to
typed selectors**. Conversion retains the original code/comments as non-executing
comments, leaves unrelated declarations and resource identities unchanged, and
participates in both ASM and GUI undo/redo. Cancel or a source change while the
dialog is open cannot publish the proposal. After conversion, ordinary GUI edits
update the typed literals; they do not regenerate the original branch structure.
Build uses the existing checked typed-condition runtime.

The recognizer tracks A, Z and C across local control flow and reconstructs nested
AND/OR/NOT, including branch joins, masked equality and unsigned threshold/equality
tests. Supported input is `LDA.W`, immediate `AND.W`/`BIT.W`/`CMP.W`, named-local
`BEQ`/`BNE`/`BCC`/`BCS`/`BRA`/`JMP`, `SEC`, `CLC`, `NOP`, and `RTS`.
RAM reads cover `$09A2/$09A4/$09A6/$09A8` (equipped/collected items/beams) and
`$09C2/$09C4/$09C6/$09C8/$09CA/$09CC/$09CE/$09D0/$09D4/$09D6`
(current/maximum energy, ammunition and reserves). Operands must be literals,
not symbol expressions. Capacity comparisons require an unmasked field; gear
equality/mask tests expand to individual bit predicates.

Unknown registers/flags, writes, calls, mode changes, indexed/indirect reads,
unknown RAM, loops, unreachable statements, invalid relative-branch distances,
fall-through and excessive control flow are rejected. Limits are 64 instructions,
512 symbolic execution steps, 512 nodes per intermediate expression, and the
existing 8-level/64-node final condition model. Complement simplification compares
leaf predicates directly rather than recursively rebuilding their negations.
Unsupported import drafts remain editable/saveable, but do not become executable
by bypassing recognition. Use the separate explicit binding contract below for
trusted custom routines. Neither a last-valid projection nor a recognized
proposal is supplied to a headless build in place of validated typed source.

`AsmSelectorPredicateRecognizerTest` checks truth tables, flag preservation,
unsigned boundaries, RAM bindings and rejection limits. Source-flow tests cover
non-mutating previews, cold reopen, CRLF/comments, invalid siblings, stale-dialog
guards and reciprocal history. A real-Asar integration test builds the converted
predicate from a serialized model, verifies all owned room resources, rejects
unconverted undo states even after a successful cached build, and checks redo ROM
parity.

### Executable ASM-only predicates

Existing source-owned rooms can bind a conditional state to actual authored
bank-`$8F` code without converting that code into GUI logic. The mandatory default
remains typed and last. Manual source edits do **not** themselves switch authority:
supported tables and typed conditions stay bidirectional. Custom executable logic
uses an explicit marker and locks only the visual selector section.

#### Recommended: inline code with managed placement

Open **Room Info → Selectors** (or navigate directly to that state's condition
macro in ASM Source). Replace **only the non-default condition macro's body**:

```asm
    ; smedit:managed-predicate-v1
    jsr.w .test
    rts
.test:
    lda.w $09C8         ; MaxMissiles
    and.w #$0001
    cmp.w #$0001        ; Carry set iff capacity is odd
    rts
```

Leave the surrounding `macro SmeditSelectors_...Condition_...()` / `endmacro`
intact. There is no `dw` binding, `org`, entry label or end label to maintain.
The build generates a stable `<condition macro name>_Predicate` symbol and its
`_End` boundary without changing authored source. Local helper labels live under
that generated entry label. The body must contain 1–1024 assembled bytes.

The build first assembles authored source with discovery-only symbol stubs, then
chooses verified trailing free space in bank `$8F` using both reference and authored
ROM bytes. Complete manually declared `Label` / `Label_End` envelopes in that bank
are excluded too, including unchanged padding. Managed bodies are packed to their
actual sizes in deterministic symbol order; code growth can move following bodies.
Authored allocations can move the whole block. Symbolic calls and selector adapters
are reassembled against the actual placement. The placement stays pinned during
generated-room/asset/patch passes of the same build. Bounds, full ownership and
generated-claim collision checks fail the build rather than overwrite other data.

Managed bodies accept normal 65C816 instructions, local labels, `db/dw/dl/dd`,
and literal `fill` / `fillbyte`, one statement per line. Placement directives,
includes, macro calls and global labels are deliberately excluded so the body
cannot escape the allocation. Use the advanced binding below for metaprogramming
or intentionally fixed placement. All predicates use the calling contract below;
managed placement does not prove runtime logic correct. Refer to other routines
symbolically rather than copying their current compiled addresses.

#### Advanced: an explicitly placed routine

The original fixed-address workflow remains available and is never silently
relocated. In the existing condition macro, replace **only its body**:

```asm
    ; smedit:asm-predicate-v1
    dw MyRoomPredicate
```

This marker is distinct from the import-only `; smedit:predicate-v1`. The binding
must contain exactly one plain global label; numeric pointers, expressions,
multiple words, mixed markers and inline instructions are rejected. Room/state
identity and resource declarations still use the existing manifest. Raw source
can reorder existing references while retaining exact identity coverage; this
does not create missing states/resources automatically.

Define the routine and a matching `MyRoomPredicate_End` label in project source.
The complete envelope must be 1–1024 bytes inside bank `$8F`, in space that was
`$FF` in the verified reference ROM. Choose source space that no other authored
routine/module already uses. For a project with an unused native bank-`$8F` tail,
this example binds a condition that has no current typed threshold equivalent:

```asm
pushpc
org Freespace_Bank8F_E99B ; Only if this source area is still unused!
MyRoomPredicate:
    jsr.w .test
    rts
.test:
    lda.w $09C8         ; MaxMissiles
    and.w #$0001
    cmp.w #$0001        ; Carry set iff capacity is odd
    rts
MyRoomPredicate_End:
pullpc
```

The predicate enters in native mode with 16-bit A/X/Y. Return carry set for a
match, clear for a non-match, via `RTS`. Balance the routine's own stack and do not
change emulation mode. A generated tagged adapter saves/restores P, DB, D, X and Y,
normalizes register widths before restoration, and handles the native selector
success/skip protocol. **Do not** pop the selector-loop return address or jump
directly to the game's state-selection exit from inside this predicate.

Build first assembles project source, resolves both labels from real assembler
symbols, verifies the native `$8F:E5D2–E5EA` dispatcher/exit ABI, and claims the
entire routine envelope—including unchanged `$FF` bytes. Allocators exclude those
full ranges. The room graph uses an allocated adapter pointing to the resolved
routine; no guessed GUI condition is emitted for that state. A second assembly
checks stable label placements, and the existing full-ROM parity and structural
checks still apply. Moving a routine between builds updates its adapter through
symbols, not a fixed numeric GUI pointer. Missing labels, bad bounds, occupied
reference data, changed engine ABI, and conflicting ROM patches fail the build.

**Room Info** displays `Managed in ASM · MyRoomPredicate` with a neutral notice and
an **Open selector ASM** action. Valid custom logic is not an invalid-source error.
Selector editing, state
membership controls and simulation lock for that room; selecting known states,
tiles/BTS, headers/state scalars and other supported source resources remain
editable. Scope prompts and resource labels also identify the opaque state rather
than showing its old native condition. Invalid binding drafts retain a clearly
stale preview where possible; they remain saveable but cannot build. Source edits
use ordinary ASM history, and visual resource edits retain their existing shared
history. Restoring supported typed declarations automatically restores visual
selector controls; no separate mode toggle is needed. Typed-only rooms keep their
full state lifecycle workflow.

The Room Data build snapshot decodes the exact tagged adapter as an explicit
opaque condition and still shows its state resources. An exported ROM alone does
not contain the project source/identity contract: opening it cannot synthesize an
editable typed state manifest. Use the original source project/base ROM for that
workflow. No JSON copy of executable predicate values is introduced.

Safety boundary: this is trusted game code, **not a sandbox or a proof of arbitrary
routine correctness**. Assembly/adapter/ownership checks cannot prove stack balance,
termination, intended side effects, assumptions about visually edited geometry,
hidden calls/references or arbitrary engine-format changes. Helpers outside the
declared envelope remain ordinary ASM dependencies. Raw source/module overlap and
reference correctness remain the author's responsibility. A valid binding also
does not imply that an unsaved/invalid routine body will assemble successfully.

UX follow-ups recorded: consider an explicit reviewed GUI-to-custom-code scaffold
that preserves existing behavior (never silently replace a condition with a blank
routine); consider allowing edits to known
typed sibling predicates without unlocking opaque branches or pretending the full
graph can be simulated. The current policy locks only the selector section, not
the room's supported resource editors.

Coverage includes instruction-level native dispatch for both outcomes with
register/width clobbers, exact adapter/tag decoding and corruption rejection,
headered ROMs, opaque-manifest rejection, symbol/bounds/free-space/ABI guards,
unchanged-byte ownership, stale/cold projections, unrelated GUI edits, and a
real-Asar scenario covering routine relocation, headless reopen/builds, resource
parity, undo/cache parity and ROM-patch conflicts.
Managed-inline coverage additionally checks packed sizes, local and cross-routine
JSR relocation, growth, complete fill ownership, unchanged source on builds,
oversize rejection, reciprocal PLM edits, repair/unlock transitions and cache/undo
ROM parity.

## Next layers

The separation between editable source and executable output is intentional.
Follow-up work can now be incremental:

1. Continue conservative editing assistance beyond the shipped scope-aware
   instruction/directive/label/macro completion and debounced diagnostics: add
   richer expression-aware hints, additional Asar-aware checks, and explicit
   statement formatting that preserves expressions, comments, literal forms,
   and `.B`/`.W`/`.L` suffixes. Literal project-wide
   find/replace already has a reviewed, buffer-only preview, and Tab/Shift-Tab
   provide selection-scoped indentation without a whole-file formatter.
2. Continue the artifact sinks for semantic editors. Existing-room graphs now
   have an atomic byte-faithful generated-ASM backend, including variable-sized
   allocations and pointer changes. Replace that bridge incrementally with
   typed labels/tables. The generated PLM-list/state-pointer pilot and read-only
   result navigation are implemented. The separate reciprocal authoring pilot now
   shares fixed-layout declarations and relocatable authored object macros with the
   GUI, including state exceptions and copy-on-write. Initial screen grids,
   scroll-trigger commands, paired save-spawn records and ordinary door/default FX
   now use the same authority contract, including paired enemy populations/GFX.
   Tile/PLM pattern strokes, room generation, themes, resizing and reset now have atomic source/model undo boundaries.
   Decoded room layouts/BTS/L2 now have a reciprocal typed source asset as well.
   Ordinary header/state scalars now have reciprocal source declarations too.
   Conditions and selector order now have reciprocal source ownership too.
   Add/duplicate/delete now publish atomically across owned source resources.
   Bounded handwritten predicate import now has explicit review/conversion.
   Explicit executable predicates now have an ASM-only binding/adapter boundary.
   Broader recognition and placement/scaffolding assistance can follow incrementally.
   Existing DoorDef fields now have reciprocal declarations with stable identities.
   Managed arrival ASM now shares checked placement with predicates and symbolic DoorDefs.
   Cross-area header/map/door/save transactions now work from the existing GUI actions.
   Same-area source/map drags now share header/map/reveal history without door/save changes.
   Relocation-aware reset now returns native room/map/save/connection ownership atomically,
   including after reopen and with source/canvas/map history.
   Coordinated resize now handles incoming source-owned DoorDefs, proven scroll
   arrivals and geometry-dependent Reset with the same shared history boundary.
   Structural connection edits now use stable declaration identities, checked symbolic
   lists, all-state BTS remapping and automatic adoption on a completed edit.
   Symbolic incoming-door conditions now link stable identities through the live
   GUI, simulator and checked build, including nested logic and address relocation.
   Stable project-created room identities and symbolic FX/save references to
   new DoorDefs are the next structural layer. Asset-owned tileset/palette/separate-background
   authoring follows those room workflows.
   Graphics, map, text, and sound encoders should follow the same
   project-owned `data/`/source boundary before Asar.
3. Extend the reverse semantic bridge to remaining specialized title/menu,
   cinematic, and generated-runtime assets as those editors gain stable semantic
   selection entry points. The inventory must explicitly include regional
   Japanese content: the Japanese font/tile encoding, Japanese strings and intro
   text/control streams, regional intro/cinematic images and frame tables, plus
   item-drop, weapon, beam, and projectile graphics/animations.
4. Use retained project symbols for emulator run-to-address, breakpoints,
   registers/stack inspection, and source-mapped crash diagnostics.

Arbitrary-hack disassembly and automatic source merge/conflict resolution are not
implied by the current editable workspace.
