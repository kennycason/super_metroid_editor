# Music Import Native Payload Testing

This pass tracks SMEDIT music import/export hardening: raw N-SPC transfer-chain import for mITroid-style output, Impulse Tracker `.it` import with first-pass custom BRR payload support, and the piano-roll toolbar Import/Export menus.

## What to test

1. Create or obtain a mITroid Super Metroid `.nspc` output file, usually named `musicdata.nspc`.
2. Open SMEDIT, load a project, and go to the Sounds tab.
3. Select the target track you want to replace.
4. Open `Edit Track`.
5. Open the `Import` dropdown and choose `SPC / N-SPC (.spc, .nspc)`.
6. Choose the raw `.nspc` file.
7. Confirm the import preview says `Raw N-SPC`.
8. Apply the import.
9. Save the project and export a ROM.
10. Test the target room/track in emulator.

## Expected behavior

- Raw `.nspc` files without SMEDIT's editable bundle header are parsed as SPC transfer chains.
- The import preview should show the detected source play index, note count, channel count, and warnings.
- Applying the import stores both the editable piano-roll interpretation and the original raw transfer payload in the project.
- ROM export preserves the raw transfer payload, including custom sample/instrument/pattern data.
- If the raw payload was authored for play index 5 but you apply it to play index 6, export remaps the source song-table pointer to the selected target play index.
- The source play index pointer should not be overwritten unless it is also the selected target track.

## Important limitation

The native payload is preserved only while the applied song and instruments still match the raw import. If you edit notes or instruments after applying the raw import, SMEDIT intentionally switches back to editable sequence export for that track. That protects against exporting stale raw bytes that no longer match the visible piano roll.

## Current workflow for `.it`

SMEDIT now has an `Impulse Tracker (.it)` importer in the piano-roll `Import` dropdown. It parses Impulse Tracker module headers, sample headers, orders, and packed patterns, then converts importable notes from the first 8 tracker channels into editable SMEDIT/N-SPC piano-roll notes.

Current `IT In` behavior:

- Imports note timing, note pitch, note-off/cut/fade stops, volume-column/channel/global-volume note velocity, initial speed, speed-adjusted row timing, and tempo commands where possible.
- Converts supported embedded IT samples into BRR and builds native transfer payload blocks for the target play index.
- Writes a self-contained native payload with N-SPC sequence data, instrument table bytes, sample directory entries, and BRR sample data.
- Maps IT sample-mode instruments to generated Super Metroid instrument table entries when the native payload is valid.
- Reports active IT sample metadata, including compressed/stereo/16-bit/looped counts, so unsupported custom sample cases are visible before apply.
- Stages an import report before applying, like MIDI and N-SPC import.
- Saves both the editable piano-roll interpretation and the native payload while notes/instruments remain unchanged.

Current `IT In` limitations:

- Custom BRR payloads currently require sample-mode IT modules with embedded mono, uncompressed PCM samples.
- IT-compressed samples, stereo samples, and instrument-mode sample maps/envelopes are reported and fall back to editable sequence import without custom BRR payload blocks.
- Sample tuning, ADSR/envelope behavior, vibrato, and tracker effect playback are approximated.
- Does not preserve most tracker effects yet; unsupported effect commands are reported.
- Ignores channels above the SNES 8-voice limit.

For full custom sample-bank replacement, use mITroid or another N-SPC conversion tool outside SMEDIT:

1. Author or adapt an 8-channel `.it` module.
2. Convert it with mITroid using the Super Metroid profile.
3. Import the generated raw `.nspc` through `Import` -> `SPC / N-SPC (.spc, .nspc)`.

## Real `.it` fixture

For a real-world smoke test, this pass downloaded:

`~/Downloads/ModArchive - antartica rainforests - giants.it`

Source: Mod Archive module ID `213016`, "antartica rainforests" / `giants.it`. This is a stress fixture with 63 tracker channels, so it is useful for confirming that SMEDIT imports playable first-8-channel data and reports ignored channels above the SNES voice limit.

Optional regression command:

```bash
./gradlew --no-daemon -Dsmedit.realItFixture="$HOME/Downloads/ModArchive - antartica rainforests - giants.it" :desktopApp:jvmTest --tests com.supermetroid.editor.ui.ImpulseTrackerImportTest.real\ world\ impulse\ tracker\ fixture\ imports\ when\ supplied
```

The next longer-term path is to add IT compressed-sample decoding, instrument-mode sample maps/envelopes, better pitch/tuning conversion, and more tracker effect translation.

## External reference findings

- mITroid converts 8-channel `.it` modules into Super Metroid-compatible N-SPC transfer data.
- VARIA Randomizer's custom music docs describe essentially the same target model SMEDIT is moving toward: user-facing track metadata points to `.nspc` data, then export rewrites music data and the bank `$8F` pointer table as needed.
- VARIA also calls out a practical hack-authoring rule: area tracks are safer replacement targets than many boss/cinematic transitions, because some transitions dynamically reload music data and can add lag or require code-specific patching.
- The public VARIA source clone includes scripts and IPS patches for custom music, but not standalone reusable `.nspc` song files.

## Regression checks

- Importing an ordinary `.spc` snapshot still stages an editable SPC import.
- Importing a SMEDIT-exported `.nspc` bundle still stages an editable `N-SPC` import, not `Raw N-SPC`.
- Resetting the piano roll removes the native payload for the selected track.
- Reopening a project with a raw native payload should keep `Raw N-SPC` export behavior as long as no further piano-roll edits are made.
- Applying a supported `.it` import should report `Built custom IT native payload`, then playback/export should use the imported BRR samples as long as no further piano-roll edits are made.
- Exporting `.nspc` from a still-matching supported `.it` or raw native import should produce raw transfer blocks that preserve custom samples; exporting `.nspc` from an ordinary editable track still produces the SMEDIT editable bundle.
- Applying a compressed, stereo, or instrument-mode `.it` should still import visible notes, but should report why custom BRR payload export was disabled.
