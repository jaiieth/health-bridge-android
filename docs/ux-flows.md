# Health Bridge flows and states

This matrix describes b387fea. Citations below use file aliases with exact symbols. They describe source behavior, not device verification.

| Alias | File |
| --- | --- |
| Activity | [MainActivity.kt](../app/src/main/java/dev/healthbridge/MainActivity.kt) |
| Control | [SyncControl.kt](../app/src/main/java/dev/healthbridge/SyncControl.kt) |
| Display | [SyncStatusDisplay.kt](../app/src/main/java/dev/healthbridge/SyncStatusDisplay.kt) |
| Storage | [Store.kt](../app/src/main/java/dev/healthbridge/Store.kt) |
| Worker | [SyncWorker.kt](../app/src/main/java/dev/healthbridge/SyncWorker.kt) |
| Coordinator | [Containment.kt](../app/src/main/java/dev/healthbridge/Containment.kt) |
| HTTP | [Transport.kt](../app/src/main/java/dev/healthbridge/Transport.kt) |

## Flow

| From | Action | Result and implementing symbols |
| --- | --- | --- |
| Open app | Bind saved settings, refresh every two seconds | No startup scheduling or cancellation. Activity `onCreate`, `refresh`; Control `setEnabled` and `save` are called only by explicit listeners. |
| Setup | Edit endpoint, enter token and interval, Save settings | Validate, save under mutex, clear entered token, refresh. Activity `onCreate` save listener; HTTP `validateUrl`; Control `save`. |
| OFF | Explicit switch ON | Persist intent, schedule periodic worker only if readback matches. Control `setEnabled`; Worker `Schedule.periodic`. |
| ON | Explicit switch OFF | Persist OFF, cancel periodic, manual, and delivery-retry work; separate webhook-test is excluded. Control `setEnabled`; Worker `Schedule.pauseHealth`. |
| Configured | Send test | Enqueue isolated one-shot empty batch, independently of switch or health permissions. Activity `refresh`, `onCreate`; Worker `Schedule.manual`, `SyncWorker.doWork`; Coordinator `run`, `EmptyTest.body`. |
| ON, configured, permissions granted | Record paused health status | Enqueue non-test worker; record containment pause without health delivery. Activity `refresh`, `onCreate`; Coordinator `run`. |

## State matrix

| State | Entry condition | Screen shows | Allowed actions | UI must not imply | Implementation |
| --- | --- | --- | --- | --- | --- |
| First run, no configuration | Empty settings; sync preference defaults false | OFF; destination Not configured; token not saved; test unavailable | Edit setup, save valid configuration, grant access, switch ON | Opening the app enables sync or contacts the receiver | Storage `Settings.endpoint`, `token`, `syncEnabled`; Activity `onCreate`, `refresh`; Display `format`, `host`, `tokenIndicator` |
| Connection setup | Edit endpoint or enter new token | Full endpoint only after Edit; masked initially empty token; interval; inline validation errors | Save HTTPS URL without user info or fragment, printable nonempty token, interval 15..525600. Blank token keeps saved value | Saved secret is loaded into token field; save proves connectivity | Activity `onCreate` save listener, `input`, `showError`; HTTP `validateUrl`; Display `tokenFieldValue` |
| Default Sync OFF | Preference false, including fresh install | OFF regardless of result, setup, or permission state | Setup, access, configured empty test, switch ON | Retained data is deleted; an active empty test is cancelled | Storage `Settings.syncEnabled`; Display `format` first branch; Activity `refresh`; Worker `Schedule.pauseHealth` |
| Explicit ON | Switch persists true | Before worker: BLOCKED if setup or access missing; otherwise WAITING for Ready, Sync OFF, or empty-test results. After non-test worker: PAUSED | Switch OFF; save; configured empty test; record pause only with setup and permissions | ON means health data is being collected or delivered | Control `setEnabled`; Display `format` ordered branches; Activity `refresh`; Coordinator `run` |
| Settings save while ON | Valid save, no forbidden retained-batch endpoint or token change | Settings saved. Sync remains ON. Status comes from existing persisted result | Save interval and reschedule periodic work | Save pauses or cancels health schedules, changes sync intent, or delivers data | Activity `onCreate` save listener; Control `save`; Storage `Settings.save` |
| Settings save while OFF | Same validation while preference false | Settings saved. Sync remains OFF | Save configuration without scheduling | Save turns sync ON | Activity `onCreate`; Control `save`; Storage `Settings.save` |
| Toolbar save | No toolbar exists in this Activity | No toolbar save UI | Settings card Save settings is the implemented path | A separate toolbar action exists | Activity `onCreate` constructs the entire screen; no toolbar handler implemented |
| Isolated empty Test, no Pending | Configured; explicit Send test | Requested text initially; then recorded test interpretation. Exact ACCEPTED HTTP 2xx string gives acknowledgement | Explicit test again; no automatic retry | Empty acceptance confirms health delivery or updates last successful health sync | Activity `onCreate`, `refresh`; Worker `Schedule.manual`; Coordinator `run`; Display `testResult` |
| Isolated empty Test, legacy Pending exists | Configured; retained row present; explicit test | Same separate test feedback; endpoint and token locked | Empty test, interval save, switch control | Test replays, replaces, acknowledges, deletes, or advances the legacy batch | Activity `refresh`, save listener; Coordinator `run` never reads or mutates Pending or cursors; `EmptyTest` creates a fresh ID and empty body |
| Fresh-sync chunk progress | Not implemented in this revision | Static containment progress text | Record paused health status when eligible | Any chunk count, collection, or fresh delivery is running | Display `progress`; Coordinator `run` non-test branch returns immediately; Worker `SyncWorker.doWork` calls only containment |
| Chunk partial failure | Not implemented; no ChunkSync coordinator in this revision | No chunk partial-failure UI | Existing containment controls only | A subset of health chunks was accepted, retried, or committed | Display `progress`, `format`; Coordinator `run`; Worker `SyncWorker.doWork` |
| Blocked 400 | Empty test returns HTTP 400 | Test card: BLOCKED HTTP 400, no acceptance confirmed. Main status follows switch/setup/access precedence, usually WAITING when ON and ready | Another explicit test, setup edits if unlocked | Main status necessarily says Blocked; payload repair or retry occurs automatically | Coordinator `classify`, `run`; Display `testResult`, `format`; Activity `refresh` |
| Blocked 409 | Empty test returns HTTP 409 | Test card: BLOCKED HTTP 409, no acceptance confirmed | Same explicit actions as 400 | Conflict means accepted duplicate or health delivery success | Coordinator `classify`, `run`; Display `testResult`, `format` |
| Blocked 413 | Empty test returns HTTP 413 | Test card: BLOCKED HTTP 413, no acceptance confirmed | Same explicit actions as 400 | Automatic splitting or smaller chunk retry exists | Coordinator `classify`, `run`; Display `testResult`, `format` |
| Paused unknown outcome | Transport boundary records unknown marker; cancellation or later non-IO exception can leave marker | Main BLOCKED when ON, OFF when OFF; test card says acceptance unknown. This is not the PAUSED enum state | Check receiver before another explicit test; UI still permits configured test | Retry is safe or automatic; unknown means rejected; PAUSED enum represents this marker | HTTP `sendEmpty` beforeSend; Coordinator `run`, enteredTransport and exception paths; Display `format`, `testResult`; Activity `refresh` |
| Unrecognized recorded result | ON with setup and permissions, result matches no earlier branch | UNKNOWN; no health delivery confirmed | Existing controls | Old success text proves delivery | Display `format` final branch |
| Last actual successful delivery | Historical parseable `SyncStatus.lastSuccess` exists | Separately labeled Last successful health sync with local absolute time and relative age; invalid or Never becomes Never recorded | Existing controls; no timestamp editing UI | Empty test creates a health success, or timestamp proves current connection | Storage `SyncStatus`, `Store.success`; Display `lastSuccess`; Activity `onCreate`, `refresh`; Coordinator `run` copies status changing only result. Current worker never calls `Store.success`. |
| Legacy Pending retained and paused | Persisted Pending from prior code | Endpoint/token lock reason; status still follows OFF/ON and result precedence, not Pending alone | Interval save; switch; configured empty test; permission action | Retained batch is automatically sent, cleared, or retried; lock means confirmed failure | Storage `Pending`; Activity `refresh`, save listener under `AppState.mutex`; Coordinator `run`; Display `format` does not branch on pending |

## Boundaries

| Constraint | Implementation |
| --- | --- |
| Test has no health records and no automatic retry | Coordinator `EmptyTest.body`, `run`; HTTP `client` disables connection retry and redirects; Worker `SyncWorker.doWork` returns success after coordinator completion. |
| Health access is separate from empty testing | Activity `refresh` enables Send test using configuration only; `onCreate` permission dialog and permission launcher are separate. |
| Retained data does not unlock on Save or Test | Activity `onCreate` save lock check; Coordinator `run` has no Pending or cursor mutation. |
| Display has no delivery authority | Display `Snapshot`, `format`, `testResult`, `progress` format read-only inputs. Activity `refresh` binds them. |
