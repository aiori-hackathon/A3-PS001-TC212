# Android Ransomware Early Warning (AIORI 3 PS-001)

A behavioral detection prototype designed to identify ransomware-like file-system activity on Android within a controlled lab environment, trigger an early warning, and safely terminate registered test workloads.

---

## Overview

Signature-based antivirus tools often struggle with zero-day or uncatalogued ransomware because recognition can arrive after file modifications have already begun. While Android's application sandbox prevents ordinary apps from performing cross-app memory inspection or promising device-wide control, destructive file activity still leaves an observable trail. Rapid bursts of `open`, `write`, and `rename` operations across multiple files provide a strong behavioral signal.

This project builds a measurable, explainable lab pipeline to test whether file-system telemetry (`FileObserver` / `inotify`) can reliably distinguish between benign bulk file updates and simulated ransomware behavior. 

### Strict Lab Boundaries
* **Scope:** Monitoring is strictly limited to one explicitly accessible target directory on a single emulator or rooted test device.
* **Safety:** All tests run exclusively on dummy files using harmless simulations. No real malware is used, and no claims of device-wide prevention on stock Android are made.
* **Containment:** Upon detection, the system terminates only the registered, harness-controlled test workload.

---

## System Architecture & Pipeline

The full execution, monitoring, and response path stays strictly inside the lab boundary so that every decision is auditable against ground-truth labels.

### End-to-End Execution Flow

```text
+-----------------------+      +-----------------------+      +-----------------------+      +-----------------------+
| 1. Execute Workload   |      | 2. Capture Events     |      | 3. Window & Score     |      | 4. Respond & Evaluate |
+-----------------------+      +-----------------------+      +-----------------------+      +-----------------------+
| Launch benign or      | ---> | FileObserver/inotify  | ---> | Aggregate features in | ---> | Log alert, terminate  |
| simulated run via     |      | watches the target    |      | a rolling window and  |      | registered workload,  |
| the test harness.     |      | test directory.       |      | apply heuristic rules.|      | and compute metrics.  |
+-----------------------+      +-----------------------+      +-----------------------+      +-----------------------+
            |                              |                              |                              |
            v                              v                              v                              v
   [Run ID & Labels]             [Timestamped Events]           [Heuristic Score]             [JSON/CSV Evidence]
```

### Heuristic Scoring Model

Instead of flagging every bulk file operation, the detector aggregates events over a rolling time window and evaluates three core signals before making a decision:

```text
  [Raw FileObserver / inotify Stream]
                   |
                   +---> 01. Activity Rate : Frequency of write and rename bursts over the window.
                   |
                   +---> 02. Breadth       : Count of distinct files touched (single file vs. many files).
                   |
                   +---> 03. Pattern       : Sequential ordering of open -> write -> rename operations.
                   |
                   v
     [Explainable Heuristic Score]
                   |
                   +---> (Below Threshold) ---> Continue Monitoring
                   |
                   +---> (Above Threshold) ---> Log Alert & Terminate Registered Workload
```

---

## Proposed Project Structure

```text
aiori-ps001-ransomware-warning/
├── docs/                        # Architecture diagrams, methodology report, and pseudocode
├── harness/                     # Test runner to launch and register workloads with ground-truth labels
├── workloads/
│   ├── benign/                  # 2 benign bulk-operation workloads
│   └── simulations/             # 3 harmless ransomware-like file modification simulations
├── telemetry/                   # FileObserver / inotify event listener scoped to the target directory
├── detector/                    # Rolling window aggregator, heuristic rules engine, and scoped killer
├── evaluation/                  # Scripts to parse logs and compute precision, recall, delay, and impact
└── logs/                        # Exported JSON/CSV raw event streams, alerts, and run summaries
```


---

## Experimental Matrix & Evaluation

The detector is evaluated by comparing a naive baseline against a calibrated multi-signal configuration across five distinct workloads on the same documented lab setup.

| Workload Cohort | Baseline Configuration | Calibrated Configuration |
| :--- | :--- | :--- |
| **2 Benign Workloads** | Single-event frequency threshold over a fixed time window; log false alerts and misses. | Combined write/rename rate, distinct file count, and event-sequence heuristic over a tunable rolling window. |
| **3 Harmless Simulations** | Same fixed threshold; record true triggers and missed detections. | Scoped, prompt termination of the registered workload upon crossing the calibrated threshold. |

### Core Metrics Tracked
* **Detection Delay:** Elapsed time (in ms or s) from the start of the workload run to the alert trigger.
* **Files Changed Before Alert:** Number of distinct dummy files impacted prior to workload containment.
* **Precision:** True positive alerts divided by total triggered alerts.
* **Recall:** Detected simulation runs divided by total simulated attack runs.

---

## Delivery Roadmap

```text
[Phase 1: Lab & Telemetry] ---> [Phase 2: Detector Baseline] ---> [Phase 3: Test & Tune] ---> [Phase 4: Package & Demo]
  Exit: Repeatable event          Exit: Alert triggers on           Exit: 4 core metrics        Exit: Reproducible
        logs verified                   crafted test events               computed from logs          end-to-end demo
```


1. **Phase 1 (Lab & Telemetry):** Configure the emulator or rooted device, set up the target directory, and verify `FileObserver` / `inotify` event visibility.
2. **Phase 2 (Detector Baseline):** Implement rolling feature windows, transparent scoring rules, alert logging, and the scoped termination mechanism.
3. **Phase 3 (Test & Tune):** Execute the 2 benign workloads and 3 safe simulations, calibrate heuristic thresholds, and calculate performance metrics from exported logs.
4. **Phase 4 (Package & Demo):** Finalize system diagrams, pseudocode, evaluation reports, and the reproducible walkthrough.

---

## Team & Supervision

* **Team Members:** Sanketh Bhat, Meghana Jayashankar
* **Faculty Mentor:** Dr. Animesh Giri

## References

1. Android Developers: `FileObserver` API Documentation
2. Android Developers: Application Sandbox and Security Best Practices
3. MITRE ATT&CK Mobile: Data Encrypted for Impact (T1486)