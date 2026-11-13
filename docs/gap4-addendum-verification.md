# Gap 4 addendum verification

This report supersedes the earlier cleanup claims and test counts. Independent
review is unassigned. #4 and #6 remain OPEN, unverified on device, and not
production-ready. The [policy design](gap4-policy-design.md) is FOR REVIEW ONLY,
NOT IMPLEMENTATION APPROVAL.

## Changes and facts

Work stays on `hb/gap4-attempt-intent`, based on `3be5bd3`, starting this addendum
at `37a115d`. No delegated writer was active when work resumed. No new agent,
reviewer, branch, release action, push, tag or merge was started.

The completed cleanup section from `readReceipt` through `reclaimDone` is restored
byte-for-byte to base, including its reference reader and file allowlists. Generic
atomic writes retain base behavior; only intent writes use exclusive temp creation
and the existing no-replacement move. Base rollback's allowlist is restored. Intent
metadata prevents this path from deleting new attempts. New intent reservation
parsing is separated from the legacy parser used by completed cleanup. There is
no new deletion authority. `scripts/check-gap4-preservation.sh` checks the cleanup
section and original gap assertions against base.

Five new DONE generations remain byte-identical over repeated recovery/cleanup
with valid, malformed, absent intent or absent reservation evidence, with no
receipt and no release callback. Legacy pre-publication no-intent fixtures remain
Retain and untouched. Base legacy completed cleanup is exercised with explicitly
synthetic legacy fixtures, not by changing real retained data. Tests that previously
expected removal of new owned attempts now require retention of all surviving bytes
and refusal to overwrite a prior attempt. No original blocker assertion is changed.

Original gap fixtures are relocated to `src/gapReproduction/kotlin`, preserving
all assertions and removing only the Ignore import and annotations. They are
non-ignored and directly runnable through separate Gradle tasks. Default-suite
success does not imply their success or resolution of either issue.

Boundary characterization has 18 restart cases across B0..B8. Outbox boundaries
use disk-backed cursor test support, not Room equivalence. Eight real Room tests
use production BridgeDb and generated Store over SQLite. Two launch native JVM
subprocesses and halt with exit 73 inside the actual transactions, after the first
cursor target and after Pending deletion/cursor update before status insertion.
A file-fsynced marker proves the intermediate SQL state; reopening restores the
old committed state. Raw child logs and pre-reopen database copies are in the
`/tmp/hb-room-crash-*` directories named in the XML stdout. These are synthetic
host tests, not device crash or power-loss proof. FreshChunkSync status remains
outside commitChunkCursors and is not made atomic by testing Store.success.

## Commands and raw evidence

Environment:

```sh
export JAVA_HOME=/home/ubuntu/toolchain/jdk-17.0.21+9
export ANDROID_HOME=/home/ubuntu/toolchain/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew testDebugUnitTest testReleaseUnitTest lintDebug assembleDebug
scripts/reproduce-outbox-gap.sh 4
scripts/reproduce-outbox-gap.sh 6
HB_ROBOLECTRIC_SDK_DIR=/tmp/hb-gap4-addendum/offline-sdk ./gradlew --offline testDebugUnitTest testReleaseUnitTest lintDebug assembleDebug
scripts/check-gap4-preservation.sh
scripts/summarize-gap4-extension.py /tmp/hb-gap4-addendum/verified
```

The first restoration run had 303 passed, 12 failed, 3 skipped, preserved at
`/tmp/hb-gap4-addendum/reversion-first.log` and `reversion-first-xml`. They expected
new intent-bearing attempts to be removed. Those expectations contradicted the
required base allowlist restoration. The subsequent full gate passed, with raw log
`/tmp/hb-gap4-addendum/full-gate.log` and XML in `full-debug-xml` and
`full-release-xml`, each 324 passed, 0 failed, 0 skipped. Lint has 0 errors,
0 fatal issues, 13 warnings. AssembleDebug succeeded.

The relocated fixture compiler initially failed before running tests. Logs are
`gap4.log`, `gap4-fixed.log`, and `gap4-final.log` under the same evidence root.
Causes were the KotlinCompile constructor, JavaExec input collection including
compiler jars as source files, and a transitive test-parameter-injector Kotlin 2.2
metadata version. The harness now invokes the cached compiler on explicit fixture
sources, allowing newer metadata in this test-only compilation. No production
compiler, dependency or source policy is weakened. The initial signer command
lacked java on PATH; the corrected command succeeded and is in `signer.txt`.

## Repeated crash results

Each cycle attempts one new generation, reopens the same directory, verifies all
prior bytes, and invokes recovery and base cleanup. All cycles have zero sends.

| Cycle | Attempts total | Owned total | New retained bytes | Retained bytes | Admission |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 1 | 1 | 21856464 | 21856464 | admitted, crash |
| 2 | 2 | 2 | 21856478 | 43712942 | admitted, crash |
| 3 | 3 | 3 | 21856478 | 65569420 | admitted, crash |
| 4 | 4 | 3 | 0 | 65569420 | OUTBOX_QUOTA |
| 5 | 5 | 3 | 0 | 65569420 | OUTBOX_QUOTA |
| 6 | 6 | 3 | 0 | 65569420 | OUTBOX_QUOTA |
| 7 | 7 | 3 | 0 | 65569420 | OUTBOX_QUOTA |

This establishes a finite observed bound, not forward progress. The exact blocker
is `unpublished-intents-have-no-release-authority`. Base DONE cleanup also excludes
intent metadata. The policy design quantifies the additional 113,775-byte peak in
the three-large-DONE counterfactual comparison and the separate pin starvation.

## Assumptions and unproven claims

Same-directory no-replacement rename atomicity on Android is unproven. Filesystem
power-loss durability, physical block usage, cross-process writers and every
possible transport outcome remain unproven. Intent is provenance, never proof of
no-send by itself. No unbounded liveness or global exactly-once claim is made.
Production limits, control reserve, consent, scheduling, signer, application ID,
version and production dependencies remain unchanged. Published v0.1.1 context is
accepted from the owner; it was not reverified or modified. No shared evidence was
removed or overwritten. No independent review has been assigned or certified.

## Dedicated blocker results

The #4 reproduction reports 2 passed, 0 failed, 0 skipped, with raw log and XML
at `/tmp/hb-gap-reproduction-2vqbPC/`.
The #6 reproduction reports 0 passed, 1 failed, 0 skipped. Its failure is
`originalNewestThreeAdmissionFixture`, expected DONE but got BLOCKED. Raw log,
XML and summary are `/tmp/hb-gap-reproduction-KGWD4Z/`. The wrapper name is the authoritative XML failure name.

## Final collection note

The offline gate also returned BUILD SUCCESSFUL, 85 actionable tasks, in
`/tmp/hb-gap4-addendum/offline-gate.log`. Its debug XML reports 324 passed,
0 failed, 0 skipped. Before release XML could be copied, concurrent external
activity replaced the build directory. The result collector therefore failed
with `No XML results for testReleaseUnitTest`; its partial output must not be
presented as a complete summary. The earlier full gate release XML is preserved
in `/tmp/hb-gap4-addendum/full-release-xml`, with 324 passed, 0 failed, 0 skipped.
Both preserved full-suite counts are in `full-gate-summary.json`.

HEAD changed externally from 37a115d to
f2ced272567dcf2d26b0196a7181bc131995479d during collection. That commit captured
the source and test edits already exercised by this run. No further production
edit was made after detecting this activity. This report records the evidence
rather than claiming exclusive ownership of the shared build directory.

The copied debug APK is `/tmp/hb-gap4-addendum/verified/health-bridge-gap4-debug.apk`.
SHA-256 is `3e49a1feb217ca8651d88408055559620cbb6025934bb7e5a6cf49a75d00a10a`.
Signer certificate SHA-256 is
`d813dfca059eea31840113b9510ac51ced2e63b1ae16104134e4f72a3fd54fa6`.
