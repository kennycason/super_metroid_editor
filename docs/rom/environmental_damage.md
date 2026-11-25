# Environmental Damage

SMEDIT's **Patches → Environmental Damage** editor controls the base damage from
heated rooms, lava, and acid. Values are shown as whole energy per second; `0`
keeps existing native room effects, animations, and drain sounds while removing
their damage. In per-area mode, a positive regional heat value adds the native
heat effect throughout that region; a value of `0` does not add it.

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
check from Varia-or-Gravity to Varia only. Gravity then takes one quarter of the
configured heat rate while its native damage reduction remains enabled; enabling
**Gravity Suit No Damage Reduction** removes that quartering as well.
This is the Map Rando-style split: Gravity's native movement behavior in water,
lava, and acid is unchanged; only its heated-room immunity is removed. The patch
also reselects the animated heat palette whenever the equipped suit changes, so
collecting or switching Gravity cannot temporarily retain or flicker to the old
suit palette.

Two additional patches separate suit defense from those environmental abilities:

- **Gravity Suit No Damage Reduction** removes Gravity's quarter-damage effect
  from direct and periodic damage and removes its lava immunity. Liquid movement
  remains unrestricted. Heated-room immunity remains independently controlled by
  **Gravity Suit No Heat Protection**.
- **Varia Suit No Damage Reduction** removes Varia's half-damage effect from
  direct and periodic damage while retaining Varia's heated-room immunity.

Enabling both Gravity patches therefore leaves Gravity as a movement-only upgrade.
The two damage-reduction patches compose: with both enabled, neither suit reduces
enemy, projectile, acid, spike, or other periodic damage. These curated suit
patches are intentionally incompatible with **Complementary Suits**, which replaces
the same native suit-check routines with different semantics.

### Heated-room blue suit fix

The separate **Heated-Room Blue Suit Fix** preserves blue speed while Samus is
taking heated-room damage without Varia. It does not grant heat immunity, change
the configured damage rate, suppress damage sounds, or preserve blue speed from
ordinary actions that cancel it.

Vanilla's speed-booster animation routine calls the library-3 sound queue when a
new speed stage begins, then accidentally uses the sound routine's return value as
an index into the five-word speed-timer table at `$91:B61F`. Heat-damage sounds can
fill enough queue slots for that index to select the following zero byte at
`$91:B629`, producing a zero timer and the intermittent blue-suit failure noted in
the disassembly. Matching Map Rando's `vanilla_bugfixes.asm`, this patch changes
PC `$08B629` (`$91:B629`) from `$00` to `$01`, providing a valid one-frame fallback
timer. The write is guarded by its vanilla expected byte and composes with
**Environmental Damage**, **Keep Blue Speed in Air**, and **Infinite Blue Suit**.

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

## Per-area rates

Enable **Per-Area Rates** to replace the three global controls with a nine-row
Crateria/Brinstar/Upper Norfair/Lower Norfair/Wrecked Ship/Maridia/Tourian/Ceres/Debug
table. Every row has independent Heat, Lava, and Acid values. Missing area values
independently inherit the corresponding global/source value. Upper and Lower
Norfair never inherit from one another, so editing either row leaves the other
unchanged.

The engine exposes only one Norfair area index, so the runtime additionally reads
`RoomIndex` at `$7E:079D`. Lava Dive (`$25`) remains Upper Norfair. The Lower
Norfair elevator (`$26`) and the Lower Norfair block beginning with its elevator
save room (`$34+`) use the Lower Norfair row; the intervening native room indexes
`$27-$33` remain Upper Norfair.
Exact fractional ASM source values flow into inherited table entries without
rounding; affected GUI inputs remain read-only when a value cannot be represented
as a whole energy-per-second rate.

For every region, a positive heat rate creates the native heat effect throughout
that region while preserving its existing room FX. A heat rate of `0` does not add
the effect; any native heated-room FX remains present but deals zero damage. Lava
and acid presence still comes from native room FX. Tourian additionally observes
the two safety exclusions below. Native suit checks and liquid movement remain
outside the new hooks.

At runtime, three checked 19-byte accumulator blocks call compact routines in
vanilla free space at `$DF:FE00`. The routines use the current engine
`AreaIndex` at `$7E:079F` and `RoomIndex` at `$7E:079D` to select 16.16 values from
three nine-entry region tables. The generated routines, shared region classifier,
and tables occupy 236 bytes and end before the separate per-area heat helper at
`$DF:FF00`. When any regional heat value is positive, compiler/native-resolved
room-load calls use that helper to run the native FX loader and add the heat object
only in regions with positive heat, subject to Tourian's exclusions. The
heat-palette suit synchronization helper
uses the preceding checked range at `$DF:FD80`; it does not overlap the per-area
runtime at `$DF:FE00`. Invalid area indexes safely use Crateria's entry.

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

The optional `per_area_rates` field accepts `0` or `1`. Region keys use the stable
form `<hazard>_<region>_per_second`, for example:

```json
{
  "schemaVersion": 1,
  "patches": {
    "environmental_damage": {
      "enabled": true,
      "config": {
        "per_area_rates": 1,
        "heat_norfair_per_second": 60,
        "heat_lower_norfair_per_second": 90,
        "lava_maridia_per_second": 12,
        "acid_tourian_per_second": 0
      }
    }
  }
}
```

Supported region names are `crateria`, `brinstar`, `norfair` (Upper Norfair),
`lower_norfair`, `wrecked_ship`, `maridia`, `tourian`, `ceres`, and `debug`.
Per-area mode supports patched-ROM, patch-only, and compiler-checked ASM-source
builds.

### Tourian heat safety

A positive `heat_tourian_per_second` value automatically heats Tourian. Two
optional `0`/`1` fields control its exclusions:

- `tourian_no_heat_baby_to_mb` defaults to `1`. It protects room indexes `$07-$0C`:
  Big Boy/Baby Metroid, Seaweed, Tourian Recharge, Mother Brain, Tourian Eye Door,
  and Rinka Shaft. Early Tourian through Dust Torizo remains heated.
- `tourian_no_heat_during_escape` defaults to `0`. Set it to `1` to exclude all four
  timed Tourian escape rooms (`$0E-$11`). Lower Tourian Save (`$0D`) remains heated.

Existing room FX always load first, and the helper detects an existing heat object
so damage is never doubled. The default behavior therefore protects the one-energy
Baby Skip route and Mother Brain while keeping the escape heated.

Older project files may still contain the retired `all_rooms_heated` field. It is
accepted for compatibility but ignored; it is not exposed by the editor, schema,
or AI tools.

```json
{
  "schemaVersion": 1,
  "patches": {
    "environmental_damage": {
      "enabled": true,
      "config": {
        "per_area_rates": 1,
        "heat_tourian_per_second": 20,
        "tourian_no_heat_baby_to_mb": 1,
        "tourian_no_heat_during_escape": 0
      }
    }
  }
}
```

The per-area heat helper supports patched-ROM, patch-only, and compiler-checked ASM-source
builds. Source builds resolve and verify the room-load calls, native FX loader,
palette-FX spawner, and heat object from compiler symbols before writing hooks.

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
independent suit check and palette-sync helper do not overlap those accumulator
blocks, so **Gravity Suit No Heat Protection** remains compatible. ROM-only and
headless builds reject projects with authored patch data; ordinary ROM/IPS
environmental overrides remain available.

Per-area values remain project configuration rather than rewriting 24 literals
into the two native source files. During an ASM-source build, SMEDIT resolves and
validates all three accumulator blocks and their source words through compiler
symbols, releases exactly those blocks to the config patch, and then installs the
same table-driven runtime used by patched-ROM builds. Relocated recognized routines
therefore receive their compiled destinations; no native fixed-address fallback is
used. The generated bank-$DF block still requires its checked free range and fails
closed if authored code occupies it.

See the [patch integration inventory](../asm/patch_support.md) for the next families.
