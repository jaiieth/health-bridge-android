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
  Stage B caps receipt count at 16. Unknown contents are not silently deleted.

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

## Stage B retention

The owner-approved policy keeps the three newest recognised, proven-DONE generations
in full. Ranking uses persisted DONE `updatedAt`, then generation ID for a deterministic
timestamp tie. It never assumes a random directory is delivered. Unknown, corrupt,
pending, unacked, UNKNOWN_ACCEPTANCE, and pointer-referenced generations are not
pruned. A legacy Pending in the database blocks reclamation entirely.

`ChunkCursorStore.committed` defaults to false. The production adapter reads committed
Room cursor rows and requires every current cursor to cover the generation target.
This proof is checked before initial release and again when resuming a receipt. DONE,
NONE stop reason, all persisted ACK identities/timestamps, valid fingerprint, child
indices/counts, exact known filenames, and body hashes must also pass. ACK flags are
trusted only as application-owned durable state written after the unchanged ACK
validator. External forgery of private application files is not a supported proof model.

Before deleting a body, a bounded root receipt is atomically written and directory-synced.
Its exact fields are format, generation, fingerprint, endpointHash, targets, chunkCount,
totalBytes, contentHash, completedAt, and manifestHash. `targets` is the committed cursor
map. `totalBytes` is the original sum of body-file byte lengths. `contentHash` is SHA-256
of the ordered concatenation of each child's fixed-length ASCII body SHA-256.
`completedAt` is persisted DONE updatedAt. `manifestHash` binds the complete DONE
manifest, including ACK metadata. Each receipt is at most 2048 bytes and contains no
payload or record-ID list.

At most 16 completed receipts are retained. Before adding a seventeenth, the oldest
valid receipt whose generation directory is already absent and is not pointer-referenced
is removed and the parent is synced. A receipt for an in-progress release is never
retired. Unknown receipts are never evicted. This explicitly bounds local history;
older pruned history is not retained indefinitely. The 64 KiB control cap independently
includes all receipt and pointer atomic coexistence. With 16 maximum-sized receipts,
their replacement/new temp and the bounded pointer/refusal records fit this cap.

The three retained generations remain in payload accounting during every future
admission. Cleanup creates only control bytes and monotonically removes payload bytes;
it never discounts prospective reclamation from a reservation before deletion happens.
Extra unrecognised or unproven generations can remain beyond the three proven completions.
They still count toward quota and can safely cause backpressure.

| Stage B restart point | Safe state |
| --- | --- |
| Before receipt rename or its directory fsync | All bodies remain. The receipt is regenerated or fenced before deletion. |
| Receipt durable, no body removed | Receipt contains the original byte total and hashes. Cursor and legacy checks run again. |
| Between individual body removals | DONE manifest and receipt remain. Remaining bodies must match their hashes before idempotent removal. |
| Bodies removed and generation directory synced | Receipt proves delivery. Generation metadata can be removed. |
| During publication/reservation metadata removal | DONE manifest still exists and binds the receipt. Cleanup resumes. |
| DONE manifest removed, generation directory remains | No bodies can remain after the earlier directory fence. The durable receipt authorizes removal of the empty/known metadata directory. |
| Generation removed before parent fsync | Receipt remains. Restart safely handles an absent or reappearing already-released directory. |
| Parent fsync complete | Three full completions plus bounded receipts remain. Repeated runs leave identical inventories. |

No migration, receiver work, sync enabling, database schema change, or legacy Pending
mutation is introduced. The strict earlier body-before-tombstone order was superseded
by the owner's tombstone-first decision. Retention is implemented only in Stage B.

Stage A verification used the preinstalled toolchain from scripts/verify.sh.
`./gradlew --no-daemon testDebugUnitTest --rerun-tasks` reported `BUILD SUCCESSFUL in 52s`.
JUnit XML totals were 243 tests, zero failures, errors, or skips, including all 230 baseline
method identities. `./gradlew --no-daemon lintDebug` reported `BUILD SUCCESSFUL in 50s`
and `0 errors, 11 warnings`. `./gradlew --no-daemon clean assembleDebug` reported
`BUILD SUCCESSFUL in 28s`. Evidence is saved in `/tmp/hb-v3-stage-a-*`.

Stage B verification used the same preinstalled toolchain.
`./gradlew --no-daemon testDebugUnitTest --rerun-tasks` reported `BUILD SUCCESSFUL in 1m`.
JUnit XML totals were 251 tests, zero failures, errors, or skips, including all 230 baseline
method identities. `./gradlew --no-daemon lintDebug` reported `BUILD SUCCESSFUL in 53s`
and `0 errors, 11 warnings`. `./gradlew --no-daemon clean assembleDebug` reported
`BUILD SUCCESSFUL in 28s`. Evidence is saved in `/tmp/hb-v3-stage-b-*`.

OutboxAdmissionTest covers quota boundaries, near-budget escaping, retained pending bytes,
partial temporary body/manifest failures, stable refusal inventories, same-reservation
retry, unknown-file preservation, and pointer-rename crash/fallback. DoneRetentionTest
covers N=3 retention, literal tombstone hashes/byte totals, every release boundary,
every atomic tombstone boundary, cursor/legacy proof rechecks, corrupt metadata,
receipt caps, both budgets at atomic peaks, and safe clearing of quota backpressure.
Existing durability and containment tests remain in the forced full runs. Assertions
that required orphan retention or mislabeled quota/manifest refusals as OVERSIZED were
updated to the newly authorized behavior; their test identities were retained.

Keeping three large full completions can itself leave insufficient space for a new
generation. The approved policy then requires backpressure rather than evicting one
of those three or exceeding the payload quota. No automatic policy override is added.
