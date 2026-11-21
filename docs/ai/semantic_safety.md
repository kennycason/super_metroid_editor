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

Documentation and model instructions explain this invariant, but correctness does not depend on the model remembering it. The mutation boundary enforces it deterministically.

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
