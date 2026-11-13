# Health Bridge

Health Bridge is currently in containment. Health collection and delivery are paused. Explicit webhook tests send one signed empty batch with no automatic retry. It has no backend, analytics SDK, or Google Drive integration. See [the current UI rationale](docs/ui-design-rationale.md).

## Build and set up

1. Install JDK 17 and Android command-line tools. Accept the SDK licenses and install `platforms;android-35` and `build-tools;35.0.0` with `sdkmanager`.
2. Set `JAVA_HOME` and `ANDROID_HOME`, or set `sdk.dir` in an untracked `local.properties`.
3. Run `./gradlew assembleDebug lintDebug testDebugUnitTest`. The pinned Gradle 8.9 wrapper downloads its checksum-verified distribution. AGP is 8.7.3 and Kotlin is 2.0.21. Health Connect is pinned to 1.1.0-beta01 because stable 1.1.0 requires compileSdk 36.
4. Install `app/build/outputs/apk/debug/app-debug.apk` on Android 8 or later. Health Connect requires a supported Android 9+ device. Android 14+ includes the provider; earlier supported devices need the Health Connect app. Unsupported devices display a status message.
5. Enter your HTTPS webhook URL, a nonempty printable ASCII bearer token, and an interval in minutes. Save settings. The default is 360 minutes and the minimum is 15 minutes.
6. Tap **Grant health permissions**, read the rationale, and grant all six read permissions. The screen identifies partial and denied access. Optional background read access is requested only when the provider supports it.
7. Sync defaults to OFF. Use **Send test** for a separate, explicit empty webhook test without health access. ON schedules only the containment worker, which records PAUSED. **Record paused health status** cannot export health records. Opening the app or saving settings pauses health work. Retained data is unchanged and does not prevent an isolated empty test.

For this workspace, source `/home/ubuntu/toolchain/env.sh` to use the installed local JDK and SDK. This host is ARM64; Android's x86-64 native build tools run through QEMU with `QEMU_LD_PREFIX=/usr/x86_64-linux-gnu`. Run `scripts/verify.sh` to build, lint, run unit tests, and print the APK path, byte size, SHA-256, and manifest badging. Reports are saved under `verification/`.

CI runs the wrapper on pushes and pull requests and uploads the debug APK. Nothing is deployed or published.

## Current containment behavior

The encrypted `Settings.syncEnabled` preference is the only switch state. Explicit ON schedules the existing worker; explicit OFF cancels health work. The worker records OFF or PAUSED without reading or sending health data. Save and startup never schedule work. Retained rows lock endpoint and token edits. Empty tests bypass the retained queue, record their outcome, and run once without automatic retry. A recorded test acceptance does not confirm health delivery.

## Historical export design, currently disabled

The following collection, cursor, and retry description documents the earlier export design. It does not describe the contained worker or the current UI.


Read types are SleepSession, HeartRateVariabilityRmssd, RestingHeartRate, HeartRate series, Steps, and ExerciseSession. Sync skips when a required permission is missing. Periodic and manual sync use unique WorkManager requests with a connected-network constraint. Android can defer background work. Collection in the background additionally requires the provider's optional background read permission. Without it, keep the app open while collecting. No foreground service polls for data.

The first collection covers the preceding 30 days, subject to Health Connect's history access limit. Each later read starts at that type's last successful cursor and ends at a fixed collection timestamp. Every page is read. Room retains the exact JSON bytes, batch ID, candidate cursors, attempt count, and next attempt time before delivery. A successful HTTP response advances cursors and removes the pending batch in one transaction. Webhook tests have no cursors.

Only HTTP 2xx succeeds. Redirects are rejected. OkHttp connect, read, write, and total call timeouts are 30 seconds. Failures retry with delays of 30, 60, 120, and 240 seconds, subject to WorkManager scheduling. Five attempts exhaust the queue; the batch remains stored and blocks new collection. **Sync now** explicitly resets its retry budget. Endpoint and token changes are blocked while any batch remains queued, to prevent sending retained data to a different receiver.

This is a time-window export, not a mirror of edits or deletions. Late inserts and later edits to older records can be missed. Sessions overlapping a cursor can repeat. Upsert by source package and record ID. Large histories and heart rate series are collected in memory as one batch. A crash after server acceptance can replay that batch, so the receiver must deduplicate batch IDs. Clearing app data removes credentials, queue, and cursors.

## Webhook contract

The request body is UTF-8 JSON. Times are ISO-8601 UTC. Instant records have identical `startTime` and `endTime`. `timezone` contains original zone offsets, or null offsets if the source did not supply them. Health Connect does not provide an IANA time zone identifier.

```json
{
  "schemaVersion": 1,
  "batchId": "5fb29ac4-1cca-48af-811c-d1be9e4386f2",
  "records": [
    {
      "type": "StepsRecord",
      "recordId": "health-connect-record-id",
      "startTime": "2026-11-12T00:00:00Z",
      "endTime": "2026-11-12T01:00:00Z",
      "timezone": {"startOffset": "+07:00", "endOffset": "+07:00"},
      "values": {"count": 1200, "unit": "count"},
      "source": {
        "package": "com.example.source",
        "name": "Example health app",
        "device": {"manufacturer": "Example", "model": "Watch", "type": 1}
      },
      "lastModifiedTime": "2026-11-12T01:01:00Z",
      "recordingMethod": 1,
      "clientRecordId": null,
      "clientRecordVersion": 0
    }
  ]
}
```

Source app display names can be null because Android limits package visibility. Device metadata can also be null. Values contain sleep stages with UTC times and integer stage codes with unit `stage_code`; HRV `rmssd` with unit `ms`; resting heart rate `beatsPerMinute` with unit `bpm`; heart rate timestamped `samples` with unit `bpm`; steps `count` with unit `count`; or exercise `exerciseType` with unit `exercise_type_code`. Enum codes follow the Health Connect SDK. No exercise routes are requested.

Headers are:

```text
Content-Type: application/json; charset=utf-8
Authorization: Bearer TOKEN
X-Health-Bridge-Batch-Id: UUID
X-Health-Bridge-Signature: sha256=LOWERCASE_HEX
```

HMAC-SHA256 uses the token's UTF-8 bytes as the key and the exact raw HTTP body as the message. Verify before parsing or reserializing JSON:

```python
import hashlib
import hmac

def authorized(headers, raw_body, token):
    expected = "sha256=" + hmac.new(
        token.encode("utf-8"), raw_body, hashlib.sha256
    ).hexdigest()
    return (
        hmac.compare_digest(headers.get("Authorization", "").encode(),
                            ("Bearer " + token).encode())
        and hmac.compare_digest(headers.get("X-Health-Bridge-Signature", "").encode(),
                                expected.encode())
    )
```

[tools/receiver.py](tools/receiver.py) is a standalone TLS receiver example. It is not started by the build. It needs a trusted certificate and key, and `HEALTH_BRIDGE_TOKEN` in its environment. Add durable batch deduplication and processing before returning 2xx. The receiver must match the batch ID header to the signed body and reject unauthorized requests.

## Security

The receiver must verify both the bearer token and HMAC and serve TLS. The app adds no encryption beyond TLS. It uses Android's platform certificate validation and has no certificate pinning.

The health-data queue is protected only by the app sandbox. The token is encrypted using EncryptedSharedPreferences with a Keystore-wrapped key; this does not encrypt the Room queue. Backup is disabled. The app writes no health records, payload bodies, or tokens to logcat.

A compromised webhook URL or token means health data disclosure. A compromised device can also expose queued health data and credentials. Send only to a receiver you control, protect its storage, and rotate credentials if exposed. HMAC does not prevent replay by itself; the receiver must deduplicate batch IDs.

## Verification limits

Unit tests cover HTTPS URL rejection, a known HMAC vector, raw-byte sensitivity, and retry limits. Build and lint verify the packaged artifact. Real Health Connect permission grants, collection, background execution, and device Keystore behavior require a supported device. No real health records or remote webhook are used during the build.
