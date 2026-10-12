#!/usr/bin/env python3
"""Validate draft assessment banks and prepare blind form-review artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import sys
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BANK = REPO_ROOT / "local/assessment-pilot-v2/bank.json"
DEFAULT_OUT = REPO_ROOT / "local/assessment-pilot-v2/review"
EXPECTED_STATUS = "draft_ai_authored_pending_human_review"
EXPECTED_BANK_VERSION = "backend-python-v2.0-draft"
EXPECTED_SPECIALIZATION = "backend-python-v1"
GRADES = ("junior", "middle")
COMPETENCY_IDS = ("python-basics", "http-api", "sql-transactions", "reliability")
CONTENT_REVIEW_AXES = ("job_relevance", "grade_alignment", "unambiguous", "accessible_wording")


class AssessmentReviewError(ValueError):
    """Invalid bank, form, review, or output path."""


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def digest_json(value: Any) -> str:
    return hashlib.sha256(canonical_json(value)).hexdigest()


def _nonempty_string(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _require_string(value: Any, description: str) -> str:
    if not _nonempty_string(value):
        raise AssessmentReviewError(f"{description} must be a non-empty string")
    return value


def _unique_id(value: Any, description: str, seen: set[str]) -> str:
    identifier = _require_string(value, f"{description} ID")
    if identifier in seen:
        raise AssessmentReviewError(f"duplicate ID: {identifier}")
    seen.add(identifier)
    return identifier


def _exact_fields(value: Any, fields: set[str], description: str) -> None:
    if not isinstance(value, dict) or set(value) != fields:
        raise AssessmentReviewError(f"{description} has missing or unknown fields")


def _normalized_text(value: str) -> str:
    return " ".join(value.split()).casefold()


def validate_bank(bank: dict[str, Any]) -> None:
    """Validate structure and minimum junior/middle coverage for review use."""
    if not isinstance(bank, dict):
        raise AssessmentReviewError("bank must be an object")
    bank_required = {"bank_version", "status", "specialization_id", "families"}
    bank_allowed = bank_required | {"languages", "draft_revision", "authorship"}
    if not bank_required <= set(bank) or set(bank) - bank_allowed:
        raise AssessmentReviewError("bank root has missing or unknown fields")
    if bank.get("bank_version") != EXPECTED_BANK_VERSION:
        raise AssessmentReviewError(f"bank_version must be {EXPECTED_BANK_VERSION}")
    if bank.get("status") != EXPECTED_STATUS:
        raise AssessmentReviewError(f"status must be {EXPECTED_STATUS}")
    if bank.get("specialization_id") != EXPECTED_SPECIALIZATION:
        raise AssessmentReviewError(f"specialization_id must be {EXPECTED_SPECIALIZATION}")
    families = bank.get("families")
    if not isinstance(families, list):
        raise AssessmentReviewError("families must be an array")

    seen: set[str] = set()
    family_counts = {(grade, competency): 0 for grade in GRADES for competency in COMPETENCY_IDS}
    for family in families:
        _exact_fields(family, {"id", "grade", "competency_id", "version", "job_task_id", "job_task_description", "source_refs", "variants"}, "family")
        if not isinstance(family, dict):
            raise AssessmentReviewError("family must be an object")
        family_id = _unique_id(family.get("id"), "family", seen)
        del family_id
        grade = family.get("grade")
        if grade not in GRADES:
            raise AssessmentReviewError("family grade must be junior or middle")
        competency_id = family.get("competency_id")
        if competency_id not in COMPETENCY_IDS:
            raise AssessmentReviewError("competency_id must be one of the four assessment blocks")
        family_counts[(grade, competency_id)] += 1
        version = family.get("version")
        if not isinstance(version, (str, int)) or isinstance(version, bool) or not str(version).strip():
            raise AssessmentReviewError("family version must be a non-empty string or integer")
        _require_string(family.get("job_task_id"), "job_task_id")
        _require_string(family.get("job_task_description"), "job_task_description")

        refs = family.get("source_refs")
        if not isinstance(refs, list) or not refs:
            raise AssessmentReviewError("each family must have source_refs")
        for ref in refs:
            _exact_fields(ref, {"title", "url", "section"}, "source reference")
            if not isinstance(ref, dict):
                raise AssessmentReviewError("source reference must be an object")
            _require_string(ref.get("title"), "source title")
            url = _require_string(ref.get("url"), "source URL")
            if not url.startswith(("https://", "http://")):
                raise AssessmentReviewError("source URL must use HTTP or HTTPS")
            _require_string(ref.get("section"), "source section")

        variants = family.get("variants")
        if not isinstance(variants, list) or len(variants) != 2:
            raise AssessmentReviewError(f"family {family.get('id')} must have exactly two variants")
        for variant in variants:
            _exact_fields(variant, {"id", "prompt", "subquestions"}, "variant")
            if not isinstance(variant, dict):
                raise AssessmentReviewError("variant must be an object")
            _unique_id(variant.get("id"), "variant", seen)
            _require_string(variant.get("prompt"), "variant prompt")
            questions = variant.get("subquestions")
            if not isinstance(questions, list) or len(questions) != 2:
                raise AssessmentReviewError("each variant must have exactly two subquestions")
            for question in questions:
                _exact_fields(question, {"id", "prompt", "options", "correct_option_id", "explanation"}, "subquestion")
                if not isinstance(question, dict):
                    raise AssessmentReviewError("subquestion must be an object")
                _unique_id(question.get("id"), "subquestion", seen)
                _require_string(question.get("prompt"), "subquestion prompt")
                options = question.get("options")
                if not isinstance(options, list) or len(options) != 4:
                    raise AssessmentReviewError("each subquestion must have exactly four options")
                option_ids = set()
                option_texts = set()
                for option in options:
                    _exact_fields(option, {"id", "text"}, "option")
                    if not isinstance(option, dict):
                        raise AssessmentReviewError("option must be an object")
                    option_id = _require_string(option.get("id"), "option ID")
                    if option_id in option_ids:
                        raise AssessmentReviewError(f"duplicate option ID in subquestion {question['id']}")
                    option_ids.add(option_id)
                    option_text = _require_string(option.get("text"), "option text")
                    normalized_text = _normalized_text(option_text)
                    if normalized_text in option_texts:
                        raise AssessmentReviewError(f"duplicate option text in subquestion {question['id']}")
                    option_texts.add(normalized_text)
                correct_id = question.get("correct_option_id")
                if not _nonempty_string(correct_id) or correct_id not in option_ids:
                    raise AssessmentReviewError(f"subquestion {question['id']} has an invalid correct_option_id")
                _require_string(question.get("explanation"), "answer explanation")
    if "languages" in bank:
        languages = bank["languages"]
        if not isinstance(languages, list) or not languages or any(language != "ru" for language in languages):
            raise AssessmentReviewError("languages must be a non-empty array containing only ru")
    if "draft_revision" in bank:
        revision = bank["draft_revision"]
        if not isinstance(revision, int) or isinstance(revision, bool) or revision <= 0:
            raise AssessmentReviewError("draft_revision must be a positive integer")
    if "authorship" in bank:
        _require_string(bank["authorship"], "authorship")
    if not families:
        raise AssessmentReviewError("bank must contain families")
    missing = [f"{grade}/{competency}" for (grade, competency), count in family_counts.items() if count < 8]
    if missing:
        raise AssessmentReviewError("coverage requires at least 8 families per grade and competency block: " + ", ".join(missing))


def build_forms(bank: dict[str, Any], seed: int) -> tuple[dict[str, Any], dict[str, Any]]:
    """Build four deterministic solver-blind forms and their separate private keys."""
    validate_bank(bank)
    if not isinstance(seed, int) or isinstance(seed, bool):
        raise AssessmentReviewError("seed must be an integer")
    rng = random.Random(seed)
    bank_sha256 = digest_json(bank)
    forms = []
    key_rows = []

    for grade in GRADES:
        selected = {"A": [], "B": []}
        for competency_id in COMPETENCY_IDS:
            eligible = [family for family in bank["families"] if family["grade"] == grade and family["competency_id"] == competency_id]
            rng.shuffle(eligible)
            selected["A"].extend(eligible[:3])
            selected["B"].extend(eligible[3:6])
        for form_suffix in ("A", "B"):
            tasks = []
            for family in selected[form_suffix]:
                variant = rng.choice(family["variants"])
                task = {
                    "task_id": variant["id"],
                    "family_id": family["id"],
                    "competency_id": family["competency_id"],
                    "prompt": variant["prompt"],
                    "subquestions": [],
                }
                for question in variant["subquestions"]:
                    options = list(question["options"])
                    rng.shuffle(options)
                    task["subquestions"].append({
                        "id": question["id"],
                        "prompt": question["prompt"],
                        "options": [{"id": option["id"], "text": option["text"]} for option in options],
                    })
                    key_rows.append({
                        "form_id": f"{grade}-{form_suffix}",
                        "task_id": variant["id"],
                        "competency_id": family["competency_id"],
                        "subquestion_id": question["id"],
                        "correct_option_id": question["correct_option_id"],
                    })
                tasks.append(task)
            forms.append({"form_id": f"{grade}-{form_suffix}", "grade": grade, "tasks": tasks})

    public_forms = {
        "format": "assessment-study-forms-v1",
        "bank_version": bank["bank_version"],
        "bank_sha256": bank_sha256,
        "seed": seed,
        "status": EXPECTED_STATUS,
        "forms": forms,
    }
    private_keys = {
        "format": "assessment-study-keys-v1",
        "forms_sha256": digest_json(public_forms),
        "bank_sha256": bank_sha256,
        "keys": key_rows,
    }
    return public_forms, private_keys


def build_review_packet(bank: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    """Build a complete solver-blind packet for independent content review."""
    validate_bank(bank)
    bank_sha256 = digest_json(bank)
    items = []
    answers = []
    for family in bank["families"]:
        for variant in family["variants"]:
            item = {
                "family_id": family["id"],
                "grade": family["grade"],
                "competency_id": family["competency_id"],
                "version": family["version"],
                "job_task_id": family["job_task_id"],
                "job_task_description": family["job_task_description"],
                "source_refs": family["source_refs"],
                "variant_id": variant["id"],
                "prompt": variant["prompt"],
                "subquestions": [],
            }
            for question in variant["subquestions"]:
                item["subquestions"].append({
                    "id": question["id"],
                    "prompt": question["prompt"],
                    "options": [{"id": option["id"], "text": option["text"]} for option in question["options"]],
                })
                answers.append({
                    "family_id": family["id"],
                    "variant_id": variant["id"],
                    "subquestion_id": question["id"],
                    "correct_option_id": question["correct_option_id"],
                    "explanation": question["explanation"],
                })
            items.append(item)
    packet = {
        "format": "assessment-expert-review-packet-v1",
        "bank_version": bank["bank_version"],
        "bank_sha256": bank_sha256,
        "status": EXPECTED_STATUS,
        "items": items,
    }
    packet_sha256 = digest_json(packet)
    answer_pack = {
        "format": "assessment-expert-review-answerpack-v1",
        "bank_sha256": bank_sha256,
        "packet_sha256": packet_sha256,
        "answers": answers,
    }
    template = {
        "format": "assessment-expert-reviews-v1",
        "packet_sha256": packet_sha256,
        "bank_sha256": bank_sha256,
        "records": [],
        "resolutions": [],
        "record_template": {
            "packet_sha256": packet_sha256,
            "bank_sha256": bank_sha256,
            "family_id": "",
            "variant_id": "",
            "subquestion_id": "",
            "reviewer_id": "",
            "selected_option_id": "",
            "rationale": "",
            "job_relevance": None,
            "grade_alignment": None,
            "unambiguous": None,
            "accessible_wording": None,
        },
    }
    return packet, answer_pack, template


def _ensure_local_output(path: Path) -> Path:
    resolved = Path(path).expanduser().resolve()
    local_root = (REPO_ROOT / "local").resolve()
    if resolved == local_root or local_root not in resolved.parents:
        raise AssessmentReviewError(f"output must be a new directory inside {local_root}")
    return resolved


def export_review_artifacts(bank: dict[str, Any], out_dir: Path, seed: int) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    """Write public forms, separate private keys, and an empty review template."""
    output = _ensure_local_output(Path(out_dir))
    forms, keys = build_forms(bank, seed)
    if output.exists():
        raise AssessmentReviewError(f"refusing to overwrite existing output directory: {output}")
    template = {
        "format": "assessment-study-reviews-v1",
        "forms_sha256": digest_json(forms),
        "bank_sha256": forms["bank_sha256"],
        "records": [],
        "resolutions": [],
        "record_template": {
            "form_id": "", "task_id": "", "subquestion_id": "", "reviewer_id": "",
            "selected_option_id": "", "rationale": "",
        },
    }
    output.mkdir(parents=True, exist_ok=False)
    for filename, value in (("forms.json", forms), ("private_keys.json", keys), ("review_template.json", template)):
        (output / filename).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return forms, keys, template


def export_expert_review_artifacts(bank: dict[str, Any], out_dir: Path) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    """Write a complete independent-review packet, private answer pack, and template."""
    output = _ensure_local_output(Path(out_dir))
    packet, answer_pack, template = build_review_packet(bank)
    if output.exists():
        raise AssessmentReviewError(f"refusing to overwrite existing output directory: {output}")
    output.mkdir(parents=True, exist_ok=False)
    for filename, value in (("expert_packet.json", packet), ("expert_answer_pack.json", answer_pack), ("expert_review_template.json", template)):
        (output / filename).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return packet, answer_pack, template


def _form_index(forms: dict[str, Any]) -> tuple[dict[str, dict[str, Any]], dict[tuple[str, str, str], dict[str, Any]]]:
    form_ids: dict[str, dict[str, Any]] = {}
    questions: dict[tuple[str, str, str], dict[str, Any]] = {}
    for form in forms.get("forms", []):
        form_id = form.get("form_id")
        if not _nonempty_string(form_id) or form_id in form_ids:
            raise AssessmentReviewError("forms contain a missing or duplicate form_id")
        form_ids[form_id] = form
        for task in form.get("tasks", []):
            for question in task.get("subquestions", []):
                key = (form_id, task.get("task_id"), question.get("id"))
                if not all(_nonempty_string(part) for part in key) or key in questions:
                    raise AssessmentReviewError("forms contain a missing or duplicate task/question ID")
                questions[key] = question
    return form_ids, questions


def _validate_forms_payload(forms: dict[str, Any]) -> None:
    _exact_fields(forms, {"format", "bank_version", "bank_sha256", "seed", "status", "forms"}, "public forms root")
    if forms.get("format") != "assessment-study-forms-v1" or forms.get("status") != EXPECTED_STATUS:
        raise AssessmentReviewError("public forms format/status is invalid")
    _require_string(forms.get("bank_version"), "bank_version")
    _require_string(forms.get("bank_sha256"), "bank_sha256")
    if not isinstance(forms.get("seed"), int) or isinstance(forms.get("seed"), bool):
        raise AssessmentReviewError("forms seed must be an integer")
    form_rows = forms.get("forms")
    if not isinstance(form_rows, list):
        raise AssessmentReviewError("forms must be an array")
    expected_forms = {f"{grade}-{suffix}" for grade in GRADES for suffix in ("A", "B")}
    seen_forms = set()
    families_by_form = {}
    for form in form_rows:
        _exact_fields(form, {"form_id", "grade", "tasks"}, "form")
        form_id = _require_string(form.get("form_id"), "form_id")
        grade = form.get("grade")
        if grade not in GRADES or form_id != f"{grade}-{form_id[-1]}" or form_id[-1:] not in ("A", "B") or form_id in seen_forms:
            raise AssessmentReviewError("form ID/grade is invalid or duplicated")
        seen_forms.add(form_id)
        tasks = form.get("tasks")
        if not isinstance(tasks, list) or len(tasks) != 12:
            raise AssessmentReviewError("each form must contain 12 tasks")
        families = set()
        competency_counts = {competency: 0 for competency in COMPETENCY_IDS}
        for task in tasks:
            _exact_fields(task, {"task_id", "family_id", "competency_id", "prompt", "subquestions"}, "form task")
            _require_string(task.get("task_id"), "task_id")
            families.add(_require_string(task.get("family_id"), "family_id"))
            competency = task.get("competency_id")
            if competency not in COMPETENCY_IDS:
                raise AssessmentReviewError("form task has an unknown competency_id")
            competency_counts[competency] += 1
            _require_string(task.get("prompt"), "task prompt")
            questions = task.get("subquestions")
            if not isinstance(questions, list) or len(questions) != 2:
                raise AssessmentReviewError("each form task must contain two subquestions")
            for question in questions:
                _exact_fields(question, {"id", "prompt", "options"}, "form subquestion")
                _require_string(question.get("id"), "subquestion ID")
                _require_string(question.get("prompt"), "subquestion prompt")
                options = question.get("options")
                if not isinstance(options, list) or len(options) != 4:
                    raise AssessmentReviewError("each form subquestion must contain four options")
                option_ids = set()
                for option in options:
                    _exact_fields(option, {"id", "text"}, "form option")
                    option_id = _require_string(option.get("id"), "option ID")
                    if option_id in option_ids:
                        raise AssessmentReviewError("form subquestion has duplicate option IDs")
                    option_ids.add(option_id)
                    _require_string(option.get("text"), "option text")
        if len(families) != 12 or any(count != 3 for count in competency_counts.values()):
            raise AssessmentReviewError("each form must have three tasks per competency and unique families")
        families_by_form[form_id] = families
    if seen_forms != expected_forms:
        raise AssessmentReviewError("public forms must contain junior/middle A and B forms")
    for grade in GRADES:
        if families_by_form[f"{grade}-A"] & families_by_form[f"{grade}-B"]:
            raise AssessmentReviewError("A/B forms must not share a family")


def _validate_expert_packet(packet: dict[str, Any]) -> None:
    _exact_fields(packet, {"format", "bank_version", "bank_sha256", "status", "items"}, "expert packet root")
    if packet.get("format") != "assessment-expert-review-packet-v1" or packet.get("status") != EXPECTED_STATUS:
        raise AssessmentReviewError("expert packet format/status is invalid")
    _require_string(packet.get("bank_version"), "bank_version")
    _require_string(packet.get("bank_sha256"), "bank_sha256")
    items = packet.get("items")
    if not isinstance(items, list) or not items:
        raise AssessmentReviewError("expert packet items must be a non-empty array")
    item_ids = set()
    question_ids = set()
    for item in items:
        _exact_fields(item, {"family_id", "grade", "competency_id", "version", "job_task_id", "job_task_description", "source_refs", "variant_id", "prompt", "subquestions"}, "expert packet item")
        family_id = _require_string(item.get("family_id"), "family_id")
        variant_id = _require_string(item.get("variant_id"), "variant_id")
        item_key = (family_id, variant_id)
        if item_key in item_ids:
            raise AssessmentReviewError("expert packet has duplicate family/variant IDs")
        item_ids.add(item_key)
        if item.get("grade") not in GRADES or item.get("competency_id") not in COMPETENCY_IDS:
            raise AssessmentReviewError("expert packet has an unknown grade or competency")
        _require_string(item.get("job_task_id"), "job_task_id")
        _require_string(item.get("job_task_description"), "job_task_description")
        _require_string(item.get("prompt"), "variant prompt")
        refs = item.get("source_refs")
        if not isinstance(refs, list) or not refs:
            raise AssessmentReviewError("expert packet item must include source references")
        for ref in refs:
            _exact_fields(ref, {"title", "url", "section"}, "expert packet source reference")
            for field in ("title", "url", "section"):
                _require_string(ref.get(field), f"source {field}")
        questions = item.get("subquestions")
        if not isinstance(questions, list) or len(questions) != 2:
            raise AssessmentReviewError("expert packet item must contain two subquestions")
        for question in questions:
            _exact_fields(question, {"id", "prompt", "options"}, "expert packet subquestion")
            question_id = _require_string(question.get("id"), "subquestion ID")
            if question_id in question_ids:
                raise AssessmentReviewError("expert packet has duplicate subquestion IDs")
            question_ids.add(question_id)
            _require_string(question.get("prompt"), "subquestion prompt")
            options = question.get("options")
            if not isinstance(options, list) or len(options) != 4:
                raise AssessmentReviewError("expert packet subquestion must contain four options")
            option_ids = set()
            option_texts = set()
            for option in options:
                _exact_fields(option, {"id", "text"}, "expert packet option")
                option_id = _require_string(option.get("id"), "option ID")
                if option_id in option_ids:
                    raise AssessmentReviewError("expert packet subquestion has duplicate option IDs")
                option_ids.add(option_id)
                text = _require_string(option.get("text"), "option text")
                normalized = _normalized_text(text)
                if normalized in option_texts:
                    raise AssessmentReviewError("expert packet subquestion has duplicate option text")
                option_texts.add(normalized)


def import_reviews(forms: dict[str, Any], keys: dict[str, Any], reviews: dict[str, Any]) -> dict[str, Any]:
    """Validate submissions and summarize coverage without inferring human identity."""
    if not isinstance(forms, dict) or not isinstance(keys, dict) or not isinstance(reviews, dict):
        raise AssessmentReviewError("packet, private key, and review input must be objects")
    packet_format = forms.get("format")
    if not isinstance(packet_format, str):
        raise AssessmentReviewError("public packet format must be a string")
    if packet_format not in {"assessment-expert-review-packet-v1", "assessment-study-forms-v1"}:
        raise AssessmentReviewError("unsupported public packet format")
    full_packet = packet_format == "assessment-expert-review-packet-v1"
    if full_packet:
        _validate_expert_packet(forms)
    else:
        _validate_forms_payload(forms)
    packet_hash = digest_json(forms)
    bank_hash = forms.get("bank_sha256")
    digest_field = "packet_sha256" if full_packet else "forms_sha256"
    expected_key_format = "assessment-expert-review-answerpack-v1" if full_packet else "assessment-study-keys-v1"
    expected_review_format = "assessment-expert-reviews-v1" if full_packet else "assessment-study-reviews-v1"
    if keys.get("format") != expected_key_format or keys.get(digest_field) != packet_hash:
        raise AssessmentReviewError("private key packet/forms hash mismatch or stale input")
    if keys.get("bank_sha256") != bank_hash:
        raise AssessmentReviewError("private key bank hash mismatch or stale input")
    if reviews.get("format") != expected_review_format or reviews.get(digest_field) != packet_hash:
        raise AssessmentReviewError("review packet/forms hash mismatch or stale input")
    if reviews.get("bank_sha256") != bank_hash:
        raise AssessmentReviewError("review bank hash mismatch or stale input")
    allowed_key_fields = {"format", "packet_sha256", "bank_sha256", "answers"} if full_packet else {"format", "forms_sha256", "bank_sha256", "keys"}
    allowed_review_fields = {"format", "packet_sha256", "bank_sha256", "records", "resolutions", "record_template"} if full_packet else {"format", "forms_sha256", "bank_sha256", "records", "resolutions", "record_template"}
    if set(keys) != allowed_key_fields:
        raise AssessmentReviewError("private key contains unexpected fields")
    if set(reviews) - allowed_review_fields:
        raise AssessmentReviewError("review import contains unexpected fields")

    if full_packet:
        questions = {}
        for item in forms.get("items", []):
            for question in item.get("subquestions", []):
                qkey = (item.get("family_id"), item.get("variant_id"), question.get("id"))
                if not all(_nonempty_string(part) for part in qkey) or qkey in questions:
                    raise AssessmentReviewError("packet contains missing or duplicate family/variant/question IDs")
                questions[qkey] = question
        key_rows = keys.get("answers")
        if not isinstance(key_rows, list):
            raise AssessmentReviewError("private answers must be an array")
        key_id_fields = ("family_id", "variant_id", "subquestion_id")
        answer_field = "correct_option_id"
        record_fields = {"packet_sha256", "bank_sha256", "family_id", "variant_id", "subquestion_id", "reviewer_id", "selected_option_id", "rationale", *CONTENT_REVIEW_AXES}
        resolution_fields = {"packet_sha256", "bank_sha256", "family_id", "variant_id", "subquestion_id", "selected_option_id", "resolved_by", "rationale"}
    else:
        _, indexed = _form_index(forms)
        questions = {(form_id, task_id, question_id): question for (form_id, task_id, question_id), question in indexed.items()}
        key_rows = keys.get("keys")
        if not isinstance(key_rows, list):
            raise AssessmentReviewError("private keys must be an array")
        key_id_fields = ("form_id", "task_id", "subquestion_id")
        answer_field = "correct_option_id"
        record_fields = {"form_id", "task_id", "subquestion_id", "reviewer_id", "selected_option_id", "rationale"}
        resolution_fields = {"form_id", "task_id", "subquestion_id", "selected_option_id", "resolved_by", "rationale"}
    key_by_question = {}
    for row in key_rows:
        expected_fields = {*key_id_fields, answer_field} | ({"competency_id"} if not full_packet else {"explanation"})
        if not isinstance(row, dict) or set(row) != expected_fields:
            raise AssessmentReviewError("private answer row has an invalid shape")
        qkey = tuple(_require_string(row[field], f"private key {field}") for field in key_id_fields)
        if qkey in key_by_question or qkey not in questions:
            raise AssessmentReviewError("private key has duplicate or unknown question IDs")
        if full_packet:
            _require_string(row["explanation"], "private answer explanation")
        else:
            _require_string(row["competency_id"], "private key competency_id")
        option_ids = {option["id"] for option in questions[qkey]["options"]}
        correct_option_id = _require_string(row[answer_field], f"private answer {answer_field}")
        if correct_option_id not in option_ids:
            raise AssessmentReviewError("private key answer does not match public option IDs")
        key_by_question[qkey] = row
    if set(key_by_question) != set(questions):
        raise AssessmentReviewError("private key is missing question keys")

    records = reviews.get("records", [])
    resolutions = reviews.get("resolutions", [])
    if not isinstance(records, list) or not isinstance(resolutions, list):
        raise AssessmentReviewError("records and resolutions must be arrays")
    seen = set()
    selections: dict[tuple[str, str, str], list[str]] = {qkey: [] for qkey in questions}
    reviewers_by_question: dict[tuple[str, str, str], set[str]] = {qkey: set() for qkey in questions}
    reviewer_ids = set()
    for record in records:
        if not isinstance(record, dict) or set(record) != record_fields:
            raise AssessmentReviewError("review record has an invalid shape")
        if full_packet and (record["packet_sha256"] != packet_hash or record["bank_sha256"] != bank_hash):
            raise AssessmentReviewError("review record packet/bank digest mismatch")
        qkey = tuple(_require_string(record[field], f"review {field}") for field in key_id_fields)
        if qkey not in questions:
            raise AssessmentReviewError("review contains an unknown packet/form, task, or question ID")
        reviewer = _require_string(record["reviewer_id"], "reviewer_id")
        reviewer_key = reviewer.strip() if full_packet else reviewer
        if not reviewer_key:
            raise AssessmentReviewError("reviewer_id must be a non-empty string")
        option = _require_string(record["selected_option_id"], "selected_option_id")
        option_ids = {item["id"] for item in questions[qkey]["options"]}
        if option not in option_ids:
            raise AssessmentReviewError("selected_option_id is unknown")
        _require_string(record["rationale"], "review rationale")
        if full_packet:
            for axis in CONTENT_REVIEW_AXES:
                value = record[axis]
                if value is not None and type(value) is not bool:
                    raise AssessmentReviewError(f"content axis {axis} must be boolean or null")
        pair = (*qkey, reviewer_key)
        if pair in seen:
            raise AssessmentReviewError("duplicate question/reviewer review")
        seen.add(pair)
        reviewer_ids.add(reviewer_key)
        reviewers_by_question[qkey].add(reviewer_key)
        selections[qkey].append(option)

    resolution_map = {}
    for resolution in resolutions:
        if not isinstance(resolution, dict) or set(resolution) != resolution_fields:
            raise AssessmentReviewError("resolution has an invalid shape")
        if full_packet and (resolution["packet_sha256"] != packet_hash or resolution["bank_sha256"] != bank_hash):
            raise AssessmentReviewError("resolution packet/bank digest mismatch")
        qkey = tuple(_require_string(resolution[field], f"resolution {field}") for field in key_id_fields)
        if qkey not in questions or qkey in resolution_map:
            raise AssessmentReviewError("resolution has an unknown or duplicate question ID")
        selected_option_id = _require_string(resolution["selected_option_id"], "resolution selected_option_id")
        if selected_option_id not in {item["id"] for item in questions[qkey]["options"]}:
            raise AssessmentReviewError("resolution selected_option_id is unknown")
        resolver = _require_string(resolution["resolved_by"], "resolution resolver")
        if not resolver.strip():
            raise AssessmentReviewError("resolution resolver must be a non-empty string")
        _require_string(resolution["rationale"], "resolution rationale")
        resolution_map[qkey] = resolution

    disagreements = {qkey for qkey, answers in selections.items() if len(set(answers)) > 1}
    unresolved = disagreements - set(resolution_map)
    resolved = disagreements & set(resolution_map)
    completed_questions = sum(bool(answers) for answers in selections.values())
    questions_reviewed_by_two_or_more = sum(len(reviewers) >= 2 for reviewers in reviewers_by_question.values())
    missing_reviewer_slots = sum(max(0, 2 - len(reviewers)) for reviewers in reviewers_by_question.values())
    content_review_coverage = {}
    if full_packet:
        for axis in CONTENT_REVIEW_AXES:
            positive = sum(record[axis] is True for record in records)
            negative = sum(record[axis] is False for record in records)
            missing = sum(record[axis] is None for record in records) + missing_reviewer_slots
            content_review_coverage[axis] = {
                "reviewed": positive,
                "submitted": positive + negative,
                "missing": missing,
                "negative": negative,
                "complete": missing == 0 and negative == 0 and completed_questions == len(questions) and questions_reviewed_by_two_or_more == len(questions),
            }
    content_review_complete = full_packet and all(axis["complete"] for axis in content_review_coverage.values())
    return {
        "format": "assessment-study-review-report-v1",
        digest_field: packet_hash,
        "bank_sha256": bank_hash,
        "status": "human_review_evidence_incomplete" if unresolved or completed_questions < len(questions) or (full_packet and not content_review_complete) else "human_review_submissions_complete_identity_unverified",
        "question_count": len(questions),
        "technical_key_coverage": {
            "keyed_questions": len(key_by_question),
            "total_questions": len(questions),
            "complete": len(key_by_question) == len(questions),
        },
        "questions_with_reviews": completed_questions,
        "questions_without_reviews": len(questions) - completed_questions,
        "record_count": len(records),
        "reviewer_ids_count": len(reviewer_ids),
        "two_human_reviewers_verified": None,
        "human_identity_verified": None,
        "reviewer_independence_verified": None,
        "assessment_release_authorized": False,
        "disagreements_unresolved": len(unresolved),
        "disagreements_resolved_explicitly": len(resolved),
        "unresolved_question_ids": [list(qkey) for qkey in sorted(unresolved)],
        **({
            "required_reviewers_per_question": 2,
            "questions_reviewed_by_two_or_more": questions_reviewed_by_two_or_more,
            "content_review_coverage": content_review_coverage,
            "content_review_complete": content_review_complete,
        } if full_packet else {}),
    }


def _read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise AssessmentReviewError(f"cannot read JSON file {path}: {exc}") from exc


def _write_json(path: Path, value: Any) -> None:
    content = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("x", encoding="utf-8") as stream:
            stream.write(content)
    except FileExistsError as exc:
        raise AssessmentReviewError(f"refusing to overwrite existing output file: {path}") from exc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    export = subparsers.add_parser("export", help="validate a draft bank and export blind forms")
    export.add_argument("--bank", type=Path, default=DEFAULT_BANK)
    export.add_argument("--out-dir", type=Path, default=DEFAULT_OUT)
    export.add_argument("--seed", type=int, required=True)
    expert = subparsers.add_parser("expert-export", help="export all bank items for independent content review")
    expert.add_argument("--bank", type=Path, default=DEFAULT_BANK)
    expert.add_argument("--out-dir", type=Path, default=REPO_ROOT / "local/assessment-pilot-v2/expert-review")
    imported = subparsers.add_parser("import-reviews", help="validate submitted reviews")
    imported.add_argument("--forms", type=Path, required=True)
    imported.add_argument("--keys", type=Path, required=True)
    imported.add_argument("--reviews", type=Path, required=True)
    imported.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "export":
            export_review_artifacts(_read_json(args.bank), args.out_dir, args.seed)
        elif args.command == "expert-export":
            export_expert_review_artifacts(_read_json(args.bank), args.out_dir)
        else:
            _ensure_local_output(args.out)
            report = import_reviews(_read_json(args.forms), _read_json(args.keys), _read_json(args.reviews))
            _write_json(args.out, report)
    except AssessmentReviewError as exc:
        print(f"assessment_review: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
