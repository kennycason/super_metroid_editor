# Hyper Beam Item

SMEDIT's **Hyper Beam Item** bundled patch adds visible, Chozo-orb, and hidden
pickups that can be placed like vanilla items. Collecting one enables Super
Metroid's native rainbow Hyper Beam. It does not add an inventory bit, an item
tracker slot, or a pause-menu equipment entry.

This is distinct from the older **Hyper Beam** patch, which starts every game
with Hyper Beam forced on. Enable **Hyper Beam Item** when Hyper Beam should be
earned at a location in the world. The editor treats these as mutually
exclusive Hyper Beam modes so they cannot be enabled accidentally together.

## Editor workflow

1. Enable **Patches → Hyper Beam Item**.
2. Open a room's PLM/item placement controls.
3. Select **Hyper Beam** and choose Visible, Chozo, or Hidden.
4. Place it and export the ROM.

Collection displays a native small **HYPER BEAM** message box before control
returns to Samus.

The custom PLM IDs are `$F300`, `$F304`, and `$F308`. Each placement's normal
PLM argument is its unique bit in the saved item-collected table at
`$7E:D870`; that is world-pickup persistence, not Samus inventory.

The command-line build API uses `hyper_beam_item` for the patch and
`hyper_beam` for the placeable item:

```json
{
  "schemaVersion": 1,
  "patches": {
    "hyper_beam_item": { "enabled": true }
  },
  "items": [
    {
      "item": "hyper_beam",
      "roomId": 37368,
      "x": 84,
      "y": 68,
      "kind": "visible"
    }
  ]
}
```

## Verified engine behavior

The implementation follows the native acquisition routine at `$91:E5F0`:

- store `$1009` in equipped beams at `$7E:09A6`;
- call the native beam-tile and palette refresh at `$90:AC8D`;
- spawn palette FX object `$8D:E1F0`;
- store `$8000` in the Hyper Beam flag at `$7E:0A76`;
- clear the resume-charging sound flag at `$7E:0DC0`.

The beam-refresh trampoline explicitly restores data bank `$90` before entering
the native routine. This is required because the beam graphics pointer table is
bank-relative; using the trampoline's own bank `$84` leaves otherwise-working
Hyper Beam projectiles invisible.

Vanilla SRAM saves `$09A2-$0A01`, which includes equipped beams, but does not
save `$0A76`. The patch therefore wraps the native beam refresh routine only
during game state 6 (loading saved game data): when the saved equipped-beam
value is exactly `$1009`, it restores `$0A76` and the native Hyper palette
effect. Restricting restoration to the load state leaves Mother Brain's native
acquisition sequence untouched. Room transitions then use the game's existing
palette-FX lifecycle.

Message ID `$1F` points to a 64-byte `HYPER BEAM` tilemap at `$85:9D40`.
Hyper Beam and Spider Ball install the same relocated message-definition table
at `$85:9C00`; each patch owns a different message ID and tilemap, so either
works alone and both can be enabled together.

The pickup intentionally does not modify collected beams (`$09A8`) or collected
equipment (`$09A4`). The engine's Hyper Beam mode takes over beam firing,
equipment-screen behavior, projectile damage, and transition-time palette
refreshes exactly as it does during the Mother Brain sequence.

## Graphics and source

The ROM sprite is two animated 16×16, 4bpp frames: a dark orb crossed by a
wide bright beam from bottom-left to top-right. Its eight tiles live at
`$89:F100-$F1FF`. The matching SMEDIT sprite-sheet icon is at `(80, 80)`.

Rebuild the checked-in artifacts with:

```bash
python3 tools/build_hyper_beam_item_patch.py
python3 tools/build_hyper_beam_item_icon.py
```

The patch builder validates the expected JU ROM bytes, free-space allocations,
generated IPS records, and final patched bytes before writing the IPS. The icon
builder requires Pillow and refuses to overwrite an occupied sprite-sheet cell.

Engine references used for the implementation are the local annotated
`sm_disassembly` banks `$81`, `$84`, `$89`, `$90`, and `$91`, plus the local
`snesrev/sm` decompilation equivalents.
