#!/usr/bin/env python3
"""Aggregate live parity evidence into machine-readable and human reports."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List


PARITY_DIR = Path(__file__).resolve().parent
REPO_ROOT = PARITY_DIR.parent
REFERENCE_FILE = PARITY_DIR / "reference.properties"
DEFAULT_REPORT_DIR = PARITY_DIR / "reports"
DEFAULT_TEST_RESULTS = REPO_ROOT / "shared" / "build" / "test-results" / "parityTest"


def read_properties(path: Path) -> Dict[str, str]:
    result: Dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not key.strip() or not value.strip():
            raise ValueError(f"Invalid property line in {path}: {raw_line!r}")
        result[key.strip()] = value.strip()
    return result


def git_output(*args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(REPO_ROOT), *args],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    ).stdout.strip()


def collect_test_results(directory: Path) -> Dict[str, object]:
    xml_files = sorted(directory.glob("TEST-*.xml"))
    if not xml_files:
        raise ValueError(f"No parity JUnit XML found in {directory}")
    totals: Counter[str] = Counter()
    cases: List[Dict[str, object]] = []
    for xml_file in xml_files:
        root = ET.parse(xml_file).getroot()
        totals["tests"] += int(root.attrib.get("tests", 0))
        totals["failures"] += int(root.attrib.get("failures", 0))
        totals["errors"] += int(root.attrib.get("errors", 0))
        totals["skipped"] += int(root.attrib.get("skipped", 0))
        totals["timeMillis"] += round(float(root.attrib.get("time", 0.0)) * 1000)
        for case in root.findall("testcase"):
            status = "pass"
            if case.find("failure") is not None or case.find("error") is not None:
                status = "mismatch"
            elif case.find("skipped") is not None:
                status = "skipped"
            cases.append(
                {
                    "className": case.attrib.get("classname", ""),
                    "name": case.attrib.get("name", ""),
                    "status": status,
                    "timeSeconds": float(case.attrib.get("time", 0.0)),
                }
            )
    return {
        "tests": totals["tests"],
        "failures": totals["failures"],
        "errors": totals["errors"],
        "skipped": totals["skipped"],
        "timeSeconds": totals["timeMillis"] / 1000.0,
        "cases": cases,
    }


def foundation_checks(test_results: Dict[str, object]) -> List[Dict[str, object]]:
    test_status = (
        "pass"
        if test_results["failures"] == 0
        and test_results["errors"] == 0
        and test_results["skipped"] == 0
        else "mismatch"
    )
    return [
        {
            "id": "F-01",
            "name": "Exact assembly build",
            "status": "pass",
            "evidence": "Pinned source rebuild is byte-identical to the clean ROM.",
        },
        {
            "id": "F-02",
            "name": "Portable private-fixture contract",
            "status": "pass",
            "evidence": "ROM identity and clean pinned checkout passed strict validation.",
        },
        {
            "id": "F-03",
            "name": "Source-symbol catalog",
            "status": "pass",
            "evidence": "All pinned WLA labels parsed into deterministic name/address records.",
        },
        {
            "id": "F-04",
            "name": "Extracted-asset manifest",
            "status": "pass",
            "evidence": "Every active incbin matches its named rebuilt-ROM range.",
        },
        {
            "id": "F-05",
            "name": "Address drift coverage",
            "status": "partial" if test_status == "pass" else "mismatch",
            "evidence": (
                "Twelve standalone constants plus all 87 tileset fields are source-linked; "
                "the remaining address inventory is not yet mapped."
            ),
        },
        {
            "id": "F-06",
            "name": "Unified parity report",
            "status": "pass",
            "evidence": "This JSON/Markdown report was generated from live task outputs.",
        },
        {
            "id": "F-07",
            "name": "Independent golden-image policy",
            "status": "uncovered",
            "evidence": "Policy and independent approved goldens remain queued.",
        },
    ]


def named_test_status(test_results: Dict[str, object], class_name: str, name_prefix: str) -> str:
    matches = [
        case
        for case in test_results["cases"]
        if case["className"] == class_name and str(case["name"]).startswith(name_prefix)
    ]
    if len(matches) != 1:
        return "mismatch"
    return "pass" if matches[0]["status"] == "pass" else "mismatch"


def compression_checks(test_results: Dict[str, object], lz5: Dict[str, object]) -> List[Dict[str, object]]:
    class_name = "com.supermetroid.editor.rom.LZ5SourceParityTest"
    decode_status = named_test_status(
        test_results,
        class_name,
        "every extracted LZ5 stream matches the independent source oracle",
    )
    recompress_status = named_test_status(
        test_results,
        class_name,
        "every extracted LZ5 payload survives SMEDIT recompression",
    )
    stream_count = int(lz5["exactCompressedStreamCount"])
    return [
        {
            "id": "G-02",
            "name": "LZ5 decompression",
            "status": decode_status,
            "evidence": (
                f"All {stream_count} exact extracted streams match independent decoded sizes/hashes; "
                "malformed and destination-overflowing streams are rejected."
            ),
        },
        {
            "id": "G-03",
            "name": "LZ5 recompression",
            "status": recompress_status,
            "evidence": (
                f"All {stream_count} decoded payloads survive SMEDIT encode/decode byte-exactly; "
                "the 64 KiB engine destination limit is enforced."
            ),
        },
    ]


def tileset_checks(test_results: Dict[str, object], tilesets: Dict[str, object]) -> List[Dict[str, object]]:
    class_name = "com.supermetroid.editor.rom.TilesetSourceParityTest"
    cre_status = named_test_status(
        test_results,
        class_name,
        "CRE ownership boundaries and every engine consumer match source",
    )
    pointer_status = named_test_status(
        test_results,
        class_name,
        "all 29 tileset pointer triples match named source assets",
    )
    cre = tilesets["cre"]
    graphics = cre["graphics"]
    tile_table = cre["tileTable"]
    return [
        {
            "id": "G-05",
            "name": "CRE graphics/tile table ownership",
            "status": cre_status,
            "evidence": (
                f"Exact {graphics['decompressedSize']:,}-byte graphics and "
                f"{tile_table['decompressedSize']:,}-byte metatile payloads, contiguous ROM split, "
                f"runtime WRAM/VRAM boundaries, and all "
                f"{graphics['consumers']['consumerCount'] + tile_table['consumers']['consumerCount']} "
                "direct engine consumers match source."
            ),
        },
        {
            "id": "G-06",
            "name": "Tileset pointer manifest",
            "status": pointer_status,
            "evidence": (
                f"All {tilesets['pointerFieldCount']} fields in {tilesets['tilesetCount']} pointer triples "
                f"map to {tilesets['uniqueResourceCount']} named assets; intentional aliases are explicit."
            ),
        },
    ]


def tile_format_checks(
    test_results: Dict[str, object], tile_formats: Dict[str, object]
) -> List[Dict[str, object]]:
    class_name = "com.supermetroid.editor.rom.TileFormatSourceParityTest"
    pixels_status = named_test_status(
        test_results,
        class_name,
        "all source tiles match independent 2bpp and 4bpp pixel oracles",
    )
    metatiles_status = named_test_status(
        test_results,
        class_name,
        "every source metatile word and combined table placement matches oracle",
    )
    totals = tile_formats["totals"]
    return [
        {
            "id": "G-04",
            "name": "2bpp/4bpp tile decoding",
            "status": pixels_status,
            "evidence": (
                f"Independent planar decoding matches {totals['graphics4bppTileCount']:,} 4bpp tiles "
                f"across {totals['graphics4bppResourceCount']} resources and "
                f"{totals['graphics2bppTileCount']:,} standard BG3 2bpp tiles, including "
                "global split-plane Ceres data and all flip combinations."
            ),
        },
        {
            "id": "G-07",
            "name": "Metatile word semantics",
            "status": metatiles_status,
            "evidence": (
                f"All {totals['metatileWordCount']:,} source words in "
                f"{totals['metatileTableResourceCount']} tables match tile/palette/priority/flip semantics "
                "and runtime CRE/tileset placement; all 65,536 word values round-trip."
            ),
        },
    ]


def animated_tile_checks(
    test_results: Dict[str, object], animated_tiles: Dict[str, object]
) -> List[Dict[str, object]]:
    status = named_test_status(
        test_results,
        "com.supermetroid.editor.rom.AnimatedTileSourceParityTest",
        "all animated tile objects frames activations and DMA consumers match source",
    )
    totals = animated_tiles["totals"]
    return [
        {
            "id": "G-08",
            "name": "Animated tiles",
            "status": status,
            "evidence": (
                f"All {totals['assetCount']} bank-$87 payloads, "
                f"{totals['uniqueFrameInstructionCount']} reachable frame instructions, "
                f"{totals['objectCount']} object definitions, {totals['areaBitMappingCount']} "
                f"area/bit mappings, and {totals['consumerCount']} engine call sites match source and ROM."
            ),
        }
    ]


def item_plm_graphics_checks(
    test_results: Dict[str, object], item_plm_graphics: Dict[str, object]
) -> List[Dict[str, object]]:
    class_name = "com.supermetroid.editor.rom.ItemPlmGraphicsSourceParityTest"
    payload_status = named_test_status(
        test_results,
        class_name,
        "all item PLM payloads pixels variants and editor IDs match source",
    )
    placement_status = named_test_status(
        test_results,
        class_name,
        "item PLM four slot allocator metatile tables and VRAM placement match source",
    )
    status = "pass" if payload_status == "pass" and placement_status == "pass" else "mismatch"
    totals = item_plm_graphics["totals"]
    return [
        {
            "id": "G-09",
            "name": "Item/PLM graphics",
            "status": status,
            "evidence": (
                f"All {totals['assetCount']} bank-$89 payloads / {totals['tileCount']} 4bpp tiles, "
                f"{totals['loadRecordCount']} visible/Chozo/shot-block PLM IDs and load records, "
                f"{totals['drawPointerCount']} draw pointers, and {totals['slotCount']} runtime "
                "VRAM/metatile slots match source, ROM, and SMEDIT's item catalog."
            ),
        }
    ]


def enemy_header_checks(
    test_results: Dict[str, object], enemy_headers: Dict[str, object]
) -> List[Dict[str, object]]:
    class_name = "com.supermetroid.editor.rom.EnemyHeaderSourceParityTest"
    header_status = named_test_status(
        test_results,
        class_name,
        "all bank A0 enemy header macro fields match the production parser",
    )
    graphics_status = named_test_status(
        test_results,
        class_name,
        "every GRAPHADR range alias and extracted asset segment matches source",
    )
    totals = enemy_headers["totals"]
    return [
        {
            "id": "E-01",
            "name": "Enemy species headers",
            "status": header_status,
            "evidence": (
                f"All {totals['headerCount']} bank-$A0 headers / "
                f"{totals['macroFieldCount']:,} macro fields match source, rebuilt ROM, "
                "and SMEDIT's production parser; the one source-declared unused header "
                "remains explicit."
            ),
        },
        {
            "id": "E-02",
            "name": "Enemy GRAPHADR ownership",
            "status": graphics_status,
            "evidence": (
                f"All {totals['nonEmptyGraphicsHeaderCount']} nonempty species associations / "
                f"{totals['uniqueGraphicsRangeCount']} unique ranges map without gaps to "
                f"{totals['sourceAssetCount']} named assets; "
                f"{totals['startAliasGroupCount']} shared-start groups, "
                f"{totals['overlappingUniqueRangePairCount']} overlapping range pairs, and "
                f"{totals['multiAssetGraphicsHeaderCount']} multi-asset ranges are explicit."
            ),
        },
    ]


def enemy_oam_checks(
    test_results: Dict[str, object], enemy_oam: Dict[str, object]
) -> List[Dict[str, object]]:
    class_name = "com.supermetroid.editor.rom.EnemyOamSourceParityTest"
    standard_status = named_test_status(
        test_results,
        class_name,
        "all named standard enemy OAM structures match the production parser",
    )
    extended_status = named_test_status(
        test_results,
        class_name,
        "all named extended enemy spritemaps and tilemaps match the production parser",
    )
    totals = enemy_oam["totals"]
    return [
        {
            "id": "E-04",
            "name": "Standard enemy OAM structures",
            "status": standard_status,
            "evidence": (
                f"All {totals['standardLabelCount']:,} named structures / "
                f"{totals['standardEntryCount']:,} entries match source bytes and SMEDIT field decoding, "
                f"including {totals['standardZeroEntryCount']} valid empty structures and priority/flip/size semantics."
            ),
        },
        {
            "id": "E-05",
            "name": "Extended/multibox enemy structures",
            "status": extended_status,
            "evidence": (
                f"All {totals['extendedLabelCount']:,} extended spritemaps / "
                f"{totals['extendedChildAssociationCount']:,} child associations and "
                f"{totals['tilemapLabelCount']} extended tilemaps / {totals['tilemapWordCount']:,} words "
                "match source pointers, signed offsets, hitboxes, and production parsing."
            ),
        },
    ]


def enemy_instruction_checks(
    test_results: Dict[str, object],
    enemy_instructions: Dict[str, object],
    enemy_vertical_slices: Dict[str, object],
) -> List[Dict[str, object]]:
    inventory_status = named_test_status(
        test_results,
        "com.supermetroid.editor.rom.EnemyInstructionSourceParityTest",
        "all named enemy instruction lists match source records and measured preview coverage",
    )
    slice_status = named_test_status(
        test_results,
        "com.supermetroid.editor.rom.EnemyVerticalSliceSourceParityTest",
        "Zoomer Sidehopper and walking Space Pirate match complete source slices",
    )
    totals = enemy_instructions["totals"]
    slice_totals = enemy_vertical_slices["totals"]
    return [
        {
            "id": "E-06",
            "name": "Enemy instruction-list interpreter",
            # Passing proves the inventory and the measured limitation. It does
            # not turn the current fixed-chunk scanner into a control-flow
            # interpreter, so this remains intentionally partial.
            "status": (
                "partial"
                if inventory_status == "pass" and slice_status == "pass"
                else "mismatch"
            ),
            "evidence": (
                f"Source structure is pinned for {totals['enemyListCount']:,} lists / "
                f"{totals['recordCount']:,} records. The current preview recovers "
                f"{totals['productionRecoveredSourceFrameCount']:,} of "
                f"{totals['productionRenderableFrameCount']:,} renderable source frames "
                f"({totals['productionMissedSourceFrameCount']:,} misses across "
                f"{totals['listsWithMissedSourceFrames']:,} lists). The manifest proves common-family "
                f"widths for {totals['commonWidthKnownHandlerCount']} of "
                f"{totals['uniqueHandlerCount']} handler addresses, but the generic scanner executes none; "
                f"every miss is listed. A bounded interpreter now proves "
                f"{slice_totals['sliceCount']} complete ordinary-enemy slices / "
                f"{slice_totals['frameOccurrenceCount']} frame occurrences."
            ),
        }
    ]


def enemy_species_status_checks(
    test_results: Dict[str, object], enemy_species_status: Dict[str, object]
) -> List[Dict[str, object]]:
    inventory_status = named_test_status(
        test_results,
        "com.supermetroid.editor.rom.EnemySpeciesStatusSourceParityTest",
        "all source enemy species have an explicit editor rendering status",
    )
    totals = enemy_species_status["totals"]
    return [
        {
            "id": "E-08",
            "name": "All-species render inventory",
            # Passing proves a complete, pinned support ledger. Tile-sheet-only
            # and failed species remain implementation work, so E-08 is partial.
            "status": "partial" if inventory_status == "pass" else "mismatch",
            "evidence": (
                f"All {totals['speciesCount']} source headers are classified: "
                f"{totals['assembledCount']} assembled, {totals['compositeCount']} composite, "
                f"{totals['tile-sheet-onlyCount']} tile-sheet-only, "
                f"{totals['nonvisualCount']} nonvisual, and {totals['failedCount']} failed. "
                f"Production preview paths render {totals['previewAvailableCount']} species; "
                "the detailed ledger names every remaining gap."
            ),
        }
    ]


def ordinary_enemy_animation_checks(
    test_results: Dict[str, object], ordinary_enemy_animations: Dict[str, object]
) -> List[Dict[str, object]]:
    status = named_test_status(
        test_results,
        "com.supermetroid.editor.rom.OrdinaryEnemyAnimationSourceParityTest",
        "helper selected ordinary enemy routes timing and renders match source",
    )
    totals = ordinary_enemy_animations["totals"]
    return [
        {
            "id": "E-11",
            "name": "Helper-selected ordinary-enemy animations",
            "status": status,
            "evidence": (
                f"{totals['speciesCount']} species that evade the conservative init scanner now have "
                f"{totals['animationCount']} explicit source routes / {totals['animationFrameCount']} guided frames. "
                f"All {totals['spritemapCount']} OAM maps, {totals['instructionListCount']} lists, palettes, "
                "durations, default previews, component pixels, and animated pixels are source-pinned."
            ),
        }
    ]


def kraid_checks(
    test_results: Dict[str, object], kraid: Dict[str, object]
) -> List[Dict[str, object]]:
    status = named_test_status(
        test_results,
        "com.supermetroid.editor.rom.KraidSourceParityTest",
        "Kraid BG2 composition and ownership match the source manifest",
    )
    totals = kraid["totals"]
    return [
        {
            "id": "B-01",
            "name": "Kraid and Mini Kraid source renderers",
            "status": status,
            "evidence": (
                f"Tileset $1A's complete 32 KiB no-CRE graphics resource, "
                f"{totals['backgroundMapCount']} compressed room maps "
                f"({totals['activeBackgroundMapCount']} active), {totals['headMapCount']} custom "
                f"32x12 head maps / {totals['customFrameOccurrenceCount']} frame occurrences, "
                f"{totals['paletteStateCount']} palette states, and "
                f"{totals['linkedOamHeaderCount']} headers sharing the $AB:CC00 OAM range are pinned. "
                f"Production also parses and renders {totals['activeOamSequenceCount']} exact linked-OAM "
                f"sequences / {totals['activeOamFrameOccurrenceCount']} frame occurrences. Mini Kraid has "
                f"{totals['miniKraidSequenceCount']} exact lists / "
                f"{totals['miniKraidFrameOccurrenceCount']} frame occurrences / "
                f"{totals['miniKraidUniquePoseCount']} unique source poses instead of a shared-bank scan. "
                f"All {totals['backgroundMapConsumerAssociationCount']} BG-map and "
                f"{totals['roomBackgroundTileConsumerCount']} room-background-tile consumer associations are named. "
                "Production renders and safely edits the 32x11 uploaded head region through varGfx[\"26\"]; "
                "deterministic pixel hashes pin all four full-body states and every parsed animation frame."
            ),
        }
    ]


def phantoon_checks(
    test_results: Dict[str, object], phantoon: Dict[str, object]
) -> List[Dict[str, object]]:
    status = named_test_status(
        test_results,
        "com.supermetroid.editor.rom.PhantoonSourceParityTest",
        "Phantoon BG2 composition palettes and animations match the source manifest",
    )
    totals = phantoon["totals"]
    return [
        {
            "id": "B-02",
            "name": "Phantoon source renderer",
            "status": status,
            "evidence": (
                f"All {totals['headerCount']} independently animated enemy slots, "
                f"{totals['tilemapCount']} active BG2 tilemaps / {totals['tilemapWordCount']} words, "
                f"{totals['extendedSpritemapCount']} extended spritemaps, and "
                f"{totals['instructionListCount']} instruction lists / "
                f"{totals['frameOccurrenceCount']} frame occurrences are source-pinned. "
                f"Production renders {totals['productionAnimationCount']} exact part animations / "
                f"{totals['productionAnimationFrameCount']} frame occurrences as complete compositions, "
                f"supports all {totals['healthPaletteCount']} health palettes, and safely edits the "
                "source-owned tileset-$05 graphics prefix while keeping placement data read-only."
            ),
        }
    ]


def draygon_checks(
    test_results: Dict[str, object], draygon: Dict[str, object]
) -> List[Dict[str, object]]:
    status = named_test_status(
        test_results,
        "com.supermetroid.editor.rom.DraygonSourceParityTest",
        "Draygon graphics ownership palettes compositions and animations match source",
    )
    totals = draygon["totals"]
    return [
        {
            "id": "B-03",
            "name": "Draygon source renderer",
            "status": status,
            "evidence": (
                f"All {totals['headerCount']} independently animated enemy slots, "
                f"{totals['standardSpritemapCount']} standard OAM maps / "
                f"{totals['standardOamEntryCount']} entries, "
                f"{totals['extendedSpritemapCount']} extended maps, and "
                f"{totals['tilemapCount']} BG2 maps / {totals['tilemapWordCount']} words are pinned. "
                f"Production parses and renders {totals['productionAnimationCount']} exact lists / "
                f"{totals['productionAnimationFrameCount']} complete four-slot frame occurrences, "
                f"all {totals['healthPaletteStageCount']} health stages, and the hurt flash. "
                "The contract keeps tileset-$1C BG pixels separate from the shared $B0:C800 OBJ payload."
            ),
        }
    ]


def ridley_checks(
    test_results: Dict[str, object], ridley: Dict[str, object]
) -> List[Dict[str, object]]:
    status = named_test_status(
        test_results,
        "com.supermetroid.editor.rom.RidleySourceParityTest",
        "Ridley shared encounters DMA assembly palettes and animations match source",
    )
    totals = ridley["totals"]
    return [
        {
            "id": "B-04",
            "name": "Ridley shared Norfair/Ceres source renderer",
            "status": status,
            "evidence": (
                f"Both encounter headers share {totals['baseAssetCount']} contiguous base assets / "
                f"{totals['baseAssetByteCount']} bytes, while "
                f"{totals['runtimeDmaAssetCount']} ribs/claws DMA assets remain separately owned. "
                f"The custom assembly pins {totals['bodyExtendedSpritemapCount']} body maps / "
                f"{totals['bodyExtendedChildCount']} child links, "
                f"{totals['wingSpritemapCount']} wing maps, {totals['tailSpritemapCount']} tail maps, "
                f"and {totals['animationListCount']} encounter lists / "
                f"{totals['animationFrameCount']} timed frames. Production hashes all "
                f"{totals['paletteStageCount']} palette stages, complete compositions, and curated animations; "
                "Ceres-only actions and hit-counter palette behavior remain explicit inside one Ridley editor."
            ),
        }
    ]


def mother_brain_checks(
    test_results: Dict[str, object], mother_brain: Dict[str, object]
) -> List[Dict[str, object]]:
    test_status = named_test_status(
        test_results,
        "com.supermetroid.editor.rom.MotherBrainSourceParityTest",
        "Mother Brain ownership compositions palettes and animations match source",
    )
    totals = mother_brain["totals"]
    return [
        {
            "id": "B-05",
            "name": "Mother Brain phase 1/2 source renderer",
            # This slice completes sprite composition and palette ownership. HDMA,
            # projectiles, room destruction, and target-driven neck AI remain effects work.
            "status": "partial" if test_status == "pass" else "mismatch",
            "evidence": (
                f"Both headers and {totals['activeRoomStateCount']} live room states are pinned with "
                f"{totals['headSpritemapCount']} head maps / {totals['headOamEntryCount']} OAM entries, "
                f"{totals['bodyExtendedSpritemapCount']} active extended body maps / "
                f"{totals['bodyExtendedChildCount']} child links, and {totals['bodyTilemapCount']} BG2 maps / "
                f"{totals['bodyTilemapWordCount']} words. Production checks "
                f"{totals['instructionListCount']} body/head lists / {totals['frameOccurrenceCount']} timed frames, "
                f"{totals['healthPaletteRowCount'] // 2} health pairs, and "
                f"{totals['rainbowPaletteStageCount']} split main/back-leg rainbow stages. "
                "Phase-1 room art and phase-2's four physical pixel owners remain explicit; "
                "remaining fight HDMA and projectile effects are not part of the sprite canvas."
            ),
        }
    ]


def crocomire_checks(
    test_results: Dict[str, object], crocomire: Dict[str, object]
) -> List[Dict[str, object]]:
    status = named_test_status(
        test_results,
        "com.supermetroid.editor.rom.CrocomireSourceParityTest",
        "Crocomire split graphics death phases and animations match source",
    )
    totals = crocomire["totals"]
    return [
        {
            "id": "B-06",
            "name": "Crocomire living/melting/skeleton source renderer",
            "status": status,
            "evidence": (
                f"Both linked headers, {totals['assetCount']} pixel assets / "
                f"{totals['assetByteCount']} bytes, {totals['standardSpritemapCount']} OBJ maps / "
                f"{totals['standardOamEntryCount']} entries, {totals['extendedSpritemapCount']} extended maps, "
                f"and {totals['tilemapCount']} BG2 maps / {totals['tilemapWordCount']} words are pinned. "
                f"Production decodes all {totals['instructionListCount']} active lists / "
                f"{totals['frameOccurrenceCount']} timed frames and renders "
                f"{totals['guidedAnimationCount']} guided animations / "
                f"{totals['guidedAnimationFrameCount']} frames. Tileset-$1B room pixels, the editable living "
                "OBJ payload, two melting overlays, and six skeleton DMA chunks remain distinct owners."
            ),
        }
    ]


def spore_spawn_checks(
    test_results: Dict[str, object], spore_spawn: Dict[str, object]
) -> List[Dict[str, object]]:
    status = named_test_status(
        test_results,
        "com.supermetroid.editor.rom.SporeSpawnSourceParityTest",
        "Spore Spawn body stalk projectiles palettes and animations match source",
    )
    totals = spore_spawn["totals"]
    return [
        {
            "id": "B-07",
            "name": "Spore Spawn cross-bank source renderer",
            "status": status,
            "evidence": (
                f"Both linked headers and the {totals['assetByteCount']}-byte shared OBJ owner are pinned with "
                f"{totals['activeExtendedSpritemapCount']} active body maps / "
                f"{totals['activeExtendedChildCount']} child links, "
                f"{totals['projectileSpritemapCount']} stalk/spawner/spore projectile maps, and "
                f"{totals['paletteCount']} palette rows. Production decodes all "
                f"{totals['instructionListCount']} boss lists / {totals['frameOccurrenceCount']} timed frames "
                "and composes the four bank-$86 stalk projectiles with the bank-$A5 body using the exact "
                "quarter/half/three-quarter runtime interpolation."
            ),
        }
    ]


def botwoon_checks(
    test_results: Dict[str, object], botwoon: Dict[str, object]
) -> List[Dict[str, object]]:
    status = named_test_status(
        test_results,
        "com.supermetroid.editor.rom.BotwoonSourceParityTest",
        "Botwoon head body history palettes projectiles and animations match source",
    )
    totals = botwoon["totals"]
    return [
        {
            "id": "B-08",
            "name": "Botwoon position-history source renderer",
            "status": status,
            "evidence": (
                f"The {totals['assetByteCount']}-byte shared OBJ owner, "
                f"{totals['activeHeadSpritemapCount']} active head maps, "
                f"{totals['activeProjectileSpritemapCount']} active body/tail/spit maps, "
                f"{totals['activeHeadInstructionListCount']} active head lists, and "
                f"{totals['paletteCount']} palette rows are pinned. Production composes the enemy head "
                "with twelve animated body projectiles and one tail by sampling the circular position "
                "history at the source's health-dependent byte distances; every speed stage preserves "
                "the same 12-pixel segment cadence."
            ),
        }
    ]


def torizo_checks(
    test_results: Dict[str, object], torizo: Dict[str, object]
) -> List[Dict[str, object]]:
    status = named_test_status(
        test_results,
        "com.supermetroid.editor.rom.TorizoSourceParityTest",
        "Torizo shared body runtime overlays projectiles palettes and animations match source",
    )
    totals = torizo["totals"]
    return [
        {
            "id": "B-09",
            "name": "Bomb/Golden Torizo shared-source renderer",
            "status": status,
            "evidence": (
                f"All {totals['headerCount']} family headers and {totals['assetByteCount']} source bytes are pinned, "
                f"including {totals['activeExtendedSpritemapCount']} active body maps, "
                f"{totals['activeStandardSpritemapCount']} body-child OAM maps, and "
                f"{totals['activeProjectileSpritemapCount']} bank-$8D projectile/effect maps. Production applies "
                f"all {totals['runtimeTransferCount']} eye, damage, and egg-release DMA transfers and exposes "
                f"{totals['paletteRowCount']} encounter/health palette rows while keeping the shared editable "
                "OBJ owner distinct from runtime overlays, Golden egg pixels, and Bomb statue fragments."
            ),
        }
    ]


def metroid_checks(
    test_results: Dict[str, object], metroid: Dict[str, object]
) -> List[Dict[str, object]]:
    status = named_test_status(
        test_results,
        "com.supermetroid.editor.rom.MetroidSourceParityTest",
        "Metroid insides shell electricity timing and renders match source",
    )
    totals = metroid["totals"]
    return [
        {
            "id": "B-10",
            "name": "Metroid three-owner runtime renderer",
            "status": status,
            "evidence": (
                f"The {totals['assetByteCount']}-byte shared OBJ owner, "
                f"{totals['insideSpritemapCount']} enemy-inside maps, "
                f"{totals['shellSpritemapCount']} shell maps, and "
                f"{totals['electricitySpritemapCount']} electricity maps are pinned. Production synchronizes "
                f"all {totals['spriteObjectFrameOccurrenceCount']} companion frame occurrences / "
                f"{totals['spriteObjectTickCount']} ticks and proves that both source-labelled unused lists "
                "are runtime-active fallthrough continuations of sprite objects $32 and $34."
            ),
        }
    ]


def samus_checks(
    test_results: Dict[str, object], samus: Dict[str, object]
) -> List[Dict[str, object]]:
    status = named_test_status(
        test_results,
        "com.supermetroid.editor.rom.SamusSourceParityTest",
        "all Samus poses DMA assets spritemaps and base palettes match source",
    )
    totals = samus["totals"]
    return [
        {
            "id": "S-01",
            "name": "Samus pose and timing tables",
            "status": status,
            "evidence": (
                f"All {totals['poseCount']} pose IDs resolve to "
                f"{totals['uniqueAnimationDefinitionCount']} unique definitions / "
                f"{totals['animationDefinitionFrameOccurrenceCount']} frame occurrences and "
                f"{totals['uniqueAnimationDelayCount']} exact delay streams."
            ),
        },
        {
            "id": "S-02",
            "name": "Samus top/bottom DMA graph",
            "status": status,
            "evidence": (
                f"All {totals['dmaTableCount']} top/bottom tables and "
                f"{totals['dmaEntryCount']} seven-byte entries are source-pinned, including "
                "the engine's intentional contiguous-table indexing."
            ),
        },
        {
            "id": "S-03",
            "name": "Samus spritemap indices",
            "status": status,
            "evidence": (
                f"Every pose/frame top and bottom lookup is bounded; "
                f"{totals['nullSpritemapLookupCount']} null half-lookups remain explicit."
            ),
        },
        {
            "id": "S-04",
            "name": "Samus spritemap structures",
            "status": status,
            "evidence": (
                f"All {totals['spritemapCount']} referenced spritemaps / "
                f"{totals['spritemapEntryCount']} OAM entries match production-decoder geometry."
            ),
        },
        {
            "id": "S-05",
            "name": "Samus DMA asset ownership",
            "status": status,
            "evidence": (
                f"The {totals['dmaEntryCount']} DMA entries map bijectively to "
                f"{totals['tileAssetCount']} extracted payloads / "
                f"{totals['tileAssetByteCount']:,} bytes; no missing, duplicate, or overread owner remains."
            ),
        },
        {
            "id": "S-06",
            "name": "Samus palette states",
            "status": "partial" if status == "pass" else status,
            "evidence": (
                f"All {totals['paletteCount']} normal suit palettes are source-pinned. Runtime heat, "
                "charge, speed, hurt, death, and other special palette programs remain to be modeled."
            ),
        },
        {
            "id": "S-07",
            "name": "Samus all-pose render atlas",
            "status": "partial" if status == "pass" else status,
            "evidence": (
                f"Production tilemap and reconstructed-VRAM hashes match for all "
                f"{totals['animationDefinitionFrameOccurrenceCount']} pose-frame occurrences; "
                "independently reviewed Power/Varia/Gravity pixel goldens remain queued."
            ),
        },
        {
            "id": "S-08",
            "name": "Samus edit/export ownership",
            "status": "uncovered" if status == "pass" else status,
            "evidence": (
                "Source ownership is now explicit, but editing and SpriteSomething-compatible "
                "sheet import/export are not implemented yet."
            ),
        },
    ]


def room_checks(
    test_results: Dict[str, object], rooms: Dict[str, object], scroll_runtime: Dict[str, object],
    backgrounds: Dict[str, object], minimap: Dict[str, object], load_stations: Dict[str, object],
) -> List[Dict[str, object]]:
    class_name = "com.supermetroid.editor.rom.RoomSourceParityTest"
    source_header_status = named_test_status(
        test_results,
        class_name,
        "all room headers selectors and state records match named source macros",
    )
    source_level_status = named_test_status(
        test_results,
        class_name,
        "all extracted room level streams match source and production decoding",
    )
    source_resource_status = named_test_status(
        test_results,
        class_name,
        "all state PLM enemy GFX FX scroll and door resources match production parsers",
    )
    export_status = named_test_status(
        test_results,
        class_name,
        "no-edit room export is byte exact and every header and state field mutation stays allowlisted",
    )
    noop_status = named_test_status(
        test_results,
        class_name,
        "explicit no-op edits never rewrite or relocate room resources",
    )
    header_status = "pass" if source_header_status == export_status == noop_status == "pass" else "mismatch"
    level_status = "pass" if source_level_status == noop_status == "pass" else "mismatch"
    resource_status = "pass" if source_resource_status == noop_status == "pass" else "mismatch"
    runtime_class = "com.supermetroid.editor.rom.ScrollRuntimeSourceParityTest"
    runtime_test_statuses = [
        named_test_status(
            test_results,
            runtime_class,
            "all generic scroll PLM commands and extension chains match source",
        ),
        named_test_status(
            test_results,
            runtime_class,
            "all active door ASM scroll writes match source and mixed routines fail closed",
        ),
        named_test_status(
            test_results,
            runtime_class,
            "complete direct writer inventory and load precedence match source",
        ),
    ]
    runtime_status = "pass" if all(status == "pass" for status in runtime_test_statuses) else "mismatch"
    background_class = "com.supermetroid.editor.rom.BackgroundSourceParityTest"
    background_test_statuses = [
        named_test_status(
            test_results,
            background_class,
            "all library background programs and commands match named source",
        ),
        named_test_status(
            test_results,
            background_class,
            "compressed assets transfers and embedded ownership cover every room state",
        ),
        named_test_status(
            test_results,
            background_class,
            "production reconstructs repeated wide Kraid and door selected tilemaps",
        ),
    ]
    background_status = "pass" if all(status == "pass" for status in background_test_statuses) else "mismatch"
    minimap_class = "com.supermetroid.editor.rom.MinimapSourceParityTest"
    minimap_test_statuses = [
        named_test_status(
            test_results,
            minimap_class,
            "all area tilemaps and map data masks match source and round trip exactly",
        ),
        named_test_status(
            test_results,
            minimap_class,
            "coordinate transforms preserve both pages MSB bit order and padding row",
        ),
        named_test_status(
            test_results,
            minimap_class,
            "pause map graphics and all vanilla station placements match source",
        ),
    ]
    minimap_status = "pass" if all(status == "pass" for status in minimap_test_statuses) else "mismatch"
    load_station_class = "com.supermetroid.editor.rom.LoadStationSourceParityTest"
    load_station_test_statuses = [
        named_test_status(
            test_results,
            load_station_class,
            "all load station lists and runtime spawn fields match source",
        ),
        named_test_status(
            test_results,
            load_station_class,
            "save PLMs own exactly the addressable save entries",
        ),
        named_test_status(
            test_results,
            load_station_class,
            "special elevator debug Ceres and gunship entries stay distinct",
        ),
    ]
    load_station_status = "pass" if all(status == "pass" for status in load_station_test_statuses) else "mismatch"
    resources = rooms["resourceCounts"]
    runtime_plms = scroll_runtime["genericPlms"]
    runtime_doors = scroll_runtime["doors"]
    runtime_writers = scroll_runtime["writerInventory"]
    return [
        {
            "id": "R-01",
            "name": "Room catalog and 11-byte headers",
            "status": header_status,
            "evidence": (
                f"All {rooms['roomCount']} source room macros, including the explicitly unused/debug records, "
                f"match rebuilt-ROM bytes and production parsing; byte-exact no-edit, explicit no-op, and "
                f"whole-catalog mutation exporter proofs are {header_status}."
            ),
        },
        {
            "id": "R-02",
            "name": "State selectors and 26-byte state data",
            "status": header_status,
            "evidence": (
                f"All {rooms['stateCount']} states and {rooms['conditionalSelectorCount']} conditional selectors "
                f"match source order, condition operands, pointers, fields, and production inspection; "
                f"byte-exact no-edit, explicit no-op, and whole-catalog mutation exporter proofs are {header_status}."
            ),
        },
        {
            "id": "R-03",
            "name": "Room level-data streams",
            "status": level_status,
            "evidence": (
                f"All {rooms['levelStreamCount']} source streams "
                f"({rooms['activeLevelStreamCount']} active + {rooms['unusedLevelStreamCount']} explicitly unused) "
                f"match compressed/decoded bytes and semantic Layer 1/BTS/Layer 2 boundaries; "
                f"{rooms['levelStreamWithLayer2Count']} carry Layer 2. Bowling Alley and Double Chamber's "
                f"source-owned over-allocation is explicit; the explicit no-op export proof is {noop_status}."
            ),
        },
        {
            "id": "R-04",
            "name": "PLM populations",
            "status": resource_status,
            "evidence": f"All {resources['plm']} distinct PLM sets match IDs, coordinates, parameters, terminators, aliases, production serialization, and explicit no-op export.",
        },
        {
            "id": "R-05",
            "name": "Enemy populations",
            "status": resource_status,
            "evidence": f"All {resources['enemyPopulation']} distinct populations match every 16-byte enemy record, production parsing, and explicit no-op export.",
        },
        {
            "id": "R-06",
            "name": "Enemy GFX sets",
            "status": resource_status,
            "evidence": f"All {resources['enemyGfx']} distinct sets match species/palette words, terminators, the four-slot engine limit, and no-op population export.",
        },
        {
            "id": "R-07",
            "name": "FX entries",
            "status": resource_status,
            "evidence": f"All {resources['fx']} distinct lists match every field, sentinel, and explicit no-op export; production now correctly treats the two-byte $FFFF no-FX record as a terminator.",
        },
        {
            "id": "R-08",
            "name": "Door lists and DDBs",
            "status": resource_status,
            "evidence": (
                f"All {resources['doorList']} room lists / {resources['doorAssociation']} associations "
                f"resolve to {resources['uniqueDoorDef']} source door records and match production fields, including special null records and explicit no-op export."
            ),
        },
        {
            "id": "R-09",
            "name": "Static scrolls and sentinels",
            "status": resource_status,
            "evidence": (
                f"All {resources['scrollTable']} named tables and {resources['scrollAssociation']} state associations match source/ROM bytes, "
                f"including {resources['scrollSentinelAssociation']} sentinel associations and 38 alias groups. Double Chamber's intentional "
                "overread is recorded raw and normalized to the engine's green semantics in production; explicit no-op export is byte exact."
            ),
        },
        {
            "id": "R-10",
            "name": "Scroll PLMs and door-ASM overrides",
            "status": runtime_status if resource_status == "pass" else resource_status,
            "evidence": (
                f"Load precedence is source-proven as static table → PLM setup → incoming door ASM → room setup ASM. "
                f"All {runtime_plms['triggerCount']} generic triggers / {runtime_plms['uniqueCommandStreamCount']} command streams / "
                f"{runtime_plms['commandCount']} writes and {runtime_plms['extensionCount']} extensions match production; "
                f"the one source-owned East Pants orphan is explicit. All {runtime_doors['scrollWriterRoutineCount']} active door scroll writers / "
                f"{runtime_doors['scrollWriterAssociationCount']} door associations match source, including the mixed elevatube routine; "
                f"the complete engine inventory covers {runtime_writers['routineCount']} routines / {runtime_writers['storeSiteCount']} direct stores."
            ),
        },
        {
            "id": "R-11",
            "name": "BG data and embedded Layer 2",
            "status": background_status if level_status == "pass" else level_status,
            "evidence": (
                f"All {rooms['levelStreamWithLayer2Count']} Layer-2-bearing level streams and "
                f"{backgrounds['embeddedLayer2StateCount']} active embedded-Layer-2 states are proven. "
                f"All {backgrounds['programCount']} named library-background programs "
                f"({backgrounds['activeProgramCount']} active + {backgrounds['unusedProgramCount']} unused), "
                f"{backgrounds['commandCount']} commands, {backgrounds['stateAssociationCount']} state associations, "
                f"{backgrounds['referencedCompressedBackgroundCount']} referenced compressed backgrounds, and "
                f"{backgrounds['doorDependentTransferCount']} door-dependent transfers match source and production."
            ),
        },
        {
            "id": "R-12",
            "name": "Minimap and map stations",
            "status": minimap_status,
            "evidence": (
                f"All {minimap['areaCount']} bank-$B5 area tilemaps / {minimap['tilemapWordCount']} words, "
                f"{minimap['mapDataByteCount']} map-data bytes / {minimap['revealedTileCount']} set cells, "
                f"{minimap['mapStationPlacementCount']} map-station placements across "
                f"{minimap['mapStationAreaCount']} areas, {minimap['roomCount']} room rectangles / "
                f"{minimap['roomScreenCount']} screens inside the engine-safe 64x"
                f"{minimap['roomCoordinateHeight']} room-coordinate area, both two-page coordinate transforms, "
                f"{minimap['graphics']['tileCount']} pause-map graphics tiles, and exact production "
                "read/write round trips match source."
            ),
        },
        {
            "id": "R-13",
            "name": "Save/elevator/debug stations",
            "status": load_station_status,
            "evidence": (
                f"All {load_stations['areaCount']} bank-$80 load-station lists / "
                f"{load_stations['entryCount']} entries / {load_stations['entryByteCount']} bytes and every "
                "14-byte runtime spawn field match source and production parsing. The engine-addressable "
                f"save partition contains {load_stations['occupiedSaveSlotCount']} occupied + "
                f"{load_stations['emptySaveSlotCount']} empty slots and "
                f"{load_stations['savePlmPlacementCount']} normal save PLMs; "
                f"{load_stations['occupiedElevatorEntryCount']} occupied elevator entries, "
                f"{load_stations['debugEntryCount']} debug entries, "
                f"{load_stations['ceresSequenceEntryCount']} Ceres sequence entries, and the gunship-owned "
                "Crateria slot remain explicitly distinct."
            ),
        },
    ]


def markdown_report(report: Dict[str, object]) -> str:
    identity = report["identity"]
    summary = report["summary"]
    symbols = report["symbols"]
    assets = report["assets"]
    lz5 = report["lz5"]
    tilesets = report["tilesets"]
    tile_formats = report["tileFormats"]
    animated_tiles = report["animatedTiles"]
    item_plm_graphics = report["itemPlmGraphics"]
    enemy_headers = report["enemyHeaders"]
    enemy_oam = report["enemyOam"]
    enemy_instructions = report["enemyInstructions"]
    enemy_vertical_slices = report["enemyVerticalSlices"]
    kraid = report["kraid"]
    phantoon = report["phantoon"]
    draygon = report["draygon"]
    ridley = report["ridley"]
    mother_brain = report["motherBrain"]
    crocomire = report["crocomire"]
    spore_spawn = report["sporeSpawn"]
    botwoon = report["botwoon"]
    torizo = report["torizo"]
    metroid = report["metroid"]
    samus = report["samus"]
    rooms = report["rooms"]
    scroll_runtime = report["scrollRuntime"]
    backgrounds = report["backgrounds"]
    minimap = report["minimap"]
    load_stations = report["loadStations"]
    ordinary_enemy_animations = report["ordinaryEnemyAnimations"]
    enemy_species_status = report["enemySpeciesStatus"]
    tests = report["tests"]
    lines = [
        "# SMEDIT Parity Report",
        "",
        f"Overall: **{report['overall'].upper()}**",
        "",
        "## Identity",
        "",
        "| Input | Value |",
        "|---|---|",
        f"| SMEDIT commit | `{identity['smeditCommit']}`{' (dirty)' if identity['smeditDirty'] else ''} |",
        f"| Disassembly commit | `{identity['disassemblyCommit']}` |",
        f"| Clean/rebuilt ROM SHA-256 | `{identity['romSha256']}` |",
        f"| Asar | `{identity['asarVersion']}` at `{identity['asarCommit']}` |",
        f"| Generated | `{report['generatedAt']}` |",
        "",
        "## Parity status",
        "",
        "| Status | Count |",
        "|---|---:|",
    ]
    for status in ("pass", "partial", "mismatch", "uncovered"):
        lines.append(f"| {status.capitalize()} | {summary.get(status, 0)} |")
    lines.extend(
        [
            "",
            "| ID | Unit | Status | Evidence |",
            "|---|---|---|---|",
        ]
    )
    for check in report["checks"]:
        lines.append(
            f"| {check['id']} | {check['name']} | **{str(check['status']).capitalize()}** | {check['evidence']} |"
        )
    lines.extend(
        [
            "",
            "## Live evidence",
            "",
            f"- Symbols: **{symbols['count']:,}** parsed labels.",
            f"- Assets: **{assets['activeCount']:,}** active NTSC ranges, all byte-identical; "
            f"**{assets['inactiveCount']}** PAL-only declarations recorded.",
            f"- Source comment warnings: **{assets['sourceCommentSizeMismatchCount']}** size comments "
            "disagree with authoritative extracted/assembled bytes.",
            f"- LZ5: **{lz5['exactStreamCount']}** exact streams "
            f"(**{lz5['activeStreamCount']}** active, **{lz5['unusedStreamCount']}** unused), "
            f"largest decoded payload **{lz5['maxDecompressedSize']:,} bytes**.",
            "- LZ5 command use in the vanilla corpus: "
            + ", ".join(
                f"{command}={count:,}" for command, count in lz5["commandCounts"].items()
            )
            + ".",
            f"- Tilesets: **{tilesets['count']}** triples / **{tilesets['pointerFieldCount']}** fields map to "
            f"**{tilesets['uniqueResourceCount']}** named source assets "
            f"({tilesets['uniqueResourceCounts']['tileTable']} tables, "
            f"{tilesets['uniqueResourceCounts']['graphics']} graphics, "
            f"{tilesets['uniqueResourceCounts']['palette']} palettes).",
            "- Intentional tileset alias groups: "
            + ", ".join(
                f"{kind}={count}" for kind, count in tilesets["aliasGroupCounts"].items()
            )
            + ".",
            f"- CRE: **{tilesets['cre']['graphicsDecompressedBytes']:,}** graphics bytes and "
            f"**{tilesets['cre']['tileTableDecompressedBytes']:,}** tile-table bytes; "
            f"**{tilesets['cre']['consumerCount']}** direct engine consumers inventoried.",
            f"- Planar pixels: **{tile_formats['graphics4bppTileCount']:,}** 4bpp tiles across "
            f"**{tile_formats['graphics4bppResourceCount']}** resources plus "
            f"**{tile_formats['graphics2bppTileCount']:,}** standard BG3 2bpp tiles.",
            f"- Metatiles: **{tile_formats['metatileCount']:,}** entries / "
            f"**{tile_formats['metatileWordCount']:,}** words across "
            f"**{tile_formats['metatileTableResourceCount']}** source tables.",
            f"- Standard area payloads: **{tile_formats['shortStandard4bppResourceCount']}** define "
            "576 tiles followed by the engine's 64-tile reserved blank gap before CRE.",
            f"- Animated tiles: **{animated_tiles['assetCount']}** bank-$87 payloads, "
            f"**{animated_tiles['referencedAssetCount']}** referenced by "
            f"**{animated_tiles['uniqueFrameInstructionCount']}** frame instructions across "
            f"**{animated_tiles['objectCount']}** objects; **{animated_tiles['orphanAssetCount']}** "
            "explicit unused payloads remain unreferenced.",
            f"- Item PLM graphics: **{item_plm_graphics['assetCount']}** bank-$89 payloads / "
            f"**{item_plm_graphics['tileCount']}** tiles feed **{item_plm_graphics['loadRecordCount']}** "
            f"PLM variants through **{item_plm_graphics['slotCount']}** wrapping runtime slots and "
            f"**{item_plm_graphics['drawPointerCount']}** draw pointers.",
            f"- Enemy headers: **{enemy_headers['headerCount']}** source macros / "
            f"**{enemy_headers['macroFieldCount']:,}** fields; "
            f"**{enemy_headers['nonEmptyGraphicsHeaderCount']}** nonempty GRAPHADR associations "
            f"map to **{enemy_headers['uniqueGraphicsRangeCount']}** unique ranges and "
            f"**{enemy_headers['sourceAssetCount']}** named assets.",
            f"- Enemy GRAPHADR ownership: **{enemy_headers['startAliasGroupCount']}** shared-start "
            f"groups, **{enemy_headers['overlappingUniqueRangePairCount']}** overlapping unique-range "
            f"pairs, and **{enemy_headers['multiAssetGraphicsHeaderCount']}** species ranges spanning "
            "multiple adjacent extracted assets.",
            f"- Enemy OAM: **{enemy_oam['standardLabelCount']:,}** named standard structures / "
            f"**{enemy_oam['standardEntryCount']:,}** entries, **{enemy_oam['extendedLabelCount']:,}** "
            f"extended structures / **{enemy_oam['extendedChildAssociationCount']:,}** child links, and "
            f"**{enemy_oam['tilemapLabelCount']}** extended tilemaps / "
            f"**{enemy_oam['tilemapWordCount']:,}** words.",
            f"- Enemy instructions: **{enemy_instructions['listCount']:,}** named lists / "
            f"**{enemy_instructions['recordCount']:,}** source records; the current preview recovers "
            f"**{enemy_instructions['recoveredSourceFrameCount']:,}** of "
            f"**{enemy_instructions['renderableFrameCount']:,}** renderable source frames and misses "
            f"**{enemy_instructions['missedSourceFrameCount']:,}** across "
            f"**{enemy_instructions['listsWithMissedFrames']:,}** lists.",
            f"- Verified enemy slices: **{enemy_vertical_slices['sliceCount']}** "
            f"(Zoomer, Sidehopper, walking Space Pirate), covering "
            f"**{enemy_vertical_slices['frameCount']}** source frame occurrences / "
            f"**{enemy_vertical_slices['uniqueFrameSpritemapCount']}** unique spritemaps and "
            f"**{enemy_vertical_slices['handlerCount']}** handler occurrences through production tracing and rendering.",
            f"- Kraid: **{kraid['headMapCount']}** custom BG2 head maps / "
            f"**{kraid['customFrameCount']}** frame occurrences, **{kraid['paletteStateCount']}** "
            f"palette states, and **{kraid['linkedOamHeaderCount']}** linked OAM headers are source-pinned.",
            f"- Phantoon: **{phantoon['tilemapCount']}** active BG2 tilemaps / "
            f"**{phantoon['tilemapWordCount']}** words, **{phantoon['instructionListCount']}** part lists / "
            f"**{phantoon['frameCount']}** frame occurrences, and **{phantoon['healthPaletteCount']}** "
            "runtime health palettes are source-pinned.",
            f"- Draygon: **{draygon['standardSpritemapCount']}** standard OAM maps, "
            f"**{draygon['extendedSpritemapCount']}** extended maps, **{draygon['tilemapCount']}** BG2 maps, "
            f"and **{draygon['productionAnimationCount']}** production lists / "
            f"**{draygon['productionAnimationFrameCount']}** complete frame occurrences are source-pinned.",
            f"- Ridley: **{ridley['bodyExtendedSpritemapCount']}** body maps, "
            f"**{ridley['wingSpritemapCount']}** wing maps, **{ridley['tailSpritemapCount']}** tail maps, "
            f"**{ridley['runtimeDmaAssetCount']}** ribs/claws DMA assets, and "
            f"**{ridley['animationListCount']}** encounter lists / **{ridley['animationFrameCount']}** "
            "timed frames are source-pinned across the shared Norfair/Ceres visual recipe.",
            f"- Mother Brain: **{mother_brain['headSpritemapCount']}** head maps, "
            f"**{mother_brain['bodyExtendedSpritemapCount']}** extended body maps, "
            f"**{mother_brain['bodyTilemapCount']}** BG2 maps, and "
            f"**{mother_brain['instructionListCount']}** body/head lists / "
            f"**{mother_brain['frameCount']}** timed frames are source-pinned.",
            f"- Crocomire: **{crocomire['extendedSpritemapCount']}** extended maps, "
            f"**{crocomire['standardSpritemapCount']}** OBJ maps, **{crocomire['tilemapCount']}** BG2 maps, and "
            f"**{crocomire['instructionListCount']}** active lists / **{crocomire['frameCount']}** timed frames "
            "cover the living, melting, and skeleton phases with distinct runtime pixel owners.",
            f"- Spore Spawn: **{spore_spawn['activeExtendedSpritemapCount']}** active body maps, "
            f"**{spore_spawn['projectileSpritemapCount']}** stalk/spawner/spore projectile maps, and "
            f"**{spore_spawn['instructionListCount']}** lists / **{spore_spawn['frameCount']}** timed frames "
            "are source-pinned across banks $A5, $86, and $8D.",
            f"- Botwoon: **{botwoon['activeHeadSpritemapCount']}** active head maps, "
            f"**{botwoon['activeProjectileSpritemapCount']}** active body/tail/spit projectile maps, "
            f"**{botwoon['guidedAnimationCount']}** guided animations / "
            f"**{botwoon['guidedAnimationFrameCount']}** rendered frames, and "
            f"**{botwoon['paletteCount']}** palette rows are source-pinned across banks $B3, $86, and $8D.",
            f"- Torizo: **{torizo['activeBodySpritemapCount']}** active full-body maps, "
            f"**{torizo['activeBodyChildSpritemapCount']}** active body-child maps, "
            f"**{torizo['activeProjectileSpritemapCount']}** active projectile/effect maps, "
            f"**{torizo['runtimeTransferCount']}** runtime tile transfers, and "
            f"**{torizo['paletteRowCount']}** palette rows are source-pinned across the unified Bomb/Golden workspace.",
            f"- Metroid: **{metroid['insideSpritemapCount']}** inside maps, "
            f"**{metroid['shellSpritemapCount']}** shell maps, and "
            f"**{metroid['electricitySpritemapCount']}** electricity maps are source-pinned across "
            f"**{metroid['spriteObjectFrameOccurrenceCount']}** independently timed companion frames; "
            f"**{metroid['fallthroughContinuationCount']}** source-labelled unused lists are proven runtime-active continuations.",
            f"- Samus: **{samus['poseCount']}** poses / **{samus['frameOccurrenceCount']:,}** frame occurrences, "
            f"**{samus['dmaEntryCount']}** exact DMA payloads, and **{samus['spritemapCount']}** spritemaps are "
            "source-pinned through production decoding.",
            f"- Rooms: **{rooms['roomCount']}** headers / **{rooms['stateCount']}** states, "
            f"**{rooms['levelStreamCount']}** level streams, and **{rooms['doorAssociationCount']}** door associations "
            "are source-pinned through production parsers and an exact exporter mutation allowlist.",
            f"- Runtime scrolls: **{scroll_runtime['triggerCount']}** generic triggers / "
            f"**{scroll_runtime['commandStreamCount']}** command streams / **{scroll_runtime['commandCount']}** writes, "
            f"**{scroll_runtime['extensionCount']}** extensions, **{scroll_runtime['doorWriterRoutineCount']}** active "
            f"door writer routines, and **{scroll_runtime['directWriterRoutineCount']}** total direct engine writers are source-pinned; "
            f"**{scroll_runtime['orphanExtensionCount']}** vanilla orphan is explicit.",
            f"- Room backgrounds: **{backgrounds['programCount']}** named programs / "
            f"**{backgrounds['commandCount']}** commands / **{backgrounds['stateAssociationCount']}** state associations, "
            f"**{backgrounds['embeddedLayer2StateCount']}** embedded-Layer-2 states, and "
            f"**{backgrounds['doorDependentTransferCount']}** door-dependent transfers are source-pinned.",
            f"- Minimap: **{minimap['areaCount']}** area tilemaps / **{minimap['tilemapWordCount']:,}** words, "
            f"**{minimap['mapDataByteCount']:,}** map-data bytes, **{minimap['mapStationPlacementCount']}** "
            f"map-station placements, an engine-safe **64×{minimap['roomCoordinateHeight']}** room area, and both "
            "page/padding transforms are source-pinned with exact write round trips.",
            f"- Load stations: **{load_stations['entryCount']}** entries / "
            f"**{load_stations['entryByteCount']:,} bytes** across **{load_stations['areaCount']}** lists; "
            f"**{load_stations['occupiedSaveSlotCount']}** occupied + "
            f"**{load_stations['emptySaveSlotCount']}** empty save slots, "
            f"**{load_stations['occupiedElevatorEntryCount']}** occupied elevator entries, "
            f"**{load_stations['debugEntryCount']}** debug entries, and "
            f"**{load_stations['ceresSequenceEntryCount']}** Ceres entries are source-pinned.",
            f"- Ordinary enemy source routes: **{ordinary_enemy_animations['speciesCount']}** helper-selected species expose "
            f"**{ordinary_enemy_animations['animationCount']}** actions / "
            f"**{ordinary_enemy_animations['animationFrameCount']}** guided frames from exact source instruction lists.",
            f"- Enemy species status: **{enemy_species_status['assembledCount']}** assembled, "
            f"**{enemy_species_status['compositeCount']}** composite, "
            f"**{enemy_species_status['tileSheetOnlyCount']}** tile-sheet-only, "
            f"**{enemy_species_status['nonvisualCount']}** nonvisual, and "
            f"**{enemy_species_status['failedCount']}** failed; production preview paths cover "
            f"**{enemy_species_status['previewAvailableCount']} / {enemy_species_status['speciesCount']}** source species.",
            f"- Strict parity tests: **{tests['tests']}** run, **{tests['failures']}** failures, "
            f"**{tests['errors']}** errors, **{tests['skipped']}** skipped in {tests['timeSeconds']:.3f}s.",
            "- Address drift: **12** standalone SMEDIT constants plus all **87** tileset fields currently mapped.",
            "",
            "Detailed symbol, asset, compression, tileset, tile-format, animated-tile, item-PLM, enemy-header, enemy-OAM, enemy-instruction, enemy-slice, room/state/resource, runtime-scroll, enemy-species-status, alias, and CRE records "
            "are in `symbols.json`, `assets.json`, `lz5.json`, `tilesets.json`, `tile-formats.json`, "
            "`animated-tiles.json`, `item-plm-graphics.json`, `enemy-headers.json`, `enemy-oam.json`, and "
            "`enemy-instructions.json`, `enemy-vertical-slices.json`, `rooms.json`, `scroll-runtime.json`, `backgrounds.json`, `minimap.json`, `load-stations.json`, `ordinary-enemy-animations.json`, `kraid.json`, `phantoon.json`, `draygon.json`, `ridley.json`, `mother-brain.json`, `crocomire.json`, `spore-spawn.json`, `botwoon.json`, `torizo.json`, `metroid.json`, `samus.json`, and `enemy-species-status.json` beside this report. "
            "A human-readable species ledger is also in `enemy-species-status.md`.",
            "",
        ]
    )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Aggregate the strict parity run into JSON and Markdown.")
    parser.add_argument("--report-dir", type=Path, default=DEFAULT_REPORT_DIR)
    parser.add_argument("--test-results", type=Path, default=DEFAULT_TEST_RESULTS)
    args = parser.parse_args()

    report_dir = args.report_dir.expanduser().resolve()
    symbols_path = report_dir / "symbols.json"
    assets_path = report_dir / "assets.json"
    lz5_path = report_dir / "lz5.json"
    tilesets_path = report_dir / "tilesets.json"
    tile_formats_path = report_dir / "tile-formats.json"
    animated_tiles_path = report_dir / "animated-tiles.json"
    item_plm_graphics_path = report_dir / "item-plm-graphics.json"
    enemy_headers_path = report_dir / "enemy-headers.json"
    enemy_oam_path = report_dir / "enemy-oam.json"
    enemy_instructions_path = report_dir / "enemy-instructions.json"
    enemy_vertical_slices_path = report_dir / "enemy-vertical-slices.json"
    kraid_path = report_dir / "kraid.json"
    phantoon_path = report_dir / "phantoon.json"
    draygon_path = report_dir / "draygon.json"
    ridley_path = report_dir / "ridley.json"
    mother_brain_path = report_dir / "mother-brain.json"
    crocomire_path = report_dir / "crocomire.json"
    spore_spawn_path = report_dir / "spore-spawn.json"
    botwoon_path = report_dir / "botwoon.json"
    torizo_path = report_dir / "torizo.json"
    metroid_path = report_dir / "metroid.json"
    samus_path = report_dir / "samus.json"
    rooms_path = report_dir / "rooms.json"
    scroll_runtime_path = report_dir / "scroll-runtime.json"
    backgrounds_path = report_dir / "backgrounds.json"
    minimap_path = report_dir / "minimap.json"
    load_stations_path = report_dir / "load-stations.json"
    ordinary_enemy_animations_path = report_dir / "ordinary-enemy-animations.json"
    enemy_species_status_path = report_dir / "enemy-species-status.json"
    if (
        not symbols_path.is_file()
        or not assets_path.is_file()
        or not lz5_path.is_file()
        or not tilesets_path.is_file()
        or not tile_formats_path.is_file()
        or not animated_tiles_path.is_file()
        or not item_plm_graphics_path.is_file()
        or not enemy_headers_path.is_file()
        or not enemy_oam_path.is_file()
        or not enemy_instructions_path.is_file()
        or not enemy_vertical_slices_path.is_file()
        or not kraid_path.is_file()
        or not phantoon_path.is_file()
        or not draygon_path.is_file()
        or not ridley_path.is_file()
        or not mother_brain_path.is_file()
        or not crocomire_path.is_file()
        or not spore_spawn_path.is_file()
        or not botwoon_path.is_file()
        or not torizo_path.is_file()
        or not metroid_path.is_file()
        or not samus_path.is_file()
        or not rooms_path.is_file()
        or not scroll_runtime_path.is_file()
        or not backgrounds_path.is_file()
        or not minimap_path.is_file()
        or not load_stations_path.is_file()
        or not ordinary_enemy_animations_path.is_file()
        or not enemy_species_status_path.is_file()
    ):
        print(
            "ERROR: symbol/asset/LZ5/tileset/tile-format/animated-tile/item-PLM/enemy-header/enemy-OAM/enemy-instruction/enemy-slice/room/scroll-runtime/background/minimap/load-station/ordinary-enemy/Kraid/Phantoon/Draygon/Ridley/Mother-Brain/Crocomire/Spore-Spawn/Botwoon/Torizo/Metroid/Samus/enemy-species reports are missing; "
            "run ./gradlew parityReport",
            file=sys.stderr,
        )
        return 2

    reference = read_properties(REFERENCE_FILE)
    symbols = json.loads(symbols_path.read_text(encoding="utf-8"))
    assets = json.loads(assets_path.read_text(encoding="utf-8"))
    lz5 = json.loads(lz5_path.read_text(encoding="utf-8"))
    tilesets = json.loads(tilesets_path.read_text(encoding="utf-8"))
    tile_formats = json.loads(tile_formats_path.read_text(encoding="utf-8"))
    animated_tiles = json.loads(animated_tiles_path.read_text(encoding="utf-8"))
    item_plm_graphics = json.loads(item_plm_graphics_path.read_text(encoding="utf-8"))
    enemy_headers = json.loads(enemy_headers_path.read_text(encoding="utf-8"))
    enemy_oam = json.loads(enemy_oam_path.read_text(encoding="utf-8"))
    enemy_instructions = json.loads(enemy_instructions_path.read_text(encoding="utf-8"))
    enemy_vertical_slices = json.loads(enemy_vertical_slices_path.read_text(encoding="utf-8"))
    kraid = json.loads(kraid_path.read_text(encoding="utf-8"))
    phantoon = json.loads(phantoon_path.read_text(encoding="utf-8"))
    draygon = json.loads(draygon_path.read_text(encoding="utf-8"))
    ridley = json.loads(ridley_path.read_text(encoding="utf-8"))
    mother_brain = json.loads(mother_brain_path.read_text(encoding="utf-8"))
    crocomire = json.loads(crocomire_path.read_text(encoding="utf-8"))
    spore_spawn = json.loads(spore_spawn_path.read_text(encoding="utf-8"))
    botwoon = json.loads(botwoon_path.read_text(encoding="utf-8"))
    torizo = json.loads(torizo_path.read_text(encoding="utf-8"))
    metroid = json.loads(metroid_path.read_text(encoding="utf-8"))
    samus = json.loads(samus_path.read_text(encoding="utf-8"))
    rooms = json.loads(rooms_path.read_text(encoding="utf-8"))
    scroll_runtime = json.loads(scroll_runtime_path.read_text(encoding="utf-8"))
    backgrounds = json.loads(backgrounds_path.read_text(encoding="utf-8"))
    minimap = json.loads(minimap_path.read_text(encoding="utf-8"))
    load_stations = json.loads(load_stations_path.read_text(encoding="utf-8"))
    ordinary_enemy_animations = json.loads(ordinary_enemy_animations_path.read_text(encoding="utf-8"))
    enemy_species_status = json.loads(enemy_species_status_path.read_text(encoding="utf-8"))
    tests = collect_test_results(args.test_results.expanduser().resolve())
    if symbols["symbolCount"] != int(reference["symbols.count"]):
        raise ValueError("symbol report count does not match the pinned reference")
    if assets["activeAssetCount"] != int(reference["assets.ntsc.count"]):
        raise ValueError("asset report count does not match the pinned reference")
    pinned_lz5_fields = {
        "exactCompressedStreamCount": "lz5.streams.count",
        "activeCompressedStreamCount": "lz5.streams.active.count",
        "unusedCompressedStreamCount": "lz5.streams.unused.count",
        "maxDecompressedSize": "lz5.output.maxBytes",
    }
    for field, property_name in pinned_lz5_fields.items():
        if int(lz5[field]) != int(reference[property_name]):
            raise ValueError(f"LZ5 report field {field} does not match the pinned reference")
    for command in range(8):
        if int(lz5["aggregateCommandCounts"][str(command)]) != int(
            reference[f"lz5.command.{command}.count"]
        ):
            raise ValueError(f"LZ5 command {command} count does not match the pinned reference")

    pinned_tileset_fields = {
        "tilesetCount": "tilesets.count",
        "pointerFieldCount": "tilesets.pointerFields.count",
        "uniqueResourceCount": "tilesets.resources.unique.count",
    }
    for field, property_name in pinned_tileset_fields.items():
        if int(tilesets[field]) != int(reference[property_name]):
            raise ValueError(f"tileset report field {field} does not match the pinned reference")
    for kind in ("tileTable", "graphics", "palette"):
        if int(tilesets["uniqueResourceCounts"][kind]) != int(
            reference[f"tilesets.{kind}.unique.count"]
        ):
            raise ValueError(f"tileset {kind} unique count does not match the pinned reference")
        if int(tilesets["aliasGroupCounts"][kind]) != int(
            reference[f"tilesets.{kind}.aliasGroups.count"]
        ):
            raise ValueError(f"tileset {kind} alias count does not match the pinned reference")
    for kind, property_prefix in (("graphics", "cre.graphics"), ("tileTable", "cre.tileTable")):
        cre_resource = tilesets["cre"][kind]
        expected_fields = {
            "compressedSize": f"{property_prefix}.compressedBytes",
            "decompressedSize": f"{property_prefix}.decompressedBytes",
        }
        for field, property_name in expected_fields.items():
            if int(cre_resource[field]) != int(reference[property_name]):
                raise ValueError(f"CRE {kind} {field} does not match the pinned reference")
        if int(cre_resource["consumers"]["consumerCount"]) != int(
            reference[f"{property_prefix}.consumer.count"]
        ):
            raise ValueError(f"CRE {kind} consumer count does not match the pinned reference")
        if int(cre_resource["consumers"]["referenceCount"]) != int(
            reference[f"{property_prefix}.reference.count"]
        ):
            raise ValueError(f"CRE {kind} reference count does not match the pinned reference")

    pinned_tile_format_totals = {
        "graphics4bppResourceCount": "tileFormats.graphics4bpp.resource.count",
        "tilesetGraphics4bppResourceCount": "tileFormats.graphics4bpp.tilesetResource.count",
        "creGraphics4bppResourceCount": "tileFormats.graphics4bpp.creResource.count",
        "standard4bppResourceCount": "tileFormats.graphics4bpp.standardResource.count",
        "splitPlane4bppResourceCount": "tileFormats.graphics4bpp.splitPlaneResource.count",
        "shortStandard4bppResourceCount": "tileFormats.graphics4bpp.shortStandardResource.count",
        "graphics4bppTileCount": "tileFormats.graphics4bpp.tile.count",
        "graphics2bppResourceCount": "tileFormats.graphics2bpp.resource.count",
        "graphics2bppTileCount": "tileFormats.graphics2bpp.tile.count",
        "metatileTableResourceCount": "tileFormats.metatileTable.resource.count",
        "metatileCount": "tileFormats.metatile.count",
        "metatileWordCount": "tileFormats.metatileWord.count",
    }
    for field, property_name in pinned_tile_format_totals.items():
        if int(tile_formats["totals"][field]) != int(reference[property_name]):
            raise ValueError(f"tile-format total {field} does not match the pinned reference")
    pinned_tile_format_hashes = {
        "graphics4bppPixels": "tileFormats.graphics4bpp.aggregatePixel.sha256",
        "graphics2bppPixels": "tileFormats.graphics2bpp.aggregatePixel.sha256",
        "metatileSemantics": "tileFormats.metatile.aggregateSemantic.sha256",
    }
    for field, property_name in pinned_tile_format_hashes.items():
        if tile_formats["aggregateHashes"][field] != reference[property_name]:
            raise ValueError(f"tile-format aggregate {field} does not match the pinned reference")

    pinned_animated_tile_totals = {
        "assetCount": "animatedTiles.asset.count",
        "assetByteCount": "animatedTiles.asset.byte.count",
        "referencedAssetCount": "animatedTiles.asset.referenced.count",
        "orphanAssetCount": "animatedTiles.asset.orphan.count",
        "sourceDeclaredUnusedAssetCount": "animatedTiles.asset.sourceDeclaredUnused.count",
        "objectCount": "animatedTiles.object.count",
        "nonEmptyObjectCount": "animatedTiles.object.nonEmpty.count",
        "uniqueFrameInstructionCount": "animatedTiles.frame.unique.count",
        "objectFrameAssociationCount": "animatedTiles.frame.objectAssociation.count",
        "areaListCount": "animatedTiles.areaList.count",
        "areaBitMappingCount": "animatedTiles.areaBitMapping.count",
        "consumerCount": "animatedTiles.consumer.count",
        "spawnConsumerCount": "animatedTiles.consumer.spawn.count",
        "handlerConsumerCount": "animatedTiles.consumer.handler.count",
        "dmaConsumerCount": "animatedTiles.consumer.dma.count",
    }
    for field, property_name in pinned_animated_tile_totals.items():
        if int(animated_tiles["totals"][field]) != int(reference[property_name]):
            raise ValueError(f"animated-tile total {field} does not match the pinned reference")
    pinned_animated_tile_hashes = {
        "assets": "animatedTiles.asset.aggregate.sha256",
        "objects": "animatedTiles.object.aggregate.sha256",
        "frames": "animatedTiles.frame.aggregate.sha256",
        "areaActivation": "animatedTiles.areaActivation.aggregate.sha256",
    }
    for field, property_name in pinned_animated_tile_hashes.items():
        if animated_tiles["aggregateHashes"][field] != reference[property_name]:
            raise ValueError(f"animated-tile aggregate {field} does not match the pinned reference")

    pinned_item_plm_totals = {
        "assetCount": "itemPlm.asset.count",
        "assetByteCount": "itemPlm.asset.byte.count",
        "tileCount": "itemPlm.tile.count",
        "frameCount": "itemPlm.frame.count",
        "loadRecordCount": "itemPlm.loadRecord.count",
        "plmIdCount": "itemPlm.id.count",
        "visiblePlmCount": "itemPlm.visible.count",
        "chozoPlmCount": "itemPlm.chozo.count",
        "hiddenPlmCount": "itemPlm.hidden.count",
        "paletteProfileCount": "itemPlm.paletteProfile.count",
        "slotCount": "itemPlm.slot.count",
        "drawPointerCount": "itemPlm.drawPointer.count",
        "dispatchCallCount": "itemPlm.dispatch.count",
    }
    for field, property_name in pinned_item_plm_totals.items():
        if int(item_plm_graphics["totals"][field]) != int(reference[property_name]):
            raise ValueError(f"item-PLM total {field} does not match the pinned reference")
    pinned_item_plm_hashes = {
        "assets": "itemPlm.asset.aggregate.sha256",
        "pixels": "itemPlm.pixel.aggregate.sha256",
        "loadRecords": "itemPlm.loadRecord.aggregate.sha256",
        "slots": "itemPlm.slot.aggregate.sha256",
    }
    for field, property_name in pinned_item_plm_hashes.items():
        if item_plm_graphics["aggregateHashes"][field] != reference[property_name]:
            raise ValueError(f"item-PLM aggregate {field} does not match the pinned reference")

    pinned_enemy_header_totals = {
        "headerCount": "enemyHeaders.header.count",
        "headerByteCount": "enemyHeaders.header.byte.count",
        "macroFieldCount": "enemyHeaders.macroField.count",
        "sourceDeclaredUnusedHeaderCount": "enemyHeaders.header.sourceDeclaredUnused.count",
        "nonEmptyGraphicsHeaderCount": "enemyHeaders.graphics.nonEmptyHeader.count",
        "zeroSizeGraphicsHeaderCount": "enemyHeaders.graphics.zeroSizeHeader.count",
        "alternateVramLayoutHeaderCount": "enemyHeaders.graphics.alternateVramLayoutHeader.count",
        "uniqueGraphicsStartCount": "enemyHeaders.graphics.uniqueStart.count",
        "uniqueGraphicsRangeCount": "enemyHeaders.graphics.uniqueRange.count",
        "graphicsAssociationByteCount": "enemyHeaders.graphics.associationByte.count",
        "graphicsTileAssociationCount": "enemyHeaders.graphics.tileAssociation.count",
        "sourceAssetCount": "enemyHeaders.graphics.sourceAsset.count",
        "sourceAssetSegmentAssociationCount": "enemyHeaders.graphics.segmentAssociation.count",
        "multiAssetGraphicsHeaderCount": "enemyHeaders.graphics.multiAssetHeader.count",
        "startAliasGroupCount": "enemyHeaders.graphics.startAliasGroup.count",
        "exactRangeAliasGroupCount": "enemyHeaders.graphics.exactRangeAliasGroup.count",
        "overlappingUniqueRangePairCount": "enemyHeaders.graphics.overlapPair.count",
        "crossStartOverlapPairCount": "enemyHeaders.graphics.crossStartOverlapPair.count",
    }
    for field, property_name in pinned_enemy_header_totals.items():
        if int(enemy_headers["totals"][field]) != int(reference[property_name]):
            raise ValueError(f"enemy-header total {field} does not match the pinned reference")
    pinned_enemy_header_hashes = {
        "headers": "enemyHeaders.header.aggregate.sha256",
        "sourceFields": "enemyHeaders.sourceField.aggregate.sha256",
        "graphics": "enemyHeaders.graphics.aggregate.sha256",
        "overlaps": "enemyHeaders.overlap.aggregate.sha256",
    }
    for field, property_name in pinned_enemy_header_hashes.items():
        if enemy_headers["aggregateHashes"][field] != reference[property_name]:
            raise ValueError(f"enemy-header aggregate {field} does not match the pinned reference")

    pinned_enemy_oam_totals = {
        "standardLabelCount": "enemyOam.standard.label.count",
        "standardUniqueAddressCount": "enemyOam.standard.uniqueAddress.count",
        "standardUnusedLabelCount": "enemyOam.standard.unusedLabel.count",
        "standardZeroEntryCount": "enemyOam.standard.zeroEntry.count",
        "standardEntryCount": "enemyOam.standard.entry.count",
        "standard8x8EntryCount": "enemyOam.standard.entry8x8.count",
        "standard16x16EntryCount": "enemyOam.standard.entry16x16.count",
        "extendedLabelCount": "enemyOam.extended.label.count",
        "extendedUniqueAddressCount": "enemyOam.extended.uniqueAddress.count",
        "extendedUnusedLabelCount": "enemyOam.extended.unusedLabel.count",
        "extendedChildAssociationCount": "enemyOam.extended.child.count",
        "extendedOamChildAssociationCount": "enemyOam.extended.oamChild.count",
        "extendedTilemapChildAssociationCount": "enemyOam.extended.tilemapChild.count",
        "uniqueHitboxAddressCount": "enemyOam.extended.hitboxAddress.count",
        "tilemapLabelCount": "enemyOam.tilemap.label.count",
        "tilemapUniqueAddressCount": "enemyOam.tilemap.uniqueAddress.count",
        "tilemapUnusedLabelCount": "enemyOam.tilemap.unusedLabel.count",
        "tilemapRunCount": "enemyOam.tilemap.run.count",
        "tilemapWordCount": "enemyOam.tilemap.word.count",
    }
    for field, property_name in pinned_enemy_oam_totals.items():
        if int(enemy_oam["totals"][field]) != int(reference[property_name]):
            raise ValueError(f"enemy-OAM total {field} does not match the pinned reference")
    pinned_enemy_oam_hashes = {
        "standard": "enemyOam.standard.aggregate.sha256",
        "extended": "enemyOam.extended.aggregate.sha256",
        "tilemaps": "enemyOam.tilemap.aggregate.sha256",
    }
    for field, property_name in pinned_enemy_oam_hashes.items():
        if enemy_oam["aggregateHashes"][field] != reference[property_name]:
            raise ValueError(f"enemy-OAM aggregate {field} does not match the pinned reference")

    pinned_enemy_instruction_totals = {
        "enemyListCount": "enemyInstructions.list.count",
        "sourceDeclaredUnusedListCount": "enemyInstructions.list.sourceDeclaredUnused.count",
        "excludedNonEnemyListCount": "enemyInstructions.list.excludedNonEnemy.count",
        "recordCount": "enemyInstructions.record.count",
        "frameRecordCount": "enemyInstructions.frame.count",
        "uniqueFrameSpritemapCount": "enemyInstructions.frame.uniqueSpritemap.count",
        "handlerOccurrenceCount": "enemyInstructions.handler.occurrence.count",
        "uniqueHandlerCount": "enemyInstructions.handler.unique.count",
        "commonWidthKnownHandlerCount": "enemyInstructions.handler.commonWidthKnown.count",
        "unsupportedHandlerCount": "enemyInstructions.handler.unsupported.count",
        "inconsistentHandlerWidthCount": "enemyInstructions.handler.inconsistentWidth.count",
        "productionRenderableFrameCount": "enemyInstructions.preview.renderableFrame.count",
        "productionUnrenderableFrameCount": "enemyInstructions.preview.unrenderableFrame.count",
        "productionRecoveredSourceFrameCount": "enemyInstructions.preview.recoveredSourceFrame.count",
        "productionMissedSourceFrameCount": "enemyInstructions.preview.missedSourceFrame.count",
        "productionOutOfBlockFrameCount": "enemyInstructions.preview.outOfBlockFrame.count",
        "listsWithMissedSourceFrames": "enemyInstructions.preview.listWithMiss.count",
        "listsWithUnsupportedHandlers": "enemyInstructions.handler.listWithUnsupported.count",
        "frameOnlyListCount": "enemyInstructions.list.frameOnly.count",
        "handlerOnlyListCount": "enemyInstructions.list.handlerOnly.count",
        "sourceCommentAddressMismatchCount": "enemyInstructions.sourceCommentAddressMismatch.count",
    }
    for field, property_name in pinned_enemy_instruction_totals.items():
        if int(enemy_instructions["totals"][field]) != int(reference[property_name]):
            raise ValueError(
                f"enemy-instruction total {field} does not match the pinned reference"
            )
    pinned_enemy_instruction_hashes = {
        "lists": "enemyInstructions.list.aggregate.sha256",
        "handlers": "enemyInstructions.handler.aggregate.sha256",
        "misses": "enemyInstructions.miss.aggregate.sha256",
    }
    for field, property_name in pinned_enemy_instruction_hashes.items():
        if enemy_instructions["aggregateHashes"][field] != reference[property_name]:
            raise ValueError(
                f"enemy-instruction aggregate {field} does not match the pinned reference"
            )

    pinned_enemy_slice_totals = {
        "sliceCount": "enemyVerticalSlices.slice.count",
        "frameOccurrenceCount": "enemyVerticalSlices.frame.count",
        "uniqueFrameSpritemapCount": "enemyVerticalSlices.frame.uniqueSpritemap.count",
        "handlerOccurrenceCount": "enemyVerticalSlices.handler.count",
        "standardFrameOccurrenceCount": "enemyVerticalSlices.frame.standard.count",
        "extendedFrameOccurrenceCount": "enemyVerticalSlices.frame.extended.count",
    }
    for field, property_name in pinned_enemy_slice_totals.items():
        if int(enemy_vertical_slices["totals"][field]) != int(reference[property_name]):
            raise ValueError(f"enemy-slice total {field} does not match the pinned reference")
    if enemy_vertical_slices["aggregateHashes"]["slices"] != reference[
        "enemyVerticalSlices.aggregate.sha256"
    ]:
        raise ValueError("enemy-slice aggregate does not match the pinned reference")

    pinned_kraid_totals = {
        "roomStateCount": "kraid.roomState.count",
        "backgroundMapCount": "kraid.backgroundMap.count",
        "activeBackgroundMapCount": "kraid.backgroundMap.active.count",
        "headMapCount": "kraid.headMap.count",
        "customSequenceCount": "kraid.sequence.count",
        "customFrameOccurrenceCount": "kraid.sequence.frame.count",
        "customHandlerOccurrenceCount": "kraid.sequence.handler.count",
        "mouthHitboxCount": "kraid.mouthHitbox.count",
        "paletteStateCount": "kraid.paletteState.count",
        "linkedOamHeaderCount": "kraid.oamHeader.count",
        "linkedOamInstructionListCount": "kraid.oamInstructionList.count",
        "linkedOamFrameOccurrenceCount": "kraid.oamFrame.count",
        "activeOamSequenceCount": "kraid.activeOamSequence.count",
        "activeOamFrameOccurrenceCount": "kraid.activeOamFrame.count",
        "miniKraidSequenceCount": "kraid.miniKraid.sequence.count",
        "miniKraidFrameOccurrenceCount": "kraid.miniKraid.frame.count",
        "miniKraidUniquePoseCount": "kraid.miniKraid.pose.count",
        "backgroundMapConsumerAssociationCount": "kraid.backgroundMap.consumerAssociation.count",
        "roomBackgroundTileConsumerCount": "kraid.roomBackgroundTiles.consumer.count",
    }
    for field, property_name in pinned_kraid_totals.items():
        if int(kraid["totals"][field]) != int(reference[property_name]):
            raise ValueError(f"Kraid total {field} does not match the pinned reference")
    pinned_kraid_hashes = {
        "ownership": "kraid.ownership.aggregate.sha256",
        "headMaps": "kraid.headMap.aggregate.sha256",
        "customSequences": "kraid.sequence.aggregate.sha256",
        "palettes": "kraid.palette.aggregate.sha256",
        "liveCompositeTilemap": "kraid.compositeTilemap.sha256",
        "activeOamSequences": "kraid.activeOamSequence.aggregate.sha256",
        "miniKraid": "kraid.miniKraid.aggregate.sha256",
    }
    for field, property_name in pinned_kraid_hashes.items():
        if kraid["aggregateHashes"][field] != reference[property_name]:
            raise ValueError(f"Kraid aggregate {field} does not match the pinned reference")

    pinned_phantoon_totals = {
        "roomStateCount": "phantoon.roomState.count",
        "headerCount": "phantoon.header.count",
        "tilemapCount": "phantoon.tilemap.count",
        "tilemapRunCount": "phantoon.tilemap.run.count",
        "tilemapWordCount": "phantoon.tilemap.word.count",
        "extendedSpritemapCount": "phantoon.extendedSpritemap.count",
        "extendedChildCount": "phantoon.extendedChild.count",
        "instructionListCount": "phantoon.instructionList.count",
        "frameOccurrenceCount": "phantoon.frame.count",
        "handlerOccurrenceCount": "phantoon.handler.count",
        "productionAnimationCount": "phantoon.productionAnimation.count",
        "productionAnimationFrameCount": "phantoon.productionAnimation.frame.count",
        "paletteStateCount": "phantoon.paletteState.count",
        "healthPaletteCount": "phantoon.healthPalette.count",
        "hitboxCount": "phantoon.hitbox.count",
    }
    for field, property_name in pinned_phantoon_totals.items():
        if int(phantoon["totals"][field]) != int(reference[property_name]):
            raise ValueError(f"Phantoon total {field} does not match the pinned reference")
    pinned_phantoon_hashes = {
        "ownership": "phantoon.ownership.aggregate.sha256",
        "headers": "phantoon.header.aggregate.sha256",
        "tilemaps": "phantoon.tilemap.aggregate.sha256",
        "extendedSpritemaps": "phantoon.extendedSpritemap.aggregate.sha256",
        "instructionLists": "phantoon.instructionList.aggregate.sha256",
        "productionAnimations": "phantoon.productionAnimation.aggregate.sha256",
        "palettes": "phantoon.palette.aggregate.sha256",
        "hitboxes": "phantoon.hitbox.aggregate.sha256",
    }
    for field, property_name in pinned_phantoon_hashes.items():
        if phantoon["aggregateHashes"][field] != reference[property_name]:
            raise ValueError(f"Phantoon aggregate {field} does not match the pinned reference")

    pinned_draygon_totals = {
        "roomStateCount": "draygon.roomState.count",
        "headerCount": "draygon.header.count",
        "standardSpritemapCount": "draygon.standardSpritemap.count",
        "standardOamEntryCount": "draygon.standardOamEntry.count",
        "extendedSpritemapCount": "draygon.extendedSpritemap.count",
        "extendedChildCount": "draygon.extendedChild.count",
        "tilemapCount": "draygon.tilemap.count",
        "tilemapRunCount": "draygon.tilemap.run.count",
        "tilemapWordCount": "draygon.tilemap.word.count",
        "instructionListCount": "draygon.instructionList.count",
        "frameOccurrenceCount": "draygon.frame.count",
        "handlerOccurrenceCount": "draygon.handler.count",
        "productionAnimationCount": "draygon.productionAnimation.count",
        "productionAnimationFrameCount": "draygon.productionAnimation.frame.count",
        "unusedInstructionListCount": "draygon.unusedInstructionList.count",
        "sourcePaletteCount": "draygon.sourcePalette.count",
        "healthPaletteStageCount": "draygon.healthPaletteStage.count",
        "hitboxCount": "draygon.hitbox.count",
    }
    for field, property_name in pinned_draygon_totals.items():
        if int(draygon["totals"][field]) != int(reference[property_name]):
            raise ValueError(f"Draygon total {field} does not match the pinned reference")
    pinned_draygon_hashes = {
        "ownership": "draygon.ownership.aggregate.sha256",
        "headers": "draygon.header.aggregate.sha256",
        "standardSpritemaps": "draygon.standardSpritemap.aggregate.sha256",
        "extendedSpritemaps": "draygon.extendedSpritemap.aggregate.sha256",
        "tilemaps": "draygon.tilemap.aggregate.sha256",
        "instructionLists": "draygon.instructionList.aggregate.sha256",
        "productionAnimations": "draygon.productionAnimation.aggregate.sha256",
        "unusedInstructionLists": "draygon.unusedInstructionList.aggregate.sha256",
        "palettes": "draygon.palette.aggregate.sha256",
        "hitboxes": "draygon.hitbox.aggregate.sha256",
    }
    for field, property_name in pinned_draygon_hashes.items():
        if draygon["aggregateHashes"][field] != reference[property_name]:
            raise ValueError(f"Draygon aggregate {field} does not match the pinned reference")

    pinned_ridley_totals = {
        "headerCount": "ridley.header.count",
        "baseAssetCount": "ridley.baseAsset.count",
        "baseAssetByteCount": "ridley.baseAsset.byte.count",
        "runtimeDmaAssetCount": "ridley.runtimeDmaAsset.count",
        "runtimeDmaByteCount": "ridley.runtimeDmaAsset.byte.count",
        "bodyExtendedSpritemapCount": "ridley.bodyExtendedSpritemap.count",
        "bodyExtendedChildCount": "ridley.bodyExtendedChild.count",
        "bodyChildSpritemapCount": "ridley.bodyChildSpritemap.count",
        "bodyChildOamEntryCount": "ridley.bodyChildOamEntry.count",
        "wingSpritemapCount": "ridley.wingSpritemap.count",
        "wingOamEntryCount": "ridley.wingOamEntry.count",
        "tailSpritemapCount": "ridley.tailSpritemap.count",
        "tailOamEntryCount": "ridley.tailOamEntry.count",
        "animationListCount": "ridley.animationList.count",
        "activeAnimationListCount": "ridley.animationList.active.count",
        "unusedAnimationListCount": "ridley.animationList.unused.count",
        "animationFrameCount": "ridley.animation.frame.count",
        "animationHandlerCount": "ridley.animation.handler.count",
        "paletteStageCount": "ridley.paletteStage.count",
        "runtimeTableCount": "ridley.runtimeTable.count",
    }
    for field, property_name in pinned_ridley_totals.items():
        if int(ridley["totals"][field]) != int(reference[property_name]):
            raise ValueError(f"Ridley total {field} does not match the pinned reference")
    pinned_ridley_hashes = {
        "ownership": "ridley.ownership.aggregate.sha256",
        "headers": "ridley.header.aggregate.sha256",
        "baseAssets": "ridley.baseAsset.aggregate.sha256",
        "runtimeAssets": "ridley.runtimeAsset.aggregate.sha256",
        "bodyExtendedSpritemaps": "ridley.bodyExtendedSpritemap.aggregate.sha256",
        "bodyChildSpritemaps": "ridley.bodyChildSpritemap.aggregate.sha256",
        "wingSpritemaps": "ridley.wingSpritemap.aggregate.sha256",
        "tailSpritemaps": "ridley.tailSpritemap.aggregate.sha256",
        "animationLists": "ridley.animationList.aggregate.sha256",
        "runtimeTables": "ridley.runtimeTable.aggregate.sha256",
        "palettes": "ridley.palette.aggregate.sha256",
    }
    for field, property_name in pinned_ridley_hashes.items():
        if ridley["aggregateHashes"][field] != reference[property_name]:
            raise ValueError(f"Ridley aggregate {field} does not match the pinned reference")

    pinned_mother_brain_totals = {
        "roomStateCount": "motherBrain.roomState.count",
        "activeRoomStateCount": "motherBrain.activeRoomState.count",
        "headerCount": "motherBrain.header.count",
        "assetCount": "motherBrain.asset.count",
        "headSpritemapCount": "motherBrain.headSpritemap.count",
        "headOamEntryCount": "motherBrain.headOamEntry.count",
        "bodyExtendedSpritemapCount": "motherBrain.bodyExtendedSpritemap.count",
        "bodyExtendedChildCount": "motherBrain.bodyExtendedChild.count",
        "bodyTilemapCount": "motherBrain.bodyTilemap.count",
        "bodyTilemapRunCount": "motherBrain.bodyTilemap.run.count",
        "bodyTilemapWordCount": "motherBrain.bodyTilemap.word.count",
        "instructionListCount": "motherBrain.instructionList.count",
        "frameOccurrenceCount": "motherBrain.frame.count",
        "handlerOccurrenceCount": "motherBrain.handler.count",
        "unusedHeadSpritemapCount": "motherBrain.unusedHeadSpritemap.count",
        "unusedExtendedSpritemapCount": "motherBrain.unusedExtendedSpritemap.count",
        "unusedTilemapCount": "motherBrain.unusedTilemap.count",
        "unusedInstructionListCount": "motherBrain.unusedInstructionList.count",
        "basePaletteCount": "motherBrain.basePalette.count",
        "healthPaletteRowCount": "motherBrain.healthPaletteRow.count",
        "rainbowPaletteStageCount": "motherBrain.rainbowPaletteStage.count",
    }
    for field, property_name in pinned_mother_brain_totals.items():
        if int(mother_brain["totals"][field]) != int(reference[property_name]):
            raise ValueError(f"Mother Brain total {field} does not match the pinned reference")
    pinned_mother_brain_hashes = {
        "ownership": "motherBrain.ownership.aggregate.sha256",
        "headers": "motherBrain.header.aggregate.sha256",
        "assets": "motherBrain.asset.aggregate.sha256",
        "headSpritemaps": "motherBrain.headSpritemap.aggregate.sha256",
        "bodyExtendedSpritemaps": "motherBrain.bodyExtendedSpritemap.aggregate.sha256",
        "bodyTilemaps": "motherBrain.bodyTilemap.aggregate.sha256",
        "instructions": "motherBrain.instructionList.aggregate.sha256",
        "unusedStructures": "motherBrain.unusedStructure.aggregate.sha256",
        "palettes": "motherBrain.palette.aggregate.sha256",
    }
    for field, property_name in pinned_mother_brain_hashes.items():
        if mother_brain["aggregateHashes"][field] != reference[property_name]:
            raise ValueError(f"Mother Brain aggregate {field} does not match the pinned reference")

    pinned_crocomire_totals = {
        "headerCount": "crocomire.header.count",
        "assetCount": "crocomire.asset.count",
        "assetByteCount": "crocomire.asset.byte.count",
        "standardSpritemapCount": "crocomire.standardSpritemap.count",
        "standardOamEntryCount": "crocomire.standardOamEntry.count",
        "extendedSpritemapCount": "crocomire.extendedSpritemap.count",
        "extendedChildCount": "crocomire.extendedChild.count",
        "tilemapCount": "crocomire.tilemap.count",
        "tilemapRunCount": "crocomire.tilemap.run.count",
        "tilemapWordCount": "crocomire.tilemap.word.count",
        "instructionListCount": "crocomire.instructionList.count",
        "frameOccurrenceCount": "crocomire.frame.count",
        "uniqueFrameCount": "crocomire.frame.unique.count",
        "handlerOccurrenceCount": "crocomire.handler.count",
        "guidedAnimationCount": "crocomire.guidedAnimation.count",
        "guidedAnimationFrameCount": "crocomire.guidedAnimation.frame.count",
        "unusedInstructionListCount": "crocomire.unusedInstructionList.count",
        "paletteCount": "crocomire.palette.count",
    }
    for field, property_name in pinned_crocomire_totals.items():
        if int(crocomire["totals"][field]) != int(reference[property_name]):
            raise ValueError(f"Crocomire total {field} does not match the pinned reference")
    pinned_crocomire_hashes = {
        "ownership": "crocomire.ownership.aggregate.sha256",
        "headers": "crocomire.header.aggregate.sha256",
        "assets": "crocomire.asset.aggregate.sha256",
        "standardSpritemaps": "crocomire.standardSpritemap.aggregate.sha256",
        "extendedSpritemaps": "crocomire.extendedSpritemap.aggregate.sha256",
        "tilemaps": "crocomire.tilemap.aggregate.sha256",
        "instructionLists": "crocomire.instructionList.aggregate.sha256",
        "guidedAnimations": "crocomire.guidedAnimation.aggregate.sha256",
        "unusedInstructionLists": "crocomire.unusedInstructionList.aggregate.sha256",
        "palettes": "crocomire.palette.aggregate.sha256",
    }
    for field, property_name in pinned_crocomire_hashes.items():
        if crocomire["aggregateHashes"][field] != reference[property_name]:
            raise ValueError(f"Crocomire aggregate {field} does not match the pinned reference")

    pinned_ordinary_enemy_totals = {
        "speciesCount": "ordinaryEnemyAnimations.species.count",
        "assetCount": "ordinaryEnemyAnimations.asset.count",
        "assetByteCount": "ordinaryEnemyAnimations.asset.byte.count",
        "spritemapCount": "ordinaryEnemyAnimations.spritemap.count",
        "oamEntryCount": "ordinaryEnemyAnimations.oamEntry.count",
        "instructionListCount": "ordinaryEnemyAnimations.instructionList.count",
        "sourceFrameOccurrenceCount": "ordinaryEnemyAnimations.sourceFrameOccurrence.count",
        "animationCount": "ordinaryEnemyAnimations.animation.count",
        "animationFrameCount": "ordinaryEnemyAnimations.animationFrame.count",
        "paletteCount": "ordinaryEnemyAnimations.palette.count",
    }
    for field, property_name in pinned_ordinary_enemy_totals.items():
        if int(ordinary_enemy_animations["totals"][field]) != int(reference[property_name]):
            raise ValueError(f"ordinary-enemy animation total {field} does not match the pinned reference")
    pinned_ordinary_enemy_hashes = {
        "ownership": "ordinaryEnemyAnimations.ownership.aggregate.sha256",
        "species": "ordinaryEnemyAnimations.species.aggregate.sha256",
        "spritemaps": "ordinaryEnemyAnimations.spritemap.aggregate.sha256",
        "instructionLists": "ordinaryEnemyAnimations.instructionList.aggregate.sha256",
        "animations": "ordinaryEnemyAnimations.animation.aggregate.sha256",
        "palettes": "ordinaryEnemyAnimations.palette.aggregate.sha256",
    }
    for field, property_name in pinned_ordinary_enemy_hashes.items():
        if ordinary_enemy_animations["aggregateHashes"][field] != reference[property_name]:
            raise ValueError(f"ordinary-enemy animation aggregate {field} does not match the pinned reference")

    pinned_samus_totals = {
        "poseCount": "samus.pose.count",
        "uniqueAnimationDefinitionCount": "samus.animationDefinition.unique.count",
        "uniqueAnimationDefinitionFrameCount": "samus.animationDefinition.uniqueFrame.count",
        "animationDefinitionFrameOccurrenceCount": "samus.animationDefinition.frameOccurrence.count",
        "uniqueAnimationDelayCount": "samus.animationDelay.unique.count",
        "dmaTableCount": "samus.dmaTable.count",
        "dmaEntryCount": "samus.dmaEntry.count",
        "tileAssetCount": "samus.tileAsset.count",
        "tileAssetByteCount": "samus.tileAsset.byte.count",
        "spritemapCount": "samus.spritemap.count",
        "spritemapEntryCount": "samus.spritemapEntry.count",
        "nullSpritemapLookupCount": "samus.spritemapLookup.null.count",
        "paletteCount": "samus.palette.count",
    }
    for field, property_name in pinned_samus_totals.items():
        if int(samus["totals"][field]) != int(reference[property_name]):
            raise ValueError(f"Samus total {field} does not match the pinned reference")
    pinned_samus_hashes = {
        "dma": "samus.dma.aggregate.sha256",
        "animationDelays": "samus.animationDelay.aggregate.sha256",
        "animations": "samus.animation.aggregate.sha256",
        "spritemaps": "samus.spritemap.aggregate.sha256",
        "palettes": "samus.palette.aggregate.sha256",
    }
    for field, property_name in pinned_samus_hashes.items():
        if samus["aggregateHashes"][field] != reference[property_name]:
            raise ValueError(f"Samus aggregate {field} does not match the pinned reference")

    pinned_enemy_species_status_totals = {
        "speciesCount": "enemySpeciesStatus.species.count",
        "assembledCount": "enemySpeciesStatus.assembled.count",
        "tile-sheet-onlyCount": "enemySpeciesStatus.tile-sheet-only.count",
        "compositeCount": "enemySpeciesStatus.composite.count",
        "nonvisualCount": "enemySpeciesStatus.nonvisual.count",
        "failedCount": "enemySpeciesStatus.failed.count",
        "previewAvailableCount": "enemySpeciesStatus.previewAvailable.count",
        "tileDataAvailableCount": "enemySpeciesStatus.tileDataAvailable.count",
        "paletteAvailableCount": "enemySpeciesStatus.paletteAvailable.count",
    }
    for field, property_name in pinned_enemy_species_status_totals.items():
        if int(enemy_species_status["totals"][field]) != int(reference[property_name]):
            raise ValueError(f"enemy-species status total {field} does not match the pinned reference")
    if enemy_species_status["aggregateHashes"]["species"] != reference[
        "enemySpeciesStatus.aggregate.sha256"
    ]:
        raise ValueError("enemy-species status aggregate does not match the pinned reference")

    pinned_room_totals = {
        "roomCount": "rooms.room.count",
        "stateCount": "rooms.state.count",
        "conditionalSelectorCount": "rooms.selector.conditional.count",
        "levelStreamCount": "rooms.level.total.count",
        "activeLevelStreamCount": "rooms.level.active.count",
        "unusedLevelStreamCount": "rooms.level.unused.count",
        "levelStreamWithLayer2Count": "rooms.level.layer2.count",
    }
    for field, property_name in pinned_room_totals.items():
        if int(rooms[field]) != int(reference[property_name]):
            raise ValueError(f"room report field {field} does not match the pinned reference")
    pinned_room_resources = {
        "plm": "rooms.resource.plm.count",
        "enemyPopulation": "rooms.resource.enemyPopulation.count",
        "enemyGfx": "rooms.resource.enemyGfx.count",
        "fx": "rooms.resource.fx.count",
        "scrollAssociation": "rooms.resource.scrollAssociation.count",
        "scrollTable": "rooms.resource.scrollTable.count",
        "scrollSentinelAssociation": "rooms.resource.scrollSentinelAssociation.count",
        "doorList": "rooms.resource.doorList.count",
        "doorAssociation": "rooms.resource.doorAssociation.count",
        "uniqueDoorDef": "rooms.resource.uniqueDoorDef.count",
    }
    for field, property_name in pinned_room_resources.items():
        if int(rooms["resourceCounts"][field]) != int(reference[property_name]):
            raise ValueError(f"room resource count {field} does not match the pinned reference")
    pinned_room_hashes = {
        "roomHeaders": "rooms.roomHeaders.aggregate.sha256",
        "selectors": "rooms.selectors.aggregate.sha256",
        "states": "rooms.states.aggregate.sha256",
        "levels": "rooms.levels.aggregate.sha256",
        "resources": "rooms.resources.aggregate.sha256",
        "doors": "rooms.doors.aggregate.sha256",
    }
    for field, property_name in pinned_room_hashes.items():
        if rooms["aggregateHashes"][field] != reference[property_name]:
            raise ValueError(f"room aggregate {field} does not match the pinned reference")

    pinned_scroll_runtime_fields = {
        ("genericPlms", "triggerCount"): "scrollRuntime.plm.trigger.count",
        ("genericPlms", "solidTriggerCount"): "scrollRuntime.plm.solidTrigger.count",
        ("genericPlms", "populationCount"): "scrollRuntime.plm.population.count",
        ("genericPlms", "stateAssociationCount"): "scrollRuntime.plm.stateAssociation.count",
        ("genericPlms", "uniqueCommandStreamCount"): "scrollRuntime.commandStream.count",
        ("genericPlms", "commandCount"): "scrollRuntime.command.count",
        ("genericPlms", "extensionCount"): "scrollRuntime.extension.count",
        ("genericPlms", "orphanExtensionCount"): "scrollRuntime.extension.orphan.count",
        ("doors", "nonzeroValidAssociationCount"): "scrollRuntime.door.nonzeroAssociation.count",
        ("doors", "nonzeroValidRoutineCount"): "scrollRuntime.door.nonzeroRoutine.count",
        ("doors", "scrollWriterAssociationCount"): "scrollRuntime.door.writerAssociation.count",
        ("doors", "scrollWriterRoutineCount"): "scrollRuntime.door.writerRoutine.count",
        ("doors", "pureScrollRoutineCount"): "scrollRuntime.door.pureWriter.count",
        ("doors", "mixedScrollRoutineCount"): "scrollRuntime.door.mixedWriter.count",
        ("doors", "sourceDeclaredUnusedWriterCount"): "scrollRuntime.door.unusedWriter.count",
        ("writerInventory", "routineCount"): "scrollRuntime.writer.routine.count",
        ("writerInventory", "storeSiteCount"): "scrollRuntime.writer.storeSite.count",
    }
    for (section, field), property_name in pinned_scroll_runtime_fields.items():
        if int(scroll_runtime[section][field]) != int(reference[property_name]):
            raise ValueError(f"scroll-runtime {section}.{field} does not match the pinned reference")
    for category, count in scroll_runtime["writerInventory"]["categoryCounts"].items():
        if int(count) != int(reference[f"scrollRuntime.writer.{category}.count"]):
            raise ValueError(f"scroll-runtime writer category {category} does not match the pinned reference")
    for field in ("commandStreams", "extensions", "doorRoutines", "writerInventory"):
        if scroll_runtime["aggregateHashes"][field] != reference[
            f"scrollRuntime.{field}.aggregate.sha256"
        ]:
            raise ValueError(f"scroll-runtime aggregate {field} does not match the pinned reference")

    pinned_background_fields = {
        "programCount": "background.program.count",
        "activeProgramCount": "background.program.active.count",
        "unusedProgramCount": "background.program.unused.count",
        "stateAssociationCount": "background.stateAssociation.count",
        "embeddedLayer2StateCount": "background.embeddedState.count",
        "commandCount": "background.command.count",
        "doorDependentTransferCount": "background.doorTransfer.count",
        "compressedBackgroundCount": "background.compressed.count",
        "referencedCompressedBackgroundCount": "background.compressed.referenced.count",
        "unreferencedCompressedBackgroundCount": "background.compressed.unreferenced.count",
    }
    for field, property_name in pinned_background_fields.items():
        if int(backgrounds[field]) != int(reference[property_name]):
            raise ValueError(f"background report field {field} does not match the pinned reference")
    for field in ("programs", "commands", "consumers"):
        if backgrounds["aggregateHashes"][field] != reference[f"background.{field}.aggregate.sha256"]:
            raise ValueError(f"background aggregate {field} does not match the pinned reference")

    pinned_minimap_fields = {
        "areaCount": "minimap.area.count",
        "areaMapPointerCount": "minimap.areaMapPointer.count",
        "mapDataPointerCount": "minimap.mapDataPointer.count",
        "mapDataUniquePointerCount": "minimap.mapDataUniquePointer.count",
        "tilemapWordCount": "minimap.tilemapWord.count",
        "mapDataByteCount": "minimap.mapDataByte.count",
        "revealedTileCount": "minimap.revealedTile.count",
        "mapStationPlacementCount": "minimap.mapStationPlacement.count",
        "mapStationAreaCount": "minimap.mapStationArea.count",
        "roomCount": "minimap.room.count",
        "roomScreenCount": "minimap.roomScreen.count",
        "roomCoordinateHeight": "minimap.roomCoordinate.height",
        "maxRoomRight": "minimap.room.maxRight",
        "maxRoomBottom": "minimap.room.maxBottom",
        "engineConsumerCount": "minimap.engineConsumer.count",
    }
    for field, property_name in pinned_minimap_fields.items():
        if int(minimap[field]) != int(reference[property_name]):
            raise ValueError(f"minimap report field {field} does not match the pinned reference")
    if int(minimap["graphics"]["tileCount"]) != int(reference["minimap.graphics.tile.count"]):
        raise ValueError("minimap graphics tile count does not match the pinned reference")
    if int(minimap["graphics"]["usedByteCount"]) != int(reference["minimap.graphics.usedByte.count"]):
        raise ValueError("minimap graphics byte count does not match the pinned reference")
    for field in ("tilemaps", "mapData", "mapStations", "consumers"):
        if minimap["aggregateHashes"][field] != reference[f"minimap.{field}.aggregate.sha256"]:
            raise ValueError(f"minimap aggregate {field} does not match the pinned reference")
    if minimap["coordinateTransform"]["tilemapTransformSha256"] != reference[
        "minimap.tilemapTransform.sha256"
    ]:
        raise ValueError("minimap tilemap transform does not match the pinned reference")
    if minimap["coordinateTransform"]["mapDataTransformSha256"] != reference[
        "minimap.mapDataTransform.sha256"
    ]:
        raise ValueError("minimap map-data transform does not match the pinned reference")
    if minimap["graphics"]["rawSha256"] != reference["minimap.graphics.raw.sha256"]:
        raise ValueError("minimap graphics bytes do not match the pinned reference")
    if minimap["graphics"]["decodedPixelSha256"] != reference[
        "minimap.graphics.decoded.sha256"
    ]:
        raise ValueError("minimap decoded graphics do not match the pinned reference")

    pinned_load_station_fields = {
        "areaCount": "loadStations.area.count",
        "entryCount": "loadStations.entry.count",
        "entryByteCount": "loadStations.entry.byte.count",
        "saveSlotCount": "loadStations.saveSlot.count",
        "occupiedSaveSlotCount": "loadStations.saveSlot.occupied.count",
        "emptySaveSlotCount": "loadStations.saveSlot.empty.count",
        "savePlmPlacementCount": "loadStations.savePlm.count",
        "elevatorEntryCount": "loadStations.elevator.count",
        "occupiedElevatorEntryCount": "loadStations.elevator.occupied.count",
        "debugEntryCount": "loadStations.debug.count",
        "occupiedDebugEntryCount": "loadStations.debug.occupied.count",
        "ceresSequenceEntryCount": "loadStations.ceresSequence.count",
        "gunshipLandingEntryCount": "loadStations.gunshipLanding.count",
        "doorBtsNonzeroCount": "loadStations.doorBts.nonzero.count",
        "engineConsumerCount": "loadStations.engineConsumer.count",
    }
    for field, property_name in pinned_load_station_fields.items():
        if int(load_stations[field]) != int(reference[property_name]):
            raise ValueError(f"load-station report field {field} does not match the pinned reference")
    for field in ("pointers", "entries", "savePlms", "consumers"):
        if load_stations["aggregateHashes"][field] != reference[
            f"loadStations.{field}.aggregate.sha256"
        ]:
            raise ValueError(f"load-station aggregate {field} does not match the pinned reference")

    graphics_checks = (
        compression_checks(tests, lz5)
        + tile_format_checks(tests, tile_formats)
        + tileset_checks(tests, tilesets)
        + animated_tile_checks(tests, animated_tiles)
        + item_plm_graphics_checks(tests, item_plm_graphics)
    )
    graphics_checks.sort(key=lambda check: str(check["id"]))
    checks = (
        foundation_checks(tests)
        + graphics_checks
        + enemy_header_checks(tests, enemy_headers)
        + enemy_oam_checks(tests, enemy_oam)
        + enemy_instruction_checks(tests, enemy_instructions, enemy_vertical_slices)
        + ordinary_enemy_animation_checks(tests, ordinary_enemy_animations)
        + enemy_species_status_checks(tests, enemy_species_status)
        + kraid_checks(tests, kraid)
        + phantoon_checks(tests, phantoon)
        + draygon_checks(tests, draygon)
        + ridley_checks(tests, ridley)
        + mother_brain_checks(tests, mother_brain)
        + crocomire_checks(tests, crocomire)
        + spore_spawn_checks(tests, spore_spawn)
        + botwoon_checks(tests, botwoon)
        + torizo_checks(tests, torizo)
        + metroid_checks(tests, metroid)
        + samus_checks(tests, samus)
        + room_checks(tests, rooms, scroll_runtime, backgrounds, minimap, load_stations)
    )
    raw_summary = Counter(str(check["status"]) for check in checks)
    summary = {
        status: raw_summary[status]
        for status in ("pass", "partial", "mismatch", "uncovered")
    }
    overall = "mismatch" if summary["mismatch"] else "pass"
    report: Dict[str, object] = {
        "schemaVersion": 28,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "scope": "foundation-compression-shared-graphics-rooms-states-resources-scroll-runtime-backgrounds-minimap-load-stations-export-enemy-headers-oam-instructions-slices-ordinary-enemy-routes-kraid-phantoon-draygon-ridley-mother-brain-crocomire-spore-spawn-botwoon-torizo-metroid-samus-and-species-status",
        "overall": overall,
        "identity": {
            "smeditCommit": git_output("rev-parse", "HEAD"),
            "smeditDirty": bool(git_output("status", "--porcelain")),
            "disassemblyCommit": symbols["disassemblyCommit"],
            "romSha256": assets["romSha256"],
            "asarCommit": reference["assembler.commit"],
            "asarVersion": reference["assembler.version"],
        },
        "summary": summary,
        "checks": checks,
        "symbols": {"count": symbols["symbolCount"]},
        "assets": {
            "activeCount": assets["activeAssetCount"],
            "inactiveCount": assets["inactiveOrUnavailableIncbinCount"],
            "sourceCommentSizeMismatchCount": assets["sourceCommentSizeMismatchCount"],
        },
        "lz5": {
            "exactStreamCount": lz5["exactCompressedStreamCount"],
            "activeStreamCount": lz5["activeCompressedStreamCount"],
            "unusedStreamCount": lz5["unusedCompressedStreamCount"],
            "maxDecompressedSize": lz5["maxDecompressedSize"],
            "commandCounts": lz5["aggregateCommandCounts"],
        },
        "tilesets": {
            "count": tilesets["tilesetCount"],
            "pointerFieldCount": tilesets["pointerFieldCount"],
            "uniqueResourceCount": tilesets["uniqueResourceCount"],
            "uniqueResourceCounts": tilesets["uniqueResourceCounts"],
            "aliasGroupCounts": tilesets["aliasGroupCounts"],
            "cre": {
                "graphicsDecompressedBytes": tilesets["cre"]["graphics"]["decompressedSize"],
                "tileTableDecompressedBytes": tilesets["cre"]["tileTable"]["decompressedSize"],
                "consumerCount": (
                    tilesets["cre"]["graphics"]["consumers"]["consumerCount"]
                    + tilesets["cre"]["tileTable"]["consumers"]["consumerCount"]
                ),
            },
        },
        "tileFormats": {
            "graphics4bppResourceCount": tile_formats["totals"]["graphics4bppResourceCount"],
            "graphics4bppTileCount": tile_formats["totals"]["graphics4bppTileCount"],
            "graphics2bppResourceCount": tile_formats["totals"]["graphics2bppResourceCount"],
            "graphics2bppTileCount": tile_formats["totals"]["graphics2bppTileCount"],
            "shortStandard4bppResourceCount": tile_formats["totals"]["shortStandard4bppResourceCount"],
            "metatileTableResourceCount": tile_formats["totals"]["metatileTableResourceCount"],
            "metatileCount": tile_formats["totals"]["metatileCount"],
            "metatileWordCount": tile_formats["totals"]["metatileWordCount"],
            "aggregateHashes": tile_formats["aggregateHashes"],
        },
        "animatedTiles": {
            "assetCount": animated_tiles["totals"]["assetCount"],
            "assetByteCount": animated_tiles["totals"]["assetByteCount"],
            "referencedAssetCount": animated_tiles["totals"]["referencedAssetCount"],
            "orphanAssetCount": animated_tiles["totals"]["orphanAssetCount"],
            "sourceDeclaredUnusedAssetCount": animated_tiles["totals"]["sourceDeclaredUnusedAssetCount"],
            "objectCount": animated_tiles["totals"]["objectCount"],
            "uniqueFrameInstructionCount": animated_tiles["totals"]["uniqueFrameInstructionCount"],
            "areaBitMappingCount": animated_tiles["totals"]["areaBitMappingCount"],
            "consumerCount": animated_tiles["totals"]["consumerCount"],
            "aggregateHashes": animated_tiles["aggregateHashes"],
        },
        "itemPlmGraphics": {
            "assetCount": item_plm_graphics["totals"]["assetCount"],
            "assetByteCount": item_plm_graphics["totals"]["assetByteCount"],
            "tileCount": item_plm_graphics["totals"]["tileCount"],
            "frameCount": item_plm_graphics["totals"]["frameCount"],
            "loadRecordCount": item_plm_graphics["totals"]["loadRecordCount"],
            "plmIdCount": item_plm_graphics["totals"]["plmIdCount"],
            "paletteProfileCount": item_plm_graphics["totals"]["paletteProfileCount"],
            "slotCount": item_plm_graphics["totals"]["slotCount"],
            "drawPointerCount": item_plm_graphics["totals"]["drawPointerCount"],
            "aggregateHashes": item_plm_graphics["aggregateHashes"],
        },
        "enemyHeaders": {
            "headerCount": enemy_headers["totals"]["headerCount"],
            "macroFieldCount": enemy_headers["totals"]["macroFieldCount"],
            "sourceDeclaredUnusedHeaderCount": enemy_headers["totals"]["sourceDeclaredUnusedHeaderCount"],
            "nonEmptyGraphicsHeaderCount": enemy_headers["totals"]["nonEmptyGraphicsHeaderCount"],
            "uniqueGraphicsStartCount": enemy_headers["totals"]["uniqueGraphicsStartCount"],
            "uniqueGraphicsRangeCount": enemy_headers["totals"]["uniqueGraphicsRangeCount"],
            "sourceAssetCount": enemy_headers["totals"]["sourceAssetCount"],
            "multiAssetGraphicsHeaderCount": enemy_headers["totals"]["multiAssetGraphicsHeaderCount"],
            "startAliasGroupCount": enemy_headers["totals"]["startAliasGroupCount"],
            "exactRangeAliasGroupCount": enemy_headers["totals"]["exactRangeAliasGroupCount"],
            "overlappingUniqueRangePairCount": enemy_headers["totals"]["overlappingUniqueRangePairCount"],
            "crossStartOverlapPairCount": enemy_headers["totals"]["crossStartOverlapPairCount"],
            "aggregateHashes": enemy_headers["aggregateHashes"],
        },
        "enemyOam": {
            "standardLabelCount": enemy_oam["totals"]["standardLabelCount"],
            "standardEntryCount": enemy_oam["totals"]["standardEntryCount"],
            "standardZeroEntryCount": enemy_oam["totals"]["standardZeroEntryCount"],
            "extendedLabelCount": enemy_oam["totals"]["extendedLabelCount"],
            "extendedChildAssociationCount": enemy_oam["totals"]["extendedChildAssociationCount"],
            "extendedOamChildAssociationCount": enemy_oam["totals"]["extendedOamChildAssociationCount"],
            "extendedTilemapChildAssociationCount": enemy_oam["totals"]["extendedTilemapChildAssociationCount"],
            "tilemapLabelCount": enemy_oam["totals"]["tilemapLabelCount"],
            "tilemapRunCount": enemy_oam["totals"]["tilemapRunCount"],
            "tilemapWordCount": enemy_oam["totals"]["tilemapWordCount"],
            "aggregateHashes": enemy_oam["aggregateHashes"],
        },
        "enemyInstructions": {
            "listCount": enemy_instructions["totals"]["enemyListCount"],
            "recordCount": enemy_instructions["totals"]["recordCount"],
            "frameCount": enemy_instructions["totals"]["frameRecordCount"],
            "handlerOccurrenceCount": enemy_instructions["totals"]["handlerOccurrenceCount"],
            "uniqueHandlerCount": enemy_instructions["totals"]["uniqueHandlerCount"],
            "supportedHandlerCount": enemy_instructions["totals"]["commonWidthKnownHandlerCount"],
            "unsupportedHandlerCount": enemy_instructions["totals"]["unsupportedHandlerCount"],
            "renderableFrameCount": enemy_instructions["totals"]["productionRenderableFrameCount"],
            "unrenderableFrameCount": enemy_instructions["totals"]["productionUnrenderableFrameCount"],
            "recoveredSourceFrameCount": enemy_instructions["totals"]["productionRecoveredSourceFrameCount"],
            "missedSourceFrameCount": enemy_instructions["totals"]["productionMissedSourceFrameCount"],
            "outOfBlockFrameCount": enemy_instructions["totals"]["productionOutOfBlockFrameCount"],
            "listsWithMissedFrames": enemy_instructions["totals"]["listsWithMissedSourceFrames"],
            "listsWithUnsupportedHandlers": enemy_instructions["totals"]["listsWithUnsupportedHandlers"],
            "sourceCommentAddressMismatchCount": enemy_instructions["totals"]["sourceCommentAddressMismatchCount"],
            "aggregateHashes": enemy_instructions["aggregateHashes"],
        },
        "enemyVerticalSlices": {
            "sliceCount": enemy_vertical_slices["totals"]["sliceCount"],
            "frameCount": enemy_vertical_slices["totals"]["frameOccurrenceCount"],
            "uniqueFrameSpritemapCount": enemy_vertical_slices["totals"]["uniqueFrameSpritemapCount"],
            "handlerCount": enemy_vertical_slices["totals"]["handlerOccurrenceCount"],
            "standardFrameCount": enemy_vertical_slices["totals"]["standardFrameOccurrenceCount"],
            "extendedFrameCount": enemy_vertical_slices["totals"]["extendedFrameOccurrenceCount"],
            "aggregateHashes": enemy_vertical_slices["aggregateHashes"],
        },
        "kraid": {
            "roomStateCount": kraid["totals"]["roomStateCount"],
            "backgroundMapCount": kraid["totals"]["backgroundMapCount"],
            "activeBackgroundMapCount": kraid["totals"]["activeBackgroundMapCount"],
            "headMapCount": kraid["totals"]["headMapCount"],
            "customSequenceCount": kraid["totals"]["customSequenceCount"],
            "customFrameCount": kraid["totals"]["customFrameOccurrenceCount"],
            "customHandlerCount": kraid["totals"]["customHandlerOccurrenceCount"],
            "mouthHitboxCount": kraid["totals"]["mouthHitboxCount"],
            "paletteStateCount": kraid["totals"]["paletteStateCount"],
            "linkedOamHeaderCount": kraid["totals"]["linkedOamHeaderCount"],
            "linkedOamInstructionListCount": kraid["totals"]["linkedOamInstructionListCount"],
            "linkedOamFrameCount": kraid["totals"]["linkedOamFrameOccurrenceCount"],
            "backgroundMapConsumerAssociationCount": kraid["totals"]["backgroundMapConsumerAssociationCount"],
            "roomBackgroundTileConsumerCount": kraid["totals"]["roomBackgroundTileConsumerCount"],
            "aggregateHashes": kraid["aggregateHashes"],
        },
        "phantoon": {
            "roomStateCount": phantoon["totals"]["roomStateCount"],
            "headerCount": phantoon["totals"]["headerCount"],
            "tilemapCount": phantoon["totals"]["tilemapCount"],
            "tilemapRunCount": phantoon["totals"]["tilemapRunCount"],
            "tilemapWordCount": phantoon["totals"]["tilemapWordCount"],
            "extendedSpritemapCount": phantoon["totals"]["extendedSpritemapCount"],
            "instructionListCount": phantoon["totals"]["instructionListCount"],
            "frameCount": phantoon["totals"]["frameOccurrenceCount"],
            "productionAnimationCount": phantoon["totals"]["productionAnimationCount"],
            "productionAnimationFrameCount": phantoon["totals"]["productionAnimationFrameCount"],
            "healthPaletteCount": phantoon["totals"]["healthPaletteCount"],
            "aggregateHashes": phantoon["aggregateHashes"],
        },
        "draygon": {
            "roomStateCount": draygon["totals"]["roomStateCount"],
            "headerCount": draygon["totals"]["headerCount"],
            "standardSpritemapCount": draygon["totals"]["standardSpritemapCount"],
            "standardOamEntryCount": draygon["totals"]["standardOamEntryCount"],
            "extendedSpritemapCount": draygon["totals"]["extendedSpritemapCount"],
            "tilemapCount": draygon["totals"]["tilemapCount"],
            "tilemapWordCount": draygon["totals"]["tilemapWordCount"],
            "instructionListCount": draygon["totals"]["instructionListCount"],
            "frameCount": draygon["totals"]["frameOccurrenceCount"],
            "productionAnimationCount": draygon["totals"]["productionAnimationCount"],
            "productionAnimationFrameCount": draygon["totals"]["productionAnimationFrameCount"],
            "healthPaletteStageCount": draygon["totals"]["healthPaletteStageCount"],
            "hitboxCount": draygon["totals"]["hitboxCount"],
            "aggregateHashes": draygon["aggregateHashes"],
        },
        "ridley": {
            "headerCount": ridley["totals"]["headerCount"],
            "baseAssetCount": ridley["totals"]["baseAssetCount"],
            "baseAssetByteCount": ridley["totals"]["baseAssetByteCount"],
            "runtimeDmaAssetCount": ridley["totals"]["runtimeDmaAssetCount"],
            "bodyExtendedSpritemapCount": ridley["totals"]["bodyExtendedSpritemapCount"],
            "bodyExtendedChildCount": ridley["totals"]["bodyExtendedChildCount"],
            "wingSpritemapCount": ridley["totals"]["wingSpritemapCount"],
            "tailSpritemapCount": ridley["totals"]["tailSpritemapCount"],
            "animationListCount": ridley["totals"]["animationListCount"],
            "animationFrameCount": ridley["totals"]["animationFrameCount"],
            "paletteStageCount": ridley["totals"]["paletteStageCount"],
            "aggregateHashes": ridley["aggregateHashes"],
        },
        "motherBrain": {
            "roomStateCount": mother_brain["totals"]["roomStateCount"],
            "activeRoomStateCount": mother_brain["totals"]["activeRoomStateCount"],
            "headerCount": mother_brain["totals"]["headerCount"],
            "assetCount": mother_brain["totals"]["assetCount"],
            "headSpritemapCount": mother_brain["totals"]["headSpritemapCount"],
            "headOamEntryCount": mother_brain["totals"]["headOamEntryCount"],
            "bodyExtendedSpritemapCount": mother_brain["totals"]["bodyExtendedSpritemapCount"],
            "bodyExtendedChildCount": mother_brain["totals"]["bodyExtendedChildCount"],
            "bodyTilemapCount": mother_brain["totals"]["bodyTilemapCount"],
            "bodyTilemapWordCount": mother_brain["totals"]["bodyTilemapWordCount"],
            "instructionListCount": mother_brain["totals"]["instructionListCount"],
            "frameCount": mother_brain["totals"]["frameOccurrenceCount"],
            "healthPalettePairCount": mother_brain["totals"]["healthPaletteRowCount"] // 2,
            "rainbowPaletteStageCount": mother_brain["totals"]["rainbowPaletteStageCount"],
            "aggregateHashes": mother_brain["aggregateHashes"],
        },
        "crocomire": {
            "headerCount": crocomire["totals"]["headerCount"],
            "assetCount": crocomire["totals"]["assetCount"],
            "assetByteCount": crocomire["totals"]["assetByteCount"],
            "standardSpritemapCount": crocomire["totals"]["standardSpritemapCount"],
            "standardOamEntryCount": crocomire["totals"]["standardOamEntryCount"],
            "extendedSpritemapCount": crocomire["totals"]["extendedSpritemapCount"],
            "extendedChildCount": crocomire["totals"]["extendedChildCount"],
            "tilemapCount": crocomire["totals"]["tilemapCount"],
            "tilemapWordCount": crocomire["totals"]["tilemapWordCount"],
            "instructionListCount": crocomire["totals"]["instructionListCount"],
            "frameCount": crocomire["totals"]["frameOccurrenceCount"],
            "uniqueFrameCount": crocomire["totals"]["uniqueFrameCount"],
            "guidedAnimationCount": crocomire["totals"]["guidedAnimationCount"],
            "guidedAnimationFrameCount": crocomire["totals"]["guidedAnimationFrameCount"],
            "paletteCount": crocomire["totals"]["paletteCount"],
            "aggregateHashes": crocomire["aggregateHashes"],
        },
        "sporeSpawn": {
            "headerCount": spore_spawn["totals"]["headerCount"],
            "assetByteCount": spore_spawn["totals"]["assetByteCount"],
            "standardSpritemapCount": spore_spawn["totals"]["standardSpritemapCount"],
            "activeExtendedSpritemapCount": spore_spawn["totals"]["activeExtendedSpritemapCount"],
            "activeExtendedChildCount": spore_spawn["totals"]["activeExtendedChildCount"],
            "projectileSpritemapCount": spore_spawn["totals"]["projectileSpritemapCount"],
            "instructionListCount": spore_spawn["totals"]["instructionListCount"],
            "frameCount": spore_spawn["totals"]["frameOccurrenceCount"],
            "guidedAnimationCount": spore_spawn["totals"]["guidedAnimationCount"],
            "paletteCount": spore_spawn["totals"]["paletteCount"],
            "aggregateHashes": spore_spawn["aggregateHashes"],
        },
        "botwoon": {
            "headerCount": botwoon["totals"]["headerCount"],
            "assetByteCount": botwoon["totals"]["assetByteCount"],
            "activeHeadSpritemapCount": botwoon["totals"]["activeHeadSpritemapCount"],
            "unusedHeadSpritemapCount": botwoon["totals"]["unusedHeadSpritemapCount"],
            "activeProjectileSpritemapCount": botwoon["totals"]["activeProjectileSpritemapCount"],
            "unusedProjectileSpritemapCount": botwoon["totals"]["unusedProjectileSpritemapCount"],
            "activeHeadInstructionListCount": botwoon["totals"]["activeHeadInstructionListCount"],
            "activeProjectileInstructionListCount": botwoon["totals"]["activeProjectileInstructionListCount"],
            "guidedAnimationCount": botwoon["totals"]["guidedAnimationCount"],
            "guidedAnimationFrameCount": botwoon["totals"]["guidedAnimationFrameCount"],
            "paletteCount": botwoon["totals"]["paletteCount"],
            "aggregateHashes": botwoon["aggregateHashes"],
        },
        "torizo": {
            "headerCount": torizo["totals"]["headerCount"],
            "assetByteCount": torizo["totals"]["assetByteCount"],
            "activeBodySpritemapCount": torizo["totals"]["activeExtendedSpritemapCount"],
            "unusedBodySpritemapCount": torizo["totals"]["unusedExtendedSpritemapCount"],
            "activeBodyChildSpritemapCount": torizo["totals"]["activeStandardSpritemapCount"],
            "unusedBodyChildSpritemapCount": torizo["totals"]["unusedStandardSpritemapCount"],
            "activeProjectileSpritemapCount": torizo["totals"]["activeProjectileSpritemapCount"],
            "unusedProjectileSpritemapCount": torizo["totals"]["unusedProjectileSpritemapCount"],
            "bodyInstructionListCount": torizo["totals"]["activeBodyInstructionListCount"],
            "projectileInstructionSymbolCount": torizo["totals"]["projectileInstructionSymbolCount"],
            "runtimeTransferCount": torizo["totals"]["runtimeTransferCount"],
            "paletteRowCount": torizo["totals"]["paletteRowCount"],
            "aggregateHashes": torizo["aggregateHashes"],
        },
        "metroid": {
            "headerCount": metroid["totals"]["headerCount"],
            "assetByteCount": metroid["totals"]["assetByteCount"],
            "insideSpritemapCount": metroid["totals"]["insideSpritemapCount"],
            "shellSpritemapCount": metroid["totals"]["shellSpritemapCount"],
            "electricitySpritemapCount": metroid["totals"]["electricitySpritemapCount"],
            "spriteObjectFrameOccurrenceCount": metroid["totals"]["spriteObjectFrameOccurrenceCount"],
            "spriteObjectTickCount": metroid["totals"]["spriteObjectTickCount"],
            "fallthroughContinuationCount": metroid["totals"]["fallthroughContinuationCount"],
            "paletteCount": metroid["totals"]["paletteCount"],
            "aggregateHashes": metroid["aggregateHashes"],
        },
        "samus": {
            "poseCount": samus["totals"]["poseCount"],
            "uniqueAnimationDefinitionCount": samus["totals"]["uniqueAnimationDefinitionCount"],
            "frameOccurrenceCount": samus["totals"]["animationDefinitionFrameOccurrenceCount"],
            "uniqueAnimationDelayCount": samus["totals"]["uniqueAnimationDelayCount"],
            "dmaTableCount": samus["totals"]["dmaTableCount"],
            "dmaEntryCount": samus["totals"]["dmaEntryCount"],
            "tileAssetCount": samus["totals"]["tileAssetCount"],
            "tileAssetByteCount": samus["totals"]["tileAssetByteCount"],
            "spritemapCount": samus["totals"]["spritemapCount"],
            "spritemapEntryCount": samus["totals"]["spritemapEntryCount"],
            "nullSpritemapLookupCount": samus["totals"]["nullSpritemapLookupCount"],
            "paletteCount": samus["totals"]["paletteCount"],
            "aggregateHashes": samus["aggregateHashes"],
        },
        "rooms": {
            "roomCount": rooms["roomCount"],
            "stateCount": rooms["stateCount"],
            "conditionalSelectorCount": rooms["conditionalSelectorCount"],
            "levelStreamCount": rooms["levelStreamCount"],
            "activeLevelStreamCount": rooms["activeLevelStreamCount"],
            "unusedLevelStreamCount": rooms["unusedLevelStreamCount"],
            "levelStreamWithLayer2Count": rooms["levelStreamWithLayer2Count"],
            "plmSetCount": rooms["resourceCounts"]["plm"],
            "enemyPopulationCount": rooms["resourceCounts"]["enemyPopulation"],
            "enemyGfxSetCount": rooms["resourceCounts"]["enemyGfx"],
            "fxListCount": rooms["resourceCounts"]["fx"],
            "scrollAssociationCount": rooms["resourceCounts"]["scrollAssociation"],
            "scrollTableCount": rooms["resourceCounts"]["scrollTable"],
            "scrollSentinelAssociationCount": rooms["resourceCounts"]["scrollSentinelAssociation"],
            "doorListCount": rooms["resourceCounts"]["doorList"],
            "doorAssociationCount": rooms["resourceCounts"]["doorAssociation"],
            "uniqueDoorDefCount": rooms["resourceCounts"]["uniqueDoorDef"],
            "aggregateHashes": rooms["aggregateHashes"],
        },
        "scrollRuntime": {
            "triggerCount": scroll_runtime["genericPlms"]["triggerCount"],
            "commandStreamCount": scroll_runtime["genericPlms"]["uniqueCommandStreamCount"],
            "commandCount": scroll_runtime["genericPlms"]["commandCount"],
            "extensionCount": scroll_runtime["genericPlms"]["extensionCount"],
            "orphanExtensionCount": scroll_runtime["genericPlms"]["orphanExtensionCount"],
            "doorWriterRoutineCount": scroll_runtime["doors"]["scrollWriterRoutineCount"],
            "doorWriterAssociationCount": scroll_runtime["doors"]["scrollWriterAssociationCount"],
            "directWriterRoutineCount": scroll_runtime["writerInventory"]["routineCount"],
            "directStoreSiteCount": scroll_runtime["writerInventory"]["storeSiteCount"],
            "writerCategoryCounts": scroll_runtime["writerInventory"]["categoryCounts"],
            "aggregateHashes": scroll_runtime["aggregateHashes"],
        },
        "backgrounds": {
            "programCount": backgrounds["programCount"],
            "activeProgramCount": backgrounds["activeProgramCount"],
            "unusedProgramCount": backgrounds["unusedProgramCount"],
            "stateAssociationCount": backgrounds["stateAssociationCount"],
            "embeddedLayer2StateCount": backgrounds["embeddedLayer2StateCount"],
            "commandCount": backgrounds["commandCount"],
            "commandCounts": backgrounds["commandCounts"],
            "compressedBackgroundCount": backgrounds["compressedBackgroundCount"],
            "referencedCompressedBackgroundCount": backgrounds["referencedCompressedBackgroundCount"],
            "doorDependentTransferCount": backgrounds["doorDependentTransferCount"],
            "aggregateHashes": backgrounds["aggregateHashes"],
        },
        "minimap": {
            "areaCount": minimap["areaCount"],
            "tilemapWordCount": minimap["tilemapWordCount"],
            "mapDataByteCount": minimap["mapDataByteCount"],
            "revealedTileCount": minimap["revealedTileCount"],
            "mapStationPlacementCount": minimap["mapStationPlacementCount"],
            "mapStationAreaCount": minimap["mapStationAreaCount"],
            "roomCount": minimap["roomCount"],
            "roomScreenCount": minimap["roomScreenCount"],
            "roomCoordinateHeight": minimap["roomCoordinateHeight"],
            "maxRoomRight": minimap["maxRoomRight"],
            "maxRoomBottom": minimap["maxRoomBottom"],
            "graphicsTileCount": minimap["graphics"]["tileCount"],
            "coordinateTransform": minimap["coordinateTransform"],
            "aggregateHashes": minimap["aggregateHashes"],
        },
        "loadStations": {
            "areaCount": load_stations["areaCount"],
            "entryCount": load_stations["entryCount"],
            "entryByteCount": load_stations["entryByteCount"],
            "saveSlotCount": load_stations["saveSlotCount"],
            "occupiedSaveSlotCount": load_stations["occupiedSaveSlotCount"],
            "emptySaveSlotCount": load_stations["emptySaveSlotCount"],
            "savePlmPlacementCount": load_stations["savePlmPlacementCount"],
            "elevatorEntryCount": load_stations["elevatorEntryCount"],
            "occupiedElevatorEntryCount": load_stations["occupiedElevatorEntryCount"],
            "debugEntryCount": load_stations["debugEntryCount"],
            "ceresSequenceEntryCount": load_stations["ceresSequenceEntryCount"],
            "gunshipLandingEntryCount": load_stations["gunshipLandingEntryCount"],
            "engineConsumerCount": load_stations["engineConsumerCount"],
            "aggregateHashes": load_stations["aggregateHashes"],
        },
        "ordinaryEnemyAnimations": {
            "speciesCount": ordinary_enemy_animations["totals"]["speciesCount"],
            "assetByteCount": ordinary_enemy_animations["totals"]["assetByteCount"],
            "spritemapCount": ordinary_enemy_animations["totals"]["spritemapCount"],
            "instructionListCount": ordinary_enemy_animations["totals"]["instructionListCount"],
            "animationCount": ordinary_enemy_animations["totals"]["animationCount"],
            "animationFrameCount": ordinary_enemy_animations["totals"]["animationFrameCount"],
            "aggregateHashes": ordinary_enemy_animations["aggregateHashes"],
        },
        "enemySpeciesStatus": {
            "speciesCount": enemy_species_status["totals"]["speciesCount"],
            "assembledCount": enemy_species_status["totals"]["assembledCount"],
            "tileSheetOnlyCount": enemy_species_status["totals"]["tile-sheet-onlyCount"],
            "compositeCount": enemy_species_status["totals"]["compositeCount"],
            "nonvisualCount": enemy_species_status["totals"]["nonvisualCount"],
            "failedCount": enemy_species_status["totals"]["failedCount"],
            "previewAvailableCount": enemy_species_status["totals"]["previewAvailableCount"],
            "tileDataAvailableCount": enemy_species_status["totals"]["tileDataAvailableCount"],
            "paletteAvailableCount": enemy_species_status["totals"]["paletteAvailableCount"],
            "aggregateHashes": enemy_species_status["aggregateHashes"],
        },
        "tests": tests,
    }
    report_dir.mkdir(parents=True, exist_ok=True)
    json_path = report_dir / "parity-report.json"
    markdown_path = report_dir / "parity-report.md"
    json_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    markdown_path.write_text(markdown_report(report), encoding="utf-8")
    print(f"Parity report: {overall.upper()}")
    print(
        "  Checks: "
        f"{summary['pass']} pass, {summary['partial']} partial, "
        f"{summary['mismatch']} mismatch, {summary['uncovered']} uncovered"
    )
    print(f"  JSON: {json_path}")
    print(f"  Markdown: {markdown_path}")
    return 1 if overall == "mismatch" else 0


if __name__ == "__main__":
    raise SystemExit(main())
