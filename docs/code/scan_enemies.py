#!/usr/bin/env python3
"""
Scan all enemy species headers from a Super Metroid ROM.

Reads the 64-byte species header at bank $A0 for every known enemy,
extracting stats, palette pointers, tile data pointers, AI bank, and
hitbox dimensions. Outputs structured text suitable for pasting into
documentation markdown files.

Usage:
    python3 scan_enemies.py <rom_path>
    python3 scan_enemies.py <rom_path> --markdown    # Output as markdown table
    python3 scan_enemies.py <rom_path> --json        # Output as JSON
    python3 scan_enemies.py <rom_path> --species 0xE4BF  # Single species

Examples:
    python3 scan_enemies.py ../../test-resources/Super\ Metroid\ \(JU\)\ [\!].smc --markdown
"""
import struct
import sys
import json
from pathlib import Path


def snes_to_pc(snes_addr):
    """Convert SNES LoROM address to PC file offset (unheadered)."""
    bank = (snes_addr >> 16) & 0xFF
    offset = snes_addr & 0xFFFF
    return ((bank & 0x7F) * 0x8000) + (offset - 0x8000)


def read_u16(rom, offset):
    return struct.unpack_from('<H', rom, offset)[0]


def read_u8(rom, offset):
    return rom[offset]


# Known enemy species IDs and names
# Sourced from RomParser.ENEMY_NAMES in the Kotlin codebase
# These are the CORRECT species IDs as offsets within bank $A0
KNOWN_ENEMIES = {
    # ── Projectiles / Effects ──
    0xCEBF: "Boyon",
    0xCEFF: "Stoke",
    0xCF3F: "Kame",
    0xCF7F: "Tatori",
    0xCFBF: "Puyo",
    0xCFFF: "Cacatac",
    0xD03F: "Owtch",
    0xD07F: "Samus' Ship",
    0xD0BF: "Samus' Ship (firing)",
    # ── Mella / Menu / Multiviola ──
    0xD0FF: "Mellow",
    0xD13F: "Mella",
    0xD17F: "Menu",
    0xD1BF: "Multiviola",
    0xD1FF: "Polyp",
    # ── Rinka / Norfair fire enemies ──
    0xD23F: "Rinka",
    0xD27F: "Reo",
    0xD2BF: "Squeept",
    0xD2FF: "Geruta",
    0xD33F: "Holtz",
    0xD37F: "Oum (baby)",
    0xD3BF: "Choot",
    # ── Rippers ──
    0xD3FF: "Ripper II",
    0xD43F: "Ripper II (variant)",
    0xD47F: "Ripper",
    # ── Dragons / Shutters ──
    0xD4BF: "Dragon",
    0xD4FF: "Door Shutter",
    0xD53F: "Door Shutter 2",
    0xD57F: "Door Shutter 2 (variant)",
    0xD5BF: "Door Shutter 2 (variant 2)",
    0xD5FF: "Door Shutter 2 (variant 3)",
    # ── Common enemies ──
    0xD63F: "Waver",
    0xD67F: "Metaree",
    0xD6BF: "Fireflea",
    0xD6FF: "Skultera",
    0xD73F: "Elevator",
    0xD75F: "Zoomer (grey)",
    0xD77F: "Sciser",
    0xD7BF: "Oum",
    0xD7DF: "Ripper II",
    0xD7FF: "Tripper",
    0xD83F: "Skree (variant)",
    0xD87F: "Reo",
    0xD89F: "Waver",
    0xD8BF: "Reo (variant)",
    0xD91F: "Geemer",
    0xD8FF: "Metroid (modified)",
    0xD93F: "Sidehopper",
    0xD97F: "Dessgeega",
    0xD99F: "Dessgeega (big)",
    0xD9BF: "Sidehopper (big)",
    0xD9DF: "Sidehopper (big, variant)",
    0xD9FF: "Sidehopper (invincible)",
    # ── Flyers / Misc ──
    0xDA3F: "Dessgeega",
    0xDA7F: "Zoa",
    0xDABF: "Viola",
    0xDB3F: "Bang",
    0xDB4F: "Ship",
    0xDB7F: "Skree (Norfair)",
    0xDBBF: "Yard",
    0xDBCF: "Kago",
    0xDBFF: "Reflec",
    # ── Wall-crawlers ──
    0xDC3F: "Geemer (horizontal)",
    0xDC7F: "Zeela",
    0xDCBF: "Sova",
    0xDCFF: "Zoomer",
    0xDD3F: "Sova",
    0xDD7F: "Metroid",
    # ── Bosses ──
    0xDDBF: "Crocomire",
    0xDE3F: "Draygon (body)",
    0xDE7F: "Draygon (eye)",
    0xDEBF: "Draygon (tail)",
    0xDEFF: "Draygon (arms)",
    0xDF3F: "Spore Spawn",
    # ── Boulder / Kzan ──
    0xDFBF: "Boulder",
    0xDFFF: "Kzan",
    0xE03F: "Kihunter",
    0xE07F: "Hibashi",
    0xE0BF: "Puromi",
    0xE0FF: "Mini Kraid (belly spike)",
    # ── Ridley / Puyo ──
    0xE13F: "Ceres Ridley",
    0xE17F: "Ridley",
    0xE1BF: "Puyo",
    0xE27F: "Zebetite",
    # ── Kraid (verified from room enemy set $A1:9EB5) ──
    0xE2BF: "Kraid",
    0xE2FF: "Kraid (upper body)",
    0xE33F: "Kraid (belly spike 1)",
    0xE37F: "Kraid (belly spike 2)",
    0xE3BF: "Kraid (belly spike 3)",
    0xE3FF: "Kraid (flying claw 1)",
    0xE43F: "Kraid (flying claw 2)",
    0xE47F: "Kraid (flying claw 3)",
    # ── Phantoon ──
    0xE4BF: "Phantoon",
    0xE4FF: "Phantoon (piece)",
    0xE53F: "Phantoon (piece 2)",
    0xE57F: "Phantoon (piece 3)",
    # ── Friendly / Misc ──
    0xE5BF: "Etecoon",
    0xE5FF: "Dachora",
    0xE63F: "Evir",
    0xE67F: "Zero",
    0xE6BF: "Eye",
    0xE6FF: "Fune",
    0xE73F: "Namihe",
    0xE7BF: "Yapping Maw",
    0xE7FF: "Kago",
    # ── Norfair / Maridia ──
    0xE83F: "Lavaman",
    0xE87F: "Beetom",
    0xE8BF: "Puu",
    0xE8FF: "Work Robot",
    0xE93F: "Work Robot (broken)",
    0xE97F: "Zeb Spawner (pipe)",
    0xE9BF: "Alcoon",
    0xE9FF: "Atomic",
    0xEA3F: "Spa (variant)",
    0xEA7F: "Koma",
    # ── Kihunter variants (SMILE: HACHI = bee) ──
    0xEABF: "Kihunter (green)",
    0xEAFF: "Kihunter (green, wings)",
    0xEB3F: "Kihunter (red)",
    0xEB7F: "Kihunter (red, wings)",
    0xEBBF: "Kihunter (gold)",
    0xEBFF: "Kihunter (gold, wings)",
    # ── Mother Brain ──
    0xEC3F: "Mother Brain (phase 1)",
    0xEC7F: "Mother Brain (phase 2)",
    # ── Special / Remains ──
    0xED3F: "Torizo Corpse",
    0xED7F: "Hopper (remains)",
    0xEEBF: "Big Metroid",
    0xEEFF: "Torizo",
    0xEF3F: "Torizo (orbs)",
    0xEF7F: "Torizo (gold)",
    0xEFBF: "Torizo (gold orbs)",
    # ── Spawners / Misc ──
    0xF07F: "Shaktool",
    0xF0BF: "Shattered Glass",
    0xF193: "Zeb",
    0xF1D3: "Zebbo",
    0xF213: "Gamet",
    0xF253: "Geega",
    0xF293: "Botwoon",
    # ── Space Pirates ──
    0xF353: "Space Pirate",
    0xF413: "Space Pirate (Norfair)",
    0xF453: "Space Pirate (Maridia)",
    0xF493: "Space Pirate (Tourian)",
    0xF593: "Space Pirate Mk.II (Norfair)",
    0xF613: "Space Pirate Mk.II (Tourian)",
    0xF653: "Space Pirate Mk.III",
    0xF693: "Space Pirate Mk.III (Brinstar)",
    0xF6D3: "Space Pirate Mk.III (Norfair)",
    0xF713: "Space Pirate Mk.III (Norfair alt)",
    0xF753: "Space Pirate Mk.III (Maridia)",
    0xF793: "Space Pirate Mk.III (Tourian)",
    # ── Ceres-only ──
    0xE1FF: "Ceres Smoke/Steam",
    0xE23F: "Ceres Door FX",
}

# Boss grouping for documentation (using correct species IDs)
BOSS_GROUPS = {
    "Kraid": [0xE2BF, 0xE2FF, 0xE33F, 0xE37F, 0xE3BF, 0xE3FF, 0xE43F, 0xE47F],
    "Phantoon": [0xE4BF, 0xE4FF, 0xE53F, 0xE57F],
    "Draygon": [0xDE3F, 0xDE7F, 0xDEBF, 0xDEFF],
    "Ridley": [0xE13F, 0xE17F],
    "Mother Brain": [0xEC3F, 0xEC7F],
    "Spore Spawn": [0xDF3F],
    "Crocomire": [0xDDBF],
    "Botwoon": [0xF293],
    "Torizo": [0xEEFF, 0xEF3F, 0xEF7F, 0xEFBF],
}


def parse_species_header(rom, species_id):
    """Parse a 64-byte enemy species header from bank $A0."""
    snes_addr = 0xA00000 | species_id
    pc = snes_to_pc(snes_addr)

    if pc + 64 > len(rom):
        return None

    data = rom[pc:pc + 64]
    if len(data) < 64:
        return None

    tile_data_size_raw = read_u16(rom, pc + 0x00)
    pal_ptr = read_u16(rom, pc + 0x02)
    hp = read_u16(rom, pc + 0x04)
    damage = read_u16(rom, pc + 0x06)
    width = read_u16(rom, pc + 0x08)
    height = read_u16(rom, pc + 0x0A)
    ai_bank = read_u8(rom, pc + 0x0C)
    init_ai = read_u16(rom, pc + 0x12)
    parts = read_u16(rom, pc + 0x14)
    gfx_offset = read_u16(rom, pc + 0x36)
    gfx_bank = read_u8(rom, pc + 0x38)
    layer_ctrl = read_u8(rom, pc + 0x39)
    drop_ptr = read_u16(rom, pc + 0x3A)
    resist_ptr = read_u16(rom, pc + 0x3C)
    name_ptr = read_u16(rom, pc + 0x3E)

    tile_data_size = tile_data_size_raw & 0x7FFF
    tile_size_flag = bool(tile_data_size_raw & 0x8000)
    gfx_snes = (gfx_bank << 16) | gfx_offset if gfx_bank > 0 else 0

    # Layer control meaning
    layer_names = {0x02: "front", 0x05: "behind Samus", 0x0B: "behind BG"}
    layer_name = layer_names.get(layer_ctrl, f"0x{layer_ctrl:02X}")

    return {
        "species_id": species_id,
        "species_hex": f"${species_id:04X}",
        "name": KNOWN_ENEMIES.get(species_id, f"Unknown_{species_id:04X}"),
        "snes_addr": f"$A0:{species_id:04X}",
        "pc_offset": f"0x{pc:06X}",
        "hp": hp,
        "damage": damage,
        "width": width,
        "height": height,
        "hitbox": f"{width}x{height}",
        "ai_bank": f"${ai_bank:02X}",
        "init_ai": f"${ai_bank:02X}:{init_ai:04X}",
        "pal_ptr": f"${ai_bank:02X}:{pal_ptr:04X}" if pal_ptr else "none",
        "tile_data_size": tile_data_size,
        "tile_size_flag": tile_size_flag,
        "gfx_addr": f"${gfx_bank:02X}:{gfx_offset:04X}" if gfx_snes else "none (boss DMA)",
        "gfx_pc": f"0x{snes_to_pc(gfx_snes):06X}" if gfx_snes else "N/A",
        "parts": parts,
        "layer": layer_name,
        "drop_ptr": f"$B4:{drop_ptr:04X}" if drop_ptr else "none",
        "resist_ptr": f"$B4:{resist_ptr:04X}" if resist_ptr else "none",
        "name_ptr": f"$B4:{name_ptr:04X}" if name_ptr else "none",
    }


def scan_all_enemies(rom, species_list=None):
    """Scan all known enemy species from the ROM."""
    if species_list is None:
        species_list = sorted(KNOWN_ENEMIES.keys())

    results = []
    for sid in species_list:
        info = parse_species_header(rom, sid)
        if info:
            results.append(info)
    return results


def scan_samus_physics(rom):
    """Extract key Samus physics constants from the ROM."""
    # These are well-documented addresses from the SM decompilation
    constants = []

    # Run speed (bank $91)
    physics = [
        ("Max run speed", 0x91, 0xB629, 2, "subpixels/frame"),
        ("Run acceleration", 0x91, 0xB62B, 2, "subpixels/frame²"),
        ("Momentum cancel threshold", 0x91, 0xB62D, 2, "subpixels"),
        ("Jump height (normal)", 0xB4, 0x0B4C, 2, "initial Y velocity"),
        ("Jump height (spin)", 0xB4, 0x0B52, 2, "initial Y velocity"),
        ("Gravity", 0xB4, 0x0B36, 2, "subpixels/frame²"),
        ("Underwater gravity", 0xB4, 0x0B38, 2, "subpixels/frame²"),
    ]

    results = []
    for name, bank, offset, size, unit in physics:
        snes_addr = (bank << 16) | offset
        pc = snes_to_pc(snes_addr)
        if pc + size <= len(rom):
            val = read_u16(rom, pc) if size == 2 else read_u8(rom, pc)
            results.append({
                "name": name,
                "snes_addr": f"${bank:02X}:{offset:04X}",
                "pc_offset": f"0x{pc:06X}",
                "value": val,
                "hex": f"${val:04X}" if size == 2 else f"${val:02X}",
                "unit": unit,
            })

    return results


def scan_beam_damages(rom):
    """Extract beam and weapon damage values."""
    # Beam damage table at $90:CC40 (PC 0x084C40)
    beams = [
        ("Power Beam", 0x90, 0xCC04),
        ("Ice Beam", 0x90, 0xCC06),
        ("Wave Beam", 0x90, 0xCC08),
        ("Spazer", 0x90, 0xCC0A),
        ("Plasma Beam", 0x90, 0xCC0C),
        ("Charge (power)", 0x90, 0xCC14),
        ("Charge (ice)", 0x90, 0xCC16),
        ("Charge (wave)", 0x90, 0xCC18),
        ("Charge (spazer)", 0x90, 0xCC1A),
        ("Charge (plasma)", 0x90, 0xCC1C),
        ("Missile", 0x90, 0xCC2E),
        ("Super Missile", 0x90, 0xCC30),
        ("Power Bomb", 0x90, 0xCC32),
    ]

    results = []
    for name, bank, offset in beams:
        snes_addr = (bank << 16) | offset
        pc = snes_to_pc(snes_addr)
        if pc + 2 <= len(rom):
            val = read_u16(rom, pc)
            results.append({
                "name": name,
                "snes_addr": f"${bank:02X}:{offset:04X}",
                "pc_offset": f"0x{pc:06X}",
                "damage": val,
            })

    return results


def format_markdown_enemies(enemies):
    """Format enemy scan results as markdown tables, grouped by category."""
    lines = []

    # Categorize
    bosses = []
    minibosses = []
    common = []
    utility = []

    boss_ids = set()
    for ids in BOSS_GROUPS.values():
        boss_ids.update(ids)
    miniboss_ids = {0xE0FF}  # Mini Kraid belly spike
    utility_ids = {0xD73F, 0xD07F, 0xD0BF, 0xD13F, 0xD17F, 0xD1BF,  # Elevator, Ship, Chozo
                   0xD4FF, 0xD53F, 0xD57F, 0xD5BF, 0xD5FF,  # Door shutters
                   0xE1FF, 0xE23F}  # Ceres effects

    for e in enemies:
        sid = e["species_id"]
        if sid in boss_ids:
            bosses.append(e)
        elif sid in miniboss_ids:
            minibosses.append(e)
        elif sid in utility_ids:
            utility.append(e)
        else:
            common.append(e)

    def table(title, items):
        lines.append(f"\n### {title}\n")
        lines.append("| Species ID | Name | HP | Damage | Hitbox | AI Bank | GFX Addr | Layer |")
        lines.append("|-----------|------|-----|--------|--------|---------|----------|-------|")
        for e in items:
            lines.append(
                f"| `{e['species_hex']}` | {e['name']} | {e['hp']} | {e['damage']} "
                f"| {e['hitbox']} | `{e['ai_bank']}` | `{e['gfx_addr']}` | {e['layer']} |"
            )

    if bosses:
        table("Major Bosses", bosses)
    if minibosses:
        table("Mini-Bosses", minibosses)
    if common:
        table("Common Enemies", common)
    if utility:
        table("Utility Entities", utility)

    return "\n".join(lines)


def format_markdown_boss_detail(enemies):
    """Format detailed boss group information."""
    lines = []

    for boss_name, boss_ids in BOSS_GROUPS.items():
        boss_enemies = [e for e in enemies if e["species_id"] in boss_ids]
        if not boss_enemies:
            continue

        lines.append(f"\n### {boss_name}\n")
        lines.append("| Entity | ID | HP | Dmg | Hitbox | Palette | Init AI | Tile Size | GFX |")
        lines.append("|--------|-----|-----|------|--------|---------|---------|-----------|-----|")
        for e in boss_enemies:
            lines.append(
                f"| {e['name']} | `{e['species_hex']}` | {e['hp']} | {e['damage']} "
                f"| {e['hitbox']} | `{e['pal_ptr']}` | `{e['init_ai']}` "
                f"| {e['tile_data_size']} | `{e['gfx_addr']}` |"
            )

    return "\n".join(lines)


def format_markdown_samus(physics):
    """Format Samus physics as markdown."""
    lines = ["\n### Samus Physics Constants\n"]
    lines.append("| Parameter | Address | Value | Hex | Unit |")
    lines.append("|-----------|---------|-------|-----|------|")
    for p in physics:
        lines.append(
            f"| {p['name']} | `{p['snes_addr']}` | {p['value']} | `{p['hex']}` | {p['unit']} |"
        )
    return "\n".join(lines)


def format_markdown_beams(beams):
    """Format beam damage table as markdown."""
    lines = ["\n### Weapon Damage Values\n"]
    lines.append("| Weapon | Address | Damage |")
    lines.append("|--------|---------|--------|")
    for b in beams:
        lines.append(f"| {b['name']} | `{b['snes_addr']}` | {b['damage']} |")
    return "\n".join(lines)


def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <rom_path> [--markdown|--json|--species 0xNNNN]")
        sys.exit(1)

    rom_path = sys.argv[1]
    with open(rom_path, 'rb') as f:
        rom = f.read()

    # Detect and strip SMC header
    if len(rom) % 0x8000 == 0x200:
        print(f"[info] Detected SMC header (512 bytes), stripping.", file=sys.stderr)
        rom = rom[0x200:]

    output_mode = "text"
    single_species = None

    for i, arg in enumerate(sys.argv[2:], 2):
        if arg == "--markdown":
            output_mode = "markdown"
        elif arg == "--json":
            output_mode = "json"
        elif arg == "--species" and i + 1 < len(sys.argv):
            single_species = int(sys.argv[i + 1], 0)

    if single_species:
        info = parse_species_header(rom, single_species)
        if info:
            if output_mode == "json":
                print(json.dumps(info, indent=2))
            else:
                for k, v in info.items():
                    print(f"  {k}: {v}")
        else:
            print(f"Could not parse species ${single_species:04X}", file=sys.stderr)
        return

    enemies = scan_all_enemies(rom)
    physics = scan_samus_physics(rom)
    beams = scan_beam_damages(rom)

    if output_mode == "json":
        print(json.dumps({
            "enemies": enemies,
            "samus_physics": physics,
            "beam_damages": beams,
        }, indent=2))
    elif output_mode == "markdown":
        print("# Super Metroid Enemy & Entity Data (ROM Scan)\n")
        print(f"Source ROM: `{Path(rom_path).name}` ({len(rom)} bytes)\n")
        print("---\n")
        print("## All Enemies by Category")
        print(format_markdown_enemies(enemies))
        print("\n---\n")
        print("## Boss Detail View")
        print(format_markdown_boss_detail(enemies))
        print("\n---\n")
        print("## Samus & Weapons")
        print(format_markdown_samus(physics))
        print(format_markdown_beams(beams))
    else:
        print(f"Scanned {len(enemies)} enemy species:\n")
        for e in enemies:
            print(f"  {e['species_hex']}  {e['name']:30s}  HP={e['hp']:5d}  "
                  f"Dmg={e['damage']:3d}  {e['hitbox']:10s}  AI={e['ai_bank']}  "
                  f"GFX={e['gfx_addr']}")

        print(f"\nSamus Physics:")
        for p in physics:
            print(f"  {p['name']:35s}  {p['snes_addr']}  = {p['value']} ({p['hex']})")

        print(f"\nWeapon Damages:")
        for b in beams:
            print(f"  {b['name']:25s}  {b['snes_addr']}  = {b['damage']}")


if __name__ == "__main__":
    main()
