# Gap 6 CTO extension evidence

This is an execution report, not independent review. #4/#6 remain OPEN, unverified
on device, not production-ready. Work remains local on hb/gap4-attempt-intent.
Starting HEAD 9b0d30e54e038a975742df9550f27b89de440081 was clean, so preceding
work was already committed. No new agent, branch, push, tag, release or merge.

## Scope and blocked requirements

Only new test and document files change in this extension. Production cleanup,
limits, control reserve, consent, application ID, signer and dependencies are
unchanged. The policy proposal is [gap6-policy-design.md](gap6-policy-design.md),
FOR REVIEW ONLY, NOT IMPLEMENTATION APPROVAL.

The real production completed validator rejects intent-bearing generations, and
its reservation parser rejects intent-aware arithmetic. The requested new policy
excludes legacy no-intent DONE generations. There is therefore no eligible input
for a successful release through the unchanged production primitive. Removing
intent metadata or converting the candidate to legacy would contradict the
requested proof. No such workaround or fake successful release is implemented.

The three new tests are:

- `actualCompletedValidatorRejectsEveryIntentBearingDoneGeneration`: actual private
  validator rejects all five new DONE fixtures; repeated real cleanup preserves bytes.
- `everyProposedPinPreservesActualOutboxInventoryOnRestart`: 12 pin/ambiguity cases,
  fresh real outbox, identical file lengths and hashes, no release callback.
- `legacyNoIntentDoneRemainsUntouchedBehindActualPendingGuard`: synthetic legacy
  fixture remains untouched under the real Pending guard. This is not proof that
  base cleanup generally rejects legacy DONE data, nor a real Room Pending test.

These observations establish refusal and preservation only. They do not isolate
individual pin predicates because all new fixtures also fail the intent allowlist.
Successful release-until-fit/next send, first-sufficient-candidate stopping,
intent-aware receipt-temp restart and exhausted-control receipt admission remain
BLOCKED by the missing primitive. No progress or idempotent release claim is made.
Exact freed inventory arithmetic remains conditional, explicitly marked in the
policy design; observed successful release bytes are not invented.

## Commands

Existing native toolchain environment:

```sh
export JAVA_HOME=/home/ubuntu/toolchain/jdk-17.0.21+9
export ANDROID_HOME=/home/ubuntu/toolchain/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew testDebugUnitTest testReleaseUnitTest lintDebug assembleDebug
scripts/reproduce-outbox-gap.sh 4
scripts/reproduce-outbox-gap.sh 6
scripts/check-gap4-preservation.sh
```

Raw gate log is `/tmp/hb-gap6-extension-gate.log`. Copied XML/logs and machine-readable
counts are under `/tmp/hb-gap6-cto-extension/`. Known-gap tasks remain outside the
default suite, non-ignored with original assertions unchanged. The original three
inherited skips were the two #4 methods and the #6 method in V011KnownGapsTest:
`gap4BodyTempSurvivesRestartWithoutAttemptIntent`,
`gap4DurableBodySurvivesRestartWithoutAttemptIntent`,
`gap6ThreeProvenDoneGenerationsPreventNextEnvelopeAdmission`.
They are no longer skipped; they run through the dedicated reproduction wrappers.
A green default suite cannot close either gap.

## Test-only dependencies

Already added in the preceding stage, none added by this extension:
Robolectric **4.18-beta-1**, androidx.room:room-testing **2.6.1**,
androidx.test:core **1.6.1**. Existing JUnit **4.13.2** and org.json **20240303**
remain. Android resources are included for unit tests. Production Room remains
2.6.1 and database version remains 1. The beta dependency requires owner acceptance;
this report does not self-certify independent review or production readiness.

## Gate results

The exact gate command returned BUILD SUCCESSFUL in 3m23s, 85 actionable tasks.
Debug: 327 tests, 0 failures, 0 errors, 0 skipped. Release: 327 tests, 0 failures,
0 errors, 0 skipped. Each includes the three named new tests above, all passed.
Lint: 0 errors, 0 fatal issues, 13 warnings. assembleDebug succeeded, output already
up to date. Counts are parsed from copied XML in `debug-xml` and `release-xml`,
not inferred from Gradle exit alone. Summary is
`/tmp/hb-gap6-cto-extension/summary.json`.

These green suites prove the observed refusal/preservation and existing tests,
not proposed release safety or liveness. `scripts/check-gap4-preservation.sh`
passed; production source and build configuration have no diff against starting
HEAD. The original standalone #6 expected-DONE assertion remains unchanged.

Standalone #4: 2 tests, 0 failures, 0 skipped. It proves the existing synthetic
intent-presence crash assertions, not reclamation or device durability.
Standalone #6: 1 test, 1 failure, 0 skipped, `originalNewestThreeAdmissionFixture`,
expected DONE versus BLOCKED. It reproduces admission starvation, not resolution.
Raw task evidence is `/tmp/hb-gap-reproduction-hN9qjM/` and
`/tmp/hb-gap-reproduction-cPv6at/`; copies are `gap4-xml` and `gap6-xml` under
`/tmp/hb-gap6-cto-extension/`. No original fixture assertion or Ignore state changed.

Full changed/added file list for this extension:

- `app/src/test/java/dev/healthbridge/Gap6PolicyBoundaryTest.kt`
- `docs/gap6-policy-design.md`
- `docs/gap6-extension-verification.md`

No files in production sources or configuration changed. Successful new-policy
release and real next-send proof remain blocked; this is a committed partial
characterization with exact limitations, not completion of those requested cases.
