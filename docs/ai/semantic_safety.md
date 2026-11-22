# SMEDIT AI Semantic Safety

SMEDIT AI operates through the same provider-neutral semantic API intended for the desktop UI, CLI, MCP, and future web clients. Models may propose work, inspect structured state, and stage mutations. SMEDIT—not the model—owns validation, exact planning, approval, atomic application, and undo/redo.

## State-sensitive room music

A room-level music edit is exported to every native runtime state. That is safe only when those states are semantically interchangeable for music.

Boss, event, escape, and progression rooms often use different music pairs in different states. For example, Bomb Torizo room `$9804` uses three source pairs (`$00:$00`, `$00:$03`, and `$24:$03`). Flattening them to Lower Norfair (`$18:$05`) removes encounter-specific behavior and can conflict with scripted song-set/play-index transitions.

The `set_room_music` contract therefore:

- inspects source music for every runtime state;
- detects existing project-owned state music edits;
- protects state-sensitive rooms by default;
- lists protected rooms in the approval plan;
- requires `overwrite_special_state_music=true` for an explicit destructive opt-in;
- preserves optimistic snapshots so stale approval, undo, and redo fail closed.

Tourian has a second, independent safety rule. Metroid, Baby Metroid, Mother
Brain, and escape sound effects borrow instruments from the native Tourian song.
Many Tourian room states use `$00:$00` to inherit the song loaded by an earlier
room, so protecting only the room containing the enemy is insufficient. A bulk
write to a preceding corridor can carry an incompatible song into the encounter.
The resulting SPC failure can leave all three sound queues non-empty; door state
`$000B` then waits forever in `$82:E29E` for those queues to drain.

SMEDIT therefore protects every Tourian room by default, even when all of its
source states have the same music bytes. `overwrite_special_state_music` does not
bypass this rule. Replacing Tourian music requires the separate, explicit
`overwrite_audio_sensitive_music=true` opt-in, and the approval plan warns that
doing so can glitch audio or deadlock a door transition.

Documentation and model instructions explain this invariant, but correctness does not depend on the model remembering it. The mutation boundary enforces it deterministically.

## Room-state FX

FX is state-scoped and one FX table can contain ordered incoming-door variants
before its default record. `get_room` and `list_room_fx` therefore expose every
runtime state and every effective FX record; an agent must not infer a room's
other states from the currently previewed state.

`set_room_fx` initially edits default records only and preserves door-specific
records. Its approval planner deep-copies the affected room models, materializes
state identities only in those staged copies, and freezes exact before/after
snapshots. Approval, undo, and redo compare the whole affected room snapshots so
an unrelated newer room edit cannot be overwritten accidentally.

The mutation boundary protects these cases by default:

- conditional boss, event, progression, and incoming-door states unless the
  request explicitly sets `include_conditional_states=true`;
- ASM-managed FX and unreadable state graphs;
- Mother Brain's runtime-indexed `$83:A0A4` FX table;
- both tide modes selected together;
- a real tide combined with `$FFFF/$FFFF` liquid heights, which can wrap liquid
  through the room.

The canonical `fxBitC` meanings are `flow_left=0x01`,
`bg_animate=0x02`, `bg_liquid=0x04`, `large_tide=0x40`, and
`small_tide=0x80`. Tides are independent from the BG2 shimmer. For an invisible
lava heat wave, use Lava with `$FFFF/$FFFF`, `bg_animate`, and no tide. See
[`rom/lava_acid_fx_rendering.md`](../rom/lava_acid_fx_rendering.md).

## Context hierarchy

Use the smallest authoritative context that answers the question:

1. Structured workspace tools for live project, room, patch, and state data.
2. `search_docs` and `read_doc` for curated SMEDIT/ROM constraints and verified behavior.
3. `search_asm` for the loaded source workspace, including unsaved buffers, when ASM mode is relevant.
4. Explicit build/export/emulator validation before claiming runtime behavior.

Do not inject the entire documentation tree or ASM corpus into every prompt. Retrieval keeps context current, focused, attributable, and within provider limits.

## Learning from incidents

When an AI-assisted edit reveals a new failure mode:

1. Repair the affected project without discarding unrelated edits.
2. Reduce the incident to a semantic invariant.
3. Enforce that invariant in the provider-neutral API or mutation planner.
4. Expose the relevant evidence in read tools and approval output.
5. Add regression coverage.
6. Document the behavior and validation route.

Conversation history can preserve user intent, but it is not a safety database. Durable lessons belong in versioned docs, schemas, policy code, and tests.
