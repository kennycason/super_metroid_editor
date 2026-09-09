# ROM Write Safety Review And Manual Test Notes

Last reviewed: 2026-09-09

This is the human acceptance pass for the transactional ROM exporter. Automated
tests establish byte ownership, rollback, pointer relocation, bounds, and parser
invariants; these checks cover the user-visible behavior and runtime combinations
that unit tests cannot fully prove.

## Review Result

- Export is transactional: the source ROM is never mutated, and an output ROM is
  created only after preflight, all writes, ownership validation, decompression
  checks, and postflight structural validation succeed.
- Final ROM/IPS output is written to a complete same-directory temporary file and
  atomically replaces the named build where supported. Validation failures always
  preserve the source and previous build; atomic-move filesystems also preserve
  the previous build if final replacement is interrupted.
- Fixed patches reject stale expected bytes and known incompatible base-ROM
  hashes. Unverified custom writes remain allowed, but the export report calls
  them out instead of claiming they have compatibility proof.
- All allocating desktop paths share one allocator session. Variable tileset
  graphics, metatile tables, palettes, room data, music, Room Names, and custom
  ASM cannot independently choose the same free bytes.
- Shared room/tileset pointers use copy-on-write. Editing one alias preserves the
  source allocation for every other reference.
- Requested data is fail-closed: malformed base64/hex, invalid pointers, unknown
  or out-of-range configured-patch values, unsupported headless edits, lossy
  music fallbacks, and legacy PNG enemy graphics stop the export rather than
  disappearing or being silently clamped.
- Ambiguous export ownership is rejected: enabled patch IDs must be unique, and
  edited room keys must resolve to one matching 16-bit room ID. Disabled draft
  patches and room records with no edits remain harmless and do not block work.
- Decompressed graphics are checked against the engine destination, not only the
  compressed ROM allocation. Normal tilesets retain their 20 KiB area/12 KiB CRE
  split; established Mode-7/no-CRE layouts retain their full 32 KiB, and Ceres
  metatile tables retain their valid 8 KiB destination. Writes that would cross
  or misrepresent those WRAM, VRAM, or DMA layouts are blocked.
- Post-export verification reads the staged room headers and state offsets, not
  the original ROM, then compares a full structural scan against baseline input
  errors.
- Desktop and headless ROM builds now run the same project preflight and staged-
  ROM structural postflight around their shared transactional write plan.
- Export blockers appear in the status bar as errors for 12 seconds. The first
  actionable error is user-facing; the complete blocker/ownership list remains
  in the log. A dedicated blocker panel is still a worthwhile UX follow-up.

## Restrictions That Are Intentional

- Save-station PLM indices are limited to `0..7`. The game masks the index with
  `AND #$0007`; load-station entries `8+` are elevator/start/debug records, not
  additional save capacity.
- Moving a room tied to AreaSave is blocked until its destination save entry and
  old-slot cleanup form a complete migration. Ordinary rooms remain freely
  movable between areas.
- Oversized fixed CRE and special boss sprite blocks are blocked until every
  hardcoded engine/DMA reference is proven relocatable. Variable tileset data
  relocates automatically where the U24 table supplies a safe pointer.
- Headless ROM builds block project edit classes they do not yet implement.
  Patch-only builds can still report ignored ROM-dependent data because no ROM
  output is being represented as complete.
- A curated patch with a stale base hash/precondition is blocked. This is not a
  generic ban on modified ROMs: unrelated baseline validation problems are
  logged but do not prevent project edits.

## Manual Acceptance Checklist

Always test from a copy of the project and keep the source ROM beside it. A
failed case must leave the source untouched and must not create or replace the
expected output file.

### 1. Clean and ordinary export

1. Open a clean vanilla project and export with no edits.
2. Confirm the status says it exported a vanilla copy and the source ROM hash is
   unchanged.
3. Make one room tile edit, one minimap edit, and one text edit; export again.
4. Open the output in the editor and emulator. Confirm all three edits exist and
   unrelated rooms, map areas, and text remain unchanged.

### 2. Spike Olympics patch combination

1. Open `projects/Super Metroid Spike Olympics I/Super Metroid Spike Olympics I.smedit`.
2. Enable Room Name Pause Map and Spider Ball, export, and confirm the log names
   distinct owners without a conflict.
3. In-game, pause in several areas and confirm room names draw correctly.
4. Test Spider Ball before Varia, immediately after collecting Varia, with Varia
   equipped, and after unequipping/re-equipping it if the inventory permits.
5. Exercise morph tunnels, slopes, wall jumps, moving platforms, door
   transitions, pause/unpause, save/load, and death/reload.
6. Specifically watch Samus for the known Varia-only black bar. Its presence is
   a runtime VRAM/palette/DMA defect still to diagnose; this review only proves
   it is not a direct Room Names/Spider Ball ROM-byte overwrite.

### 3. Area and save-station migration

1. Move a normal non-save room between areas from Room Info; confirm it vanishes
   from the old minimap immediately and appears in the destination.
2. Repeat from the minimap right-click menu and confirm both views update.
3. Move Frog Save Station and Varia Suit room only after accepting/creating the
   proposed AreaSave migration. Export and resume from each station in-game.
4. Confirm Samus loads into the intended room, facing/position/scroll are valid,
   and the old area slot no longer resumes there.
5. Try to create a ninth save index. Confirm the UI refuses it without partially
   changing the room, PLM parameter, or save table.
6. Repeat with a multi-tile save-station brush. Confirm the whole brush stamp is
   rejected—no decorative tiles, PLM, spawn override, dirty-state change, or undo
   record should remain.

### 4. Shared-pointer copy-on-write

1. Edit a metatile or palette in a tileset known to share its original pointer
   with another tileset.
2. Export, reopen the output, and compare both tilesets.
3. Confirm only the selected tileset changed and the other still renders its
   original graphics. The log should say the edit relocated for copy-on-write.

### 5. Growth and destination limits

1. Make dense/random pixel edits to variable room graphics or a variable
   metatile table so the compressed result grows. Confirm export relocates it
   and the room renders correctly.
2. Test a Ceres/Mode-7 tileset and a normal CRE-overlay tileset. Confirm neither
   is rejected merely for using its valid native layout.
3. Confirm a normal tileset cannot accept more than 20 KiB of area graphics, but
   an established no-CRE/full-layout tileset can use the full 32 KiB.
4. From a disposable project copy, inject data larger than the documented
   decompressed destination. Confirm export blocks with the destination and byte
   limit in the message and creates no output.

### 6. Conflict and stale-base behavior

1. In a disposable project, add two custom fixed patches that write different
   values to the same PC byte. Confirm export names both patches and the exact
   conflicting PC offset.
2. Apply a curated patch to an already-modified/stale version of its target
   bytes. Confirm the hash or expected-before check blocks it.
3. In a disposable project copy, put a timer/config value just outside the range
   shown in its editor. Confirm export names the field and range instead of
   silently clamping it.
4. Disable the conflicting patch and export again. Confirm a valid workflow is
   restored without editing unrelated features.

### 7. Music, sprite, and custom ASM failure paths

1. Export a normal saved music edit and play every arrangement that shares its
   transfer chain; confirm none disappeared.
2. Try an import that exceeds the SPC/ROM allocation or would require lossy
   trimming. Confirm it is blocked with a corrective action instead of silently
   shortening the song.
3. Export raw enemy tile edits and Phantoon/Kraid edits; verify animation poses,
   palettes, and boss-room transitions.
4. Confirm a legacy PNG-only enemy replacement reports that it must be reopened
   and saved through the raw tile workflow.
5. Confirm malformed or empty custom ASM, an unknown species field, and exhausted
   bank `$A0` each fail without producing partial output.

### 8. Desktop/headless parity

1. Build the same supported room/minimap/palette/patch project through desktop
   and the CLI/service ROM path.
2. Compare reports and the modified byte ranges. They should agree on canonical
   headerless PC offsets and conflict ownership.
3. Add a desktop-only edit such as music or custom ASM to the headless request.
   Confirm the headless ROM build clearly blocks and directs the user to desktop;
   it must not return a ROM missing that edit.

## Remaining Risks

- Runtime interactions in arbitrary 65816 patches cannot be derived completely
  from byte ranges. The Varia/Spider Ball visual defect needs an emulator-backed
  WRAM/VRAM/CGRAM/DMA trace and a permanent equipment-state matrix.
- Room export still uses one controlled same-owner graph transaction so multiple
  room edits can successively rebuild shared door-dependent background data.
  Converting that legacy aggregate writer to narrower direct intents is the next
  way to make accidental same-subsystem overlaps easier to detect.
- Desktop currently surfaces only the first blocker in a transient status line;
  all blockers are logged. A persistent, clickable preflight panel should group
  errors by feature and take the user to the relevant editor.
- Fixed CRE relocation, special boss DMA relocation, new-room creation, and
  managed ROM growth remain blocked or unfinished rather than being guessed.
