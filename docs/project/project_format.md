# Native SMEDIT Project Format

`.smedit` is SMEDIT's native JSON project format. It is not a SMILE or SMART project file and it
does not embed either tool's configuration model.

## What A Project Stores

A project identifies an immutable base ROM with `romPath` and stores semantic edits applied by
SMEDIT at export time. The current top-level model includes room edits, room-state manifests,
tileset defaults, patches, custom graphics, patterns, minimap and map-station edits, text and room
name overrides, custom ASM, music edits, and ROM build-label fields.

Room data is described in [`room_model.md`](room_model.md). Top-level room operations, PLM changes,
enemy changes, and similar lists are common edits applied across that room's states. The equivalent
lists inside `RoomStateEdits` target one state. Both are parts of the same current room model.

ROM addresses and allocation choices are export results, not the long-term identity of newly
authored data. Existing rooms are still keyed by their physical room-header ID until new-room
creation introduces stable project IDs for content that has no source address.

## Version Fields

- `projectFormatVersion` is an internal serialization-safety marker. It lets the loader reject a
  project written by an incompatible future build. It is not a user-selectable V1/V2 room model.
- `versionMajor`, `versionMinor`, and `buildName` label exported ROM/IPS filenames. They do not
  change project parsing or room behavior.

New and checked-in beta projects use the current schema. Markerless files from early beta builds
remain readable. Before a save crosses an internal schema boundary, SMEDIT keeps one untouched
`before-schema-upgrade` backup beside the project.

## ROM Compatibility Is Separate

Project format, ROM layout, and room-state predicate bytecode are independent concerns:

- The project schema controls how `.smedit` JSON is serialized.
- The ROM compatibility layer decides whether a selected ROM can be edited or only inspected.
- SMEDIT's tagged room-state predicate formats are generated ROM code/data ABIs. Their version
  bytes do not describe the `.smedit` schema.

The editable path currently targets the standard 3 MiB Super Metroid ROM layout. Expanded or
relocated ROMs can be inspected read-only when discovery succeeds; their discovered catalogs are
derived from ROM bytes in memory and are not copied into the project as SMART or SMILE metadata.

## Foreign-Format Imports

Any future SMART XML or other editor import must be a one-way translation boundary:

1. Parse the foreign file into a temporary importer model.
2. Validate every supported concept and report anything that cannot be represented safely.
3. Translate supported data into the native SMEDIT model.
4. Save only native `.smedit` data; do not retain a foreign XML blob or add a second internal save
   path.

SMILE normally edits ROM bytes rather than producing a separate project format. Opening a ROM that
was previously edited by SMILE is therefore ROM compatibility, not SMILE-project import.
