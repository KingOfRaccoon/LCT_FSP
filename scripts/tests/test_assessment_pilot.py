import copy
import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from scripts import assessment_pilot, assessment_review
from scripts.tests.test_assessment_review import fixture_bank


def fixture_bundle():
    forms = {
        "format": "assessment-study-forms-v1",
        "bank_version": "toy-draft-v1",
        "bank_sha256": "a" * 64,
        "seed": 17,
        "status": "draft_ai_authored_pending_human_review",
        "forms": [],
    }
    private_keys = {"format": "assessment-study-keys-v1", "forms_sha256": "", "bank_sha256": forms["bank_sha256"], "keys": []}
    for grade in ("junior", "middle"):
        for variant in ("A", "B"):
            form_id = f"{grade}-{variant}"
            tasks = []
            for competency_index in range(4):
                competency_id = sorted(assessment_pilot.COMPETENCY_IDS)[competency_index]
                for task_index in range(3):
                    task_id = f"{form_id}-task-{competency_index}-{task_index}"
                    subquestions = []
                    for question_index in range(2):
                        question_id = f"{task_id}-question-{question_index}"
                        options = [{"id": f"{question_id}-option-{i}", "text": f"Choice {i}"} for i in range(4)]
                        subquestions.append({"id": question_id, "prompt": "Toy question", "options": options})
                        private_keys["keys"].append({
                            "form_id": form_id,
                            "task_id": task_id,
                            "competency_id": competency_id,
                            "subquestion_id": question_id,
                            "correct_option_id": options[0]["id"],
                        })
                    tasks.append({
                        "task_id": task_id,
                        "family_id": f"{grade}-family-{variant}-{competency_index}-{task_index}",
                        "competency_id": competency_id,
                        "prompt": "Toy task",
                        "subquestions": subquestions,
                    })
            forms["forms"].append({"form_id": form_id, "grade": grade, "tasks": tasks})
    private_keys["forms_sha256"] = assessment_pilot.sha256_json(forms)
    assessment_pilot.validate_bundle(forms, private_keys)
    return forms, private_keys


def answer_rows(form):
    return [
        {"task_id": task["task_id"], "subquestion_id": question["id"],
         "selected_option_id": question["options"][0]["id"]}
        for task in form["tasks"] for question in task["subquestions"]
    ]


def response_for(session, form, keys, *, source="volunteer_response", status="completed", answers=None):
    return {
        "session_id": session["session_id"],
        "session_sha256": session["session_sha256"],
        "participant_id": session["participant_id"],
        "chosen_study_grade": session["chosen_study_grade"],
        "form_id": form["form_id"],
        "forms_sha256": keys["forms_sha256"],
        "bank_sha256": keys["bank_sha256"],
        "source": source,
        "status": status,
        "answers": answer_rows(form) if answers is None and status == "completed" else (answers or []),
    }


class AssessmentPilotTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        assessment_pilot.LOCAL_ROOT.mkdir(parents=True, exist_ok=True)

    def setUp(self):
        self.forms, self.keys = fixture_bundle()

    def test_session_preparation_is_seeded_counterbalanced_and_pseudonymous(self):
        prepared = assessment_pilot.prepare_sessions(self.forms, self.keys, grade="junior", planned_slots=6, seed=7)
        self.assertEqual(prepared, assessment_pilot.prepare_sessions(self.forms, self.keys, grade="junior", planned_slots=6, seed=7))
        self.assertTrue(prepared["planning_only"])
        self.assertEqual(prepared["planned_participant_slots"], 6)
        self.assertEqual([entry["form_id"] for entry in prepared["sessions"]].count("junior-A"), 6)
        self.assertEqual([entry["form_id"] for entry in prepared["sessions"]].count("junior-B"), 6)
        by_participant = {}
        for entry in prepared["sessions"]:
            by_participant.setdefault(entry["participant_id"], []).append(entry)
        self.assertTrue(all(sorted(entry["administration_order"] for entry in pair) == [1, 2] for pair in by_participant.values()))
        self.assertEqual(sum(1 for pair in by_participant.values() if next(entry for entry in pair if entry["administration_order"] == 1)["form_id"] == "junior-A"), 3)
        self.assertTrue(all(entry["forms_sha256"] == self.keys["forms_sha256"] for entry in prepared["sessions"]))
        self.assertTrue(all(entry["bank_sha256"] == self.keys["bank_sha256"] for entry in prepared["sessions"]))
        with self.assertRaises(assessment_pilot.AssessmentPilotError):
            assessment_pilot.prepare_sessions(self.forms, self.keys, grade="", planned_slots=1, seed=7)

    def test_bundle_rejects_stale_hashes_duplicate_ids_and_bad_options(self):
        stale_keys = copy.deepcopy(self.keys)
        stale_keys["forms_sha256"] = "0" * 64
        with self.assertRaises(assessment_pilot.AssessmentPilotError):
            assessment_pilot.validate_bundle(self.forms, stale_keys)

        duplicate = copy.deepcopy(self.forms)
        duplicate["forms"][0]["tasks"][1]["task_id"] = duplicate["forms"][0]["tasks"][0]["task_id"]
        with self.assertRaises(assessment_pilot.AssessmentPilotError):
            assessment_pilot.validate_bundle(duplicate, self.keys)

        bad_key = copy.deepcopy(self.keys)
        bad_key["keys"][0]["correct_option_id"] = "unknown-option"
        with self.assertRaises(assessment_pilot.AssessmentPilotError):
            assessment_pilot.validate_bundle(self.forms, bad_key)

    def test_review_exporter_reused_option_ids_are_question_scoped(self):
        bank = fixture_bank()
        for family in bank["families"]:
            for variant in family["variants"]:
                for question in variant["subquestions"]:
                    for index, option in enumerate(question["options"]):
                        option["id"] = f"option_{chr(ord('a') + index)}"
                    question["correct_option_id"] = "option_a"
        forms, keys = assessment_review.build_forms(bank, seed=71)

        sessions = assessment_pilot.prepare_sessions(forms, keys, grade="junior", planned_slots=1, seed=4)
        self.assertEqual(len(sessions["sessions"]), 2)
        with tempfile.TemporaryDirectory(dir=assessment_pilot.LOCAL_ROOT, prefix=".pilot-integration-") as directory:
            root = Path(directory)
            (root / "forms.json").write_text(json.dumps(forms), encoding="utf-8")
            (root / "keys.json").write_text(json.dumps(keys), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                assessment_pilot.main([
                    "prepare", "--forms", str(root / "forms.json"), "--keys", str(root / "keys.json"),
                    "--grade", "junior", "--slots", "1", "--seed", "4", "--out", str(root / "sessions.json"),
                ])
            cli_plan = json.loads((root / "sessions.json").read_text(encoding="utf-8"))
            self.assertTrue(cli_plan["planning_only"])
            self.assertEqual(len(cli_plan["sessions"]), 2)

        duplicate_within_question = copy.deepcopy(forms)
        question = duplicate_within_question["forms"][0]["tasks"][0]["subquestions"][0]
        question["options"][1]["id"] = question["options"][0]["id"]
        duplicate_keys = copy.deepcopy(keys)
        duplicate_keys["forms_sha256"] = assessment_pilot.sha256_json(duplicate_within_question)
        with self.assertRaises(assessment_pilot.AssessmentPilotError):
            assessment_pilot.validate_bundle(duplicate_within_question, duplicate_keys)

    def test_import_rejects_duplicate_stale_unknown_and_mismatched_bindings(self):
        sessions = assessment_pilot.prepare_sessions(self.forms, self.keys, grade="junior", planned_slots=1, seed=2)
        session = sessions["sessions"][0]
        form = next(item for item in self.forms["forms"] if item["form_id"] == session["form_id"])
        base = response_for(session, form, self.keys)
        bundle = {"format": "assessment-study-responses-v1", "responses": [base]}
        assessment_pilot.validate_responses(self.forms, self.keys, sessions, bundle)

        invalid = [
            {**base, "forms_sha256": "stale"},
            {**base, "form_id": "middle-A"},
            {**base, "answers": base["answers"] + [base["answers"][0]]},
            {**base, "answers": [{**base["answers"][0], "task_id": "not-a-task"}, *base["answers"][1:]]},
            {**base, "answers": [{**base["answers"][0], "selected_option_id": "not-an-option"}, *base["answers"][1:]]},
            {**base, "status": "completed-with-typo"},
            {**base, "source": "imported-from-email"},
        ]
        for response in invalid:
            with self.subTest(response=response), self.assertRaises(assessment_pilot.AssessmentPilotError):
                assessment_pilot.validate_responses(
                    self.forms, self.keys, sessions,
                    {"format": "assessment-study-responses-v1", "responses": [response]},
                )
        with self.assertRaises(assessment_pilot.AssessmentPilotError):
            assessment_pilot.validate_responses(
                self.forms, self.keys, sessions,
                {"format": "assessment-study-responses-v1", "responses": [base, base]},
            )

    def test_scores_are_bounded_and_threshold_is_probe_only(self):
        sessions = assessment_pilot.prepare_sessions(self.forms, self.keys, grade="junior", planned_slots=1, seed=2)
        session = next(item for item in sessions["sessions"] if item["administration_order"] == 1)
        form = next(item for item in self.forms["forms"] if item["form_id"] == session["form_id"])
        rows = answer_rows(form)
        keys_by_item = {(entry["task_id"], entry["subquestion_id"]): entry["correct_option_id"] for entry in self.keys["keys"] if entry["form_id"] == form["form_id"]}
        wrong = lambda row: next(option["id"] for task in form["tasks"] if task["task_id"] == row["task_id"] for question in task["subquestions"] if question["id"] == row["subquestion_id"] for option in question["options"] if option["id"] != keys_by_item[(row["task_id"], row["subquestion_id"])])
        for competency_id, target_correct in zip(sorted(assessment_pilot.COMPETENCY_IDS), (5, 5, 4, 4)):
            block_rows = [
                row for task in form["tasks"] if task["competency_id"] == competency_id
                for row in rows if row["task_id"] == task["task_id"]
            ]
            for row in block_rows[target_correct:]:
                row["selected_option_id"] = wrong(row)
        at_threshold = response_for(session, form, self.keys, answers=rows)
        report = assessment_pilot.analyze_responses(self.forms, self.keys, sessions, {"format": "assessment-study-responses-v1", "responses": [at_threshold]})
        scored = next(row for row in report["administrations"] if row["status"] == "completed")
        self.assertEqual(scored["score_total"], 18)
        self.assertTrue(scored["experimental_threshold_met"])
        self.assertIsNone(scored["grade_awarded"])
        self.assertTrue(all(0 <= score <= 6 for score in scored["block_scores"].values()))

        perfect_response = response_for(session, form, self.keys)
        perfect = next(row for row in assessment_pilot.analyze_responses(
            self.forms, self.keys, sessions,
            {"format": "assessment-study-responses-v1", "responses": [perfect_response]},
        )["administrations"] if row["status"] == "completed")
        self.assertEqual(perfect["score_total"], 24)
        self.assertTrue(perfect["experimental_threshold_met"])

        below_threshold_rows = copy.deepcopy(rows)
        below_threshold_rows[0]["selected_option_id"] = wrong(below_threshold_rows[0])
        below_threshold = next(row for row in assessment_pilot.analyze_responses(
            self.forms, self.keys, sessions,
            {"format": "assessment-study-responses-v1", "responses": [response_for(session, form, self.keys, answers=below_threshold_rows)]},
        )["administrations"] if row["status"] == "completed")
        self.assertEqual(below_threshold["score_total"], 17)
        self.assertFalse(below_threshold["experimental_threshold_met"])

        rows = answer_rows(form)
        for row in rows:
            row["selected_option_id"] = wrong(row)
        zero_response = response_for(session, form, self.keys, answers=rows)
        zero = next(row for row in assessment_pilot.analyze_responses(self.forms, self.keys, sessions, {"format": "assessment-study-responses-v1", "responses": [zero_response]})["administrations"] if row["status"] == "completed")
        self.assertEqual(zero["score_total"], 0)
        self.assertFalse(zero["experimental_threshold_met"])
        self.assertIsNone(zero["grade_awarded"])

    def test_completed_unanswered_scores_zero_but_terminal_states_are_not_scored(self):
        sessions = assessment_pilot.prepare_sessions(self.forms, self.keys, grade="junior", planned_slots=4, seed=3)
        responses = []
        for session in sessions["sessions"]:
            form = next(item for item in self.forms["forms"] if item["form_id"] == session["form_id"])
            participant_number = int(session["participant_id"].split("-")[1])
            if participant_number == 1 and session["administration_order"] == 1:
                answers = answer_rows(form)
                answers[0]["selected_option_id"] = "unknown"
                answers[1]["selected_option_id"] = "missing"
                responses.append(response_for(session, form, self.keys, answers=answers))
            else:
                status = {2: "refused", 3: "technical_invalidated", 4: "missing"}.get(participant_number, "completed")
                responses.append(response_for(session, form, self.keys, status=status))
        report = assessment_pilot.analyze_responses(self.forms, self.keys, sessions, {"format": "assessment-study-responses-v1", "responses": responses})
        scored = {item["session_id"]: item for item in report["administrations"]}
        completed = next(row for row in scored.values() if row["participant_id"] == "slot-0001")
        self.assertEqual(completed["score_total"], 22)
        self.assertEqual(completed["unknown_count"], 1)
        self.assertEqual(completed["unanswered_count"], 1)
        self.assertEqual(report["answer_state_meaning"]["unknown"], "participant_explicitly_does_not_know")
        self.assertEqual(report["answer_state_meaning"]["missing"], "intentionally_unanswered_item_at_completed_submission")
        self.assertIn("both answer states score zero only under the experimental study scoring rule", report["answer_state_meaning"]["scoring"])
        self.assertIn("whole-session failures use missing or technical_invalidated status", report["answer_state_meaning"]["measurement_failure"])
        for row in scored.values():
            if row["status"] in ("refused", "technical_invalidated", "missing"):
                self.assertIsNone(row["score_total"])
                self.assertIsNone(row["experimental_threshold_met"])
                self.assertIsNone(row["grade_awarded"])

    def test_pairs_and_statistics_are_source_separated_and_empty_stats_are_null(self):
        sessions = assessment_pilot.prepare_sessions(self.forms, self.keys, grade="junior", planned_slots=2, seed=1)
        responses = []
        for session in sessions["sessions"]:
            form = next(item for item in self.forms["forms"] if item["form_id"] == session["form_id"])
            source = "synthetic_fixture" if session["participant_id"] == "slot-0002" else "volunteer_response"
            answers = answer_rows(form)
            for row in answers:
                row["selected_option_id"] = next(option["id"] for task in form["tasks"] if task["task_id"] == row["task_id"] for question in task["subquestions"] if question["id"] == row["subquestion_id"] for option in question["options"] if option["id"] != row["selected_option_id"])
            responses.append(response_for(session, form, self.keys, source=source, answers=answers))
        report = assessment_pilot.analyze_responses(self.forms, self.keys, sessions, {"format": "assessment-study-responses-v1", "responses": responses})
        self.assertEqual(report["paired_agreement_by_source"]["volunteer_response"]["pairs_count"], 1)
        self.assertEqual(report["paired_agreement_by_source"]["synthetic_fixture"]["pairs_count"], 1)
        self.assertEqual(report["paired_agreement_by_source"]["volunteer_response"]["exact_score_agreement"], 1.0)
        self.assertEqual(report["paired_agreement_by_source"]["synthetic_fixture"]["experimental_threshold_agreement"], 1.0)
        self.assertEqual(report["paired_agreement_by_source"]["synthetic_fixture"]["mean_score_delta_A_minus_B"], 0)
        self.assertEqual(report["paired_agreement_by_source"]["volunteer_response"]["timing_unknown_count"], 1)
        volunteer_pair = report["participants"][0]["paired_comparison"]
        self.assertEqual(volunteer_pair["timing_status"], "unknown")
        self.assertIsNone(volunteer_pair["observed_administration_order"])
        self.assertIsNone(volunteer_pair["interval_end_to_nextstart_seconds"])
        self.assertTrue(any("unknown timing" in warning for warning in report["warnings"]))
        self.assertEqual(report["source_counts"]["volunteer_response_inputs_unverified"], 2)
        self.assertEqual(report["source_counts"]["synthetic_fixture_inputs"], 2)
        self.assertIn("synthetic_fixture_selection_is_not_empirical_evidence", report["limitations"])
        self.assertTrue(report["observed_response_inputs_unverified"])
        self.assertFalse(report["human_results_claimed"])

        empty_sessions = assessment_pilot.prepare_sessions(self.forms, self.keys, grade="junior", planned_slots=0, seed=1)
        empty = assessment_pilot.analyze_responses(self.forms, self.keys, empty_sessions, {"format": "assessment-study-responses-v1", "responses": []})
        self.assertEqual(empty["participants"], [])
        self.assertEqual(empty["status"], "pending_no_response_inputs")
        self.assertFalse(empty["human_results_claimed"])
        self.assertIsNone(empty["summary_by_source"]["volunteer_response"]["mean_score"])
        self.assertIsNone(empty["paired_agreement_by_source"]["volunteer_response"]["exact_score_agreement"])
        self.assertIsNone(empty["item_statistics_by_source"]["volunteer_response"][0]["difficulty_estimate"])
        self.assertIsNone(empty["item_statistics_by_source"]["volunteer_response"][0]["corrected_item_total_correlation"])
        self.assertIsNone(empty["block_statistics_by_source"]["volunteer_response"][0]["corrected_block_total_correlation"])

    def test_timezone_aware_times_are_normalized_and_paired_interval_is_observed(self):
        sessions = assessment_pilot.prepare_sessions(self.forms, self.keys, grade="junior", planned_slots=1, seed=11)
        responses = []
        for session in sessions["sessions"]:
            form = next(item for item in self.forms["forms"] if item["form_id"] == session["form_id"])
            response = response_for(session, form, self.keys)
            if session["form_id"] == "junior-A":
                response.update({"started_at": "2026-10-08T10:00:00+03:00", "finished_at": "2026-10-08T10:10:00+03:00"})
            else:
                response.update({"started_at": "2026-10-08T07:20:00Z", "finished_at": "2026-10-08T07:30:00Z"})
            responses.append(response)

        report = assessment_pilot.analyze_responses(
            self.forms, self.keys, sessions,
            {"format": "assessment-study-responses-v1", "responses": responses},
        )
        administrations = {row["form_id"]: row for row in report["administrations"]}
        self.assertEqual(administrations["junior-A"]["started_at"], "2026-10-08T07:00:00Z")
        self.assertEqual(administrations["junior-A"]["duration_seconds"], 600)
        pair = report["participants"][0]["paired_comparison"]
        self.assertEqual(pair["timing_status"], "observed")
        self.assertEqual(pair["observed_administration_order"], ["junior-A", "junior-B"])
        self.assertEqual(pair["interval_end_to_nextstart_seconds"], 600)
        self.assertFalse(pair["administrations_overlap"])
        planned_order = [item["form_id"] for item in sorted(sessions["sessions"], key=lambda item: item["administration_order"])]
        self.assertEqual(pair["planned_order_mismatch"], planned_order != ["junior-A", "junior-B"])
        self.assertEqual(report["paired_agreement_by_source"]["volunteer_response"]["timing_known_count"], 1)
        self.assertEqual(report["paired_agreement_by_source"]["volunteer_response"]["timing_unknown_count"], 0)

    def test_invalid_or_partial_timing_is_rejected(self):
        sessions = assessment_pilot.prepare_sessions(self.forms, self.keys, grade="junior", planned_slots=1, seed=12)
        session = sessions["sessions"][0]
        form = next(item for item in self.forms["forms"] if item["form_id"] == session["form_id"])
        base = response_for(session, form, self.keys)
        invalid_times = [
            {"started_at": "2026-10-08T10:00:00", "finished_at": "2026-10-08T10:10:00+03:00"},
            {"started_at": "not-a-time", "finished_at": "2026-10-08T10:10:00+03:00"},
            {"started_at": "2026-10-08T10:10:00+03:00", "finished_at": "2026-10-08T10:00:00+03:00"},
            {"started_at": "2026-10-08T10:00:00+03:00"},
        ]
        for timing in invalid_times:
            with self.subTest(timing=timing), self.assertRaises(assessment_pilot.AssessmentPilotError):
                assessment_pilot.validate_responses(
                    self.forms, self.keys, sessions,
                    {"format": "assessment-study-responses-v1", "responses": [{**base, **timing}]},
                )

    def test_observed_overlapping_administrations_are_flagged(self):
        sessions = assessment_pilot.prepare_sessions(self.forms, self.keys, grade="junior", planned_slots=1, seed=13)
        responses = []
        for session in sessions["sessions"]:
            form = next(item for item in self.forms["forms"] if item["form_id"] == session["form_id"])
            response = response_for(session, form, self.keys)
            if session["form_id"] == "junior-A":
                response.update({"started_at": "2026-10-08T10:00:00Z", "finished_at": "2026-10-08T10:20:00Z"})
            else:
                response.update({"started_at": "2026-10-08T10:10:00Z", "finished_at": "2026-10-08T10:30:00Z"})
            responses.append(response)
        report = assessment_pilot.analyze_responses(
            self.forms, self.keys, sessions,
            {"format": "assessment-study-responses-v1", "responses": responses},
        )
        pair = report["participants"][0]["paired_comparison"]
        self.assertEqual(pair["interval_end_to_nextstart_seconds"], -600)
        self.assertTrue(pair["administrations_overlap"])
        self.assertEqual(report["paired_agreement_by_source"]["volunteer_response"]["overlapping_pair_count"], 1)

    def test_local_writer_refuses_overwrite_and_paths_outside_local(self):
        with tempfile.TemporaryDirectory(dir=assessment_pilot.REPO_ROOT / "local") as directory:
            output = Path(directory) / "report.json"
            assessment_pilot.write_new_json(output, {"status": "pending"})
            self.assertTrue(output.is_file())
            with self.assertRaises(assessment_pilot.AssessmentPilotError):
                assessment_pilot.write_new_json(output, {"status": "replace"})
        with self.assertRaises(assessment_pilot.AssessmentPilotError):
            assessment_pilot.write_new_json(assessment_pilot.REPO_ROOT / "scripts" / "unsafe.json", {})


if __name__ == "__main__":
    unittest.main()
