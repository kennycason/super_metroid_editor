# Patch → ASM integration

Prefer common numerical settings before detailed boss AI. Two distinct workflows
must stay clear:

- **Native data:** GUI edits update the game's real source fields, and source edits
  update the GUI. First edits adopt automatically, with one source/model Undo.
- **Toggleable code patches:** preserve Enable/Disable and select a checked ROM or
  ASM implementation. An ASM backend is not automatically a reciprocal data editor.

Native numerical patches use ordinary Enable/Disable in
both modes. Edits automatically enable the patch. Disabling customized settings
requires confirmation and saves exact per-field word values before restoring the
original source values. Re-enabling restores them without reverting adjacent ASM,
comments, pointers, or relocation. Editing while disabled restores the other saved
fields in the same Undo transaction. Unchanged/simple patches toggle immediately.
Unsupported expressions or aliases with incompatible originals block the toggle
without changing source or claiming the patch was disabled; repair the affected
source first. Reset-to-original discards the saved customization explicitly.

## Inventory and order

| Family | Current source integration | Next action |
| --- | --- | --- |
| Enemy HP/damage, drops, vulnerabilities | Reciprocal native headers/tables | Maintain shared-table, binding, and byte-ownership coverage. |
| Beam normal/charged damage | Reciprocal native projectile data | Maintain symbolic relocation and independent charged values. |
| Boss HP/contact damage | Reciprocal native headers, including sub-parts | Keep detailed AI separate. |
| Environmental damage | Reciprocal heat/lava/acid word pairs | First common numerical-patch slice; see the [workflow](../rom/environmental_damage.md#asm--visual-editing). |
| Samus physics | Reciprocal native words, swapped dwords and checked operands | Byte controls preserve complete storage and fractional bits; symbolic movement consumers follow relocation. |
| Ceres/Zebes escape timers | Reciprocal checked BCD operands | Preserve exact BCD; unsupported GUI ranges stay ASM-managed. |
| Bombs and item fanfares | Reciprocal native counts/timers | Bomb slot/cooldown edits and ten-site fanfare edits are atomic; consumers and complete words/bytes are protected. |
| Controller configuration | Reciprocal new-game and Options-reset defaults | Occupied buttons swap actions. Protect the menu's decoder and action bindings; never patch its button-mask lookup table. |
| Short Charge | Reciprocal packed counter/timer table | Preserve initial delay and all five words; check both initialization paths and stage-transition logic. |
| Fast Doors | Editable curated ASM module, with reversible source/GUI gate | Check native hooks, symbolic relocation, complete consumers and helper allocations against the rest of the authored source. |
| Other derived settings | ROM implementation | Model coupled values together; choose data ownership or a toggleable backend deliberately. |
| Bundled QoL/IPS/hex patches | ROM writes and preconditions | Audit one at a time; use curated symbolic hooks/modules for code changes. |
| Hyper Beam, pause-map room names, defeated flags | Bespoke ROM/runtime implementations | Review each generated-code/runtime dependency before choosing a source backend. |
| Detailed boss behavior | ROM implementation | Lower priority than common patches; retain an ASM-managed boundary for custom logic. |

“ROM implementation” describes the current writer, not a promise that arbitrary
relocated ASM is compatible with it. Existing write conflicts/preconditions still
apply; remaining families need individual relocation/hook audits. Do not silently
promote a fixed-offset writer to ASM-safe or a generic IPS to editable source.

## Acceptance contract for each migration

1. Identify native source symbols, storage widths, consumers, aliases, and units.
2. Inspection/navigation/unchanged Save never adopt or dirty source.
3. First GUI/text edits retire only corresponding legacy config values, including
   disabled overrides, in the same source Undo/Redo transaction.
4. Resolve pending overrides through checked compiler symbols. Never reuse old ROM
   offsets after relocation; generated placements do not enter project JSON.
5. Preserve every byte of the logical field, including unchanged halves. Protect
   consumer bindings/opcodes without claiming unrelated compatible patches.
6. Unsupported drafts retain marked last-good values and disable affected controls.
   Valid exact source values that the GUI cannot represent remain ASM-managed.
7. Prove native/no-op parity, GUI/text edits, undo, conflicts, cold builds, copier
   headers, and real Asar behavior. ROM-only/headless exports must not omit owned source.

`asmWorkspace.patchSourceKeys` records namespaced identities such as
`environmental_damage:heat_per_second`, never values or placed addresses. New patch
families extend the checked source readers rather than adding schema migrations or
inventing a general-purpose Asar evaluator.

## Fast Doors code module

In ASM source mode, enabling **Fast Doors** adds `asm/modules/fast_doors.asm`
(`project/fast_doors.asm` in the source browser). The default module matches the
bundled NobodyNada IPS, apart from Asar's ROM checksum metadata. **ASM** opens
the helper routine; **Visual Editor** returns to the patch panel.

The module's literal `!SMEDIT_FastDoorsEnabled = 0/1` and the patch Enable flag
share source/model Undo. Disable conditionally assembles no hooks or helpers;
it does not delete or regenerate the file, so custom code, comments and placements
survive re-enable and reopen. The module browser's ON/OFF control edits the same
gate, including with unsaved source. Invalid gate drafts remain editable with the
last good GUI flag, but block building/toggling until repaired. Keep the outer
gate intact, use one statement per line, and keep custom helper code between the
documented start/end labels. The managed filename must remain `fast_doors.asm`.

The two native routines and their local branches/pointer table are checked before
hooking. Compiler symbols resolve all three hooks and both helpers; native routines
can move within banks $80/$82, and helpers can move within verified free space in
their respective banks. A separate compilation with Fast Doors gated off proves
that other authored ASM does not already occupy a hook or helper allocation.
All consumer, pointer-table and helper bytes are protected, including unchanged
bytes. Customizing the hook ABI, crossing banks, overwriting native game data or
emitting outside the declared spans fails closed. Arbitrary custom helper behavior
still needs gameplay testing.

ASM builds select this module instead of applying the bundled fixed-offset writes.
Unmodified legacy enabled entries install it once; customized ROM records require
explicit reconciliation, and missing owned source is never regenerated. The
identity `code_patch:fast_doors` in `patchSourceKeys` prevents ROM-only/headless
exports from silently omitting authored code, even while disabled. Other IPS/QoL
patches retain their existing backends until audited separately.

Manual check: enable Fast Doors, follow **ASM**, add a note or customize the helper,
then disable/re-enable and Undo. Build and playtest horizontal/vertical doors in
both directions, especially entrances requiring one-pixel screen alignment.

## Timers, bombs, fanfares and controllers

Each panel has a general ASM link and per-field links. Right-click a recognized
source operand → **Visual Editor** returns to that setting. Opening either view
does not adopt source or mark a build necessary; the first actual value change does.

- Timers accept seconds and store packed BCD minutes/seconds. Invalid BCD blocks an
  owned build; valid values outside the panel's range stay intact and ASM-managed.
- Bombs distinguish the five-slot hard limit from the stock three-bomb timing.
  Increasing the requested limit can reduce cooldown in the same transaction.
  Fuse-only edits do not unexpectedly alter the limit or cooldown.
- Fanfare edits synchronize the item-message wait and all nine music-resume delays.
  Independent source values remain valid, but the common GUI control becomes read-only.
- Controller defaults edit `NewSaveFile` and the options menu's Reset to default.
  Seven supported buttons must each be assigned once, matching the game's swapping
  behavior. Existing SRAM saves retain their saved controls until reset in Options.
  Independently customized initialization/reset mappings remain valid ASM; their
  coupled GUI panel is read-only. Custom menu logic also makes that panel ASM-managed.

ASM builds resolve checked symbols plus parsed instruction offsets, not address
comments. Pending legacy overrides follow relocated routines/tables. Consumer
retargeting, mismatched compiled operands, shared-byte collisions, invalid controller
permutations and changes to the controller menu decoder fail closed for owned data.
ROM controller patches check the native initialization and menu tables before writing.

### Quick manual checks

1. In Ceres Escape Time, enter **95**. Follow ASM and confirm `LDA.W #$0135` before
   `JSL.L SetTimer`. Change it to `$0200`, return via Visual Editor, and expect **120**.
   Undo includes source, ownership and Enable. Merely opening the links needs no build.
2. Set Jump to **Y** in Controller Defaults. Item Cancel should become **A**; both
   initialization and reset source paths change. Build, start a **new save**, then
   check the options labels and gameplay bindings. In an existing SRAM save, use
   **Reset to default** in the game's controller settings before checking them.
3. Set fanfare duration to **240**, then disable it. Cancel preserves the edit;
   confirming restores original source values. Re-enable and expect **240** again,
   including after saving/reopening the project.

Automated coverage includes rendered controls, source/model Undo, no-op inspection,
controller swaps, ROM/headless destinations, relocated pending overrides, complete
byte ownership, cold/headered builds and real Asar round trips. Emulator gameplay
and the on-screen options labels still benefit from the manual controller check.

## Samus physics

All seventeen controls share Bank $90 source with the GUI. Section/per-field **ASM**
links inspect without adoption; **Visual Editor** on a recognized declaration returns
to the matching control. The first real GUI/text edit retires that field's legacy
override (including a disabled override), enables the patch, and joins source Undo.

The existing 0–255 controls select bytes within native values: jump/fall words,
gravity's fractional word, swapped running/air-speed dwords, and the air-control
indexed operand. GUI changes preserve all other bits. Disable saves/restores complete
values, including authored fractions, without reverting comments or relocation.
Known pinned NTSC `!SPF`, `regional` and `wordSwap` forms remain readable without
rewriting untouched source. Arbitrary expressions stay editable in ASM and lock the
affected GUI field; integer speeds above the byte range remain exact and ASM-managed.
Invalid drafts retain marked last-good values and a repair link. Changing the region,
frame-rate or helper definitions requires explicit literals for affected expressions.

Builds check compiler symbols, emitted complete values, operand opcodes, movement
table consumers and Bank $94's normal-speed table bindings. Pending legacy overrides
follow relocated source rather than writing the old physics byte offsets. Complete
words/dwords and their bindings are protected, including unchanged fractional bytes.
ROM and headless byte patches respect copier headers; headless/ROM-only builds cannot
silently omit owned source or saved disabled values.

Manual check: change Jump Height to **6**, follow its ASM link, change its `dw` value
to `$0007`, and return through **Visual Editor**. Expect **7**, with Undo restoring
both views. Build and playtest a jump, then disable/re-enable the patch to confirm
the custom value returns.

## Short Charge

The 0–4 stage selector edits the complete five-word `SpeedBoostTimerResetValues`
table in Bank $91. The first word packs the starting counter in its high byte and
the initial timer in its low byte. GUI changes set counter = 4 − stages, preserving
the initial timer and all later stage delays. Positive timer bytes up to 255 remain
editable; zero timers, extra counter bits, or changed table layouts require ASM repair.

Section/per-field **ASM** links and **Visual Editor** return links are inspection-only.
The first GUI/text edit retires legacy scalar, `stages`, and `taps` overrides together
with one source/model Undo. Aliases must agree when building pending settings; choosing
**Use ASM values** resolves a competing legacy configuration explicitly. New enabled
panels without overrides preserve the native table. Disable snapshots all five exact
words and restores the originals; re-enable restores custom timing, even after reopen.

Checked compiler symbols follow relocated tables. Builds validate running initialization,
equipment-change initialization, and the native counter increment/cap/index/reload
sequence, including anonymous branch destinations and long operand banks. All ten table
bytes and participating instructions are protected, including unchanged timer bytes.
ROM/headless writes validate the native consumers and retain custom timing and copier
headers. Standalone IPS builds use the native default timing; authored source cannot be
omitted by a ROM-only/headless build.

Manual check: choose **2**, follow **ASM**, and expect `$0201` in the first word.
Change it to `$0307`, return via **Visual Editor**, and expect **1**. Choosing **0**
should produce `$0407` and preserve every later delay. Undo and Disable/Re-enable
should restore the complete table. Playtest a Speed Booster run; stage selection does
not automate the advanced tap timing technique.
