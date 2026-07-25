package com.supermetroid.editor.ui

enum class TileOverlay(val label: String, val shortLabel: String, val color: Long) {
    // Block types (from level data bits 12-15)
    SOLID("Solid", "S", 0xCC4488FF),       // blue
    SLOPE("Slope", "/", 0xCCEE7700),       // orange
    DOOR("Door", "D", 0xCC6080B0),         // gray-blue (casing color varies by PLM in render)
    SPIKE("Spike", "!", 0xCCFF4444),       // red
    BOMB("Bomb", "B", 0xCCAA44DD),         // purple
    CRUMBLE("Crumble", "C", 0xCCBB5522),   // brown/rust
    GRAPPLE("Grapple", "G", 0xCC00AA88),   // teal
    SPEED("Speed Booster", "~", 0xCC66AAFF),   // light blue (type 0xB + BTS 0x0E/0x0F)
    TREADMILL("Treadmill", "T", 0xCC44CCCC),   // cyan (type 0x3)
    // Shot blocks by break method (block type 0xC + BTS)
    SHOT_BEAM("Shot (Beam)", "Xb", 0xCCFFDD00),    // yellow: beam/missile/bomb
    SHOT_SUPER("Shot (Super)", "Xs", 0xCC00CC44),   // green: super missile required
    SHOT_PB("Shot (PB)", "Xp", 0xCCCC44AA),         // magenta: power bomb
    @Deprecated("BTS 0x0C-0x0D are non-functional in vanilla SM", level = DeprecationLevel.HIDDEN)
    SHOT_MISSILE("Shot (Missile)", "Xm", 0xCCFF8844), // NOT functional in vanilla SM
    // Items/powerups (from PLM data; drawn when we have item positions)
    ITEMS("Items", "I", 0xCCFFCC00),       // gold/yellow
    // Enemies (from enemy population data in bank $A1)
    ENEMIES("Enemies", "E", 0xCCFF6644),   // orange-red
    // Scroll PLMs (B703, B63B, B647 — runtime scroll triggers)
    SCROLL_PLMS("Scroll Triggers", "St", 0xCCFF8040),  // orange
    // Per-screen scroll colors (Red/Blue/Green)
    SCROLLS("Scroll Colors", "Sc", 0x60FFFFFF),
    // Liquid level (water/lava/acid from FX data)
    LIQUID("Liquid Level", "~", 0x443388FF),
    // Layer 2 background (BG data tilemap or embedded L2)
    LAYER2("Layer 2", "L2", 0x6088AACC),
    // Layer 3 visual (fog, rain, spores, heat shimmer)
    LAYER3("Layer 3", "L3", 0x60AACCFF),
    // Lighten: brighten dark rooms (Fireflea, etc.)
    LIGHTEN("Lighten", "L", 0x00FFFFFF),
}
