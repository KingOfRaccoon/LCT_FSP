import json
import tempfile
import unittest
from pathlib import Path

from scripts import blind_review


def fixture_dataset():
    needs = [{
        "id": "need-1", "split": "development", "specialization_id": "backend-python",
        "target_grade": "junior", "work_mode": "remote", "city": None,
        "workload_hours_per_week": 20,
        "requirements": [{"skill_id": "python", "importance": "required"}],
        "synthetic_template_instance": "role-secret", "title": "Public role",
    }]
    profiles = []
    for i in range(4):
        profiles.append({
            "candidate_id": f"candidate-{i}", "label": i % 4,
            "label_reason": f"secret reason {i}", "hard_incompatibility": None,
            "label_provenance": "synthetic secret provenance",
            "profile": {
                "id": f"candidate-{i}", "specialization_id": "backend-python",
                "verified_grade": "junior", "declared_grade": "junior",
                "visibility": "visible", "email_confirmed": True,
                "allowed_work_modes": ["remote"], "cities": [], "max_weekly_hours": 40,
                "test_evidenced_skills": ["python"], "candidate_declared_skills": [],
                "demo": True, "synthetic_template_instance": f"profile-secret-{i}",
                "synthetic_profile_text": f"Synthetic profile {i}",
                "email": f"person{i}@example.test", "phone": "+70000000000",
                "contact_key": "contact-secret", "score": 42, "rank": i + 1,
            },
        })
    return {
        "benchmark_id": "benchmark-secret", "policy_sha256": "policy-digest",
        "label_schema": {"secret": "synthetic expected labels"},
        "needs": needs,
        "judgment_sets": [{"need_id": "need-1", "split": "development", "profiles": profiles}],
    }


def eligible_pool(packet, template, key, candidates):
    return {
        "packet_sha256": template["packet_sha256"],
        "dataset_sha256": template["dataset_sha256"],
        "policy_sha256": key["policy_sha256"],
        "pools": {"need-1": candidates},
    }


class BlindReviewTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (blind_review.REPO_ROOT / "local").mkdir(parents=True, exist_ok=True)

    def test_export_is_seed_deterministic_and_excludes_private_and_contact_data(self):
        dataset = fixture_dataset()
        with tempfile.TemporaryDirectory(dir=blind_review.REPO_ROOT / "local") as a, \
                tempfile.TemporaryDirectory(dir=blind_review.REPO_ROOT / "local") as b:
            packet_a, key_a, template_a = blind_review.export_dataset(dataset, Path(a), seed=19)
            packet_b, key_b, template_b = blind_review.export_dataset(dataset, Path(b), seed=19)
            self.assertEqual(packet_a, packet_b)
            encoded = json.dumps(packet_a)
            for forbidden in ("candidate-0", "need-1", "secret reason", "role-secret", "profile-secret", "example.test", "contact-secret", '"label"', '"score"', '"rank"'):
                self.assertNotIn(forbidden, encoded)
            self.assertEqual(key_a, key_b)
            self.assertEqual(template_a, template_b)
            self.assertEqual(len({r["case_id"] for r in packet_a["cases"]}), 4)
            self.assertEqual(template_a["packet_sha256"], blind_review.digest_json(packet_a))

    def test_score_marks_incomplete_judgments_null_and_disagreement_unresolved(self):
        with tempfile.TemporaryDirectory(dir=blind_review.REPO_ROOT / "local") as temp:
            packet, key, template = blind_review.export_dataset(fixture_dataset(), Path(temp), seed=2)
            cases = packet["cases"]
            reviews = {"packet_sha256": template["packet_sha256"], "dataset_sha256": template["dataset_sha256"], "records": [
                {"case_id": cases[0]["case_id"], "reviewer_id": "r1", "label": 3, "rationale": "fit"},
                {"case_id": cases[0]["case_id"], "reviewer_id": "r2", "label": 0, "rationale": "no"},
            ]}
            rankings = {"need-1": [f"candidate-{i}" for i in range(4)]}
            report = blind_review.score_reviews(packet, key, reviews, rankings, eligible_pool(packet, template, key, [f"candidate-{i}" for i in range(4)]))
            self.assertEqual(report["status"], "review_inputs_unverified")
            self.assertEqual(report["disagreements_unresolved"], 1)
            self.assertEqual(report["unknown_count"], 3)
            self.assertIsNone(report["metrics"][0]["p_at_3"])
            self.assertIsNone(report["metrics"][0]["ndcg_at_5"])
            self.assertEqual(report["metrics"][0]["unjudged_ranked_count"], 4)

    def test_fixed_denominator_p_at_3_and_zero_idcg(self):
        with tempfile.TemporaryDirectory(dir=blind_review.REPO_ROOT / "local") as temp:
            packet, key, template = blind_review.export_dataset(fixture_dataset(), Path(temp), seed=8)
            cases = packet["cases"]
            # Map cases back through the private key only to construct reviewer input.
            by_candidate = {v["candidate_id"]: case for case, v in key["case_map"].items()}
            records = []
            for reviewer in ("a", "b"):
                for candidate, label in (("candidate-0", 2), ("candidate-1", 3), ("candidate-2", 0), ("candidate-3", 0)):
                    records.append({"case_id": by_candidate[candidate], "reviewer_id": reviewer, "label": label, "rationale": "reviewed"})
            reviews = {"packet_sha256": template["packet_sha256"], "dataset_sha256": template["dataset_sha256"], "records": records}
            pool = eligible_pool(packet, template, key, ["candidate-0", "candidate-1"])
            report = blind_review.score_reviews(packet, key, reviews, {"need-1": ["candidate-0"]}, pool)
            metric = report["metrics"][0]
            self.assertEqual(metric["p_at_3"], 1 / 3)
            self.assertAlmostEqual(metric["ndcg_at_5"], 3 / (7 + 3 / 1.584962500721156))
            empty_report = blind_review.score_reviews(packet, key, reviews, {"need-1": []}, pool)
            self.assertEqual(empty_report["metrics"][0]["ndcg_at_5"], 0.0)
            zero_reviews = {**reviews, "records": [
                {"case_id": by_candidate[f"candidate-{i}"], "reviewer_id": reviewer, "label": 0, "rationale": "reviewed"}
                for reviewer in ("a", "b") for i in range(4)
            ]}
            zero_report = blind_review.score_reviews(packet, key, zero_reviews, {"need-1": ["candidate-0", "candidate-1"]}, pool)
            self.assertIsNone(zero_report["metrics"][0]["ndcg_at_5"])

    def test_unjudged_omitted_eligible_candidate_makes_ndcg_null(self):
        with tempfile.TemporaryDirectory(dir=blind_review.REPO_ROOT / "local") as temp:
            packet, key, template = blind_review.export_dataset(fixture_dataset(), Path(temp), seed=11)
            by_candidate = {v["candidate_id"]: case for case, v in key["case_map"].items()}
            records = [
                {"case_id": by_candidate[f"candidate-{i}"], "reviewer_id": reviewer, "label": 2 if i == 0 else (None if i == 3 else 0), "rationale": "reviewed"}
                for reviewer in ("a", "b") for i in range(4)
            ]
            reviews = {"packet_sha256": template["packet_sha256"], "dataset_sha256": template["dataset_sha256"], "records": records}
            pools = eligible_pool(packet, template, key, ["candidate-0", "candidate-1", "candidate-2", "candidate-3"])
            report = blind_review.score_reviews(packet, key, reviews, {"need-1": ["candidate-0"]}, pools)
            self.assertIsNone(report["metrics"][0]["ndcg_at_5"])
            missing_need_pool = {**pools, "pools": {}}
            missing_report = blind_review.score_reviews(packet, key, reviews, {"need-1": ["candidate-0"]}, missing_need_pool)
            self.assertIsNone(missing_report["metrics"][0]["ndcg_at_5"])

    def test_eligible_pool_rejects_rank_outside_pool_and_stale_hash(self):
        with tempfile.TemporaryDirectory(dir=blind_review.REPO_ROOT / "local") as temp:
            packet, key, template = blind_review.export_dataset(fixture_dataset(), Path(temp), seed=12)
            pool = eligible_pool(packet, template, key, ["candidate-0"])
            empty_reviews = {"packet_sha256": template["packet_sha256"], "dataset_sha256": template["dataset_sha256"], "records": []}
            with self.assertRaises(blind_review.BlindReviewError):
                blind_review.score_reviews(packet, key, empty_reviews, {"need-1": ["candidate-1"]}, pool)
            for field in ("packet_sha256", "dataset_sha256", "policy_sha256"):
                stale = {**pool, field: "stale"}
                with self.subTest(hash=field), self.assertRaises(blind_review.BlindReviewError):
                    blind_review.score_reviews(packet, key, empty_reviews, {"need-1": []}, stale)

    def test_rejects_stale_hashes_duplicates_unknown_cases_and_invalid_labels(self):
        with tempfile.TemporaryDirectory(dir=blind_review.REPO_ROOT / "local") as temp:
            packet, key, template = blind_review.export_dataset(fixture_dataset(), Path(temp), seed=3)
            case_id = packet["cases"][0]["case_id"]
            base = {"packet_sha256": template["packet_sha256"], "dataset_sha256": template["dataset_sha256"], "records": []}
            for reviews, rankings, message in [
                ({**base, "packet_sha256": "stale"}, {}, "packet hash"),
                ({**base, "dataset_sha256": "stale"}, {}, "dataset hash"),
                ({**base, "gold": [0, 1, 2]}, {}, "gold import"),
                ({**base, "records": [{"case_id": "unknown", "reviewer_id": "r", "label": 1}]}, {}, "unknown case"),
                ({**base, "records": [{"case_id": case_id, "reviewer_id": "r", "label": True}]}, {}, "label"),
                ({**base, "records": [{"case_id": case_id, "reviewer_id": "r", "label": 1}, {"case_id": case_id, "reviewer_id": "r", "label": 2}]}, {}, "duplicate"),
                (base, {"unknown-need": ["candidate-0"]}, "unknown need"),
                (base, {"need-1": ["candidate-0", "candidate-0"]}, "duplicate ranking"),
                (base, {"need-1": ["ghost"]}, "unknown candidate"),
            ]:
                with self.subTest(message=message), self.assertRaises(blind_review.BlindReviewError):
                    blind_review.score_reviews(packet, key, reviews, rankings)


if __name__ == "__main__":
    unittest.main()
