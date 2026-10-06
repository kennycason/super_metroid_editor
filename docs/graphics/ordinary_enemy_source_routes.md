# Ordinary Enemy Source Animation Routes

SMEDIT's generic ordinary-enemy preview scanner is intentionally conservative. It can
recognize common direct instruction-list assignments, but it does not emulate arbitrary
enemy init AI, helper routines, or state tables. When the exact source makes an indirect
route unambiguous, SMEDIT records that route explicitly instead of broadening the scanner
with a guess.

The E-11 source-routed group covers Puyo `$CFBF`, Owtch `$D03F`, Choot `$D3BF`,
the two Sbug/roach headers `$D87F/$D8BF`, Evir plus its internal projectile
`$E63F/$E67F`, Magdollite `$E83F`, Beetom `$E87F`, the three Kihunter color
headers `$EABF/$EB3F/$EBBF`, and both Sidehopper corpse headers `$ED7F/$EDBF`.
`parityOrdinaryEnemyAnimations` joins
each exact bank-`$A2/$A3/$A8/$A9` header, raw tile
owner, header palette, named instruction list, timed frame, and OAM map. Production
rendering and the source manifest must agree on 160 maps / 764 OAM entries, 78 lists / 264
source frame occurrences, and 59 selectable actions / 255 guided render frames. Raw
component and complete-animation pixel hashes make a plausible but incorrectly routed
preview fail parity.

## Puyo

Puyo's init AI passes `InstList_Puyo_GroundedDropping_Fast` through
`SetPuyoInstList`; a direct store to the enemy instruction-list field is not present in
the init routine. The editor exposes the three exact four-frame grounded loops:

- fast `$A2:99AD` — five ticks per pose;
- medium `$A2:99C1` — eight ticks per pose;
- slow `$A2:99D5` — ten ticks per pose.

Its five hopping pose lists at `$A2:99E9..9A01` each draw one frame and then sleep.
Gameplay AI selects and advances those lists as Puyo moves. The editor's “Hop poses”
actions therefore join the five exact right- or left-ordered poses for inspection; they
are not presented as a source-owned self-running timing loop.

## Owtch

Owtch init chooses a direction through a helper. Each `_0` list runs a direction setup
handler and falls through to its corresponding `_1` visual loop. The editor starts the
preview at the independently looping visual lists—left `$A2:A3AD` and right `$A2:A3BF`—
while the manifest retains both setup lists so their source boundary and relationship
cannot disappear silently. Each direction has three eight-tick poses.

## Choot

Choot init passes idle list `$A2:D82C` through `SetChootInstList`. The editor also
exposes the exact two-frame jumping list `$A2:D834` and two-frame falling list
`$A2:D840`. These are state-selected sequences, so they remain separate actions rather
than being invented into one looping animation.

## Sbug / roach

The source calls `$D87F/$D8BF` `Sbug` and describes their AI as a roach. Older SMEDIT
tables mislabeled both as Reo, even though the actual Reo is `$D27F`. The two Sbug
headers share pixels, palette, AI, OAM, and animation lists; `$D8BF` differs by setting
the header's alternate-VRAM-layout bit.

Init derives a direction index from the enemy's room parameter and looks it up through
`InstListPointers_Sbug`, so a direct list pointer does not appear in init. The editor
exposes all eight exact four-frame, five-tick loops: up, up-left, left, down-left, down,
down-right, right, and up-right. Both header variants use this one source-owned action
set rather than duplicating placement or pixel ownership.

## Evir

Evir is a three-slot possessor family rather than three independent enemies. Header
`$E63F` owns the body and arms slots; `$E67F` is the separately typed projectile slot.
Both headers point to `Tiles_Evir` and `Palette_Evir`, and the projectile should not be
offered independently in the room-enemy picker or sprite navigation.

Body loops begin at `$A8:86A7` (left) and `$A8:870B` (right), each with six ten-tick
poses. The adjacent arms slot uses `$A8:86C3` and `$A8:8727`, each with sixteen
ten-tick poses followed by a three-tile 48-tick resting pose. The editor keeps all four
component loops and the normal attached projectile pose at `$A8:876F` under the single
Evir entry. `$E67F` still has its own source-verified default preview, so it is covered
by parity without becoming a misleading standalone editor item.

Projectile regeneration starts at `$A8:8775` and uses custom handlers to adjust its X
offset, repeat a regeneration pose, and return to the normal pose. Those handler records
and both regeneration frames are pinned in the manifest, but SMEDIT does not invent a
self-running loop for the AI-controlled movement sequence.

## Magdollite / Lavaman

The community-facing Lavaman entry is source `Magdollite` `$E83F`, a three-slot enemy:
head, growing pillar, and throwing hand. Its source actions expose both four-frame idle
directions, both six-pose lava throws, both five-pose submerges, the left/right base-pillar
loops, six ordered pillar heights, both narrow-pillar orientations, and the hand cap.
Movement, sound, projectile-spawn, and wait-flag handlers are traversed only where their
source-defined widths are known; the preview does not attempt to simulate their gameplay
side effects.

## Beetom

Beetom `$E87F` now exposes four-frame left/right crawling loops, four-pose hops, the
left/right latch sequence, and both draining loops. Its init AI enters a direction setup
list before falling through to the visual crawl loop. SMEDIT uses those independently
labeled visual entries for the default preview while preserving the setup lists in the
manifest.

## Kihunter

Kihunter is a two-slot enemy family, not six unrelated sprites. The green, red, and
gold body headers `$EABF/$EB3F/$EBBF` each transfer the complete `$1000`-byte
`Tiles_Kihunter` owner. Their paired `$EAFF/$EB7F/$EBFF` headers are runtime slot-2
wing companions that transfer only the first `$0200` bytes from that same owner. A
generic standalone wing entry could therefore pair the short transfer with neighboring
body maps and produce the disconnected shapes seen in the old preview.

Sprite navigation now presents one entry per color. Each workspace contains ten exact
body actions—idle, swipe, hop, land, and acid attack facing both ways—plus left/right
wing flaps and the falling-wing pose. Wing actions render the complete Kihunter: attached
left/right wing frames synchronize one-for-one with the matching idle-body frames, with
the body layered above the wing slot in runtime OAM order. Falling wings use one stable
body reference pose because their later position is moved by AI rather than encoded in
the spritemap. The wing headers remain valid in room/runtime data
and retain source-verified defaults; they are simply no longer presented as independent
characters. The source fixture pins 41 maps / 300 OAM entries, 13 lists / 59 frame
occurrences, all three palettes, and all 13 actions. Large tile ownership alone no longer
activates the broad boss-pose scanner, so Kihunter and corpse sheets cannot acquire an
unrelated “Body Pose Preview.”

## Sidehopper corpse

`$ED7F` and `$EDBF` share the same `$A9` OAM and behavior lists but do not share one raw
pixel owner. Tourian loads the common corpse block at OBJ tile `$100`, followed by the
large Sidehopper block at `$170`; dead poses use the first block while living idle/hop
poses cross into the second. Production therefore builds the exact combined runtime
buffer for rendering while keeping each header's `GRAPHADR` bytes separately editable.
Both variants expose hopping, idle, drained-corpse, and dead actions.

## Editing boundary

The species' raw `GRAPHADR` pixels and header palette remain editable through the normal
enemy source editor. OAM placement, instruction timing, and AI action selection are
source-verified but read-only. Expanding editable animation logic requires a separate
relocation/export design; this route only makes the vanilla composition accurate and
regression-tested.

Generated evidence is in ignored
`parity/reports/ordinary-enemy-animations.json`. The production definitions live in
`SourceEnemyAnimations`, and `OrdinaryEnemyAnimationSourceParityTest` prevents their
addresses, timing, ownership, or rendered pixels from drifting from the source.
