"""Check integration boundaries and report freshly generated JVM result totals."""

from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/dev/healthbridge"
BASE = "b387fea"


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args]).decode()


def original(name):
    return git("show", f"{BASE}:app/src/main/java/dev/healthbridge/{name}.kt")


def section(text, start, end):
    return text[text.index(start):text.index(end, text.index(start))]


def check(value, message):
    if not value:
        raise SystemExit(f"FAIL {message}")
    print(f"PASS {message}")


check(not git("remote").strip(), "scratch clone has no remote")
for name, boundaries in {
    "Store": [("@Entity", "@Dao"), ("@Database", "object AppState"),
              ("    @Transaction\n    suspend fun success", "\n@Database")],
    "Transport": [("    fun signature", "    private val client"),
                  ("    suspend fun sendEmpty", "    fun send("),
                  ("    fun send(", "\nobject RetryPolicy")],
}.items():
    before = original(name)
    after = (SOURCE / f"{name}.kt").read_text()
    for start, end in boundaries:
        check(section(before, start, end) == section(after, start, end), f"{name}: {start.strip()} unchanged")

for name in ("MainActivity", "SyncControl", "SyncStatusDisplay", "HealthReader", "RecordJson"):
    check(original(name) == (SOURCE / f"{name}.kt").read_text(), f"{name} unchanged")

changed = git("diff", "--name-only", BASE).splitlines()
check(not any(p.endswith(("gradle.kts", "AndroidManifest.xml")) or p.startswith("app/src/main/res/")
              for p in changed), "build, manifest and UI resources unchanged")

for variant in ("testDebugUnitTest", "testReleaseUnitTest"):
    files = list((ROOT / "app/build/test-results" / variant).glob("TEST-*.xml"))
    check(bool(files), f"{variant} result files exist")
    totals = {key: sum(int(ET.parse(path).getroot().get(key, 0)) for path in files)
              for key in ("tests", "failures", "errors", "skipped")}
    print(f"{variant}: {len(files)} classes, {totals}")
    check(totals == dict(tests=152, failures=0, errors=0, skipped=0), f"{variant} expected totals")
