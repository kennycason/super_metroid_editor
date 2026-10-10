# SMEDIT ASM module examples

These standalone modules are small, readable examples of extending Super Metroid through
SMEDIT's Project ASM workflow. They are source code—not prebuilt ROM patches—and are intended
to be copied into a project's **ASM > Modules** collection, enabled, and built from SMEDIT.

| Example | What it demonstrates |
| --- | --- |
| [`simple_hud_mod.asm`](simple_hud_mod.asm) | A minimal fixed-location `org` override that draws a missile watermark in unused HUD tiles. |
| [`high_jump_without_boots.asm`](high_jump_without_boots.asm) | A symbolic data-table override that gives ordinary jumps the vanilla Hi-Jump velocities. |
| [`objectives_pause_screen.asm`](objectives_pause_screen.asm) | A complete third pause page with hooks, an extended state machine, runtime tilemap/DMA work, live boss-state reads, and Super Metroid's native pause font. |

## Trying an example

1. Open a ROM-backed SMEDIT project and enable **Project ASM**.
2. Under **ASM > Modules**, create a module and paste in one example (or use the matching
   module already present in the checked-in Super Metroid Sandbox project).
3. Keep the module enabled, then choose **Build** or launch **EMU**.
4. Disable the module to test the same project without it; its source and ordering are preserved.

The examples target the pinned
[InsaneFirebat Super Metroid disassembly](https://github.com/InsaneFirebat/sm_disassembly)
used by SMEDIT. No ROM or extracted binary asset is included here. See the
[ASM workflow contract](../../docs/asm/reference_workspace.md) for workspace setup, source
authority, module ordering, and the compile-then-patch build pipeline.

## Compatibility notes

- Modules are assembled in their displayed project order. Two modules cannot safely own the
  same hook, table, or free-space range unless they were explicitly designed to cooperate.
- `objectives_pause_screen.asm` hooks vanilla pause-menu routines and uses `$82:F836..FAC2` for
  code/tables. Its placement intentionally follows SMEDIT's bundled Spider Ball range at
  `$82:F7C0..F835`. Text uses Super Metroid's existing pause-menu A-Z tiles `$30..$49`, so the
  module adds no font graphics and does not replace shared Equipment-screen tiles.
- The copies in this directory are the repository-facing references. Runnable copies also live
  in `projects/Super Metroid Sandbox/Super Metroid Sandbox_smedit/asm/modules/` so the Sandbox can
  demonstrate all three without setup.
