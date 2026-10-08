#!/usr/bin/env python3
"""Prepare blinded synthetic-profile review packets and score submitted reviews."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import sys
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATASET = REPO_ROOT / "docs/validation/matching-benchmark.v1.json"
DEFAULT_OUT = REPO_ROOT / "local/perfection-implementation/2026-10-08/blind-review"
RUBRIC = {
    "0": "Hard incompatible: an explicit hard work-condition/category/visibility/email mismatch.",
    "1": "Unknown: required evidence, verified category, or a hard work condition is unknown.",
    "2": "Qualified: confirmed target category and required test evidence; some preferred evidence is absent.",
    "3": "Strong fit: confirmed target category and the required plus preferred test evidence.",
}
JOB_FIELDS = (
    "specialization_id", "target_grade", "work_mode", "city",
    "workload_hours_per_week", "requirements",
)
PROFILE_FIELDS = (
    "specialization_id", "verified_grade", "declared_grade", "visibility",
    "email_confirmed", "allowed_work_modes", "cities", "max_weekly_hours",
    "test_evidenced_skills", "candidate_declared_skills", "demo",
)
FORBIDDEN_REVIEW_KEYS = {"gold", "expected_labels", "label_reason", "label_provenance", "hard_incompatibility", "profiles", "judgment_sets"}


class BlindReviewError(ValueError):
    pass


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def digest_json(value: Any) -> str:
    return hashlib.sha256(canonical_json(value)).hexdigest()


def _ensure_local_output(path: Path) -> Path:
    resolved = path.expanduser().resolve()
    local_root = (REPO_ROOT / "local").resolve()
    if resolved == local_root or local_root not in resolved.parents:
        raise BlindReviewError(f"output must be inside {local_root}")
    return resolved


def _find_records(dataset: dict[str, Any]) -> list[tuple[dict[str, Any], dict[str, Any], dict[str, Any]]]:
    needs = {item["id"]: item for item in dataset.get("needs", [])}
    records = []
    seen = set()
    for group in dataset.get("judgment_sets", []):
        need = needs.get(group.get("need_id"))
        if need is None:
            raise BlindReviewError("judgment set references an unknown need")
        for row in group.get("profiles", []):
            candidate_id = row.get("candidate_id")
            if not isinstance(candidate_id, str) or (group["need_id"], candidate_id) in seen:
                raise BlindReviewError("missing or duplicate candidate in source dataset")
            seen.add((group["need_id"], candidate_id))
            profile = row.get("profile", {})
            if profile.get("demo") is not True:
                raise BlindReviewError("blind-review export accepts demo profiles only")
            records.append((need, row, profile))
    return records


def export_dataset(dataset: dict[str, Any], out_dir: Path, seed: int, dataset_sha256: str | None = None):
    """Write a blinded packet, private ID key, and empty review template."""
    out_dir = _ensure_local_output(Path(out_dir))
    if not isinstance(seed, int) or isinstance(seed, bool):
        raise BlindReviewError("seed must be an integer")
    data_digest = dataset_sha256 or digest_json(dataset)
    case_map: dict[str, dict[str, str]] = {}
    cases = []
    for need, row, profile in _find_records(dataset):
        original_need = need["id"]
        original_candidate = row["candidate_id"]
        case_id = hashlib.sha256(f"{seed}\0{original_need}\0{original_candidate}".encode()).hexdigest()[:20]
        if case_id in case_map:
            raise BlindReviewError("pseudonymous case ID collision")
        case_map[case_id] = {"need_id": original_need, "candidate_id": original_candidate, "split": need.get("split", "unknown")}
        cases.append({
            "case_id": case_id,
            "job": {field: need.get(field) for field in JOB_FIELDS},
            "profile": {field: profile.get(field) for field in PROFILE_FIELDS},
            "synthetic_profiles_for_review": True,
        })
    random.Random(seed).shuffle(cases)
    packet = {
        "format": "blind-review-packet-v1",
        "seed": seed,
        "dataset_sha256": data_digest,
        "labeling_notice": "All profiles are synthetic demo profiles. Reviews do not establish human preference, hiring outcomes, or real-person validity.",
        "rubric": RUBRIC,
        "cases": cases,
    }
    packet_digest = digest_json(packet)
    private_key = {
        "format": "blind-review-private-key-v1",
        "dataset_sha256": data_digest,
        "policy_sha256": dataset.get("policy_sha256"),
        "packet_sha256": packet_digest,
        "case_map": case_map,
    }
    template = {
        "format": "blind-review-submissions-v1",
        "packet_sha256": packet_digest,
        "dataset_sha256": data_digest,
        "records": [],
        "record_template": {"case_id": "", "reviewer_id": "", "label": None, "rationale": ""},
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    for filename, value in (("reviewer_packet.json", packet), ("privatekey.json", private_key), ("review_template.json", template)):
        (out_dir / filename).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return packet, private_key, template


def _validate_reviews(packet: dict[str, Any], key: dict[str, Any], reviews: dict[str, Any]):
    packet_digest = digest_json(packet)
    if key.get("packet_sha256") != packet_digest or reviews.get("packet_sha256") != packet_digest:
        raise BlindReviewError("packet hash mismatch or stale review input")
    data_digest = packet.get("dataset_sha256")
    if key.get("dataset_sha256") != data_digest or reviews.get("dataset_sha256") != data_digest:
        raise BlindReviewError("dataset hash mismatch or stale review input")
    case_map = key.get("case_map")
    packet_cases = {case.get("case_id") for case in packet.get("cases", [])}
    if not isinstance(case_map, dict) or packet_cases != set(case_map):
        raise BlindReviewError("private key case map does not match packet")
    records = reviews.get("records", [])
    allowed_review_fields = {"format", "packet_sha256", "dataset_sha256", "records", "record_template"}
    if set(reviews) - allowed_review_fields or any(not isinstance(record, dict) or FORBIDDEN_REVIEW_KEYS.intersection(record) for record in records):
        raise BlindReviewError("review input contains prohibited imported gold fields")
    seen = set()
    grouped: dict[str, list[int | None]] = {case_id: [] for case_id in packet_cases}
    for record in records:
        case_id, reviewer_id, label = record.get("case_id"), record.get("reviewer_id"), record.get("label")
        if case_id not in packet_cases:
            raise BlindReviewError(f"unknown case ID: {case_id}")
        if not isinstance(reviewer_id, str) or not reviewer_id.strip():
            raise BlindReviewError("reviewer_id must be a non-empty string")
        pair = (case_id, reviewer_id)
        if pair in seen:
            raise BlindReviewError("duplicate (case_id, reviewer_id) review")
        seen.add(pair)
        if label is not None and (not isinstance(label, int) or isinstance(label, bool) or label not in (0, 1, 2, 3)):
            raise BlindReviewError("label must be integer 0-3 or null")
        grouped[case_id].append(label)
    judgments: dict[str, int | None] = {}
    conflicts = 0
    for case_id, labels in grouped.items():
        known = {label for label in labels if label is not None}
        if len(known) > 1:
            conflicts += 1
            judgments[case_id] = None
        elif known:
            judgments[case_id] = next(iter(known))
        else:
            judgments[case_id] = None
    return case_map, judgments, conflicts


def _validate_rankings(key: dict[str, Any], rankings: dict[str, Any]):
    case_map = key["case_map"]
    need_candidates: dict[str, set[str]] = {}
    for row in case_map.values():
        need_candidates.setdefault(row["need_id"], set()).add(row["candidate_id"])
    mapped = {}
    for need_id, candidates in rankings.items():
        if need_id not in need_candidates:
            raise BlindReviewError(f"unknown need in rankings: {need_id}")
        if not isinstance(candidates, list):
            raise BlindReviewError("each ranking must be an array of candidate IDs")
        if any(not isinstance(candidate, str) for candidate in candidates):
            raise BlindReviewError("ranking candidate IDs must be strings")
        if len(candidates) != len(set(candidates)):
            raise BlindReviewError("duplicate ranking candidate")
        unknown = set(candidates) - need_candidates[need_id]
        if unknown:
            raise BlindReviewError(f"unknown candidate in ranking for {need_id}")
        mapped[need_id] = [next(case for case, row in case_map.items() if row["need_id"] == need_id and row["candidate_id"] == cid) for cid in candidates]
    return mapped


def _validate_eligible_pools(packet: dict[str, Any], key: dict[str, Any], eligible_pools: dict[str, Any] | None):
    if eligible_pools is None:
        return {}
    expected = {
        "packet_sha256": digest_json(packet),
        "dataset_sha256": packet["dataset_sha256"],
        "policy_sha256": key.get("policy_sha256"),
    }
    for field, value in expected.items():
        if eligible_pools.get(field) != value:
            raise BlindReviewError(f"eligible-pool {field} mismatch or stale input")
    if set(eligible_pools) != {*expected, "pools"} or not isinstance(eligible_pools.get("pools"), dict):
        raise BlindReviewError("eligible-pool input must contain only hashes and pools")
    case_map = key["case_map"]
    need_candidates: dict[str, set[str]] = {}
    for row in case_map.values():
        need_candidates.setdefault(row["need_id"], set()).add(row["candidate_id"])
    validated = {}
    for need_id, candidates in eligible_pools["pools"].items():
        if need_id not in need_candidates:
            raise BlindReviewError(f"unknown need in eligible pools: {need_id}")
        if not isinstance(candidates, list) or any(not isinstance(candidate, str) for candidate in candidates):
            raise BlindReviewError("eligible pool must be an array of candidate IDs")
        if len(candidates) != len(set(candidates)):
            raise BlindReviewError("duplicate eligible-pool candidate")
        if set(candidates) - need_candidates[need_id]:
            raise BlindReviewError(f"unknown candidate in eligible pool for {need_id}")
        validated[need_id] = [
            next(case for case, row in case_map.items() if row["need_id"] == need_id and row["candidate_id"] == cid)
            for cid in candidates
        ]
    return validated


def score_reviews(
    packet: dict[str, Any],
    key: dict[str, Any],
    reviews: dict[str, Any],
    rankings: dict[str, Any] | None = None,
    eligible_pools: dict[str, Any] | None = None,
):
    case_map, judgments, conflicts = _validate_reviews(packet, key, reviews)
    ranking_cases = _validate_rankings(key, rankings or {})
    pool_cases = _validate_eligible_pools(packet, key, eligible_pools)
    for need_id, ordered in ranking_cases.items():
        if need_id in pool_cases:
            eligible = set(pool_cases[need_id])
            if set(ordered) - eligible:
                raise BlindReviewError(f"ranking candidate is outside eligible pool for {need_id}")
    judged_count = sum(label is not None for label in judgments.values())
    unknown_count = len(judgments) - judged_count - conflicts
    metrics = []
    need_ids = sorted({row["need_id"] for row in case_map.values()})
    for need_id in need_ids:
        ordered = ranking_cases.get(need_id)
        if ordered is None:
            metrics.append({"need_id": need_id, "ranked_count": None, "unjudged_ranked_count": None, "p_at_3": None, "ndcg_at_5": None})
            continue
        unknown_ranked = sum(judgments[case] is None for case in ordered)
        first_three = ordered[:3]
        p_at_3 = None if any(judgments[case] is None for case in first_three) else sum(judgments[case] >= 2 for case in first_three) / 3
        eligible = pool_cases.get(need_id)
        if eligible_pools is None or eligible is None or any(judgments[case] is None for case in eligible):
            ndcg = None
        else:
            ranked_gains = [2 ** judgments[case] - 1 for case in ordered]
            dcg = sum(gain / math.log2(index + 2) for index, gain in enumerate(ranked_gains[:5]))
            eligible_gains = [2 ** judgments[case] - 1 for case in eligible]
            ideal = sorted(eligible_gains, reverse=True)[:5]
            idcg = sum(gain / math.log2(index + 2) for index, gain in enumerate(ideal))
            ndcg = dcg / idcg if idcg else None
        metrics.append({"need_id": need_id, "ranked_count": len(ordered), "unjudged_ranked_count": unknown_ranked, "p_at_3": p_at_3, "ndcg_at_5": ndcg})
    return {
        "format": "blind-review-report-v1",
        "status": "review_inputs_unverified",
        "claim_boundary": "Synthetic-profile review tooling output only; no claim of two human reviewers, human validity, hiring outcomes, or benchmark gold quality.",
        "packet_sha256": digest_json(packet),
        "dataset_sha256": packet["dataset_sha256"],
        "judged_count": judged_count,
        "unknown_count": unknown_count,
        "conflict_count": conflicts,
        "disagreements_unresolved": conflicts,
        "reviewer_ids": sorted({record["reviewer_id"] for record in reviews.get("records", [])}),
        "metrics": metrics,
    }


def _read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise BlindReviewError(f"cannot read JSON {path}: {exc}") from exc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    export = commands.add_parser("export", help="create blinded reviewer packet and private key")
    export.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    export.add_argument("--out-dir", type=Path, default=DEFAULT_OUT)
    export.add_argument("--seed", type=int, required=True)
    score = commands.add_parser("score", help="validate review submissions and compute review-only metrics")
    score.add_argument("--packet", type=Path, required=True)
    score.add_argument("--key", type=Path, required=True)
    score.add_argument("--reviews", type=Path, required=True)
    score.add_argument("--rankings", type=Path)
    score.add_argument("--eligible-pools", type=Path)
    score.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "export":
            raw = args.dataset.read_bytes()
            dataset = json.loads(raw)
            export_dataset(dataset, args.out_dir, args.seed, hashlib.sha256(raw).hexdigest())
        else:
            output = _ensure_local_output(args.out)
            packet, key, reviews = (_read_json(args.packet), _read_json(args.key), _read_json(args.reviews))
            rankings = _read_json(args.rankings) if args.rankings else {}
            eligible_pools = _read_json(args.eligible_pools) if args.eligible_pools else None
            report = score_reviews(packet, key, reviews, rankings, eligible_pools)
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return 0
    except (BlindReviewError, OSError, json.JSONDecodeError) as exc:
        print(f"blind-review: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
