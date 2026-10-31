#!/usr/bin/env python3
"""Evaluate exported evidence.json using the run labels and alert outcomes."""
import argparse
import json
from pathlib import Path

def main():
    p = argparse.ArgumentParser()
    p.add_argument("evidence", help="Path to exported evidence.json")
    args = p.parse_args()
    data = json.loads(Path(args.evidence).read_text(encoding="utf-8"))
    runs = data.get("runs", [])
    tp = fp = fn = tn = 0
    delays, impacts = [], []
    for run in runs:
        simulated = run.get("label") == "simulation"
        alerted = bool(run.get("alert"))
        if simulated and alerted: tp += 1
        elif simulated and not alerted: fn += 1
        elif not simulated and alerted: fp += 1
        else: tn += 1
        delay = run.get("detection_delay_ms")
        if alerted and isinstance(delay, (int, float)): delays.append(delay)
        if alerted: impacts.append(run.get("distinct_files_at_alert", 0))
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    print("AIORI 3 · PS-001 evaluation")
    print(f"Runs: {len(runs)} | TP={tp} FP={fp} FN={fn} TN={tn}")
    print(f"Precision: {precision:.3f}")
    print(f"Recall: {recall:.3f}")
    print(f"Mean detection delay among alerted runs (ms): {sum(delays)/len(delays):.1f}" if delays else "Mean detection delay: n/a")
    print(f"Mean observed paths at alert: {sum(impacts)/len(impacts):.1f}" if impacts else "Observed paths at alert: n/a")
    print("Caution: prototype metrics depend on event coverage, calibration, and run labels.")

if __name__ == "__main__":
    main()
