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
   to follow it to its definition. Back/forward navigation preserves the trail.
5. Click a 65C816 mnemonic for the compact embedded instruction reference. Click
   a source `incbin` path, or switch to **Assets**, to inspect the derived file,
   its PC/SNES provenance, and a read-only hex view.

**Sync assets** re-extracts data from the currently loaded ROM without a network
request. **Redownload** transactionally refreshes the pinned source and derives
the assets again. A ROM fingerprint status makes stale assets visible after the
loaded ROM changes.

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

## Architecture

- `AsmReferenceRepository` owns download, archive safety, transactional cache
  activation, ROM normalization, exact range extraction, and metadata.
- `AsmSourceParser` reads `main.asm` include order and descriptions, then indexes
  every source file, authored section, global label, scoped local label, and
  extracted asset.
- `AsmWorkspaceState` owns selection, source/asset mode, history, ROM freshness,
  progress, and safe navigation.
- `AsmWorkspaceSidebar` and `AsmWorkspaceCanvas` provide the bank tree, search,
  syntax-colored source, instruction help, and binary inspector. Typography is
  taken from SMEDIT's global UI setting.

The install contract is covered by normal synthetic archive/range tests. The
real pinned download plus all 1,130 byte-exact extractions can be exercised with:

```bash
export SMEDIT_TEST_ROM='/path/to/clean/unheadered/Super Metroid.sfc'
./gradlew :desktopApp:asmReferenceIntegrationTest
```

## Next layers

The read-only boundary is intentional. Follow-up work can now be incremental:

1. Deep-link room states, PLMs, doors, FX, enemies, sprites, and other editor
   structures to their indexed source labels and assets.
2. Add richer source-linked visualizers for graphics, palettes, tilemaps, rooms,
   OAM, and music instead of duplicating existing editor renderers.
3. Create a project-owned writable ASM workspace with explicit dirty files,
   compile diagnostics, and symbol output.
4. Feed semantic editor changes into generated assets/source, assemble with the
   pinned toolchain, and run the existing ownership/preflight checks over the
   resulting ROM delta so patch-mode features remain compatible.

Writable source, arbitrary-hack disassembly, merge/conflict handling, and ASM
compilation are not implied by the current browser.
