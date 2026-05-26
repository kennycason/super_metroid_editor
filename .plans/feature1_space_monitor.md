# Feature 1: Space Utilization Monitor

## Goal
Show real-time bytes-used vs bytes-available for each room data section.
Warn users before they overflow and corrupt adjacent data.

## Where it lives
- **Room Info tab** (right panel) — a collapsible "Space Usage" section below Room Scrolls
- Shows per-section bars: Level Data, PLMs, Enemies, Scroll Data, Door List

## Data sources
Each section has a ROM pointer and a known size boundary:
- **Level Data**: Decompressed → recompressed. Compare compressed size vs original compressed size.
  Use `RomParser.decompressLZ2WithSize()` which returns (data, compressedSize).
- **PLM Set**: Count entries × 6 bytes + 2 (terminator). Compare vs next PLM set offset.
- **Enemy Population**: Count entries × 16 bytes + 2 (terminator).
- **Scroll Data**: width × height bytes. Fixed size based on room dimensions.
- **Door List**: Count entries × 12 bytes + 2 (terminator).

## Size limits
For each pointer, the "available" space is the distance to the NEXT data block in the same bank.
We can estimate this by sorting all known pointers within the bank and finding the gap.
Simpler approach: just show current size and warn if it grew (e.g., after adding PLMs/enemies).

## UI Design
```
Space Usage
━━━━━━━━━━━━━━━━━━━━━
Level Data  ████████░░  3.2KB / 4.1KB
PLMs        ████░░░░░░  156B / 512B
Enemies     ██░░░░░░░░  48B / 256B
Scrolls     ████████░░  25B / 25B (fixed)
Doors       ███░░░░░░░  60B / 120B
```

## Implementation
1. Add `readRoomSpaceUsage(roomId)` to RomParser — returns a data class with sizes
2. Add `SpaceUsageSection` composable to RoomPropertiesPanel
3. Color-code: green (< 75%), yellow (75-90%), red (> 90%)

## Tests
- Verify Landing Site and Parlor have valid size readings
- Verify PLM count × 6 + 2 matches expected sizes
