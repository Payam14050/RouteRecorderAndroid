# Route Recorder GPS engine — implementation notes

This branch introduces a first implementation pass for a more resilient route-recording engine while preserving the existing Route screen and trip history.

## Changes in this branch

- Adds Google Play services Location 21.3.0 and requests fused high-accuracy updates at a 1 second target interval, 500 ms minimum interval, without batching.
- Retains Android GPS-provider updates as a fallback if the fused request fails.
- Runs callbacks on a dedicated HandlerThread.
- Registers GNSS status callbacks and records satellites visible/used-in-fix snapshots.
- Uses a bounded 8-hour partial wake lock while recording, released on stop or service destruction.
- Uses START_STICKY and attempts to recover the latest unfinished trip after service recreation.
- Stores horizontal accuracy, bearing, provider, and satellite count with each route point.
- Adds GNSS and time-event diagnostic tables, with a version-1-to-version-2 SQLite migration retaining existing trips and points.
- Writes each point and its trip distance update in one SQLite transaction.
- Rejects fixes without accuracy or worse than 100 m for route geometry; integrates distance only when both adjacent fixes are at most 50 m accurate and the gap is at most 30 seconds.
- Logs gaps, implausible jumps, provider failures, permission issues, and service lifecycle events.

## Important limitations

This is not a copy of Galileo's proprietary implementation. The supplied Galileo XAPK was inspected only for package metadata and referenced Android/GMS APIs and database concepts; its source code and internal thresholds were not recovered.

This branch is an engineering implementation, not yet a verified release. It has not been compiled in this environment, and no APK or on-device performance claim is made. Fused-location availability depends on Google Play services; the GPS-provider fallback is intended to keep recording possible where fused updates fail.

## Validation still required

1. Build :app:assembleDebug and run existing unit tests in CI.
2. Verify upgrade from a database created by version 1 and confirm trip history is retained.
3. Test background recording with screen off, process recreation, permission revocation, and GPS disabled.
4. Compare one identical route on the same handset against Galileo, examining GPX timestamps, fix accuracy, gaps, route shape, and distance. Do not infer accuracy superiority from update frequency alone.
