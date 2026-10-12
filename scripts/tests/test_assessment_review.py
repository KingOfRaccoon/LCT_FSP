import copy
import contextlib
import io
import json
import threading
import tempfile
import unittest
from pathlib import Path

from scripts import assessment_review


def fixture_bank():
    families = []
    for grade in ("junior", "middle"):
        for competency_id in assessment_review.COMPETENCY_IDS:
            for index in range(8):
                family_id = f"{grade}-{competency_id}-family-{index}"
                variants = []
                for variant_index in range(2):
                    variant_id = f"{family_id}-variant-{variant_index}"
                    subquestions = []
                    for question_index in range(2):
                        subquestion_id = f"{variant_id}-question-{question_index}"
                        subquestions.append({
                            "id": subquestion_id,
                            "prompt": f"Question {question_index}",
                            "options": [{"id": f"{subquestion_id}-option-{i}", "text": f"Option {i}"} for i in range(4)],
                            "correct_option_id": f"{subquestion_id}-option-0",
                            "explanation": "A reasoned answer.",
                        })
                    variants.append({"id": variant_id, "prompt": f"Task {variant_index}", "subquestions": subquestions})
                families.append({
                    "id": family_id,
                    "grade": grade,
                    "competency_id": competency_id,
                    "version": 1,
                    "job_task_id": f"job-task-{family_id}",
                    "job_task_description": "A realistic backend task.",
                    "source_refs": [{"title": "Reference", "url": "https://example.test/source", "section": "1"}],
                    "variants": variants,
                })
    return {
        "bank_version": "backend-python-v2.0-draft",
        "status": "draft_ai_authored_pending_human_review",
        "specialization_id": "backend-python-v1",
        "families": families,
    }


class AssessmentReviewTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (assessment_review.REPO_ROOT / "local").mkdir(parents=True, exist_ok=True)

    def test_bank_validation_rejects_coverage_and_missing_keys(self):
        bank = fixture_bank()
        assessment_review.validate_bank(bank)

        undercovered = copy.deepcopy(bank)
        undercovered["families"] = [f for f in undercovered["families"] if f["grade"] != "junior" or f["competency_id"] != assessment_review.COMPETENCY_IDS[0] or f["id"] != f"junior-{assessment_review.COMPETENCY_IDS[0]}-family-7"]
        with self.assertRaises(assessment_review.AssessmentReviewError):
            assessment_review.validate_bank(undercovered)

        unknown_competency = copy.deepcopy(bank)
        unknown_competency["families"][0]["competency_id"] = "unapproved-competency"
        with self.assertRaises(assessment_review.AssessmentReviewError):
            assessment_review.validate_bank(unknown_competency)

        missing_key = copy.deepcopy(bank)
        del missing_key["families"][0]["variants"][0]["subquestions"][0]["correct_option_id"]
        with self.assertRaises(assessment_review.AssessmentReviewError):
            assessment_review.validate_bank(missing_key)

    def test_bank_validation_rejects_global_duplicate_ids_and_incomplete_structure(self):
        bank = fixture_bank()
        duplicate = copy.deepcopy(bank)
        duplicate["families"][1]["variants"][0]["id"] = duplicate["families"][0]["variants"][0]["id"]
        with self.assertRaises(assessment_review.AssessmentReviewError):
            assessment_review.validate_bank(duplicate)

        incomplete = copy.deepcopy(bank)
        incomplete["families"][0]["variants"][0]["subquestions"].pop()
        with self.assertRaises(assessment_review.AssessmentReviewError):
            assessment_review.validate_bank(incomplete)

    def test_bank_validation_rejects_unknown_fields_and_invalid_optional_metadata(self):
        bank = fixture_bank()
        bank["private_email"] = "private@example.test"
        with self.assertRaises(assessment_review.AssessmentReviewError):
            assessment_review.validate_bank(bank)

        for metadata in ({"languages": ["en"]}, {"draft_revision": True}, {"draft_revision": 0}, {"authorship": "  "}):
            invalid = fixture_bank()
            invalid.update(metadata)
            with self.subTest(metadata=metadata), self.assertRaises(assessment_review.AssessmentReviewError):
                assessment_review.validate_bank(invalid)

    def test_bank_validation_rejects_unknown_nested_fields_and_duplicate_option_text(self):
        cases = []
        for path, field in (
            (("families", 0), "private_email"),
            (("families", 0, "source_refs", 0), "secret"),
            (("families", 0, "variants", 0), "gold"),
            (("families", 0, "variants", 0, "subquestions", 0), "notes"),
            (("families", 0, "variants", 0, "subquestions", 0, "options", 0), "answer"),
        ):
            bank = fixture_bank()
            target = bank
            for step in path:
                target = target[step]
            target[field] = "unexpected"
            cases.append(bank)
        for index, bank in enumerate(cases):
            with self.subTest(nested_case=index), self.assertRaises(assessment_review.AssessmentReviewError):
                assessment_review.validate_bank(bank)

        duplicate_text = fixture_bank()
        question = duplicate_text["families"][0]["variants"][0]["subquestions"][0]
        question["options"][1]["text"] = "  " + question["options"][0]["text"].upper() + "  "
        with self.assertRaises(assessment_review.AssessmentReviewError):
            assessment_review.validate_bank(duplicate_text)

    def test_forms_are_reproducible_disjoint_and_contain_no_solver_material(self):
        bank = fixture_bank()
        forms, keys = assessment_review.build_forms(bank, seed=41)
        same_forms, same_keys = assessment_review.build_forms(bank, seed=41)
        self.assertEqual(forms, same_forms)
        self.assertEqual(keys, same_keys)
        self.assertEqual(forms["format"], "assessment-study-forms-v1")
        self.assertEqual(forms["status"], "draft_ai_authored_pending_human_review")
        encoded = assessment_review.canonical_json(forms).decode()
        for forbidden in ("correct_option_id", "explanation", "private", "answer"):
            self.assertNotIn(forbidden, encoded)

        form_by_id = {form["form_id"]: form for form in forms["forms"]}
        by_form = {form_id: {task["family_id"] for task in form["tasks"]} for form_id, form in form_by_id.items()}
        for grade in ("junior", "middle"):
            self.assertEqual(len(by_form[f"{grade}-A"]), 12)
            self.assertEqual(len(by_form[f"{grade}-B"]), 12)
            self.assertFalse(by_form[f"{grade}-A"] & by_form[f"{grade}-B"])
            for suffix in ("A", "B"):
                form = form_by_id[f"{grade}-{suffix}"]
                self.assertEqual(len(form["tasks"]), 12)
                self.assertEqual(sum(len(task["subquestions"]) for task in form["tasks"]), 24)
            for competency_id in assessment_review.COMPETENCY_IDS:
                for suffix in ("A", "B"):
                    tasks = form_by_id[f"{grade}-{suffix}"]["tasks"]
                    self.assertEqual(sum(task["competency_id"] == competency_id for task in tasks), 3)
        self.assertEqual(keys["forms_sha256"], assessment_review.digest_json(forms))

    def test_full_review_packet_covers_bank_without_solver_material(self):
        packet, answers, template = assessment_review.build_review_packet(fixture_bank())
        self.assertEqual(packet["format"], "assessment-expert-review-packet-v1")
        self.assertEqual(len(packet["items"]), 128)
        self.assertEqual(sum(len(item["subquestions"]) for item in packet["items"]), 256)
        encoded = assessment_review.canonical_json(packet).decode()
        self.assertNotIn("correct_option_id", encoded)
        self.assertNotIn("explanation", encoded)
        self.assertEqual(answers["packet_sha256"], assessment_review.digest_json(packet))
        self.assertEqual(template["packet_sha256"], answers["packet_sha256"])
        self.assertEqual(len(answers["answers"]), 256)

    def test_option_permutation_preserves_private_key_option_ids(self):
        forms, keys = assessment_review.build_forms(fixture_bank(), seed=9)
        options_by_question = {
            (form["form_id"], task["task_id"], question["id"]): {option["id"] for option in question["options"]}
            for form in forms["forms"] for task in form["tasks"] for question in task["subquestions"]
        }
        key_by_question = {
            (record["form_id"], record["task_id"], record["subquestion_id"]): record["correct_option_id"]
            for record in keys["keys"]
        }
        correct_positions = [
            next(index for index, option in enumerate(
                question["options"]
            ) if option["id"] == key_by_question[(form["form_id"], task["task_id"], question["id"])])
            for form in forms["forms"] for task in form["tasks"] for question in task["subquestions"]
        ]
        self.assertTrue(any(position != 0 for position in correct_positions))
        for record in keys["keys"]:
            question_key = (record["form_id"], record["task_id"], record["subquestion_id"])
            self.assertIn(record["correct_option_id"], options_by_question[question_key])

    def test_review_import_rejects_stale_hashes_duplicates_and_unknown_ids(self):
        forms, keys = assessment_review.build_forms(fixture_bank(), seed=1)
        question = forms["forms"][0]["tasks"][0]["subquestions"][0]
        base = {
            "format": "assessment-study-reviews-v1",
            "forms_sha256": assessment_review.digest_json(forms),
            "bank_sha256": forms["bank_sha256"],
            "records": [],
            "resolutions": [],
        }
        for reviews in (
            {**base, "forms_sha256": "stale"},
            {**base, "records": [{"form_id": "unknown", "task_id": "x", "subquestion_id": "y", "reviewer_id": "r", "selected_option_id": "z", "rationale": "reason"}]},
            {**base, "records": [
                {"form_id": forms["forms"][0]["form_id"], "task_id": forms["forms"][0]["tasks"][0]["task_id"], "subquestion_id": question["id"], "reviewer_id": "r", "selected_option_id": question["options"][0]["id"], "rationale": "reason"},
            ] * 2},
        ):
            with self.subTest(reviews=reviews), self.assertRaises(assessment_review.AssessmentReviewError):
                assessment_review.import_reviews(forms, keys, reviews)

    def test_review_import_rejects_unknown_formats_even_when_hashes_are_recomputed(self):
        forms, keys = assessment_review.build_forms(fixture_bank(), seed=3)
        unknown_forms = copy.deepcopy(forms)
        unknown_forms["format"] = "assessment-study-forms-v999"
        unknown_keys = copy.deepcopy(keys)
        unknown_keys["forms_sha256"] = assessment_review.digest_json(unknown_forms)
        unknown_reviews = {
            "format": "assessment-study-reviews-v1",
            "forms_sha256": assessment_review.digest_json(unknown_forms),
            "bank_sha256": forms["bank_sha256"], "records": [], "resolutions": [],
        }
        with self.assertRaises(assessment_review.AssessmentReviewError):
            assessment_review.import_reviews(unknown_forms, unknown_keys, unknown_reviews)
        non_string_format = copy.deepcopy(unknown_forms)
        non_string_format["format"] = []
        with self.assertRaises(assessment_review.AssessmentReviewError):
            assessment_review.import_reviews(non_string_format, unknown_keys, unknown_reviews)

        packet, answer_pack, template = assessment_review.build_review_packet(fixture_bank())
        unknown_packet = copy.deepcopy(packet)
        unknown_packet["format"] = "assessment-expert-review-packet-v999"
        unknown_answers = copy.deepcopy(answer_pack)
        unknown_answers["packet_sha256"] = assessment_review.digest_json(unknown_packet)
        unknown_expert_reviews = {
            "format": "assessment-expert-reviews-v1",
            "packet_sha256": unknown_answers["packet_sha256"],
            "bank_sha256": template["bank_sha256"], "records": [], "resolutions": [],
        }
        with self.assertRaises(assessment_review.AssessmentReviewError):
            assessment_review.import_reviews(unknown_packet, unknown_answers, unknown_expert_reviews)

    def test_review_import_rejects_extra_public_packet_fields_after_hash_recalculation(self):
        forms, keys = assessment_review.build_forms(fixture_bank(), seed=3)
        forms["unexpected"] = "private"
        keys["forms_sha256"] = assessment_review.digest_json(forms)
        reviews = {
            "format": "assessment-study-reviews-v1", "forms_sha256": keys["forms_sha256"],
            "bank_sha256": forms["bank_sha256"], "records": [], "resolutions": [],
        }
        with self.assertRaises(assessment_review.AssessmentReviewError):
            assessment_review.import_reviews(forms, keys, reviews)

        packet, answer_pack, template = assessment_review.build_review_packet(fixture_bank())
        packet["unexpected"] = "private"
        answer_pack["packet_sha256"] = assessment_review.digest_json(packet)
        expert_reviews = {
            "format": "assessment-expert-reviews-v1", "packet_sha256": answer_pack["packet_sha256"],
            "bank_sha256": template["bank_sha256"], "records": [], "resolutions": [],
        }
        with self.assertRaises(assessment_review.AssessmentReviewError):
            assessment_review.import_reviews(packet, answer_pack, expert_reviews)

    def test_multiple_reviewer_ids_are_not_reported_as_proof_of_two_humans(self):
        forms, keys = assessment_review.build_forms(fixture_bank(), seed=6)
        form = forms["forms"][0]
        task = form["tasks"][0]
        question = task["subquestions"][0]
        options = question["options"]
        reviews = {
            "format": "assessment-study-reviews-v1",
            "forms_sha256": assessment_review.digest_json(forms),
            "bank_sha256": forms["bank_sha256"],
            "records": [
                {"form_id": form["form_id"], "task_id": task["task_id"], "subquestion_id": question["id"], "reviewer_id": reviewer, "selected_option_id": options[index]["id"], "rationale": "Independent rationale."}
                for reviewer, index in (("reviewer-a", 0), ("reviewer-b", 1))
            ],
            "resolutions": [],
        }
        report = assessment_review.import_reviews(forms, keys, reviews)
        self.assertEqual(report["disagreements_unresolved"], 1)
        self.assertEqual(report["reviewer_ids_count"], 2)
        self.assertIsNone(report["two_human_reviewers_verified"])
        self.assertEqual(report["status"], "human_review_evidence_incomplete")

        resolved = copy.deepcopy(reviews)
        resolved["resolutions"] = [{
            "form_id": form["form_id"], "task_id": task["task_id"], "subquestion_id": question["id"],
            "selected_option_id": options[0]["id"], "resolved_by": "reviewer-a", "rationale": "Explicit decision.",
        }]
        resolved_report = assessment_review.import_reviews(forms, keys, resolved)
        self.assertEqual(resolved_report["disagreements_unresolved"], 0)
        self.assertEqual(resolved_report["disagreements_resolved_explicitly"], 1)
        self.assertIsNone(resolved_report["two_human_reviewers_verified"])

    def test_full_packet_review_records_must_match_packet_and_bank_digests(self):
        packet, answers, template = assessment_review.build_review_packet(fixture_bank())
        item = packet["items"][0]
        question = item["subquestions"][0]
        base = {
            "format": "assessment-expert-reviews-v1",
            "packet_sha256": template["packet_sha256"],
            "bank_sha256": template["bank_sha256"],
            "records": [{
                "packet_sha256": template["packet_sha256"], "bank_sha256": template["bank_sha256"],
                "family_id": item["family_id"], "variant_id": item["variant_id"],
                "subquestion_id": question["id"], "reviewer_id": "reviewer",
                "selected_option_id": question["options"][0]["id"], "rationale": "Review rationale.",
                "job_relevance": None, "grade_alignment": None,
                "unambiguous": None, "accessible_wording": None,
            }],
            "resolutions": [],
        }
        report = assessment_review.import_reviews(packet, answers, base)
        self.assertEqual(report["record_count"], 1)
        stale_record = copy.deepcopy(base)
        stale_record["records"][0]["bank_sha256"] = "stale"
        with self.assertRaises(assessment_review.AssessmentReviewError):
            assessment_review.import_reviews(packet, answers, stale_record)

        missing_answer = copy.deepcopy(answers)
        missing_answer["answers"].pop()
        with self.assertRaises(assessment_review.AssessmentReviewError):
            assessment_review.import_reviews(packet, missing_answer, base)

        boolean_reviewer = copy.deepcopy(base)
        boolean_reviewer["records"][0]["reviewer_id"] = True
        with self.assertRaises(assessment_review.AssessmentReviewError):
            assessment_review.import_reviews(packet, answers, boolean_reviewer)

    def test_full_expert_content_axes_report_missing_and_negative_as_pending(self):
        packet, answers, template = assessment_review.build_review_packet(fixture_bank())
        self.assertEqual(template["record_template"]["job_relevance"], None)
        item = packet["items"][0]
        question = item["subquestions"][0]
        record = {
            "packet_sha256": template["packet_sha256"], "bank_sha256": template["bank_sha256"],
            "family_id": item["family_id"], "variant_id": item["variant_id"],
            "subquestion_id": question["id"], "reviewer_id": "r1",
            "selected_option_id": question["options"][0]["id"], "rationale": "Content review.",
            "job_relevance": None, "grade_alignment": False,
            "unambiguous": True, "accessible_wording": None,
        }
        reviews = {
            "format": "assessment-expert-reviews-v1", "packet_sha256": template["packet_sha256"],
            "bank_sha256": template["bank_sha256"], "records": [record], "resolutions": [],
        }
        report = assessment_review.import_reviews(packet, answers, reviews)
        self.assertEqual(report["technical_key_coverage"]["keyed_questions"], 256)
        self.assertEqual(report["technical_key_coverage"]["total_questions"], 256)
        self.assertTrue(report["technical_key_coverage"]["complete"])
        self.assertFalse(report["content_review_coverage"]["job_relevance"]["complete"])
        self.assertEqual(report["content_review_coverage"]["job_relevance"]["missing"], 512)
        self.assertEqual(report["content_review_coverage"]["grade_alignment"]["negative"], 1)
        self.assertEqual(report["status"], "human_review_evidence_incomplete")
        self.assertFalse(report["content_review_complete"])
        self.assertEqual(report["required_reviewers_per_question"], 2)
        self.assertFalse(report["assessment_release_authorized"])
        self.assertIsNone(report["human_identity_verified"])
        self.assertIsNone(report["reviewer_independence_verified"])

        for invalid in ("yes", 1, 0):
            invalid_review = copy.deepcopy(reviews)
            invalid_review["records"][0]["job_relevance"] = invalid
            with self.subTest(invalid=invalid), self.assertRaises(assessment_review.AssessmentReviewError):
                assessment_review.import_reviews(packet, answers, invalid_review)

    def test_all_positive_expert_axes_still_do_not_authorize_release(self):
        packet, answers, template = assessment_review.build_review_packet(fixture_bank())
        records = []
        for item in packet["items"]:
            for question in item["subquestions"]:
                for reviewer in ("reviewer-one", "reviewer-two"):
                    records.append({
                        "packet_sha256": template["packet_sha256"], "bank_sha256": template["bank_sha256"],
                        "family_id": item["family_id"], "variant_id": item["variant_id"],
                        "subquestion_id": question["id"], "reviewer_id": reviewer,
                        "selected_option_id": question["options"][0]["id"], "rationale": "Reviewed.",
                        "job_relevance": True, "grade_alignment": True,
                        "unambiguous": True, "accessible_wording": True,
                    })
        reviews = {
            "format": "assessment-expert-reviews-v1", "packet_sha256": template["packet_sha256"],
            "bank_sha256": template["bank_sha256"], "records": records, "resolutions": [],
        }
        report = assessment_review.import_reviews(packet, answers, reviews)
        self.assertEqual(report["status"], "human_review_submissions_complete_identity_unverified")
        self.assertTrue(report["content_review_complete"])
        self.assertFalse(report["assessment_release_authorized"])
        self.assertIsNone(report["human_identity_verified"])
        self.assertIsNone(report["reviewer_independence_verified"])

        one_reviewer = copy.deepcopy(reviews)
        one_reviewer["records"] = [record for record in records if record["reviewer_id"] == "reviewer-one"]
        one_report = assessment_review.import_reviews(packet, answers, one_reviewer)
        self.assertFalse(one_report["content_review_complete"])
        self.assertEqual(one_report["questions_reviewed_by_two_or_more"], 0)
        self.assertEqual(one_report["status"], "human_review_evidence_incomplete")
        item = packet["items"][0]
        question = item["subquestions"][0]
        one_reviewer["resolutions"] = [{
            "packet_sha256": template["packet_sha256"], "bank_sha256": template["bank_sha256"],
            "family_id": item["family_id"], "variant_id": item["variant_id"],
            "subquestion_id": question["id"], "selected_option_id": question["options"][0]["id"],
            "resolved_by": "reviewer-two", "rationale": "Explicit resolution.",
        }]
        resolution_report = assessment_review.import_reviews(packet, answers, one_reviewer)
        self.assertEqual(resolution_report["questions_reviewed_by_two_or_more"], 0)
        self.assertFalse(resolution_report["content_review_complete"])

    def test_whitespace_aliases_cannot_satisfy_two_reviewers_per_question(self):
        packet, answers, template = assessment_review.build_review_packet(fixture_bank())
        item = packet["items"][0]
        question = item["subquestions"][0]
        base_record = {
            "packet_sha256": template["packet_sha256"], "bank_sha256": template["bank_sha256"],
            "family_id": item["family_id"], "variant_id": item["variant_id"],
            "subquestion_id": question["id"], "selected_option_id": question["options"][0]["id"],
            "rationale": "Reviewed.", "job_relevance": True, "grade_alignment": True,
            "unambiguous": True, "accessible_wording": True,
        }
        reviews = {
            "format": "assessment-expert-reviews-v1", "packet_sha256": template["packet_sha256"],
            "bank_sha256": template["bank_sha256"], "records": [
                {**base_record, "reviewer_id": "reviewer"},
                {**base_record, "reviewer_id": " reviewer "},
            ], "resolutions": [],
        }
        with self.assertRaisesRegex(assessment_review.AssessmentReviewError, "duplicate"):
            assessment_review.import_reviews(packet, answers, reviews)

    def test_malformed_review_ids_return_domain_error_and_cli_exit_two(self):
        forms, keys = assessment_review.build_forms(fixture_bank(), seed=17)
        reviews = {
            "format": "assessment-study-reviews-v1", "forms_sha256": assessment_review.digest_json(forms),
            "bank_sha256": forms["bank_sha256"], "records": [{
                "form_id": forms["forms"][0]["form_id"], "task_id": [],
                "subquestion_id": forms["forms"][0]["tasks"][0]["subquestions"][0]["id"],
                "reviewer_id": "reviewer", "selected_option_id": "option", "rationale": "Reason.",
            }], "resolutions": [],
        }
        with tempfile.TemporaryDirectory(dir=assessment_review.REPO_ROOT / "local") as temp:
            paths = {name: Path(temp) / f"{name}.json" for name in ("forms", "keys", "reviews")}
            for name, value in (("forms", forms), ("keys", keys), ("reviews", reviews)):
                paths[name].write_text(json.dumps(value), encoding="utf-8")
            with contextlib.redirect_stderr(io.StringIO()):
                exit_code = assessment_review.main([
                    "import-reviews", "--forms", str(paths["forms"]), "--keys", str(paths["keys"]),
                    "--reviews", str(paths["reviews"]), "--out", str(Path(temp) / "report.json"),
                ])
            self.assertEqual(exit_code, 2)

    def test_malformed_private_and_resolution_ids_raise_domain_errors(self):
        forms, keys = assessment_review.build_forms(fixture_bank(), seed=21)
        broken_keys = copy.deepcopy(keys)
        broken_keys["keys"][0]["task_id"] = []
        valid_reviews = {
            "format": "assessment-study-reviews-v1", "forms_sha256": assessment_review.digest_json(forms),
            "bank_sha256": forms["bank_sha256"], "records": [], "resolutions": [],
        }
        with self.assertRaises(assessment_review.AssessmentReviewError):
            assessment_review.import_reviews(forms, broken_keys, valid_reviews)

        packet, answers, template = assessment_review.build_review_packet(fixture_bank())
        item = packet["items"][0]
        question = item["subquestions"][0]
        expert_reviews = {
            "format": "assessment-expert-reviews-v1", "packet_sha256": template["packet_sha256"],
            "bank_sha256": template["bank_sha256"], "records": [], "resolutions": [{
                "packet_sha256": template["packet_sha256"], "bank_sha256": template["bank_sha256"],
                "family_id": item["family_id"], "variant_id": item["variant_id"],
                "subquestion_id": [], "selected_option_id": question["options"][0]["id"],
                "resolved_by": "reviewer", "rationale": "Resolution.",
            }],
        }
        with self.assertRaises(assessment_review.AssessmentReviewError):
            assessment_review.import_reviews(packet, answers, expert_reviews)

        expert_reviews["resolutions"] = []
        expert_reviews["records"] = [{
            "packet_sha256": template["packet_sha256"], "bank_sha256": template["bank_sha256"],
            "family_id": item["family_id"], "variant_id": item["variant_id"], "subquestion_id": question["id"],
            "reviewer_id": "reviewer", "selected_option_id": [], "rationale": "Malformed option.",
            "job_relevance": True, "grade_alignment": True, "unambiguous": True, "accessible_wording": True,
        }]
        with self.assertRaises(assessment_review.AssessmentReviewError):
            assessment_review.import_reviews(packet, answers, expert_reviews)

        expert_reviews["records"] = []
        expert_reviews["resolutions"] = [{
            "packet_sha256": template["packet_sha256"], "bank_sha256": template["bank_sha256"],
            "family_id": item["family_id"], "variant_id": item["variant_id"],
            "subquestion_id": question["id"], "selected_option_id": [],
            "resolved_by": "reviewer", "rationale": "Resolution.",
        }]
        with self.assertRaises(assessment_review.AssessmentReviewError):
            assessment_review.import_reviews(packet, answers, expert_reviews)

    def test_exclusive_report_write_race_preserves_winners_file(self):
        with tempfile.TemporaryDirectory(dir=assessment_review.REPO_ROOT / "local") as temp:
            output = Path(temp) / "report.json"
            barrier = threading.Barrier(2)
            results = []

            def write(payload):
                barrier.wait()
                try:
                    assessment_review._write_json(output, payload)
                    results.append(("written", payload))
                except assessment_review.AssessmentReviewError as error:
                    results.append(("rejected", str(error)))

            writers = [
                threading.Thread(target=write, args=({"writer": "a"},)),
                threading.Thread(target=write, args=({"writer": "b"},)),
            ]
            for thread in writers:
                thread.start()
            for thread in writers:
                thread.join()
            self.assertEqual(sum(kind == "written" for kind, _ in results), 1)
            self.assertEqual(sum(kind == "rejected" for kind, _ in results), 1)
            self.assertIn(json.loads(output.read_text(encoding="utf-8")), ({"writer": "a"}, {"writer": "b"}))

    def test_export_uses_only_local_new_output_directories(self):
        with tempfile.TemporaryDirectory(dir=assessment_review.REPO_ROOT / "local") as parent:
            out = Path(parent) / "new-run"
            assessment_review.export_review_artifacts(fixture_bank(), out, seed=5)
            self.assertTrue((out / "forms.json").is_file())
            self.assertTrue((out / "private_keys.json").is_file())
            self.assertTrue((out / "review_template.json").is_file())
            with self.assertRaises(assessment_review.AssessmentReviewError):
                assessment_review.export_review_artifacts(fixture_bank(), out, seed=5)
            expert_out = Path(parent) / "expert-run"
            assessment_review.export_expert_review_artifacts(fixture_bank(), expert_out)
            self.assertTrue((expert_out / "expert_packet.json").is_file())
            self.assertTrue((expert_out / "expert_answer_pack.json").is_file())
            self.assertTrue((expert_out / "expert_review_template.json").is_file())


if __name__ == "__main__":
    unittest.main()
