# Ordinary Enemy Source Animation Routes

SMEDIT's generic ordinary-enemy preview scanner is intentionally conservative. It can
recognize common direct instruction-list assignments, but it does not emulate arbitrary
enemy init AI, helper routines, or state tables. When the exact source makes an indirect
route unambiguous, SMEDIT records that route explicitly instead of broadening the scanner
with a guess.

The first E-11 source-routed group covers Puyo `$CFBF`, Owtch `$D03F`, Choot `$D3BF`,
and the two Sbug/roach headers `$D87F/$D8BF`. `parityOrdinaryEnemyAnimations` joins
each exact bank-`$A2/$A3` header, raw tile
owner, header palette, named instruction list, timed frame, and OAM map. Production
rendering and the source manifest must agree on 39 maps / 56 OAM entries, 23 lists / 60
source frame occurrences, and 18 selectable actions / 65 guided render frames. Raw
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
