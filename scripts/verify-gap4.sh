#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/home/ubuntu/toolchain/jdk-17.0.21+9}"
export ANDROID_HOME="${ANDROID_HOME:-/home/ubuntu/toolchain/android-sdk}"
export QEMU_LD_PREFIX="${QEMU_LD_PREFIX:-/usr/x86_64-linux-gnu}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
mkdir -p /tmp/hb-gap4-verification
results="$(mktemp -d /tmp/hb-gap4-verification/run-XXXXXX)"
printf 'Verification evidence: %s\n' "$results"

git status --short > "$results/git-before.txt"
git rev-parse HEAD hb/phase23-integration hb/v0.1.1-gate-candidate > "$results/refs-before.txt"
test "$(git branch --show-current)" = hb/gap4-attempt-intent
test "$(git rev-parse hb/phase23-integration)" = 947fad21910c19a83ed9d745f892d1a339922b9a
test "$(git rev-parse hb/v0.1.1-gate-candidate)" = 3be5bd332b3fc94382dea917c883b3a1300e81de

if ! ./gradlew --no-daemon testDebugUnitTest \
    --tests dev.healthbridge.AttemptIntentTest \
    --tests dev.healthbridge.DoneRetentionTest \
    --tests dev.healthbridge.OutboxAdmissionTest \
    --tests dev.healthbridge.OutboxEnvelopeTest \
    --tests dev.healthbridge.StagingRecoveryTest \
    --tests dev.healthbridge.ChunkStreamingTest \
    --tests dev.healthbridge.ChunkIoFailureTest \
    --tests dev.healthbridge.ManifestByteBudgetTest > "$results/focused.log" 2>&1; then
    cp -r app/build/test-results/testDebugUnitTest "$results/failed-focused-xml"
    tail -60 "$results/focused.log"
    exit 1
fi
cp -r app/build/test-results/testDebugUnitTest "$results/focused-xml"

if ! ./gradlew --no-daemon testDebugUnitTest testReleaseUnitTest lintDebug assembleDebug > "$results/full.log" 2>&1; then
    cp -r app/build/test-results "$results/failed-test-results"
    tail -60 "$results/full.log"
    exit 1
fi
cp -r app/build/test-results/testDebugUnitTest "$results/debug-xml"
cp -r app/build/test-results/testReleaseUnitTest "$results/release-xml"
cp app/build/reports/lint-results-debug.xml "$results/lint.xml"
cp app/build/reports/lint-results-debug.txt "$results/lint.txt"

apk="$PWD/app/build/outputs/apk/debug/app-debug.apk"
cp "$apk" "$results/health-bridge-gap4-debug.apk"
sha256sum "$apk" > "$results/apk-sha256.txt"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$apk" > "$results/apk-signer.txt" 2>&1
python3 - "$results" <<'PY'
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

results = Path(sys.argv[1])
summary = {}
for variant in ('focused', 'debug', 'release'):
    files = list((results / f'{variant}-xml').glob('TEST-*.xml'))
    assert files, variant
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    for file in files:
        suite = ET.parse(file).getroot()
        for key in counts:
            counts[key] += int(suite.get(key, 0))
    counts['passed'] = counts['tests'] - counts['failures'] - counts['errors'] - counts['skipped']
    summary[variant] = counts
    assert counts['failures'] == counts['errors'] == 0, (variant, counts)
lint = ET.parse(results / 'lint.xml').getroot()
summary['lint'] = {severity: sum(issue.get('severity') == severity for issue in lint.findall('issue'))
                   for severity in ('Error', 'Fatal', 'Warning')}
assert summary['lint']['Error'] == summary['lint']['Fatal'] == 0
summary['apk_sha256'] = (results / 'apk-sha256.txt').read_text().split()[0]
summary['signer'] = (results / 'apk-signer.txt').read_text().splitlines()
(results / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
PY

git rev-parse HEAD hb/phase23-integration hb/v0.1.1-gate-candidate > "$results/refs-after.txt"
cmp "$results/refs-before.txt" "$results/refs-after.txt"
git diff --exit-code 3be5bd332b3fc94382dea917c883b3a1300e81de -- \
    docs/v011-candidate-analysis.md app/src/test/java/dev/healthbridge/V011KnownGapsTest.kt \
    app/src/main/java/dev/healthbridge/Store.kt app/src/main/java/dev/healthbridge/SyncControl.kt \
    app/build.gradle.kts > "$results/preserved-files.diff"
git diff --check
printf 'Verification complete: %s\n' "$results"
