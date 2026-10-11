#!/usr/bin/env python3
"""Offline Proctoring and Exam Fraud Analyzer for FSP Talent Platform.

Analyzes telemetry events and video sessions, produces verifiable fraud scores,
and flags high-risk behavioral anomalies.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Tuple


EVENT_WEIGHTS = {
    "tab_hidden": 15,
    "window_blur": 6,
    "face_missing": 18,
    "multiple_faces": 25,
    "camera_disabled": 40,
    "screen_share_stopped": 35,
    "copy_attempt": 10,
    "paste_attempt": 10,
    "context_menu": 5,
    "fullscreen_exit": 5,
}


def compute_fraud_metrics(events: List[Dict[str, Any]]) -> Tuple[int, str, str, Dict[str, int]]:
    """Calculates cumulative fraud score, risk level, verdict and event breakdown."""
    score = 0
    breakdown = {k: 0 for k in EVENT_WEIGHTS}
    breakdown["other"] = 0

    for ev in events:
        etype = ev.get("event_type", "unknown")
        if etype in EVENT_WEIGHTS:
            weight = EVENT_WEIGHTS[etype]
            duration = ev.get("duration_seconds", 0)
            if etype == "tab_hidden" and duration > 1:
                weight += min(25, (duration - 1) * 2)
            score += weight
            breakdown[etype] += 1
        else:
            score += 3
            breakdown["other"] += 1

    final_score = min(100, max(0, int(score)))

    if final_score > 50:
        risk_level = "fraud"
        verdict = "HIGH_RISK_DETECTED: Multiple policy violations logged during the session."
    elif final_score > 20:
        risk_level = "suspicious"
        verdict = "SUSPICIOUS_ACTIVITY: Anomalies detected, manual reviewer verification required."
    else:
        risk_level = "clean"
        verdict = "CLEAN: Exam passed without significant proctoring anomalies."

    return final_score, risk_level, verdict, breakdown


def hash_file(file_path: Path) -> str:
    """Calculates SHA-256 digest of media or telemetry artifact."""
    hasher = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            hasher.update(chunk)
    return hasher.hexdigest()


def analyze_session_file(input_json_path: Path, video_path: Path | None = None) -> Dict[str, Any]:
    with open(input_json_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    events = data.get("events", [])
    session_id = data.get("session_id", "unknown")
    attempt_id = data.get("attempt_id", "unknown")

    score, risk, verdict, breakdown = compute_fraud_metrics(events)

    report = {
        "session_id": session_id,
        "attempt_id": attempt_id,
        "fraud_score": score,
        "risk_level": risk,
        "verdict": verdict,
        "total_events": len(events),
        "breakdown": breakdown,
        "provenance": {
            "telemetry_sha256": hash_file(input_json_path),
            "video_sha256": hash_file(video_path) if video_path and video_path.exists() else None,
        },
    }
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Analyze proctoring telemetry session for exam fraud.")
    parser.add_argument("input_json", type=Path, help="Path to proctoring session JSON")
    parser.add_argument("--video", type=Path, default=None, help="Optional path to exam video recording")
    parser.add_argument("--out", type=Path, default=None, help="Optional output path for analysis report")

    args = parser.parse_args()

    if not args.input_json.exists():
        print(f"Error: input file {args.input_json} not found.", file=sys.stderr)
        return 1

    report = analyze_session_file(args.input_json, args.video)
    report_json = json.dumps(report, ensure_ascii=False, indent=2)

    if args.out:
        args.out.write_text(report_json, encoding="utf-8")
        print(f"Report written to {args.out}")
    else:
        print(report_json)

    return 0


if __name__ == "__main__":
    sys.exit(main())
