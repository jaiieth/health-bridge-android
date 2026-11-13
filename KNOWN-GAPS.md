# Local v3 remediation

The fresh-chunking gate remains false. All fixtures are synthetic and local. No device,
receiver, SDK allocation, installation, or live delivery guarantee is established.

## Stage A admission and recovery

Both collection routes reserve before staging any body. Streaming makes a read-only
planning pass, then replays with the same child IDs and verifies every record ID/hash
and child body hash before publication. Replay reuses the planned metadata lists.
Source changes fail closed. Health Connect does not provide an immutable snapshot here.

There are two disjoint content-byte budgets.

- Payload is capped at 67,108,864 bytes. `retainedBytes()` includes bodies, manifests,
  immutable publication snapshots, reservation sidecars, unknown files, and their temps.
- Controls are capped at 65,536 bytes. Only root-level active, active-proof, previous,
  backpressure, receipt files and their temps are controls. Each record is at most
  2048 bytes. Atomic writes check existing control bytes plus the new copy before writing.
  Stage B will cap receipt count as well. Unknown contents are not silently deleted.

Payload admission computes `R = retainedPayload + bodyBytes + 3 * manifestBound + 256`.
The three manifest copies cover immutable `published.json`, current `manifest.json`,
and its replacement temp during SEND/ACK/COMMIT/DONE. A new body has either its temp
or final name, never both. The bounded, immutable reservation sidecar fits within 256
bytes. The same stored reservation governs resumed saves; it is not reduced on restart.
The accepted allocation-free ManifestByteBudget is unchanged and runs before JSON
construction. It is a conservative encoded-size bound, not an exact encoded length.
Exact body byte counts come from planning. Existing retained generations, including
all delivered generations in Stage A, reduce admission capacity.

Control admission keeps 8192 bytes available for pointer/proof replacements during an
attempt. Refusal remains writable at a full payload budget without exceeding either
cap. Logical budget separation cannot make an actually full filesystem writable; real
ENOSPC can still prevent a durable status write. Existing 1 MiB free-space headroom is
separate from both content-byte quotas. Filesystem blocks, inode overhead, and unrelated
writers outside the application's mutex are not covered by content-byte arithmetic.

A single bounded backpressure record contains version, reason, generation, required
peak, payload quota, retained payload at refusal, and manifest budget. Repeated blocked
runs check it before collection. It clears only after enough payload is actually released
to fit, a quota/manifest-budget change, or an explicit retry request through the internal
API. No user-facing setting is added. Refused runs preserve byte-identical file inventories
after the first marker write. IO_FAILURE is returned through ChunkStatus and the existing
status persistence path; a rollback does not create a second outbox IO refusal marker.

Pointer publication first keeps an authenticated previous record. New active-proof
records hash the immutable published manifest. Current manifest immutable identities
and child descriptors must match that publication. The active ID is written to a distinct
temp, file-synced, renamed, and directory-synced. Previous is retained afterward.
Restart accepts the new pointer only with a valid snapshot hash and matching manifest,
otherwise it falls back to the authenticated previous record. Resumed work still passes
the existing directory durability fence before sending. A failed fsync is returned as
IO_FAILURE, not delivery success. Legacy fresh pointers remain readable without migration.

Reasons are distinct and have literal messages in SyncStatusDisplay.

- OUTBOX_QUOTA means cumulative payload storage prevents admission.
- MANIFEST_LIMIT means manifest metadata exceeds its byte budget.
- GENERATION_RECORD_LIMIT and GENERATION_CHILD_LIMIT identify generation caps.
- OVERSIZED means a single record cannot fit.
- ENVELOPE_LIMIT means even an empty envelope cannot fit an injected limit.
- IO_FAILURE means local I/O failed, including ENOSPC.

## Stage A crash matrix

| Restart point | Safe state |
| --- | --- |
| Planning or quota refusal | No body exists. Durable backpressure prevents recollection. |
| Caught body/manifest write failure before pointer publication | Only this attempt's known, unpublished files are removed; parent is fsynced. Prior pointer and pending data survive. |
| Process death during staging or cleanup | Unpublished directories are ignored and counted. Unknown files are never guessed to be disposable. |
| New proof written, active ID still old | Authenticated previous generation remains selected. |
| Active renamed before directory fsync | New publication is accepted only after hash/structure checks; otherwise previous is selected. Resume fences precede sends. |
| SEND persisted, response or ACK persistence lost | UNKNOWN_ACCEPTANCE prevents automatic resend. |
| Cursor transaction fails | COMMIT remains resumable without resending acknowledged children. |
| Cursor committed, DONE write fails | Durable phase reload re-commits identical targets before DONE. No cursor rollback. |

A process death during rollback can leave ignored, counted bytes. There is no automatic
restart deletion of unproven directories or durable identity cache for fully rolled-back
attempts. Such attempts can require recollection; committed quota/manifest refusals do not.
If active rename already occurred, cleanup preserves that complete referenced generation
instead of deleting it after a reported I/O failure. It does not promise zero growth at
that publication boundary. Pre-publication body/manifest IOException fixtures prove
zero payload growth and previous-pointer preservation.

## Stage B status at the Stage A commit

The owner authorized keeping the three newest proven-DONE generations in full and
writing a durable tombstone before deleting older bodies. That ordering resolves the
previous byte-total recovery gap. Retention is not yet implemented in this Stage A commit.
There is no deletion of DONE, pending, unacked, UNKNOWN_ACCEPTANCE, legacy Pending,
or unrecognised generations in Stage A. Stage B requires its own commit and crash tests.

Stage A verification used the preinstalled toolchain from scripts/verify.sh.
`./gradlew --no-daemon testDebugUnitTest --rerun-tasks` reported `BUILD SUCCESSFUL in 52s`.
JUnit XML totals were 243 tests, zero failures, errors, or skips, including all 230 baseline
method identities. `./gradlew --no-daemon lintDebug` reported `BUILD SUCCESSFUL in 50s`
and `0 errors, 11 warnings`. `./gradlew --no-daemon clean assembleDebug` reported
`BUILD SUCCESSFUL in 28s`. Evidence is saved in `/tmp/hb-v3-stage-a-*`.
