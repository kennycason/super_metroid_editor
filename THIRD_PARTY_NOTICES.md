# Third-party notices

SMEDIT's Apache-2.0 license applies only to original SMEDIT code and
documentation. The components and materials below retain their own licenses.
When redistributing SMEDIT, preserve `LICENSE`, `NOTICE`, this file, and the
referenced upstream license files.

This summary is provided for attribution and navigation. The referenced
license text—not this summary—controls your use of each component.

## Packaged native components

### Snes9x / libretro core

- Upstream: <https://github.com/libretro/snes9x>
- Pinned source: `tools/snes9x/`
- License: custom Snes9x license; full text at `tools/snes9x/LICENSE`

Snes9x permits source and binary use, modification, and distribution for
non-commercial purposes, requires its license and copyright notice to remain
with copies and derived works, and directs commercial users to obtain
permission from its copyright holders. This restriction applies to the
embedded emulator core even though SMEDIT's own code is Apache-2.0. Snes9x
also contains separately licensed portions listed at the end of its license
and in license files inside `tools/snes9x/`.

### snes_spc

- Upstream: <https://github.com/jprjr/snes_spc>
- Original project: <http://www.slack.net/~ant/libs/audio.html#snes_spc>
- Pinned source: `tools/snes_spc/`
- Author: Shay Green / blargg and contributors
- License: GNU Lesser General Public License 2.1 or later; full text at
  `tools/snes_spc/license.txt`

SMEDIT dynamically loads the native library used for SPC700 audio emulation.
Redistributors must comply with the LGPL's notices, source/relinking, and
modification requirements.

### Asar

- Upstream: <https://github.com/RPGHacker/asar>
- Version: 1.81, revision `a8538ca8582cdc81de6941223b358aa851e3b7b1`
- Authors: Alcaro, RPG Hacker, and contributors
- License: GNU General Public License 3.0
- Distribution details: `docs/licenses/asar.md`

Release packages carry Asar as a separate command-line executable together
with upstream's complete GPLv3 license and the SMEDIT distribution notice.

## Patches, schemas, and community content

### Super Metroid MapRandomizer

- Upstream: <https://github.com/blkerby/MapRandomizer>
- Copyright: 2023 maddo, kyleb
- License: MIT; full text at `docs/licenses/maprandomizer-MIT.txt`

SMEDIT includes or downloads patch infrastructure derived from MapRandomizer.
Files in upstream subdirectories with a separate license remain governed by
that separate license. Individual patch authors and additional provenance are
recorded in `shared/src/commonMain/resources/patches/CREDITS.md`.

### SpriteSomething compatibility schemas

- Upstream: <https://github.com/Artheau/SpriteSomething>
- Authors: Artheau and Mike Trethewey
- License: Creative Commons Attribution-ShareAlike 4.0 International
- Bundled notice: `shared/src/jvmMain/resources/samus-community/NOTICE.md`
- License: <https://creativecommons.org/licenses/by-sa/4.0/>

SMEDIT bundles pinned layout and animation schemas used for compatibility.
Those schemas are not relicensed under Apache-2.0; adaptations must preserve
attribution and comply with CC BY-SA 4.0.

### MapRandoSprites and imported community artwork

- Catalog: <https://github.com/blkerby/MapRandoSprites>

SMEDIT downloads catalog images on demand rather than licensing or bundling
the catalog as Apache-2.0 content. The MapRandoSprites repository does not
currently publish a repository-wide license. Each sprite and other imported
artwork remains the property of its artist or rights holder and may carry
author-specific terms. Catalog attribution does not itself grant commercial,
modification, or redistribution rights. Preserve the displayed artist credit
and obtain permission when your intended use requires it.

### Bundled ROM patches

Some patches predate SMEDIT or come from the wider Super Metroid hacking
community. Known authors and sources are listed in
`shared/src/commonMain/resources/patches/CREDITS.md`. A patch without a stated
license is not automatically Apache-2.0; absent an explicit grant, obtain the
author's permission before redistributing or incorporating it elsewhere.

## JVM and UI dependencies

SMEDIT distributions also contain third-party JVM libraries and native
artifacts resolved by Gradle. Important direct dependencies include JetBrains
Kotlin and Compose Multiplatform, kotlinx.serialization, Ktor, SLF4J,
kotlin-logging, JNA, Jamepad, SDL, JUnit (test only), and their transitive
dependencies. These packages retain the license metadata and notices carried
in their upstream artifacts. Most direct application dependencies use
Apache-2.0, MIT, BSD-style, or similarly permissive licenses; JNA is available
under Apache-2.0 or LGPL-2.1-or-later. Consult the resolved artifact version
and its embedded `META-INF` notices before redistributing a modified package.

## Nintendo and ROM data

SMEDIT does not grant rights to Super Metroid ROMs, extracted ROM data, game
graphics, characters, music, trademarks, or other Nintendo material. Users
must supply their own ROM and are responsible for complying with applicable
law. Generated projects, screenshots, imported resources, and ROM outputs may
contain material outside SMEDIT's Apache-2.0 license.
