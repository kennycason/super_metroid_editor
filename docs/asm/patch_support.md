# Patch → ASM integration

Prefer common numerical settings before detailed boss AI. Two distinct workflows
must stay clear:

- **Native data:** GUI edits update the game's real source fields, and source edits
  update the GUI. First edits adopt automatically, with one source/model Undo.
- **Toggleable code patches:** preserve Enable/Disable and select a checked ROM or
  ASM implementation. An ASM backend is not automatically a reciprocal data editor.

Beam damage, boss stats, and environmental damage use ordinary Enable/Disable in
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
| Ceres/Zebes escape timers | ROM implementation | **Next:** checked symbolic BCD operands, GUI/source navigation, and native-data ownership. |
| Bombs and item fanfares | ROM implementation | Then migrate counts/timers and every coupled instruction/list word. |
| Controller configuration | ROM implementation | Bind the actual input table; validate all button masks and aliases. |
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
