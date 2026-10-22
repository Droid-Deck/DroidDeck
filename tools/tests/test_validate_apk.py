import contextlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

RELEASE = Path(__file__).resolve().parents[1] / "release"
sys.path.insert(0, str(RELEASE))
import validate_apk


class ValidateApkTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        subprocess.run(["git", "init", "-q", self.root], check=True)
        self.git("config", "user.name", "Human Developer")
        self.git("config", "user.email", "human@example.org")
        self.git("commit", "--allow-empty", "-qm", "Baseline")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")
        self.statuses = []

    def git(self, *args):
        return subprocess.check_output(["git", "-C", self.root, *args], text=True).strip()

    def validate(self, message="Source change", body="", result="success", stale=False, completed=False, filename="source.kt"):
        (self.root / filename).write_text(message)
        self.git("add", filename)
        self.git("commit", "--allow-empty", "-qm", message)
        sha = self.git("rev-parse", "HEAD")
        pr = {"number": 1, "state": "open", "head": {"sha": sha}, "user": {"login": "human", "type": "User"},
              "title": "Source change", "body": body}
        event = {"number": 1, "pull_request": {"head": {"sha": "stale" if stale else sha}}}
        if completed:
            event = {"workflow_run": {"event": "pull_request", "head_sha": "stale" if stale else sha}}
        path = self.root / "event.json"
        path.write_text(json.dumps(event))

        def api(path, *args):
            if "/statuses/" in path:
                self.statuses.append(dict(value.split("=", 1) for value in args if "=" in value))
                return {}
            if path.endswith("/pulls/1"):
                return pr
            if "/commits/" in path and path.endswith("/pulls"):
                return [pr]
            if "workflows/build.yml/runs?" in path:
                return {"workflow_runs": [{"id": 7, "head_sha": sha, "status": "completed", "html_url": "https://example.org/build"}]}
            if path.endswith("/jobs?per_page=100"):
                return {"jobs": [{"name": "Compile and test APK", "status": "in_progress" if result == "pending" else "completed", "conclusion": result}]}
            raise AssertionError(path)

        env = {"GITHUB_EVENT_PATH": str(path), "GITHUB_REPOSITORY": "owner/repo",
               "GITHUB_EVENT_NAME": "workflow_run" if completed else "pull_request_target", "GITHUB_RUN_ID": "8"}
        with contextlib.chdir(self.root), patch.dict(os.environ, env), patch.object(validate_apk, "api", side_effect=api), patch.object(validate_apk, "fetch_source"):
            validate_apk.main()

    def test_success_requires_compilation_and_clean_authorship(self):
        self.validate()
        self.assertEqual(self.statuses[-1]["state"], "success")

    def test_successful_build_cannot_hide_bot_commit(self):
        with self.assertRaises(RuntimeError):
            self.validate(message="Change\n\nCo-authored-by: OpenAI Codex <codex@openai.com>")
        self.assertEqual(self.statuses[-1]["state"], "failure")

    def test_successful_build_cannot_hide_squash_metadata(self):
        with self.assertRaises(RuntimeError):
            self.validate(body="Co-authored-by: Claude <claude@example.org>")
        self.assertEqual(self.statuses[-1]["state"], "failure")

    def test_pending_or_failed_compilation_never_passes(self):
        for result in ("pending", "failure"):
            self.validate(result=result)
            self.assertEqual(self.statuses[-1]["state"], result)

    def test_superseded_event_cannot_replace_current_status(self):
        self.validate(stale=True)
        self.assertEqual(self.statuses, [])

    def test_completed_build_revalidates_current_squash_metadata(self):
        with self.assertRaises(RuntimeError):
            self.validate(body="Co-authored-by: Claude <claude@example.org>", completed=True)
        self.assertEqual(self.statuses[-1]["state"], "failure")

    def test_superseded_build_cannot_replace_current_status(self):
        self.validate(stale=True, completed=True)
        self.assertEqual(self.statuses, [])

    def test_documentation_passes_without_waiting_for_a_skipped_build(self):
        self.validate(filename="README.md", result="pending")
        self.assertEqual(self.statuses[-1]["state"], "success")

    def test_renaming_code_to_documentation_still_requires_compilation(self):
        (self.root / "source.kt").write_text("source")
        self.git("add", "source.kt")
        self.git("commit", "-qm", "Add source")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")
        self.git("mv", "source.kt", "README.md")
        self.git("commit", "-qm", "Rename source")
        with contextlib.chdir(self.root):
            self.assertFalse(validate_apk.documentation_only(self.git("rev-parse", "HEAD")))
