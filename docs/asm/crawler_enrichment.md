# Crawler ASM enrichment review

Recipe `super-metroid-ntsc-2` adds ordinary game-code explanations for the regular
Zoomer's shared crawler AI. Existing names, upstream comments, instructions and
assets are preserved. The source uses `Zoomer`/`Crawler`; the comments keep that
vocabulary rather than renaming species or attributing shared behavior to one enemy.

The recipe is in
[`augmentation.json`](../../desktopApp/src/jvmMain/resources/asm-reference/augmentation.json).
There are no new renames in this pass. Shared `DP_Temp` storage remains shared
scratch storage, not an enemy-specific variable.

## Reading route

Start at `EnemyHeaders_Zoomer` in bank `$A0`, then follow these bank `$A3` symbols:

1. `InitAI_Zoomer_MZoomer` and `InitAI_Crawlers_Common`: initial orientation,
   speed selection, direction signs and the `$00FF` speed-lookup sentinel.
2. `Instruction_Crawlers_FunctionInY` and `MainAI_Crawlers`: animation scripts
   install a function pointer; the AI tail-jumps to that function.
3. `Function_Crawlers_CrawlingVertically` and
   `Function_Crawlers_CrawlingHorizontally`: probe support, travel along it, then
   handle inside/outside corners or schedule falling.
4. `Function_Crawlers_Falling`: use the separate fall velocity, accelerate when
   unobstructed, and restore the saved crawling function on collision.
5. `AdjustEnemyXVelocityForSlopes`: calculate a temporary distance without
   modifying the stored crawling velocity.

The four `CrawlersInstListPointerTable_*` tables connect corner transitions back
to species-specific animation lists. `InstList_Zeela_Zoomer_*_0` installs the new
handler; its `_1` loop animates without reinstalling that handler each cycle.

## What the explanations are based on

- The bank `$A0` enemy loader copies the population's initialisation value into
  `Enemy.instList`, parameter 1 into `Enemy.init0`, and parameter 2 into
  `Enemy.init1`. The Zoomer initialiser converts the low two initialisation bits
  into a real script pointer. Direction-sign selection separately reads the low
  two **property** bits; these are not the same input.
- Crawling speeds are signed 8.8 values. Clearing the words at `$12` and `$14`,
  sign-extending, and storing the speed at `$13` converts them into the movement
  helpers' signed 16.16 `$14.$12` distance. The support probe adds one pixel in
  the normal direction. The helpers report collision with carry set.
- Support contact clears `consecutiveTurnCounter`. The fourth consecutive
  unsupported-turn path saves the current function and selects falling; this is
  not a generic four-frame timer. Earlier misses choose a perpendicular
  orientation script. Inside corners reverse the other velocity component.
- Both crawling handlers select falling for earthquake type `$0014` at timer
  `$001E` but continue the current crawling invocation afterward.
- Falling moves before adding acceleration. Its fractional increment is
  `$8000` for NTSC and `$A000` for PAL. An integer-part test stops further
  acceleration at four or above; it does not clamp a pre-existing overshoot.
- Falling collision clears fall velocity and the turn counter before restoring
  `nonFallingFunction`. With `Enemy.init0 == $00FF`, it first assigns `$0080` to
  both crawling velocities.
- The slope helper samples the supporting hitbox edge, uses the slope's
  multiplier when applicable, restores the sign of the product and returns the
  temporary distance in `$14.$12`. It does not overwrite `Crawler.XVelocity`.

These are source/control-flow claims checked against the pinned disassembly and
compiled tables, not claims of new emulator-based gameplay validation. Complete
ROM parity proves preservation; it cannot by itself prove an explanation true.

## Validation and review

Ordinary JVM tests cover scoped anchors, duplicate/missing declarations, source
guards, CRLF/indentation preservation, line provenance and composition with
renames. The full enrichment gate additionally checks complete ROM bytes,
compiler symbols, source-reference edges, native bindings and authored modules:

```sh
./gradlew :desktopApp:verifyAsmEnrichment -Dsmedit.testRom=/path/to/clean-super-metroid.sfc
```

On success, the bundled-source case writes complete annotated bank previews to
`desktopApp/build/reports/asm-enrichment/src/bank_A0.asm` and `bank_A3.asm`.
These are generated review files, not project overrides or a new source of truth.
The gate also reads the compiled orientation/script tables and checks the
dispatch, earthquake, unsupported-turn and falling-acceleration contracts above.

Existing projects retain their frozen baseline. Opening or repairing one does
not add these comments or rewrite its modules. To adopt the new comments in an
existing project, expand **Project ASM enabled**, choose **Save edits & preview
baseline update…**, review any conflicts, then **Validate & apply**. Alternatively,
inspect a fresh Project ASM workspace. **Original (read-only)** remains upstream.
`asset-manifest.tsv` is unchanged: this pass neither changes assets nor their
ROM extraction ranges.
