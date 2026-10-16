# Demo Projects

Each project lives in its own folder so ROMs, exports, assets, and save states stay grouped.

Included projects:

- `Super Mazetroid/Super Mazetroid.smedit`
- `Super Metroid ASM Sandbox/Super Metroid ASM Sandbox.smedit` — ASM-source sandbox.
- `Super Metroid Echolocation/Super Metroid Echolocation.smedit`
- `Super Metroid Kaizo More Possible/Super Metroid Kaizo Lite.smedit`
- `Super Metroid Sandbox/Super Metroid Sandbox.smedit` — ROM-patching sandbox.
- `Super Metroid Spike Olympics I/Super Metroid Spike Olympics I.smedit`

`Super Metroid Spike Olympics I/Super Metroid Spike Olympics I.smedit` is the main SMEDIT demo/reference project.
`Super Metroid AI Gen/` currently has no `.smedit` project.

Use **OPEN** to select a root `.smedit` file. If its base ROM is missing, **Locate base ROM**
shows the expected filename and checks the selected ROM's content fingerprint. Supply your
own matching ROM; renamed copies are fine, but a different hack/revision or generated build
is not interchangeable. **NEW** creates a separate project and copies the selected base ROM
without renaming it. Project files record relative base-ROM paths so folders can be moved.

The active projects use the named `smedit-project` development format, currently
version 1. All current features belong to this baseline; the version is not an
ASM-feature counter. Old/markerless projects are not automatically upgraded on
Open. Restart SMEDIT after updating the app and do not save these projects with
pre-baseline builds. Historical `archive/` copies and existing backups are left
untouched and are not current-format demo projects.

To verify the active project files, run `./gradlew :cli:normalizeProjects -Pargs='--check projects'`.
For a deliberate offline update, close SMEDIT, preview with `./gradlew :cli:normalizeProjects`,
then add `-Pargs='--apply projects'`. Applying creates verified recovery copies in a
printed temporary directory; it never rewrites ROMs, assets or ASM sidecars.

Each project's support folders now use short names:

```text
asm/                authored modules and source overrides
data/               custom asset files (not automatically imported)
smedit/             generated ASM workspace, extracted assets and build cache
states/             emulator SRAM and save states
exports/            generated graphics, mapshots and audio previews
```

Folders are created only when needed. The local projects were reorganized on 2026-10-12;
Spike Olympics' distinct older nested project was preserved under `archive/legacy-project/`,
and its loose tileset images under `data/legacy-assets/`. Open the root project for normal use.

For another older project, preview with `./tools/migrate_project.sh '/path/Project/Project.smedit'`
from the repository root; close SMEDIT and add `--apply` to back up and migrate. The project
file is renamed to match its containing folder. This older tool reorganizes folders;
it does not convert retired project formats. See the instructions linked below.

ROMs, extracted binary assets, save states and generated files should stay outside source
control; ignore rules do not untrack files already committed. See the
[project format and layout](../docs/project/project_format.md) for details.
