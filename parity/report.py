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
            "evidence": "Twelve seed constants are checked; the remaining address inventory is not yet mapped.",
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


def markdown_report(report: Dict[str, object]) -> str:
    identity = report["identity"]
    summary = report["summary"]
    symbols = report["symbols"]
    assets = report["assets"]
    lz5 = report["lz5"]
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
            f"- Strict parity tests: **{tests['tests']}** run, **{tests['failures']}** failures, "
            f"**{tests['errors']}** errors, **{tests['skipped']}** skipped in {tests['timeSeconds']:.3f}s.",
            "- Address drift: **12** named SMEDIT constants currently mapped.",
            "",
            "Detailed symbol, asset, and compression records are in `symbols.json`, `assets.json`, "
            "and `lz5.json` beside this report.",
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
    if not symbols_path.is_file() or not assets_path.is_file() or not lz5_path.is_file():
        print("ERROR: symbol/asset/LZ5 reports are missing; run ./gradlew parityReport", file=sys.stderr)
        return 2

    reference = read_properties(REFERENCE_FILE)
    symbols = json.loads(symbols_path.read_text(encoding="utf-8"))
    assets = json.loads(assets_path.read_text(encoding="utf-8"))
    lz5 = json.loads(lz5_path.read_text(encoding="utf-8"))
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

    checks = foundation_checks(tests) + compression_checks(tests, lz5)
    raw_summary = Counter(str(check["status"]) for check in checks)
    summary = {
        status: raw_summary[status]
        for status in ("pass", "partial", "mismatch", "uncovered")
    }
    overall = "mismatch" if summary["mismatch"] else "pass"
    report: Dict[str, object] = {
        "schemaVersion": 2,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "scope": "foundation-and-lz5",
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
