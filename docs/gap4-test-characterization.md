# Gap 4 test-only extension results

Historical report for 37a115d. The [addendum report](gap4-addendum-verification.md)
supersedes cleanup authority, fixture placement, counts, and Room crash coverage.

This is an execution report, not an independent review. Independent review is
unassigned. The policy proposal is FOR REVIEW, NOT IMPLEMENTATION in
[gap4-policy-design.md](gap4-policy-design.md). #4 and #6 remain OPEN.
Device behavior is unverified, and this branch is not production-ready.

## Scope and preserved evidence

The extension starts at `20c43ace8faf02dece11382b15b7fbfc3bddaa59` on
`hb/gap4-attempt-intent`. It changes tests, test configuration, reproduction
launchers, result collection, and documents only. Production sources and the
original `V011KnownGapsTest.kt` are byte-identical to that start point. All
production dependency declarations, application ID, SDK/build-tools selections,
and version fields remain identical. No deletion, reclamation, quota, retention,
envelope, signing, consent, or production transaction implementation is changed.
No push, tag, merge, release, infrastructure, or shared-evidence mutation occurs.

The owner's published-v0.1.1 facts are accepted context, not reverified work.
Protected branch, tag, and release state was not changed. No other agent or
review thread was started for this extension.

## Exact verification commands

The environment is the existing native ARM64 JDK 17 and Android toolchain:

```sh
export JAVA_HOME=/home/ubuntu/toolchain/jdk-17.0.21+9
export ANDROID_HOME=/home/ubuntu/toolchain/android-sdk
export QEMU_LD_PREFIX=/usr/x86_64-linux-gnu
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew testDebugUnitTest testReleaseUnitTest lintDebug assembleDebug --continue --rerun-tasks
scripts/reproduce-outbox-gap.sh 4
scripts/reproduce-outbox-gap.sh 6
scripts/summarize-gap4-extension.py
```

The final full command was run after the reproduction launchers were moved to
`app/src/gapReproduction/java`. It returned exit 0, BUILD SUCCESSFUL, and 85
executed tasks. `--continue` requests the remaining checks even if a task fails;
it does not turn failure into success. There were no failing default tasks.
Only documents and result-collection code changed after that run.

The separate reproduction commands return 0 for #4 and 1 for #6. The launchers
invoke the original fixture methods with their original TemporaryFolder rule.
The fixture source, assertions, and inherited Ignore annotations are unchanged.
The separate tasks execute those methods without skips. The launchers live
outside default test sources. No new Ignore annotation or default-suite exclusion
was added. A passing default suite does not include a passing #6 reproduction.

## Measured results

| Suite | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Debug default | 318 | 0 | 0 | 3 |
| Release default | 318 | 0 | 0 | 3 |
| AttemptBoundaryTest, each variant | 13 | 0 | 0 | 0 |
| AttemptLivenessTest, each variant | 1 | 0 | 0 | 0 |
| RoomTransactionTest, each variant | 6 | 0 | 0 | 0 |
| Original #4 reproduction | 2 | 0 | 0 | 0 |
| Original #6 reproduction | 0 | 1 | 0 | 0 |

The three inherited skips per variant remain the two original #4 methods and the
original #6 method. Each default variant has 321 tests total. The new suites
account for 20 additional passing tests per variant. All individual suite counts
are in `/tmp/hb-gap4-extension/verified/suite-counts.tsv` and `summary.json`.

`lintDebug` reports 0 errors, 0 fatal issues, and 13 warnings. Two warnings are
newer-version suggestions for the added test-only Room-testing and AndroidX Test
Core declarations. Neither warning is suppressed. `assembleDebug` succeeds.

The #6 failure is the original final assertion:

```
#6 no forward progress with three retained DONE generations and a full envelope
expected:<DONE> but was:<BLOCKED>
```

The original #4 fixtures now pass their narrow intent-presence assertion. This
proves neither safe reclamation nor forward progress. The seven-cycle test below
still demonstrates a #4 admission blocker.

## Repeated crash measurements

These are logical retained file bytes from synthetic fixtures, not filesystem
allocated blocks or measured memory usage. Attempts and owned generations are
cumulative. Every cycle restarts over the same directory with a new outbox and
runner. The cursor test double stores its state on disk; it is not Room coverage.
All seven cycles send zero batches and perform zero cursor commits.

| Cycle | Attempts | Owned generations | Newly retained bytes | Retained bytes | Admission |
| ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 1 | 1 | 21,856,464 | 21,856,464 | Admitted, crashed |
| 2 | 2 | 2 | 21,856,478 | 43,712,942 | Admitted, crashed |
| 3 | 3 | 3 | 21,856,478 | 65,569,420 | Admitted, crashed |
| 4 | 4 | 3 | 0 | 65,569,420 | OUTBOX_QUOTA |
| 5 | 5 | 3 | 0 | 65,569,420 | OUTBOX_QUOTA |
| 6 | 6 | 3 | 0 | 65,569,420 | OUTBOX_QUOTA |
| 7 | 7 | 3 | 0 | 65,569,420 | OUTBOX_QUOTA |

Debug and release produce the same measurements. Existing generation paths,
lengths, and SHA-256 hashes remain unchanged after every subsequent attempt.
Three final restart passes also preserve the whole inventory. Retention plus
controls stays within the unchanged 67,108,864-byte quota. The named blocker is
`unpublished-intents-have-no-release-authority`. Storage is bounded by admission
refusal; ingestion liveness is not established.

The 13 boundary cases cover B0 through B8, including pre-pointer and referenced
B6, first and final ACK, before cursor commit, and after cursor commit. Existing
DoneRetentionTest fixtures supply the independently authorized release crash
intervals. The boundary matrix and precise limitations are in the policy design.

## Real Room proof and runtime failures

Room tests construct the production BridgeDb and generated Store DAO against a
file-backed SQLite database under Robolectric, then close and reopen it. They
check Pending refusal without cursor advance, multi-target commit, success
persistence, test-success semantics, and transaction rollback on SQLite trigger
failures at cursor insertion and at the final status insertion. The latter checks
that both prior cursor values and Pending survive when the final write fails.
These are real Room/SQLite tests, not fake Store implementations.

Initial attempts failed before database assertions could run:

- Robolectric 4.14.1 failed all 6 tests loading the unavailable Linux ARM64
  Conscrypt JNI library.
- Disabling Conscrypt reached SQLite, but 4.14.1 still failed all 6 Room tests
  because its native runtime does not support Linux ARM64. The combined focused
  run had 14 passed, 6 failed, and 0 skipped tests.
- A temporary x86-64 JDK under the already installed QEMU support aborted with
  exit 134 and `qemu: uncaught target signal 6`. That run has no valid completed
  suite count and is not coverage. No JVM override remains in build configuration.
- Stable Robolectric 4.17 still failed all 6 tests with
  `The Robolectric native runtime is not supported on Linux (aarch64)`.

The test-only version is therefore Robolectric 4.18-beta-1, whose loader and
native runtime support Linux ARM64. Room-testing stays at the production Room
version 2.6.1; AndroidX Test Core is 1.6.1. Android resources are enabled for local
unit tests. Conscrypt is explicitly off in these database-only tests. No fake
SQLite substitute, skipped Room assertion, or production dependency upgrade was
used. The final native runs passed all six tests in both variants.

Room rollback coverage does not prove process-kill or power-loss durability.
It also does not make FreshChunkSync's cursor and status writes atomic. That
production path uses an anonymous ChunkCursorStore adapter and performs its status
write outside commitChunkCursors. Store.success is a separate transaction method.
The broader atomicity premise remains a production integration question outside
this test-only scope.

## Legacy retention and artifact evidence

`AttemptIntentTest.legacyNoIntentPrepublicationInventoryRetainsExactly` passed in
both final variants. It checks identical paths, lengths, hashes, Pending/cursor
fixture bytes, bodies, and partial temp bytes through repeated recovery, with
zero release callbacks. Referenced and ambiguous intent fixtures also passed
unchanged. This is evidence for legacy pre-publication Retain behavior, not a
claim that independently completed legacy generations can never be released by
existing authority.

The final raw results, per-suite counts, boundary outputs, liveness outputs,
lint XML, preservation checks, and APK copy are in
`/tmp/hb-gap4-extension/verified`. The full Gradle log is
`/tmp/hb-gap4-extension/final-gate.log`. The final direct reproduction logs are
`/tmp/hb-gap4-extension/gap4-final-console.log` and
`/tmp/hb-gap4-extension/gap6-final-console.log`. Earlier failed Room logs and XML
remain in `/tmp/hb-gap4-extension`; none were replaced with passing evidence.

The rebuilt APK SHA-256 is
`65d0749c290130cb58feda00aef780c65559e4742d9408c0e2b1d66388756fa7`.
`apksigner verify --print-certs` succeeds with signer certificate SHA-256
`d813dfca059eea31840113b9510ac51ced2e63b1ae16104134e4f72a3fd54fa6`.
This is a test-stage local build, not a publication or release change.

No independent safety verdict is claimed. Atomic no-replacement on Android,
real device durability, global exactly-once delivery, #4 storage reclamation,
#6 newest-three and pin liveness, and the proposed policy remain unproven or open.
