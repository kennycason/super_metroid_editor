# Spider Ball Behavior Plan

## Goal

Build Spider Ball as a wall and ceiling traversal mode while leaving normal Morph Ball ground movement entirely under Super Metroid's vanilla physics.

The current prototype keeps breaking when Spider owns ground movement, especially downhill slopes. The planned reset is to stop treating floors as Spider surfaces. Ground, slopes, mockball, falling, and normal rolling should remain vanilla Morph Ball behavior unless Samus intentionally attaches to a wall.

## Inventory Screen Plan

Spider Ball should become a real item in editor data and ROM pickup data first, then get in-game pause-screen presentation as a follow-up patch.

Planned pause-screen layout:

- Extend the Misc section by one text row.
- Put `SPIDER BALL` under `SPRING BALL` and above `SCREW ATTACK`.
- Move the Boots section down one row so Misc has room for the new item text.
- Keep Spider Ball out of the beam/boot/suit sections; it is a Misc upgrade.
- Update the equipment-screen tilemaps, item bitmask tables, selector positions, and category movement bounds together so cursor navigation and text display remain aligned.
- Initially Spider Ball can be treated as collected-only behavior, matching the movement patch's current item gate. If we later want pause-screen toggling, switch movement gating from `collected_items` to `equipped_items`.

## State Model

### VanillaMorph

Default Super Metroid morph or spring-ball behavior.

Expected behavior:

- Flat ground uses vanilla movement speed and acceleration.
- Ground slopes use vanilla slope handling.
- Falling morph uses vanilla falling behavior.
- Mockball and other vanilla movement quirks remain untouched.
- Spider is inactive.

### SpiderWallLeft

Samus is attached to a wall on her left.

Expected behavior:

- `Up` moves up the wall.
- `Down` moves down the wall.
- No input stops movement.
- `Jump` releases Spider.
- Left/right do not drive wall movement after attachment.
- Pressing into the wall may be ignored.
- Pressing away from the wall should initially be ignored for stability.

### SpiderWallRight

Samus is attached to a wall on her right.

Expected behavior:

- `Up` moves up the wall.
- `Down` moves down the wall.
- No input stops movement.
- `Jump` releases Spider.
- Left/right do not drive wall movement after attachment.
- Pressing into the wall may be ignored.
- Pressing away from the wall should initially be ignored for stability.

### SpiderCeiling

Samus is attached to a ceiling.

Expected behavior:

- `Left` moves left across the ceiling.
- `Right` moves right across the ceiling.
- No input stops movement.
- `Jump` releases Spider.
- Up/down should initially be ignored on ceilings.
- Losing ceiling contact at a plain edge drops to vanilla morph unless a valid wall corner is found.

### CornerRecover

Short-lived recovery used only when contact is lost while moving through a corner.

Expected behavior:

- Probe the expected continuation direction for nearby wall, ceiling, or floor contact.
- If ceiling is found from a wall corner, enter `SpiderCeiling`.
- If wall is found from a ceiling corner, enter the matching wall state.
- If floor is found, release to `VanillaMorph`.
- If no valid contact is found, detach to `VanillaMorph`.

## Corner Nudge Strategy

Corner transitions should use small, collision-safe nudges instead of broad surface searching.

When Spider movement loses its current wall or ceiling contact, the recovery routine should:

1. Save Samus's current position and relevant collision flags.
2. Try a 1 px nudge in the expected corner direction.
3. Re-run contact probes.
4. Commit the nudge only if the contact result matches the expected transition.
5. Restore the saved position if the probe does not match.

This keeps the assist predictable. The nudge should help Samus negotiate corners, not magnetize her to unrelated nearby surfaces.

### Wall Corner Nudge Directions

The wall side is named relative to Samus.

| Current state | Movement | First nudge | Expected result |
|---------------|----------|-------------|-----------------|
| `SpiderWallRight` | `Up` | Right, toward wall | Floor contact means top ledge; release to vanilla morph |
| `SpiderWallLeft` | `Up` | Left, toward wall | Floor contact means top ledge; release to vanilla morph |
| `SpiderWallRight` | `Up` | Left, away from wall | Ceiling contact means transition to `SpiderCeiling` |
| `SpiderWallLeft` | `Up` | Right, away from wall | Ceiling contact means transition to `SpiderCeiling` |
| `SpiderWallRight` | `Down` | Left, away from wall | Ceiling contact means transition to `SpiderCeiling` around underside ledge |
| `SpiderWallLeft` | `Down` | Right, away from wall | Ceiling contact means transition to `SpiderCeiling` around underside ledge |

For `Up` movement, top ledge release should be checked before ceiling transition. If the toward-wall nudge finds floor contact, Spider should release immediately so vanilla morph owns the ledge.

For `Down` movement, the main useful assist is an away-and-up nudge into underside ceiling contact.

### Ceiling Corner Nudge Directions

Ceiling exits should mirror the wall behavior.

| Current state | Movement | First nudge | Expected result |
|---------------|----------|-------------|-----------------|
| `SpiderCeiling` | `Left` | Down/left or left/down probe | Left wall contact means transition to `SpiderWallLeft` |
| `SpiderCeiling` | `Right` | Down/right or right/down probe | Right wall contact means transition to `SpiderWallRight` |

The exact order may need tuning against Super Metroid's collision helpers. The important rule is that the nudge must only commit when the matching wall contact is found.

### Nudge Limits

Expected constraints:

- Start with 1 px per frame.
- Allow at most a small fixed recovery window, such as 2 to 4 px total.
- Never push through solid collision.
- Never keep searching after floor contact is found; release to vanilla morph.
- Never use corner nudges during floor-only vanilla morph movement.
- Prefer no transition over a surprising transition.

## Activation Rules

Spider should activate from intentional side wall contact, or from ceiling contact when pressing `Left` or `Right`.

Expected behavior:

- Left wall contact plus pressing `Left` attaches to the left wall.
- Right wall contact plus pressing `Right` attaches to the right wall.
- Floor-only contact never activates Spider.
- Ceiling-only contact plus holding `Left` or `Right` activates Spider from vanilla morph, so spring-ball jumps and horizontal rolls can cling to ceilings without using unmorph input.
- In a 1-tile shaft, pressing `Left` chooses the left wall and pressing `Right` chooses the right wall.
- Inactive falling morph should not attach to a wall from `Up` or `Down` alone.

## Release Rules

Spider should release cleanly back to vanilla morph whenever Super Metroid should own the movement again.

Expected behavior:

- `Jump` releases Spider.
- Unmorph input releases or dispatches to vanilla pose handling.
- Floor-only contact releases Spider.
- Moving up a wall onto a top ledge releases to vanilla morph if floor contact is found.
- Moving down a wall onto ground releases to vanilla morph if floor contact is found.
- Losing all contact releases to vanilla morph unless `CornerRecover` finds a valid continuation.

## Ground And Slopes

Spider should not control ground movement.

Expected behavior:

- Morphing on flat ground does not slow Samus down.
- Rolling left or right on flat ground is vanilla morph.
- Rolling down slopes is vanilla morph and should not hop.
- Rolling up slopes is vanilla morph unless Samus reaches a wall and presses into it.
- Spider movement routines should not call native ground movement or manually adjust X/Y while floor-only contact is active.

## Wall Movement

Wall movement is the first stable Spider feature to preserve.

Expected behavior:

- Roll or fall into a right wall while pressing `Right` to attach.
- Roll or fall into a left wall while pressing `Left` to attach.
- Once attached, `Up` and `Down` move along the wall.
- No input stops movement without detaching.
- Wall movement should zero vanilla morph velocity while active.
- Wall movement should not depend on left/right input after attachment.

## Ledge Behavior

Top ledges should hand control back to vanilla morph.

Expected behavior:

- Rolling up a wall onto a top ledge should release to vanilla morph once floor contact is found.
- After release, Samus should be able to roll normally across the ledge.
- This should avoid trying to make Spider handle top-of-ledge floor movement.

## Ceiling Behavior

Ceiling traversal should be entered through wall/corner transitions.

Expected behavior:

- Moving up a wall into a ceiling should transition to `SpiderCeiling` instead of falling.
- Moving down a wall around an underside ledge should transition to `SpiderCeiling` instead of falling.
- On ceiling, `Left` and `Right` move horizontally.
- Ceiling movement should not try to support ceiling slopes in the first implementation.
- A plain ceiling edge with no continuation should drop to vanilla morph.

## Corner Cases

These are the high-risk cases that need explicit behavior.

### Wall Up To Floor Ledge

Input:

- Attached to a wall.
- Press `Up`.
- Floor contact appears at the top.

Expected result:

- Try the toward-wall nudge first.
- Release Spider.
- Enter vanilla morph on the floor.

### Wall Up To Ceiling

Input:

- Attached to a wall.
- Press `Up`.
- Side wall contact disappears and ceiling contact should exist around the corner.

Expected result:

- Enter `CornerRecover`.
- Try the away-from-wall nudge.
- Probe toward the ceiling.
- Enter `SpiderCeiling` if ceiling contact is found.

### Wall Down To Floor

Input:

- Attached to a wall.
- Press `Down`.
- Floor contact appears.

Expected result:

- Release Spider.
- Enter vanilla morph on the floor.

### Wall Down Around Underside Ledge

Input:

- Attached to a wall.
- Press `Down`.
- Side wall contact disappears and ceiling contact should exist around the underside corner.

Expected result:

- Enter `CornerRecover`.
- Try the away-and-up nudge.
- Probe toward the underside ceiling.
- Enter `SpiderCeiling` if ceiling contact is found.

### Ceiling To Wall

Input:

- Attached to ceiling.
- Press `Left` or `Right`.
- Ceiling contact disappears and side wall contact should exist around the corner.

Expected result:

- Enter `CornerRecover`.
- Try the matching ceiling corner nudge.
- Probe for the matching wall.
- Enter `SpiderWallLeft` or `SpiderWallRight` if wall contact is found.

### Ceiling Edge

Input:

- Attached to ceiling.
- Move off a ceiling edge where no side wall continuation exists.

Expected result:

- Release Spider.
- Fall as vanilla morph.

## Implementation Phases

1. Remove all floor Spider behavior.
2. Restore wall attach from vanilla morph by pressing into left or right wall.
3. Keep wall up/down movement stable.
4. Add release-to-vanilla when floor-only contact is found.
5. Add `SpiderCeiling` state.
6. Add bounded corner nudge probes for wall-to-floor, wall-to-ceiling, and ceiling-to-wall transitions.
7. Add `CornerRecover` fallback logic when the first nudge does not find a valid contact.
8. Tune movement feel only after the state model is stable.

## Acceptance Tests

- Morph on flat ground: vanilla speed, no Spider slowdown.
- Roll left on flat ground: vanilla morph movement.
- Roll right on flat ground: vanilla morph movement.
- Roll down left-facing slopes: smooth vanilla slope handling, no hopping.
- Roll down right-facing slopes: smooth vanilla slope handling, no hopping.
- Roll into right wall while holding `Right`: attach to right wall.
- Roll into left wall while holding `Left`: attach to left wall.
- Fall in a 1-tile shaft and press `Left`: attach to left wall.
- Fall in a 1-tile shaft and press `Right`: attach to right wall.
- Fall in a 1-tile shaft and press `Up` or `Down` only: do not choose a wall.
- Spring-ball jump or roll into a ceiling while holding `Left` or `Right`: attach to the ceiling.
- On a wall, press `Up`: move up wall.
- On a wall, press `Down`: move down wall.
- On a wall, release D-pad: stop without detaching.
- On a wall, press `Jump`: detach.
- Move up a wall onto a floor ledge: use a bounded toward-wall nudge if needed, then release to vanilla morph.
- Move down a wall onto floor: release to vanilla morph.
- Move up a wall into ceiling: use a bounded away-from-wall nudge if needed, then transition to ceiling instead of falling.
- Move down a wall around underside ledge: use a bounded away-from-wall nudge if needed, then transition to ceiling instead of falling.
- On ceiling, press `Left`: move left.
- On ceiling, press `Right`: move right.
- Move across a plain ceiling edge with no continuation: detach and fall as vanilla morph.

## Non-Goals For First Stable Pass

- Spider movement on floors.
- Spider handling for ground slopes.
- Ceiling slope support.
- Direct ceiling attachment from vanilla morph.
- Detach by pressing away from a wall.
- Sprite changes.
