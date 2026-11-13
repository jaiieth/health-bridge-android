#!/usr/bin/env python3
import json
from pathlib import Path
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ET

repo = Path(__file__).resolve().parents[1]
if len(sys.argv) > 1:
    evidence = Path(sys.argv[1])
    evidence.mkdir(parents=True, exist_ok=False)
else:
    evidence = Path(tempfile.mkdtemp(prefix="hb-gap4-results-"))
summary = {"totals": {}, "suites": {}}
rows = ["task\tsuite\tpassed\tfailed\terrors\tskipped\ttotal"]
for task in ("testDebugUnitTest", "testReleaseUnitTest", "gap4Reproduction", "gap6Reproduction"):
    source = repo / "app/build/test-results" / task
    files = sorted(source.glob("TEST-*.xml"))
    if not files:
        raise RuntimeError(f"No XML results for {task}; run that task before summarizing")
    shutil.copytree(source, evidence / task)
    total = dict(tests=0, failures=0, errors=0, skipped=0)
    suites = {}
    for file in files:
        suite = ET.parse(file).getroot()
        counts = {key: int(suite.get(key, 0)) for key in total}
        for key in total:
            total[key] += counts[key]
        counts["passed"] = counts["tests"] - counts["failures"] - counts["errors"] - counts["skipped"]
        name = suite.get("name")
        suites[name] = counts
        rows.append("\t".join(map(str, (task, name, counts["passed"], counts["failures"], counts["errors"], counts["skipped"], counts["tests"]))))
        if name in ("dev.healthbridge.AttemptBoundaryTest", "dev.healthbridge.AttemptLivenessTest"):
            (evidence / f"{task}-{name.rsplit('.', 1)[1]}.txt").write_text(suite.findtext("system-out") or "")
    total["passed"] = total["tests"] - total["failures"] - total["errors"] - total["skipped"]
    summary["totals"][task] = total
    summary["suites"][task] = suites
lint_path = repo / "app/build/reports/lint-results-debug.xml"
lint = ET.parse(lint_path).getroot()
summary["lint"] = {severity: sum(issue.get("severity") == severity for issue in lint.findall("issue"))
                   for severity in ("Error", "Fatal", "Warning")}
shutil.copy2(lint_path, evidence / "lint.xml")
(evidence / "suite-counts.tsv").write_text("\n".join(rows) + "\n")
(evidence / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(evidence)
print(json.dumps({"totals": summary["totals"], "lint": summary["lint"]}, indent=2))
