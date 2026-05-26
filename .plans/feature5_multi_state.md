# Feature 5: Multi-State Room Editing

## Goal
Edit enemies, PLMs, scrolls, FX, and music independently per room state.
Essential for boss rooms (e.g., Phantoon's room has different enemies
before/after boss defeat) and event-driven progression.

## Background
Super Metroid rooms can have up to 9 states, selected by game conditions:
- State 0: Default (usually the last state checked)
- State 1+: Conditional states (boss defeated, event flag set, etc.)

Each state has its OWN:
- Level data pointer (often shared across states)
- Enemy population pointer
- PLM set pointer
- Scroll data pointer
- FX1 pointer
- Music track
- Tileset
- BG scrolling mode

Currently we only load/edit state 0. Multi-state editing means:
1. Show which states exist and what triggers them
2. Let users switch between states to view/edit each one
3. On export, write changes to the correct state's data

## Where it lives
- **Room Info tab** (left sidebar) — state selector dropdown at the top
- Shows state condition (e.g., "Phantoon defeated", "Event $0012 set")
- Switching states reloads enemies, PLMs, scrolls, FX for that state

## State data structure (from SM disassembly)
Room header at $8F:xxxx contains:
- State select code pointers (2 bytes each, until $E5E6 = default state)
- Each state pointer leads to a state condition check
- If condition met → use state data at a specific offset
- State data: 26 bytes containing all the pointers

State data layout (26 bytes):
+$00: Level data pointer (3 bytes)
+$03: Tileset
+$04: Music data/track
+$05: Music control
+$06: FX1 pointer (2 bytes)
+$08: Enemy population pointer (2 bytes)
+$0A: Enemy set pointer (2 bytes)
+$0C: Layer 2 scroll (2 bytes)
+$0E: Scroll data pointer (2 bytes)
+$10: X-ray/special pointer (2 bytes)
+$12: Main ASM pointer (2 bytes)
+$14: PLM set pointer (2 bytes)
+$16: BG data pointer (2 bytes)
+$18: Layer 1+2 setup pointer (2 bytes)

## Implementation Plan

### Phase 1: State awareness (read-only)
1. Add `parseRoomStates(roomId)` to RomParser — returns list of RoomState objects
2. Each RoomState has: condition type, condition value, and all 26 bytes of state data
3. Show state list in Room Info tab with condition descriptions
4. Add state selector dropdown

### Phase 2: State switching
1. When user selects a different state, reload:
   - Enemy population from that state's enemy pointer
   - PLM set from that state's PLM pointer
   - Scroll data from that state's scroll pointer
   - FX data from that state's FX pointer
   - Music track from that state's music field
2. MapCanvas re-renders with the new state's data
3. Level data may be shared — detect and handle

### Phase 3: Per-state editing
1. Track edits per state (not just per room)
2. On export, write changes to the correct state's pointers
3. Handle shared level data (if states share the same level data pointer,
   edits apply to all states that share it)

### Phase 4: State creation/deletion (future)
- Add new states with custom conditions
- Remove unused states

## Key challenges
- **Shared pointers**: Multiple states often share level data, PLM sets, or enemy populations.
  Editing one state's shared data affects all states that share it.
- **State condition parsing**: Need to decode the condition check routines to show
  human-readable descriptions ("Phantoon defeated", "Event $0012 set").
- **Export complexity**: Each state has its own set of pointers that may need repointing.

## Tests
- Parse Phantoon's room states (should have at least 2: before/after boss defeat)
- Parse Parlor states (3 states for different progression points)
- Verify state condition descriptions match expected values
- Verify switching states loads different enemy populations
- Export with state-specific edits preserves correct state data

## Known state conditions (from SM disassembly)
- $E5EB: Event set (2-byte event flag)
- $E5FF: Boss defeated (area boss flag)
- $E612: Morph ball obtained
- $E629: Missiles obtained
- $E640: Power bombs obtained
- $E652: Speed booster obtained
- $E669: Default state (always true — the fallback)
