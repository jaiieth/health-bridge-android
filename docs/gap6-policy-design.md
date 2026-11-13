# Gap 6 admission retention policy

**FOR REVIEW ONLY. NOT IMPLEMENTATION APPROVAL.** Independent review is
unassigned. #4 and #6 remain OPEN. This proposal grants no deletion authority.
It supersedes neither current code nor the numeric protection of the newest three.

## Implementation boundary

The proposed eligible set requires immutable attempt intent. Current production
`completedManifest` and `release` exclude that filename, and completed cleanup
accepts only legacy reservation arithmetic. Legacy no-intent DONE generations are
ineligible under this proposal. Thus the intersection of proposal eligibility and
current production release eligibility is empty. A test-only sequencer cannot
exercise successful release without removing provenance, fabricating a legacy
reservation, or changing production policy. None is an acceptable positive test.
The requested oldest-first successful send, immediate-stop release and receipt
crash-resume tests are BLOCKED by this exact mismatch. Existing legacy release
restart tests are not proof of this new policy. No release driver is implemented.

## Exact eligible inventory and authority

Only a newly owned intent-protocol generation may be considered. Hold the single
writer mutex, drain the queue, and suspend collection and transport for the entire
decision and release. Read bounded regular files with no symlinks or subdirectories.
Exactly these final files must exist inside an intact eligible generation:

- `manifest.json`, the final DONE manifest.
- `published.json`, the immutable initial STAGE publication.
- `reservation`, at most 256 bytes, with intent-aware arithmetic.
- `attempt-intent.json`, at most `I=M+32768` bytes.
- Exactly `child-0.json` through `child-(n-1).json`, n between 1 and 100.

No `manifest.json.tmp`, `published.json.tmp`, `reservation.tmp`, intent temp,
child temp, missing child, unknown file, directory or symlink is eligible. Neither
staging tombstone nor its temp is a generation file. Their presence at root blocks
the decision. No tombstone for retained data is proposed.

At root, only the exact generation-specific `receipt-<generation>.json` is
completion release authority after its own fences. Its `.tmp` is never authority.
Existing receipts and temps block a new admission-pressure authorization; they
require an independently verified resume path. They are not deleted to obtain space.
All other root entries must be recognized and bounded; ambiguity blocks globally.

The complete predicate is conjunctive:

1. Intent and reservation parse with exact schemas, lengths and digests. Generation,
   endpoint, fingerprint, ordered record IDs/hashes, targets, child indexes, batch
   IDs, counts, basenames, lengths and digests agree across intent and manifests.
2. Publication is STAGE with NONE reason and no sentAt, ackedAt or ACK flags.
   Final manifest is DONE with NONE reason, never UNKNOWN_ACCEPTANCE. Its immutable
   identity equals publication and intent. Fingerprint recomputes exactly.
3. All final children have acked=true, non-null sentAt and ackedAt; timestamps are
   valid under the writer protocol. Every body is regular, bounded and byte-exact
   against its planned length and SHA-256. Child indexes are contiguous and batch
   IDs unique and receiver-valid. Counts sum to ordered record inventory length.
4. Targets are nonempty, at most six known types. Target cursors parse as Instant,
   each at most 40 characters. The real durable Cursor row of each exact type must
   exist, parse as Instant and be at or after its target. String ordering is not
   timestamp proof. Missing, duplicate, invalid or older proof blocks.
5. The actual durable Pending row is absent. No acceptance ambiguity, active or
   previous/rollback pin, active-proof or temporary pointer refers to the generation.
   Every pointer parses and authenticates; malformed references block globally.
6. The current manifest bytes are hashed into the receipt together with immutable
   generation identity, target map, total body bytes, content digest and completion
   time. Recheck identity, pins, Pending and cursor proof before any byte release.

Intent is PROVENANCE, not release authority. ACK is durable local evidence of a
response, not global exactly-once proof. No absence-of-pointer inference can replace
these checks. A valid completed receipt is required before the first removal.

## Pins and refusal table

| Evidence | Rule |
| --- | --- |
| active | blocks, no implicit unpin; never delete reference |
| active.tmp | blocks, no implicit unpin; never delete reference |
| active-proof | blocks, no implicit unpin; never delete reference |
| active-proof.tmp | blocks, no implicit unpin; never delete reference |
| previous | blocks, no implicit unpin; never delete reference |
| previous.tmp | blocks, no implicit unpin; never delete reference |
| legacy Pending, with no intent | blocks, no implicit unpin; never alter Pending |
| staging tombstone or temp | blocks, no implicit unpin; never delete reference |
| completed receipt | blocks new authorization; no implicit unpin; independently validate resume |
| receipt temp | blocks new authorization; no implicit unpin; temp alone grants no release |
| ambiguous/corrupt entry | blocks globally, no implicit unpin; retain every byte |

A reference to a different generation protects that generation. Unreadable or
unparsable references block the whole decision. An unmet pin returns
BLOCKED-with-diagnosis. No partial release of that candidate is permitted. Mutex
or queue non-idle, missing durable cursor proof, Pending present, receipt space
exhausted, or any changed inventory also stops before authorization.

## Ordering and exact admission inequality

Order eligible generations oldest first by `(manifest.updatedAt ascending,
generation ASCII lexicographic ascending)`. This is the reverse of current
newest-first protection, including the generation tie-break. Do not use directory
mtime, receipt creation time or intent creation time as completion ordering.

Let R be all retained generation logical bytes, C all current control logical
bytes, B the next plan's body bytes and M its conservative manifest bound. Set
`I=M+32768` and `A=B+3*M+256+2*I`. All arithmetic is checked integer arithmetic.
Let F_j be the exact sum of all eligible generation file lengths removed for
candidate j, and r_j its exact encoded receipt length, at most 2048 bytes.
Receipt final plus atomic temp peak costs 2*r_j, bounded by 4096 bytes, wholly
inside the unchanged 65536-byte control reserve. Receipts remain after release.

For k completed releases, use the conservative receipt-copy-inclusive peak:

```
R(k) = R - sum(F_j, j=1..k)
C_peak(k) = C + 2*sum(r_j, j=1..k)
P_next(k) = R(k) + C_peak(k) + A
```

Release candidates in order and stop at the first k with
`P_next(k) <= 67108864` AND `R(k)+A <= 67108864-65536` AND
`C_peak(k) <= 65536`. k=0 means release nothing. Actual completed controls use
`C+sum(r_j)`; reserving two copies of every new receipt is deliberately conservative.
Before releasing candidate j, its receipt write itself must fit
`R(j-1)+C+sum(r_i,i<j)+2*r_j <= 67108864` and the control bound. Refuse and stop
if it cannot. Do not remove a body to make its own authorization fit. Existing
free-space/headroom checks remain separate. No receipt rotation, pruning or
reference removal is allowed to satisfy these inequalities.

Receipt UTF-8 JSON has exactly ten fields matching the existing encoding:
format, generation, fingerprint, endpointHash, targets, chunkCount, totalBytes,
contentHash, completedAt, manifestHash. Four hashes are 64 lowercase hex characters;
generation is 1..100 ASCII allowed characters, child count 1..100, nonnegative
byte/time values, at most six bounded targets. Exact encoded size must be <=2048,
not inferred from character count. Intent/manifest schemas retain their current
bounds: M<=8388608, I<=8421376, at most 100000 records, 100 children and unchanged
per-child limits. These bounds can exceed quota in combination; admission refuses.

At most 16 completed receipts are retained under the existing bound, 32768 final
bytes or 65536 if every record has a full temp copy. This proposal permits no
receipt eviction to admit another. A 17th receipt or insufficient control space
is BLOCKED. It provides no claim of indefinite progress. After payload release,
completion metadata is only the bounded final receipt; before or during authorized
release all generation metadata remains charged at its actual size.

## Crash resume proposal, not implementation

First verify the complete intact inventory and proof under exclusive ownership.
Write receipt temp, file fsync, no-replacement atomic rename, directory fsync.
A crash before directory durability releases nothing. A surviving temp blocks
until a separately approved recovery step validates byte equality and fences the
same authorization without replacement. A conflicting final receipt stops.
The current code does not supply this proposal's intent-aware resume operation.

After durable authority, recheck pins and durable DB proof, release only listed
matching children, fsync generation directory, remove bound publication,
reservation and intent metadata, remove final manifest last, fsync directory,
remove empty generation directory, fsync parent. Receipt remains. Every restart
reopens and revalidates authority and remaining exact inventory. Count only files
actually present; never subtract the original F twice. A missing body can resume
only with durable authority and surviving binding manifest. Unknown or changed
remaining files stop. Metadata-only residue can resume only with validated durable
receipt and its inventory bindings. No receipt temp or missing evidence authorizes
partial release. This ordering needs independent review and owner approval.

## Concrete starvation arithmetic

The unchanged Gap6ReproductionTest builds three generations, each 42 children from
approximately 520000-character synthetic payloads, then requests four children.
The matched measured accounting is in IntentAdmissionCostTest XML:
R=65632801, C=181, B_next=2080404, M_next=5901, I_next=38669.
The exact checked calculation is:

```
A = 2080404 + 3*5901 + 256 + 2*38669 = 2175701
P_next(0) = 65632801 + 181 + 2175701 = 67808683
excess = 699819
```

The measured legacy-format counterfactual is R=65596364 and P=67694908.
Intent increases peak by 113775 bytes. Both refuse with zero sends. These measured
figures use fixed target and completion timestamps; the original fixture varies
those timestamps, so its exact bytes must be read from its own raw XML rather
than asserted identical.

A conditional release of the first 42-child generation frees exactly F_1, the sum
of its 42 body files plus final manifest, publication, reservation and intent.
Those filenames and actual lengths must be emitted by a successful proposed-policy
test. Current code cannot produce that release. Without claiming an unmeasured
exact F_1, its bodies alone exceed 42*520000=21840000 bytes. Even reserving a full
4096-byte receipt pair gives P_next(1)<45972779, comfortably below both total and
payload bounds. This is conditional arithmetic, not observed admission or send.
No exact freed-file byte total or successful next-send result is established here.
The requested real successful-release measurement is BLOCKED by the empty eligible
intersection above; fabricating its value would misstate evidence.

## New policy and residual risks

Releasing any member of the newest three under admission pressure is a NEW
retention policy superseding current numeric protection. Keeping the constant 3
in source would not make this behavior unchanged. Explicit owner approval is
required before implementation, including intent-aware receipt/release support.
Pins still refuse admission. There is no liveness guarantee when pins consume
needed bytes, control evidence fills its reserve, or candidates are legacy,
unknown, corrupt or ambiguous. Finite storage plus indefinite protected intake
cannot provide no-delete liveness. No implicit unpin or partial release solves it.

Not proven: proposed receipt encoding sufficiency for intent-aware interrupted
metadata removal, device rename/no-replacement and power-loss behavior, cross-process
exclusion, real transport delivery uniqueness, all corruption interleavings, or
unbounded intake progress. Real Room transaction tests do not make FreshChunkSync
status part of its cursor transaction. This document is not an implementation or
independent review of any proposed release behavior.

## Current adversarial coverage limits

`Gap6PolicyBoundaryTest.actualCompletedValidatorRejectsEveryIntentBearingDoneGeneration`
invokes the actual private validator without altering production visibility, then
reopens and runs actual cleanup repeatedly. The pin test checks preservation for
12 cases against actual cleanup. These cases also have intent metadata, so they
prove retained inventory, not independent enforcement of each proposed pin rule.
The legacy test proves the existing Pending guard preserves a synthetic no-intent
DONE fixture. It is not evidence that production rejects all no-intent DONE data;
base production intentionally retains its independently authorized legacy cleanup.
No test-only success policy or fake release implementation is presented as coverage.

BLOCKED cases remain successful release-until-fit with a real next send,
stop-immediately after the first sufficient release, intent-aware receipt-temp
crash resume, and receipt admission under exhausted control reserve. Implementing
the missing intent-aware release primitive would cross the production policy
boundary. The baseline gap fixture remains non-ignored and fails separately.
