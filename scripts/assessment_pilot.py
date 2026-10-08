#!/usr/bin/env python3
"""Prepare local assessment study slots and analyze explicitly bound response files.

This tool is an offline research aid. It does not access a database or network,
verify people, validate a qualification, or write a grade to the application.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import random
import re
import tempfile


REPO_ROOT = Path(__file__).resolve().parents[1]
LOCAL_ROOT = REPO_ROOT / "local"
FORM_IDS = {f"{grade}-{variant}" for grade in ("junior", "middle") for variant in ("A", "B")}
COMPETENCY_IDS = {"python-basics", "http-api", "sql-transactions", "reliability"}
ANSWER_STATES = {"unknown", "missing"}
RESPONSE_SOURCES = {"volunteer_response", "synthetic_fixture"}
RESPONSE_STATUSES = {"completed", "refused", "technical_invalidated", "missing"}
PILOT_LIMITATIONS = [
    "draft_ai_authored_pending_human_review",
    "form_difficulty_is_not_calibrated",
    "synthetic_fixture_selection_is_not_empirical_evidence",
    "volunteer_source_is_self_declared_and_identity_unverified",
    "experimental_threshold_is_a_research_probe_not_a_qualification_rule",
    "scores_never_award_or_confirm_a_grade",
    "administration_duration_is_observed_metadata_not_a_timer_or_1800_second_enforcement",
    "timing_inputs_are_not_independently_verified",
    "operator_timing_capture_procedure_is_pending",
]
ANSWER_STATE_MEANING = {
    "unknown": "participant_explicitly_does_not_know",
    "missing": "intentionally_unanswered_item_at_completed_submission",
    "scoring": "both answer states score zero only under the experimental study scoring rule",
    "measurement_failure": "neither answer state means unknown or lost measurement; whole-session failures use missing or technical_invalidated status",
}


class AssessmentPilotError(ValueError):
    """Raised when an input violates the local pilot data contract."""


def canonical_json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def sha256_json(value):
    return hashlib.sha256(canonical_json(value)).hexdigest()


def _object(value, where, allowed=None, required=None):
    if not isinstance(value, dict):
        raise AssessmentPilotError(f"{where} must be an object")
    if allowed is not None:
        extra = set(value) - set(allowed)
        missing = set(required or ()) - set(value)
        if extra:
            raise AssessmentPilotError(f"{where} has unknown fields")
        if missing:
            raise AssessmentPilotError(f"{where} is missing required fields")
    return value


def _text(value, where):
    if not isinstance(value, str) or not value.strip():
        raise AssessmentPilotError(f"{where} must be a non-empty string")
    return value


def _sha(value, where):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
        raise AssessmentPilotError(f"{where} must be a lowercase SHA-256 digest")
    return value


def _parse_timestamp(value, where):
    if not isinstance(value, str) or not value.strip():
        raise AssessmentPilotError(f"{where} must be a timezone-aware ISO timestamp")
    iso_value = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed = datetime.fromisoformat(iso_value)
    except ValueError as exc:
        raise AssessmentPilotError(f"{where} must be a timezone-aware ISO timestamp") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise AssessmentPilotError(f"{where} must include a timezone offset")
    normalized = parsed.astimezone(timezone.utc)
    return normalized, normalized.isoformat().replace("+00:00", "Z")


def _response_timing(response):
    has_start = "started_at" in response
    has_finish = "finished_at" in response
    if has_start != has_finish:
        raise AssessmentPilotError("started_at and finished_at must be supplied together")
    if not has_start:
        return None
    started, normalized_start = _parse_timestamp(response["started_at"], "started_at")
    finished, normalized_finish = _parse_timestamp(response["finished_at"], "finished_at")
    duration = (finished - started).total_seconds()
    if duration < 0:
        raise AssessmentPilotError("finished_at must not precede started_at")
    return {
        "started_at": normalized_start,
        "finished_at": normalized_finish,
        "duration_seconds": duration,
        "started_datetime": started,
        "finished_datetime": finished,
    }


def validate_bundle(forms, private_keys):
    _object(forms, "forms", {"format", "bank_version", "bank_sha256", "seed", "status", "forms"},
            {"format", "bank_version", "bank_sha256", "seed", "status", "forms"})
    if forms["format"] != "assessment-study-forms-v1":
        raise AssessmentPilotError("Unsupported form format")
    if forms["status"] != "draft_ai_authored_pending_human_review":
        raise AssessmentPilotError("Pilot forms must remain marked as pending human review")
    _text(forms["bank_version"], "bank_version")
    _sha(forms["bank_sha256"], "forms.bank_sha256")
    if not isinstance(forms["seed"], int) or isinstance(forms["seed"], bool):
        raise AssessmentPilotError("forms.seed must be an integer")
    if not isinstance(forms["forms"], list):
        raise AssessmentPilotError("forms.forms must be a list")

    _object(private_keys, "private_keys", {"format", "forms_sha256", "bank_sha256", "keys"},
            {"format", "forms_sha256", "bank_sha256", "keys"})
    if private_keys["format"] != "assessment-study-keys-v1":
        raise AssessmentPilotError("Unsupported private key format")
    if private_keys["forms_sha256"] != sha256_json(forms):
        raise AssessmentPilotError("Private keys do not bind to this exact forms file")
    _sha(private_keys["forms_sha256"], "private_keys.forms_sha256")
    _sha(private_keys["bank_sha256"], "private_keys.bank_sha256")
    if private_keys["bank_sha256"] != forms["bank_sha256"]:
        raise AssessmentPilotError("Forms and private keys bind to different banks")
    if not isinstance(private_keys["keys"], list):
        raise AssessmentPilotError("private_keys.keys must be a list")

    forms_by_id = {}
    items_by_key = {}
    for form in forms["forms"]:
        _object(form, "form", {"form_id", "grade", "tasks"}, {"form_id", "grade", "tasks"})
        form_id = _text(form["form_id"], "form.form_id")
        grade, separator, variant = form_id.partition("-")
        if not separator or grade not in {"junior", "middle"} or variant not in {"A", "B"}:
            raise AssessmentPilotError("form_id must be junior-A, junior-B, middle-A, or middle-B")
        if form["grade"] != grade or form_id in forms_by_id:
            raise AssessmentPilotError("Form grade mismatch or duplicate form_id")
        if not isinstance(form["tasks"], list) or len(form["tasks"]) != 12:
            raise AssessmentPilotError("Each pilot form must contain exactly 12 tasks")
        forms_by_id[form_id] = form
        task_ids = set()
        family_ids = set()
        question_ids = set()
        competency_counts = {}
        item_count = 0
        for task in form["tasks"]:
            _object(task, "task", {"task_id", "family_id", "competency_id", "prompt", "subquestions"},
                    {"task_id", "family_id", "competency_id", "prompt", "subquestions"})
            task_id = _text(task["task_id"], "task.task_id")
            family_id = _text(task["family_id"], "task.family_id")
            competency_id = _text(task["competency_id"], "task.competency_id")
            _text(task["prompt"], "task.prompt")
            if task_id in task_ids or family_id in family_ids:
                raise AssessmentPilotError("Duplicate task_id or family_id within a form")
            task_ids.add(task_id)
            family_ids.add(family_id)
            competency_counts[competency_id] = competency_counts.get(competency_id, 0) + 1
            if not isinstance(task["subquestions"], list) or len(task["subquestions"]) != 2:
                raise AssessmentPilotError("Each task must contain exactly two subquestions")
            for question in task["subquestions"]:
                _object(question, "subquestion", {"id", "prompt", "options"}, {"id", "prompt", "options"})
                question_id = _text(question["id"], "subquestion.id")
                _text(question["prompt"], "subquestion.prompt")
                if question_id in question_ids:
                    raise AssessmentPilotError("Duplicate subquestion ID within a form")
                question_ids.add(question_id)
                if not isinstance(question["options"], list) or len(question["options"]) != 4:
                    raise AssessmentPilotError("Each subquestion must contain exactly four options")
                option_ids = set()
                for option in question["options"]:
                    _object(option, "option", {"id", "text"}, {"id", "text"})
                    option_id = _text(option["id"], "option.id")
                    _text(option["text"], "option.text")
                    if option_id in option_ids or option_id in ANSWER_STATES:
                        raise AssessmentPilotError("Option IDs must be unique and cannot use answer-state labels")
                    option_ids.add(option_id)
                item_key = (form_id, task_id, competency_id, question_id)
                if item_key in items_by_key:
                    raise AssessmentPilotError("Duplicate subquestion ID within a form")
                items_by_key[item_key] = {"option_ids": option_ids}
                item_count += 1
        if set(competency_counts) != COMPETENCY_IDS or set(competency_counts.values()) != {3}:
            raise AssessmentPilotError("Each form must have three tasks in each required competency block")
        if item_count != 24:
            raise AssessmentPilotError("Each form must contain exactly 24 scored subquestions")

    if set(forms_by_id) != FORM_IDS:
        raise AssessmentPilotError("Forms must include both A/B forms for junior and middle")
    for grade in ("junior", "middle"):
        a_families = {task["family_id"] for task in forms_by_id[f"{grade}-A"]["tasks"]}
        b_families = {task["family_id"] for task in forms_by_id[f"{grade}-B"]["tasks"]}
        if a_families & b_families:
            raise AssessmentPilotError("A/B forms for a grade must use disjoint task families")

    keys_by_item = {}
    for record in private_keys["keys"]:
        _object(record, "private key", {"form_id", "task_id", "competency_id", "subquestion_id", "correct_option_id"},
                {"form_id", "task_id", "competency_id", "subquestion_id", "correct_option_id"})
        for field in ("form_id", "task_id", "competency_id", "subquestion_id", "correct_option_id"):
            _text(record[field], f"private_key.{field}")
        key = (record["form_id"], record["task_id"], record["competency_id"], record["subquestion_id"])
        if key in keys_by_item or key not in items_by_key:
            raise AssessmentPilotError("Private keys contain a duplicate or unknown item")
        correct_id = _text(record["correct_option_id"], "private_key.correct_option_id")
        if correct_id not in items_by_key[key]["option_ids"]:
            raise AssessmentPilotError("Private key points to an option absent from public forms")
        keys_by_item[key] = correct_id
    if set(keys_by_item) != set(items_by_key):
        raise AssessmentPilotError("Every public subquestion must have exactly one private answer key")
    return {"forms_by_id": forms_by_id, "items_by_key": items_by_key, "keys_by_item": keys_by_item}


def _session_digest(session):
    return sha256_json({key: value for key, value in session.items() if key != "session_sha256"})


def prepare_sessions(forms, private_keys, grade, planned_slots, seed):
    validate_bundle(forms, private_keys)
    if grade not in {"junior", "middle"}:
        raise AssessmentPilotError("Study grade must be selected explicitly as junior or middle")
    if not isinstance(planned_slots, int) or isinstance(planned_slots, bool) or not 0 <= planned_slots <= 10000:
        raise AssessmentPilotError("planned_slots must be an integer between 0 and 10000")
    if not isinstance(seed, int) or isinstance(seed, bool):
        raise AssessmentPilotError("seed must be an integer")

    first_forms = ["A"] * (planned_slots // 2) + ["B"] * (planned_slots // 2)
    if planned_slots % 2:
        first_forms.append("A" if random.Random(seed).randrange(2) == 0 else "B")
    random.Random(seed).shuffle(first_forms)
    sessions = []
    for slot_number, first_variant in enumerate(first_forms, start=1):
        participant_id = f"slot-{slot_number:04d}"
        for order, variant in enumerate((first_variant, "B" if first_variant == "A" else "A"), start=1):
            form_id = f"{grade}-{variant}"
            session = {
                "session_id": "",
                "participant_id": participant_id,
                "chosen_study_grade": grade,
                "form_id": form_id,
                "administration_order": order,
                "forms_sha256": private_keys["forms_sha256"],
                "bank_sha256": private_keys["bank_sha256"],
            }
            session["session_id"] = "session-" + sha256_json({**session, "seed": seed})[:24]
            session["session_sha256"] = _session_digest(session)
            sessions.append(session)
    return {
        "format": "assessment-study-sessions-v1",
        "planning_only": True,
        "status": "planned_slots_not_enrollment",
        "grade": grade,
        "planned_participant_slots": planned_slots,
        "seed": seed,
        "forms_sha256": private_keys["forms_sha256"],
        "bank_sha256": private_keys["bank_sha256"],
        "sessions": sessions,
    }


def validate_sessions(forms, private_keys, session_plan):
    validate_bundle(forms, private_keys)
    _object(session_plan, "session_plan",
            {"format", "planning_only", "status", "grade", "planned_participant_slots", "seed", "forms_sha256", "bank_sha256", "sessions"},
            {"format", "planning_only", "status", "grade", "planned_participant_slots", "seed", "forms_sha256", "bank_sha256", "sessions"})
    if session_plan["format"] != "assessment-study-sessions-v1" or session_plan["planning_only"] is not True:
        raise AssessmentPilotError("Session plan must be explicitly planning-only")
    if session_plan["status"] != "planned_slots_not_enrollment":
        raise AssessmentPilotError("Unsupported session plan status")
    if not isinstance(session_plan["grade"], str) or session_plan["grade"] not in {"junior", "middle"}:
        raise AssessmentPilotError("Invalid planned study grade")
    if not isinstance(session_plan["seed"], int) or isinstance(session_plan["seed"], bool):
        raise AssessmentPilotError("Session plan seed must be an integer")
    if session_plan["forms_sha256"] != private_keys["forms_sha256"] or session_plan["bank_sha256"] != private_keys["bank_sha256"]:
        raise AssessmentPilotError("Session plan has stale form or bank binding")
    if not isinstance(session_plan["sessions"], list):
        raise AssessmentPilotError("session_plan.sessions must be a list")
    slots = session_plan["planned_participant_slots"]
    if not isinstance(slots, int) or isinstance(slots, bool) or not 0 <= slots <= 10000 or len(session_plan["sessions"]) != slots * 2:
        raise AssessmentPilotError("Session count does not match planned slot count")
    session_ids = set()
    seen_by_participant = {}
    for session in session_plan["sessions"]:
        _object(session, "session",
                {"session_id", "session_sha256", "participant_id", "chosen_study_grade", "form_id", "administration_order", "forms_sha256", "bank_sha256"},
                {"session_id", "session_sha256", "participant_id", "chosen_study_grade", "form_id", "administration_order", "forms_sha256", "bank_sha256"})
        session_id = _text(session["session_id"], "session.session_id")
        if session_id in session_ids or session["session_sha256"] != _session_digest(session):
            raise AssessmentPilotError("Duplicate session ID or invalid immutable session hash")
        session_ids.add(session_id)
        participant_id = session["participant_id"]
        if not isinstance(participant_id, str) or not re.fullmatch(r"slot-[0-9]{4,}", participant_id):
            raise AssessmentPilotError("Session participant_id must be a generated pseudonymous slot label")
        if not isinstance(session["form_id"], str) or session["chosen_study_grade"] != session_plan["grade"]:
            raise AssessmentPilotError("Session chosen_study_grade does not match explicit plan grade")
        if session["form_id"] not in {f"{session_plan['grade']}-A", f"{session_plan['grade']}-B"}:
            raise AssessmentPilotError("Session form is outside the selected study grade")
        if session["forms_sha256"] != private_keys["forms_sha256"] or session["bank_sha256"] != private_keys["bank_sha256"]:
            raise AssessmentPilotError("Session has stale form or bank binding")
        if not isinstance(session["administration_order"], int) or isinstance(session["administration_order"], bool) or session["administration_order"] not in {1, 2}:
            raise AssessmentPilotError("Session administration_order must be one or two")
        seen_by_participant.setdefault(participant_id, []).append(session)
    if len(seen_by_participant) != slots:
        raise AssessmentPilotError("Participant slot count does not match session plan")
    expected_participants = {f"slot-{number:04d}" for number in range(1, slots + 1)}
    if set(seen_by_participant) != expected_participants:
        raise AssessmentPilotError("Session plan participant slots must be contiguous generated labels")
    for pair in seen_by_participant.values():
        if {session["administration_order"] for session in pair} != {1, 2} or {session["form_id"] for session in pair} != {f"{session_plan['grade']}-A", f"{session_plan['grade']}-B"}:
            raise AssessmentPilotError("Each planned slot must contain one A and one B administration")
    return {session["session_id"]: session for session in session_plan["sessions"]}


def validate_responses(forms, private_keys, session_plan, response_bundle):
    sessions_by_id = validate_sessions(forms, private_keys, session_plan)
    _object(response_bundle, "response_bundle", {"format", "responses"}, {"format", "responses"})
    if response_bundle["format"] != "assessment-study-responses-v1":
        raise AssessmentPilotError("Unsupported response bundle format")
    if not isinstance(response_bundle["responses"], list):
        raise AssessmentPilotError("response_bundle.responses must be a list")
    forms_by_id = {form["form_id"]: form for form in forms["forms"]}
    seen_sessions = set()
    normalized = {}
    required_fields = {"session_id", "session_sha256", "participant_id", "chosen_study_grade", "form_id", "forms_sha256", "bank_sha256", "source", "status", "answers"}
    allowed_fields = required_fields | {"started_at", "finished_at"}
    for response in response_bundle["responses"]:
        _object(response, "response", allowed_fields, required_fields)
        session_id = _text(response["session_id"], "response.session_id")
        session = sessions_by_id.get(session_id)
        if session is None or session_id in seen_sessions:
            raise AssessmentPilotError("Response references an unknown or duplicate session_id")
        seen_sessions.add(session_id)
        for field in ("session_sha256", "participant_id", "chosen_study_grade", "form_id", "forms_sha256", "bank_sha256"):
            if response[field] != session.get(field):
                raise AssessmentPilotError(f"Response {field} does not match planned session")
        if response["session_sha256"] != _session_digest(session):
            raise AssessmentPilotError("Response has a stale immutable session hash")
        if response["forms_sha256"] != private_keys["forms_sha256"] or response["bank_sha256"] != private_keys["bank_sha256"]:
            raise AssessmentPilotError("Response has a stale forms or bank hash")
        if not isinstance(response["source"], str) or response["source"] not in RESPONSE_SOURCES:
            raise AssessmentPilotError("Response source must be volunteer_response or synthetic_fixture")
        if not isinstance(response["status"], str) or response["status"] not in RESPONSE_STATUSES:
            raise AssessmentPilotError("Unsupported response status")
        if not isinstance(response["answers"], list):
            raise AssessmentPilotError("response.answers must be a list")
        timing = _response_timing(response)

        form = forms_by_id[response["form_id"]]
        form_id = response["form_id"]
        items = {
            (form_id, task["task_id"], task["competency_id"], question["id"]): {
                "options": {option["id"] for option in question["options"]}
            }
            for task in form["tasks"] for question in task["subquestions"]
        }
        item_lookup = {(item[1], item[3]): item for item in items}
        answers_by_item = {}
        for answer in response["answers"]:
            _object(answer, "answer", {"task_id", "subquestion_id", "selected_option_id"},
                    {"task_id", "subquestion_id", "selected_option_id"})
            task_id = _text(answer["task_id"], "answer.task_id")
            subquestion_id = _text(answer["subquestion_id"], "answer.subquestion_id")
            item = item_lookup.get((task_id, subquestion_id))
            if item is None or item in answers_by_item:
                raise AssessmentPilotError("Answer contains an unknown or duplicate task/subquestion pair")
            selected = _text(answer["selected_option_id"], "answer.selected_option_id")
            if selected not in items[item]["options"] and selected not in ANSWER_STATES:
                raise AssessmentPilotError("Answer selected_option_id is not a public option or an allowed response state")
            answers_by_item[item] = selected
        if response["status"] == "completed" and set(answers_by_item) != set(items):
            raise AssessmentPilotError("Completed responses must include exactly one row per subquestion")
        normalized_response = dict(response)
        if timing is not None:
            normalized_response["started_at"] = timing["started_at"]
            normalized_response["finished_at"] = timing["finished_at"]
        normalized[session_id] = {
            "response": normalized_response,
            "answers_by_item": answers_by_item,
            "timing": timing,
        }
    return {"sessions_by_id": sessions_by_id, "responses_by_session": normalized}


def _mean(values):
    return sum(values) / len(values) if values else None


def _pearson(left, right):
    if len(left) < 3 or len(left) != len(right):
        return None
    left_mean, right_mean = _mean(left), _mean(right)
    left_centered = [value - left_mean for value in left]
    right_centered = [value - right_mean for value in right]
    left_ss = sum(value * value for value in left_centered)
    right_ss = sum(value * value for value in right_centered)
    if left_ss == 0 or right_ss == 0:
        return None
    return sum(a * b for a, b in zip(left_centered, right_centered)) / math.sqrt(left_ss * right_ss)


def _empty_pair_summary():
    return {
        "pairs_count": 0,
        "exact_score_agreement": None,
        "experimental_threshold_agreement": None,
        "mean_score_delta_A_minus_B": None,
        "pairs": [],
    }


def analyze_responses(forms, private_keys, session_plan, response_bundle):
    validated = validate_responses(forms, private_keys, session_plan, response_bundle)
    responses_by_session = validated["responses_by_session"]
    forms_by_id = {form["form_id"]: form for form in forms["forms"]}
    keys_by_item = validate_bundle(forms, private_keys)["keys_by_item"]
    administrations = []
    for session in session_plan["sessions"]:
        imported = responses_by_session.get(session["session_id"])
        if imported is None:
            response = {
                "status": "missing",
                "source": None,
                "answers_by_item": {},
                "timing": None,
            }
        else:
            response = {
                "status": imported["response"]["status"],
                "source": imported["response"]["source"],
                "answers_by_item": imported["answers_by_item"],
                "timing": imported["timing"],
            }
        timing = response["timing"]
        form = forms_by_id[session["form_id"]]
        row = {
            "session_id": session["session_id"],
            "participant_id": session["participant_id"],
            "chosen_study_grade": session["chosen_study_grade"],
            "form_id": session["form_id"],
            "administration_order": session["administration_order"],
            "source": response["source"],
            "status": response["status"],
            "score_total": None,
            "block_scores": None,
            "experimental_threshold_met": None,
            "grade_awarded": None,
            "unknown_count": None,
            "unanswered_count": None,
            "started_at": timing["started_at"] if timing else None,
            "finished_at": timing["finished_at"] if timing else None,
            "duration_seconds": timing["duration_seconds"] if timing else None,
            "timing_status": "observed" if timing else "unknown",
        }
        if response["status"] == "completed":
            block_scores = {}
            unknown_count = 0
            unanswered_count = 0
            score = 0
            for task in form["tasks"]:
                competency_id = task["competency_id"]
                block_scores.setdefault(competency_id, 0)
                for question in task["subquestions"]:
                    item = (session["form_id"], task["task_id"], competency_id, question["id"])
                    selected = response["answers_by_item"][item]
                    if selected == "unknown":
                        unknown_count += 1
                    elif selected == "missing":
                        unanswered_count += 1
                    elif selected == keys_by_item[item]:
                        score += 1
                        block_scores[competency_id] += 1
            row.update({
                "score_total": score,
                "block_scores": block_scores,
                "experimental_threshold_met": score >= 18 and all(value >= 3 for value in block_scores.values()),
                "unknown_count": unknown_count,
                "unanswered_count": unanswered_count,
                "score_policy": "completed_unknown_and_missing_answers_score_zero",
            })
        administrations.append(row)

    participants = []
    by_participant = {}
    for row in administrations:
        by_participant.setdefault(row["participant_id"], []).append(row)
    for participant_id, rows in sorted(by_participant.items()):
        rows.sort(key=lambda row: row["administration_order"])
        by_form = {row["form_id"]: row for row in rows}
        pair = None
        if len(by_form) == 2 and all(form_id in by_form for form_id in (f"{session_plan['grade']}-A", f"{session_plan['grade']}-B")):
            a, b = by_form[f"{session_plan['grade']}-A"], by_form[f"{session_plan['grade']}-B"]
            if a["status"] == b["status"] == "completed" and a["source"] == b["source"]:
                a_timing = responses_by_session[a["session_id"]]["timing"]
                b_timing = responses_by_session[b["session_id"]]["timing"]
                if a_timing is not None and b_timing is not None and a_timing["started_datetime"] != b_timing["started_datetime"]:
                    ordered_rows = sorted((a, b), key=lambda row: responses_by_session[row["session_id"]]["timing"]["started_datetime"])
                    first_timing = responses_by_session[ordered_rows[0]["session_id"]]["timing"]
                    second_timing = responses_by_session[ordered_rows[1]["session_id"]]["timing"]
                    interval = (second_timing["started_datetime"] - first_timing["finished_datetime"]).total_seconds()
                    observed_order = [row["form_id"] for row in ordered_rows]
                    planned_order = [row["form_id"] for row in sorted((a, b), key=lambda row: row["administration_order"])]
                    timing_status = "observed"
                    timing_reason = None
                    order_mismatch = observed_order != planned_order
                    overlap = interval < 0
                else:
                    interval = None
                    observed_order = None
                    timing_status = "unknown"
                    timing_reason = "one_or_both_administrations_lack_timing" if a_timing is None or b_timing is None else "identical_start_timestamps"
                    order_mismatch = None
                    overlap = None
                pair = {
                    "source": a["source"],
                    "score_delta_A_minus_B": a["score_total"] - b["score_total"],
                    "threshold_agreement": a["experimental_threshold_met"] == b["experimental_threshold_met"],
                    "block_deltas_A_minus_B": {
                        competency_id: a["block_scores"][competency_id] - b["block_scores"][competency_id]
                        for competency_id in a["block_scores"]
                    },
                    "timing_status": timing_status,
                    "timing_unknown_reason": timing_reason,
                    "observed_administration_order": observed_order,
                    "interval_end_to_nextstart_seconds": interval,
                    "planned_order_mismatch": order_mismatch,
                    "administrations_overlap": overlap,
                }
        participants.append({
            "participant_id": participant_id,
            "chosen_study_grade": session_plan["grade"],
            "grade_awarded": None,
            "forms": by_form,
            "paired_comparison": pair,
        })

    pair_summaries = {}
    for source in sorted(RESPONSE_SOURCES):
        pairs = [
            person["paired_comparison"]
            for person in participants
            if person["paired_comparison"] is not None and person["paired_comparison"]["source"] == source
        ]
        pair_summaries[source] = {
            "pairs_count": len(pairs),
            "exact_score_agreement": (sum(pair["score_delta_A_minus_B"] == 0 for pair in pairs) / len(pairs)) if pairs else None,
            "experimental_threshold_agreement": (sum(pair["threshold_agreement"] for pair in pairs) / len(pairs)) if pairs else None,
            "mean_score_delta_A_minus_B": _mean([pair["score_delta_A_minus_B"] for pair in pairs]),
            "timing_known_count": sum(pair["timing_status"] == "observed" for pair in pairs),
            "timing_unknown_count": sum(pair["timing_status"] == "unknown" for pair in pairs),
            "planned_order_mismatch_count": sum(pair["planned_order_mismatch"] is True for pair in pairs),
            "overlapping_pair_count": sum(pair["administrations_overlap"] is True for pair in pairs),
            "pairs": pairs,
        }

    summary_by_source = {}
    for source in sorted(RESPONSE_SOURCES):
        source_rows = [row for row in administrations if row["source"] == source]
        completed = [row for row in source_rows if row["status"] == "completed"]
        summary_by_source[source] = {
            "response_input_count": len(source_rows),
            "completed_count": len(completed),
            "refused_count": sum(row["status"] == "refused" for row in source_rows),
            "technical_invalidated_count": sum(row["status"] == "technical_invalidated" for row in source_rows),
            "explicit_missing_count": sum(row["status"] == "missing" for row in source_rows),
            "mean_score": _mean([row["score_total"] for row in completed]),
            "completed_unanswered_item_count": sum(row["unanswered_count"] for row in completed),
            "completed_unknown_item_count": sum(row["unknown_count"] for row in completed),
        }
    unreturned_count = sum(row["source"] is None for row in administrations)
    item_statistics = {source: [] for source in sorted(RESPONSE_SOURCES)}
    block_statistics = {source: [] for source in sorted(RESPONSE_SOURCES)}
    for source in sorted(RESPONSE_SOURCES):
        completed_rows = [row for row in administrations if row["source"] == source and row["status"] == "completed"]
        item_observations = {}
        for row in completed_rows:
            form = forms_by_id[row["form_id"]]
            imported = responses_by_session[row["session_id"]]
            for task in form["tasks"]:
                for question in task["subquestions"]:
                    key = (row["form_id"], task["task_id"], task["competency_id"], question["id"])
                    selected = imported["answers_by_item"][key]
                    item_observations.setdefault(key, []).append({
                        "selected": selected,
                        "correct": selected == keys_by_item[key] if selected not in ANSWER_STATES else False,
                        "answered": selected not in ANSWER_STATES,
                        "person_total_without_item": row["score_total"] - int(selected == keys_by_item[key]),
                    })
        all_item_keys = [
            (form["form_id"], task["task_id"], task["competency_id"], question["id"])
            for form in forms["forms"] for task in form["tasks"] for question in task["subquestions"]
        ]
        for key in all_item_keys:
            observations = item_observations.get(key, [])
            answered = [observation for observation in observations if observation["answered"]]
            total_scores = [observation["person_total_without_item"] for observation in answered]
            item_scores = [int(observation["correct"]) for observation in answered]
            item_statistics[source].append({
                "form_id": key[0],
                "task_id": key[1],
                "competency_id": key[2],
                "subquestion_id": key[3],
                "completed_denominator": len(observations),
                "answered_denominator": len(answered),
                "correct_count": sum(observation["correct"] for observation in answered),
                "unknown_count": sum(observation["selected"] == "unknown" for observation in observations),
                "unanswered_count": sum(observation["selected"] == "missing" for observation in observations),
                "difficulty_estimate": _mean(item_scores),
                "corrected_item_total_n": len(answered),
                "corrected_item_total_correlation": _pearson(item_scores, total_scores),
            })
        for competency_id in sorted({task["competency_id"] for form in forms["forms"] for task in form["tasks"]}):
            observations = [
                row for row in completed_rows
                if competency_id in row["block_scores"]
            ]
            scores = [row["block_scores"][competency_id] for row in observations]
            corrected_totals = [row["score_total"] - row["block_scores"][competency_id] for row in observations]
            block_statistics[source].append({
                "competency_id": competency_id,
                "denominator": len(observations),
                "mean_block_score": _mean(scores),
                "corrected_block_total_n": len(observations),
                "corrected_block_total_correlation": _pearson(scores, corrected_totals),
            })

    volunteer_inputs = sum(row["source"] == "volunteer_response" for row in administrations)
    synthetic_inputs = sum(row["source"] == "synthetic_fixture" for row in administrations)
    warnings = []
    timing_unknown_pairs = sum(summary["timing_unknown_count"] for summary in pair_summaries.values())
    if timing_unknown_pairs:
        warnings.append("Some completed same-source A/B score pairs have unknown timing and must not be interpreted as having a known test-retest interval.")
    return {
        "format": "assessment-study-report-v1",
        "status": "pending_no_response_inputs" if not response_bundle["responses"] else "descriptive_offline_analysis",
        "forms_sha256": private_keys["forms_sha256"],
        "bank_sha256": private_keys["bank_sha256"],
        "planning_only": True,
        "planned_participant_slots": session_plan["planned_participant_slots"],
        "response_input_count": len(response_bundle["responses"]),
        "source_counts": {
            "volunteer_response_inputs_unverified": volunteer_inputs,
            "synthetic_fixture_inputs": synthetic_inputs,
            "unreturned_planned_sessions": unreturned_count,
        },
        "observed_response_inputs_unverified": True,
        "human_results_claimed": False,
        "grade_awarded": None,
        "answer_state_meaning": ANSWER_STATE_MEANING,
        "participants": participants,
        "administrations": administrations,
        "summary_by_source": summary_by_source,
        "paired_agreement_by_source": pair_summaries,
        "item_statistics_by_source": item_statistics,
        "block_statistics_by_source": block_statistics,
        "warnings": warnings,
        "limitations": PILOT_LIMITATIONS,
    }


def _local_path(path, must_exist=False):
    candidate = Path(path)
    if not candidate.is_absolute():
        candidate = REPO_ROOT / candidate
    try:
        resolved = candidate.resolve(strict=must_exist)
    except FileNotFoundError as exc:
        raise AssessmentPilotError("Input file does not exist") from exc
    if not resolved.is_relative_to(LOCAL_ROOT.resolve()):
        raise AssessmentPilotError("Pilot inputs and outputs must stay under this checkout/local/")
    return resolved


def read_json(path):
    source = _local_path(path, must_exist=True)
    if not source.is_file():
        raise AssessmentPilotError("Input path must be a file")
    try:
        return json.loads(source.read_text(encoding="utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise AssessmentPilotError("Input must be valid UTF-8 JSON") from exc


def write_new_json(path, value):
    destination = _local_path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True).encode("utf-8") + b"\n"
    temporary_name = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, prefix=".assessment-pilot-", delete=False) as handle:
            temporary_name = handle.name
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.link(temporary_name, destination)
    except FileExistsError as exc:
        raise AssessmentPilotError("Refusing to overwrite an existing local pilot artifact") from exc
    finally:
        if temporary_name is not None:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass
    return destination


def _cli():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    prepare = subparsers.add_parser("prepare", help="Create counterbalanced A/B session slots for one explicit study grade")
    prepare.add_argument("--forms", required=True)
    prepare.add_argument("--keys", required=True)
    prepare.add_argument("--grade", choices=("junior", "middle"), required=True)
    prepare.add_argument("--slots", type=int, required=True)
    prepare.add_argument("--seed", type=int, required=True)
    prepare.add_argument("--out", required=True)
    analyze = subparsers.add_parser("analyze", help="Analyze local response rows bound to a session plan")
    analyze.add_argument("--forms", required=True)
    analyze.add_argument("--keys", required=True)
    analyze.add_argument("--sessions", required=True)
    analyze.add_argument("--responses", required=True)
    analyze.add_argument("--out", required=True)
    return parser


def main(argv=None):
    parser = _cli()
    args = parser.parse_args(argv)
    try:
        forms = read_json(args.forms)
        private_keys = read_json(args.keys)
        validate_bundle(forms, private_keys)
        if args.command == "prepare":
            result = prepare_sessions(forms, private_keys, args.grade, args.slots, args.seed)
        else:
            sessions = read_json(args.sessions)
            responses = read_json(args.responses)
            result = analyze_responses(forms, private_keys, sessions, responses)
        destination = write_new_json(args.out, result)
    except AssessmentPilotError as error:
        parser.exit(1, f"{error}\n")
    print(json.dumps({"status": result.get("status"), "output": str(destination.relative_to(REPO_ROOT))}, ensure_ascii=False))


if __name__ == "__main__":
    main()
