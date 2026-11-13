#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-}" in
    4) task=gap4Reproduction ;;
    6) task=gap6Reproduction ;;
    *) echo 'Usage: scripts/reproduce-outbox-gap.sh 4|6' >&2; exit 2 ;;
esac
export JAVA_HOME="${JAVA_HOME:-/home/ubuntu/toolchain/jdk-17.0.21+9}"
export ANDROID_HOME="${ANDROID_HOME:-/home/ubuntu/toolchain/android-sdk}"
export QEMU_LD_PREFIX="${QEMU_LD_PREFIX:-/usr/x86_64-linux-gnu}"
export PATH="$JAVA_HOME/bin:$PATH"
results="$(mktemp -d /tmp/hb-gap-reproduction-XXXXXX)"
echo "Evidence: $results"
status=0
./gradlew ":app:$task" > "$results/gradle.log" 2>&1 || status=$?
if [[ -d "app/build/test-results/$task" ]]; then
    cp -r "app/build/test-results/$task" "$results/xml"
    python3 - "$results" <<'PY'
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
root = Path(sys.argv[1])
counts = dict(tests=0, failures=0, errors=0, skipped=0)
failures = []
for file in (root / 'xml').glob('TEST-*.xml'):
    suite = ET.parse(file).getroot()
    for key in counts:
        counts[key] += int(suite.get(key, 0))
    for case in suite.findall('testcase'):
        failure = case.find('failure')
        if failure is not None:
            failures.append(dict(test=case.get('name'), message=failure.get('message')))
counts['passed'] = counts['tests'] - counts['failures'] - counts['errors'] - counts['skipped']
report = dict(counts=counts, failures=failures)
(root / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
PY
fi
tail -15 "$results/gradle.log"
exit "$status"
