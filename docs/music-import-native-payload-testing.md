# Music Import Native Payload Testing

This pass adds first-class raw N-SPC transfer-chain import for mITroid-style output. It does not yet run mITroid or parse `.it` files directly inside SMEDIT.

## What to test

1. Create or obtain a mITroid Super Metroid `.nspc` output file, usually named `musicdata.nspc`.
2. Open SMEDIT, load a project, and go to the Sounds tab.
3. Select the target track you want to replace.
4. Open `Edit Track`.
5. Click `N-SPC In`.
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

SMEDIT now has a first-pass `IT In` importer in the piano roll. It parses Impulse Tracker module headers/orders/packed patterns and converts importable notes from the first 8 tracker channels into editable SMEDIT/N-SPC piano-roll notes.

Current `IT In` behavior:

- Imports note timing, note pitch, note-off/cut/fade stops, volume-column note velocity, initial speed, and initial/first tempo.
- Maps IT instruments into existing Super Metroid instrument slots.
- Stages an import report before applying, like MIDI and N-SPC import.
- Saves/applies as editable piano-roll sequence data, not as a native raw payload.

Current `IT In` limitations:

- Does not import IT samples as custom BRR sample data yet.
- Does not import IT instruments as a custom Super Metroid sample directory/instrument table yet.
- Does not preserve most tracker effects yet; the first pass warns for unsupported/partial timing behavior.
- Ignores channels above the SNES 8-voice limit.

For full custom sample-bank replacement, use mITroid or another N-SPC conversion tool outside SMEDIT:

1. Author or adapt an 8-channel `.it` module.
2. Convert it with mITroid using the Super Metroid profile.
3. Import the generated raw `.nspc` through `N-SPC In`.

The longer-term path is to extend SMEDIT's clean Kotlin `.it` importer with BRR sample conversion, sample directory allocation, instrument table generation, and native transfer-block payload export.

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
