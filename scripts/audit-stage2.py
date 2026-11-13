#!/usr/bin/env python3
"""Check the preparation diff against containment and count JVM result XMLs."""

import hashlib
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
BASE = "b06171f"
SOURCE = "app/src/main/java/dev/healthbridge/"


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args])


def original(name):
    return git("show", f"{BASE}:{SOURCE}{name}.kt").decode()


def current(name):
    return (ROOT / SOURCE / f"{name}.kt").read_text()


def section(text, start, end):
    return text[text.index(start):text.index(end, text.index(start))]


def check(condition, message):
    if not condition:
        raise SystemExit(f"FAIL {message}")
    print(f"PASS {message}")


check(not git("remote").strip(), "scratch clone has no remote")
changed = set(git("diff", "--name-only", BASE).decode().splitlines())
changed.update(git("ls-files", "--others", "--exclude-standard").decode().splitlines())
allowed = ("app/src/main/java/dev/healthbridge/", "app/src/test/", "docs/", "scripts/audit-stage2.py")
check(all(path.startswith(allowed) for path in changed), "no build, manifest, dependency, or UI resource edits")
check(not any(path.endswith(("Activity.kt", "HealthReader.kt", "RecordJson.kt")) for path in changed),
      "UI, health collection, and record serialization unchanged")
old, new = original("Store"), current("Store")
check(section(old, "@Entity", "@Dao") == section(new, "@Entity", "@Dao"),
      "Pending, Cursor, and SyncStatus entities byte-identical")
check(section(old, "@Database", "class Settings") == section(new, "@Database", "class Settings"),
      "Room database declaration, version, and entities byte-identical")
check(section(old, "class Settings", "object AppState") == section(new, "class Settings", "object AppState"),
      "containment Settings byte-identical")
check(section(old, "    @Transaction\n    suspend fun success", "\n@Database") ==
      section(new, "    @Transaction\n    suspend fun success", "\n@Database"),
      "legacy success transaction byte-identical")
old, new = original("Transport"), current("Transport")
check(section(old, "    fun send(", "\nobject RetryPolicy") == section(new, "    fun send(", "\nobject RetryPolicy"),
      "legacy Transport.send byte-identical")
check(section(old, "    fun signature(", "    private val client") == section(new, "    fun signature(", "    private val client"),
      "HMAC function byte-identical")
check(section(old, "    private val client", "    suspend fun sendEmpty(") in new,
      "transport client configuration byte-identical")
check(section(old, "    suspend fun sendEmpty(", "    fun send(") in new,
      "isolated empty Test transport byte-identical")
check(section(original("Containment"), "    fun classify(", "\n    //") in current("Containment"),
      "Containment.classify byte-identical")
production = "\n".join(p.read_text() for p in (ROOT / SOURCE).glob("*.kt"))
check(not re.search(r"Transport\.send\s*\(", production), "no legacy Transport.send callers")
check(len(re.findall(r"@Entity\b", production)) == 3 and
      len(re.findall(r"@Database\b", production)) == 1 and
      not re.search(r"\bMigration\s*\(", production), "no additional Room entities, databases, or migrations")
check("freshSyncChunkingEnabled" not in production, "no separate chunking consent flag")
patch = Path("/home/ubuntu/scratch/phase23-logs/chunking.patch")
check(hashlib.sha256(patch.read_bytes()).hexdigest() ==
      "06d559e98be6414a106cca99dbea06afa7a1d95e6cbe4efd978d4a6f5da26a7b",
      "reference patch checksum")
results = sorted((ROOT / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
check(bool(results), "debug JVM test result XMLs exist")
totals = dict(tests=0, failures=0, errors=0, skipped=0)
for path in results:
    suite = ET.parse(path).getroot()
    counts = {key: int(suite.get(key, "0")) for key in totals}
    check(len(suite.findall("testcase")) == counts["tests"], f"{suite.get('name')} {counts}")
    for key in totals:
        totals[key] += counts[key]
print(f"TOTAL {len(results)} suites {totals}")
check(totals["failures"] == totals["errors"] == totals["skipped"] == 0,
      "all reported JVM tests pass without skips")
