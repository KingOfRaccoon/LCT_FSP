import unittest
from pathlib import Path
import tempfile
import json

from scripts.proctoring_analyzer import compute_fraud_metrics, analyze_session_file


class ProctoringAnalyzerTestCase(unittest.TestCase):
    def test_clean_session(self):
        events = [
            {"event_type": "context_menu", "duration_seconds": 0},
        ]
        score, risk, verdict, breakdown = compute_fraud_metrics(events)
        self.assertLessEqual(score, 20)
        self.assertEqual(risk, "clean")
        self.assertIn("CLEAN", verdict)
        self.assertEqual(breakdown["context_menu"], 1)

    def test_suspicious_session(self):
        events = [
            {"event_type": "tab_hidden", "duration_seconds": 1},
            {"event_type": "window_blur", "duration_seconds": 2},
        ]
        score, risk, verdict, _ = compute_fraud_metrics(events)
        self.assertGreater(score, 20)
        self.assertLessEqual(score, 50)
        self.assertEqual(risk, "suspicious")

    def test_fraud_session_with_multiple_violations(self):
        events = [
            {"event_type": "tab_hidden", "duration_seconds": 5},
            {"event_type": "face_missing", "duration_seconds": 3},
            {"event_type": "camera_disabled", "duration_seconds": 10},
        ]
        score, risk, verdict, breakdown = compute_fraud_metrics(events)
        self.assertGreater(score, 50)
        self.assertEqual(risk, "fraud")
        self.assertIn("HIGH_RISK_DETECTED", verdict)
        self.assertEqual(breakdown["camera_disabled"], 1)

    def test_analyze_session_file(self):
        with tempfile.NamedTemporaryFile("w+", delete=False, suffix=".json", encoding="utf-8") as f:
            json.dump({
                "session_id": "test-session-123",
                "attempt_id": "test-attempt-456",
                "events": [{"event_type": "multiple_faces"}]
            }, f)
            temp_path = Path(f.name)

        try:
            report = analyze_session_file(temp_path)
            self.assertEqual(report["session_id"], "test-session-123")
            self.assertEqual(report["attempt_id"], "test-attempt-456")
            self.assertEqual(report["fraud_score"], 25)
            self.assertEqual(report["risk_level"], "suspicious")
            self.assertIsNotNone(report["provenance"]["telemetry_sha256"])
        finally:
            temp_path.unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
