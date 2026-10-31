# Methodology and limitations

## Lab boundary
- One emulator/device and one app-private directory: `files/lab_target`.
- Only synthetic dummy text files are written, modified, or renamed.
- No cross-app inspection or device-wide protection claim.
- Response cancels/interupts only the active harness task; it does not kill processes.

## Five workloads
1. `benign_sequential`: moderate-rate dummy writes.
2. `benign_spaced`: slower dummy writes.
3. `sim_rapid_writes`: rapid dummy writes.
4. `sim_rename_burst`: rapid dummy-file creation and rename.
5. `sim_mixed_burst`: rapid dummy writes followed by renames.

These are harmless patterns, not malware.

## Starter heuristic
A rolling 2-second window counts observed mutation events, distinct observed paths, and rename/move events:
- +2 if mutation events >= 10
- +2 if distinct observed paths >= 5
- +1 if rename events >= 3
- Alert at score >= 4

This is an experimental baseline, not a validated security threshold. Calibrate it with repeated benign and simulated runs.

## Metrics
Run summaries store the alert timestamp and delay from run start. Distinct paths at alert are an approximation from observed events, not a ground-truth count of all files changed. Precision = TP/(TP+FP); recall = TP/(TP+FN). Retain misses and false alerts.

## Android telemetry caveat
FileObserver event coverage depends on Android version and filesystem. A dependable OPEN event is not exposed consistently. This prototype scores observable CREATE, MODIFY, CLOSE_WRITE, and move/rename events; verify telemetry on the selected emulator before interpreting results.

