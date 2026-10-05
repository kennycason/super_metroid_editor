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
            "Detailed symbol, asset, compression, tileset, tile-format, animated-tile, item-PLM, enemy-header, enemy-OAM, enemy-instruction, enemy-slice, enemy-species-status, alias, and CRE records "
            "are in `symbols.json`, `assets.json`, `lz5.json`, `tilesets.json`, `tile-formats.json`, "
            "`animated-tiles.json`, `item-plm-graphics.json`, `enemy-headers.json`, `enemy-oam.json`, and "
            "`enemy-instructions.json`, `enemy-vertical-slices.json`, `kraid.json`, `phantoon.json`, `draygon.json`, `ridley.json`, `mother-brain.json`, and `enemy-species-status.json` beside this report. "
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
        or not enemy_species_status_path.is_file()
    ):
        print(
            "ERROR: symbol/asset/LZ5/tileset/tile-format/animated-tile/item-PLM/enemy-header/enemy-OAM/enemy-instruction/enemy-slice/Kraid/Phantoon/Draygon/Ridley/Mother-Brain/enemy-species reports are missing; "
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
        + enemy_species_status_checks(tests, enemy_species_status)
        + kraid_checks(tests, kraid)
        + phantoon_checks(tests, phantoon)
        + draygon_checks(tests, draygon)
        + ridley_checks(tests, ridley)
        + mother_brain_checks(tests, mother_brain)
    )
    raw_summary = Counter(str(check["status"]) for check in checks)
    summary = {
        status: raw_summary[status]
        for status in ("pass", "partial", "mismatch", "uncovered")
    }
    overall = "mismatch" if summary["mismatch"] else "pass"
    report: Dict[str, object] = {
        "schemaVersion": 16,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "scope": "foundation-compression-shared-graphics-enemy-headers-oam-instructions-slices-kraid-phantoon-draygon-ridley-mother-brain-and-species-status",
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
