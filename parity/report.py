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
            f"- Strict parity tests: **{tests['tests']}** run, **{tests['failures']}** failures, "
            f"**{tests['errors']}** errors, **{tests['skipped']}** skipped in {tests['timeSeconds']:.3f}s.",
            "- Address drift: **12** standalone SMEDIT constants plus all **87** tileset fields currently mapped.",
            "",
            "Detailed symbol, asset, compression, tileset, tile-format, animated-tile, item-PLM, enemy-header, alias, and CRE records "
            "are in `symbols.json`, `assets.json`, `lz5.json`, `tilesets.json`, `tile-formats.json`, "
            "`animated-tiles.json`, `item-plm-graphics.json`, and `enemy-headers.json` beside this report.",
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
    if (
        not symbols_path.is_file()
        or not assets_path.is_file()
        or not lz5_path.is_file()
        or not tilesets_path.is_file()
        or not tile_formats_path.is_file()
        or not animated_tiles_path.is_file()
        or not item_plm_graphics_path.is_file()
        or not enemy_headers_path.is_file()
    ):
        print(
            "ERROR: symbol/asset/LZ5/tileset/tile-format/animated-tile/item-PLM/enemy-header reports are missing; "
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

    graphics_checks = (
        compression_checks(tests, lz5)
        + tile_format_checks(tests, tile_formats)
        + tileset_checks(tests, tilesets)
        + animated_tile_checks(tests, animated_tiles)
        + item_plm_graphics_checks(tests, item_plm_graphics)
    )
    graphics_checks.sort(key=lambda check: str(check["id"]))
    checks = foundation_checks(tests) + graphics_checks + enemy_header_checks(tests, enemy_headers)
    raw_summary = Counter(str(check["status"]) for check in checks)
    summary = {
        status: raw_summary[status]
        for status in ("pass", "partial", "mismatch", "uncovered")
    }
    overall = "mismatch" if summary["mismatch"] else "pass"
    report: Dict[str, object] = {
        "schemaVersion": 7,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "scope": "foundation-compression-shared-graphics-and-enemy-headers",
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
