# Environmental Damage

SMEDIT's **Patches → Environmental Damage** editor controls the base damage from
heated rooms, lava, and acid. Values are shown as whole energy per second; `0`
keeps the room effect, animation, and drain sounds while removing its damage.

## Vanilla rates and suits

| Hazard | Base rate | Varia Suit | Gravity Suit |
| --- | ---: | ---: | ---: |
| Heated room | 15/sec | Immune | Immune |
| Lava | 30/sec | 15/sec | Immune |
| Acid | 90/sec | 45/sec | 22.5/sec |

The configured value is the base rate. Native suit behavior is deliberately
preserved: the periodic-damage handler halves damage for Varia and quarters it
for Gravity, while the heat and lava handlers perform the immunity checks shown
above.

The separate **Gravity Suit No Heat Protection** patch changes the heated-room
check from Varia-or-Gravity to Varia only. When both patches are enabled,
Gravity therefore takes one quarter of the configured heat rate.

## ROM representation

The engine adds an unsigned 16.16 fixed-point amount every frame. SMEDIT converts
energy per second using a nominal 60 frames per second and writes both the
fractional and whole words.

| Hazard | Fractional word | Whole word | Vanilla fixed value |
| --- | ---: | ---: | ---: |
| Heated room | PC `$06E386` (`$8D:E386`) | PC `$06E38F` (`$8D:E38F`) | `$0000.4000` |
| Lava | PC `$081E8B` (`$90:9E8B`) | PC `$081E8D` (`$90:9E8D`) | `$0000.8000` |
| Acid | PC `$081E8F` (`$90:9E8F`) | PC `$081E91` (`$90:9E91`) | `$0001.8000` |

These locations and mechanics are confirmed against the vanilla JU ROM and the
matching routines in the local Super Metroid disassembly:

- `$8D:E379` — heated-room palette FX pre-instruction and suit check
- `$90:81C0` — lava handler and Gravity immunity
- `$90:8219` — acid handler
- `$90:E9CE` — periodic damage and Varia/Gravity division

## Headless configuration

The config type is `environmental_damage` and supports patch-only IPS builds.

```json
{
  "schemaVersion": 1,
  "patches": {
    "environmental_damage": {
      "enabled": true,
      "config": {
        "heat_per_second": 10,
        "lava_per_second": 40,
        "acid_per_second": 120
      }
    }
  }
}
```

Accepted values are `0`–`9999` energy per second for each field.

## ASM ↔ visual editing

In Project ASM mode, the section and per-hazard **ASM** links open the native source
without adopting it or requiring a build. Heat uses the two immediate operands in
`PreInstruction_PaletteFXObject_SamusInHeat` (`bank_8D.asm`); lava/acid use the named
`SamusPhysicsConstants_*DamagePerFrame` words (`bank_90.asm`). Source links and the
right-click **Visual Editor** action return to the selected hazard.

The first actual edit adopts both words of that rate automatically, retires its old
patch config, and joins source/model Undo/Redo. A bulk reset spanning both banks is
one transaction, including automatically enabling the patch. The ordinary patch
checkbox works in both ROM and ASM modes. Disabling customized rates asks for
confirmation, saves their exact word pairs, and restores the original source values.
Re-enabling restores the customization; source Undo/Redo includes both values and
the checkbox. While disabled, ASM and the controls show the original values, with a
note that custom values are saved. Editing either view re-enables the patch and
restores its other saved rates. Unadopted legacy overrides still follow their
Enable/Disable state and resolve checked compiler destinations.

The GUI uses whole 0–9999 rates with nominal 60 Hz fixed-point rounding. Exact
non-GUI fractions or shared hazard words stay intact and buildable in ASM; only the
affected GUI inputs become read-only, with exact fractional values shown. Invalid
drafts retain marked last-good values and remain saveable; an owned unsupported
word or accumulator shape must be repaired before building. Conflicting external
ASM/config edits offer **Use ASM values**, with undoable config retirement.

The exact native `!SPF` expressions are recognized, not arbitrarily evaluated.
Changed PAL/SPF definitions disable their numerical GUI controls until the values
are explicit literals. Builds check emitted words, instruction widths, live
accumulator/table bindings, and native bank bounds. Moving data or a recognized
routine prefix does not redirect a legacy override to its old address. Both words
and their consumer instructions are protected, including unchanged bytes. Heat's
independent suit check is not claimed, so **Gravity Suit No Heat Protection** remains
compatible. ROM-only and headless builds reject projects with authored patch data;
ordinary ROM/IPS environmental overrides remain available.

See the [patch integration inventory](../asm/patch_support.md) for the next families.
