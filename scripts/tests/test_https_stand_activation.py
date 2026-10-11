import json
import os
import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
LAUNCHER = ROOT / "infra" / "run-api.sh"


class HttpsStandActivationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.argv_file = self.root / "argv.json"
        fake_java = self.bin / "java"
        fake_java.write_text(
            "#!/bin/sh\n"
            "python3 -c 'import json,sys; json.dump(sys.argv[2:], open(sys.argv[1], \"w\"))' "
            f"'{self.argv_file}' \"$@\"\n",
            encoding="utf-8",
        )
        fake_java.chmod(0o755)
        self.env = {"PATH": f"{self.bin}{os.pathsep}{os.environ['PATH']}"}

    def run_launcher(self, origin, args=(), extra_env=None):
        env = self.env.copy()
        if origin is not None:
            env["PUBLIC_ORIGIN"] = origin
        if extra_env:
            env.update(extra_env)
        completed = subprocess.run(
            ["sh", str(LAUNCHER), *args],
            env=env,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        return json.loads(self.argv_file.read_text(encoding="utf-8"))

    def test_https_stand_forces_recording_and_secure_cookie_over_false_environment(self):
        argv = self.run_launcher(
            "https://hirundo-team.space",
            ["--fsp.seed-demo=true"],
            {"FSP_PROCTORING_ENABLED": "false", "SESSION_COOKIE_SECURE": "false"},
        )
        self.assertEqual(argv[:2], ["-jar", "/app/app.jar"])
        self.assertIn("--fsp.seed-demo=true", argv)
        self.assertIn("--fsp.proctoring.enabled=true", argv)
        self.assertIn("--fsp.session-cookie-secure=true", argv)

    def test_https_stand_trailing_slash_is_accepted(self):
        argv = self.run_launcher("https://hirundo-team.space/", ["--fsp.seed-demo=true"])
        self.assertIn("--fsp.proctoring.enabled=true", argv)
        self.assertIn("--fsp.session-cookie-secure=true", argv)

    def test_http_loopback_and_other_origins_keep_existing_arguments(self):
        for origin in ("http://hirundo-team.space", "http://localhost:8080", "https://other.example"):
            with self.subTest(origin=origin):
                argv = self.run_launcher(origin, ["--fsp.seed-demo=true"])
                self.assertEqual(argv, ["-jar", "/app/app.jar", "--fsp.seed-demo=true"])

    def test_empty_origin_and_shell_metacharacters_are_passed_literally(self):
        marker = self.root / "must-not-exist"
        special = f"argument with spaces $(touch {marker}) ; literal"
        argv = self.run_launcher("", [special])
        self.assertEqual(argv, ["-jar", "/app/app.jar", special])
        self.assertFalse(marker.exists())

    def test_api_image_and_compose_use_launcher_without_changing_worker(self):
        dockerfile = (ROOT / "infra" / "backend.Dockerfile").read_text(encoding="utf-8")
        compose = (ROOT / "compose.yml").read_text(encoding="utf-8")
        self.assertIn("COPY --chmod=0755 infra/run-api.sh /app/run-api.sh", dockerfile)
        self.assertIn('CMD ["/app/run-api.sh"]', dockerfile)
        self.assertIn('command: ["/app/run-api.sh", "--fsp.seed-demo=true"]', compose)
        self.assertIn('command: ["java", "-jar", "/app/app.jar", "--spring.main.web-application-type=none", "--fsp.outbox-enabled=true"]', compose)


if __name__ == "__main__":
    unittest.main()
