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
| Samus physics | GUI-owned generated ASM overlay | Not yet reciprocal native-field editing; do not label it as such. |
| Ceres/Zebes escape timers | Reciprocal checked BCD operands | Preserve exact BCD; unsupported GUI ranges stay ASM-managed. |
| Bombs and item fanfares | Reciprocal native counts/timers | Bomb slot/cooldown edits and ten-site fanfare edits are atomic; consumers and complete words/bytes are protected. |
| Controller configuration | Reciprocal new-game and Options-reset defaults | Occupied buttons swap actions. Protect the menu's decoder and action bindings; never patch its button-mask lookup table. |
| Short Charge and other derived settings | ROM implementation | Model all coupled values together; choose data ownership or a toggleable backend deliberately. |
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
