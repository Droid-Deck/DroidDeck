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

    def validate(self, message="Source change", body="", result="success", stale=False, completed=False, filename="source.kt", permission="write", role_name="write", fork=False):
        (self.root / filename).parent.mkdir(parents=True, exist_ok=True)
        (self.root / filename).write_text(message)
        self.git("add", filename)
        self.git("commit", "--allow-empty", "-qm", message)
        sha = self.git("rev-parse", "HEAD")
        pr = {"number": 1, "state": "open", "head": {"sha": sha}, "user": {"login": "human", "type": "User"},
              "title": "Source change", "body": body}
        event = {"number": 1, "pull_request": {"head": {"sha": "stale" if stale else sha}}}
        if completed:
            event = {"workflow_run": {"event": "pull_request", "head_sha": "stale" if stale else sha,
                                      "head_branch": "fix/branch", "head_repository": {"owner": {"login": "contributor"}}}}
        path = self.root / "event.json"
        path.write_text(json.dumps(event))

        def api(path, *args):
            if path.endswith("/collaborators/human/permission"):
                return {"permission": permission, "role_name": role_name}
            if "/statuses/" in path:
                self.statuses.append(dict(value.split("=", 1) for value in args if "=" in value))
                return {}
            if path.endswith("/pulls/1"):
                return pr
            if "/commits/" in path and path.endswith("/pulls"):
                return [] if fork else [pr]
            if path.endswith("/pulls?state=open&head=contributor%3Afix%2Fbranch"):
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
        self.validate(stale=True, completed=True, fork=True)
        self.assertEqual(self.statuses, [])

    def test_a_completed_fork_build_reports_on_its_pull_request(self):
        self.validate(completed=True, fork=True)
        self.assertEqual(self.statuses[-1]["state"], "success")

    def test_documentation_passes_without_waiting_for_a_skipped_build(self):
        self.validate(filename="README.md", result="pending")
        self.assertEqual(self.statuses[-1]["state"], "success")

    def test_trusted_list_requires_maintainer_pr_opener(self):
        with self.assertRaisesRegex(RuntimeError, "Only a maintainer"):
            self.validate(filename=".github/trusted-test-builds.txt")
        self.assertEqual(self.statuses[-1]["state"], "failure")

    def test_maintainer_can_change_trusted_list(self):
        self.validate(filename=".github/trusted-test-builds.txt", permission="write", role_name="maintain")
        self.assertEqual(self.statuses[-1]["state"], "success")

    def test_admin_can_change_trusted_list(self):
        self.validate(filename=".github/trusted-test-builds.txt", permission="admin", role_name="admin")
        self.assertEqual(self.statuses[-1]["state"], "success")

    def test_revalidates_pr_metadata_and_recovers_rate_limited_status_post(self):
        (self.root / "source.kt").write_text("clean")
        self.git("add", "source.kt")
        self.git("commit", "--allow-empty", "-qm", "Clean change")
        sha = self.git("rev-parse", "HEAD")
        pulls = [
            {"number": 1, "state": "open", "head": {"sha": sha}, "user": {"login": "human", "type": "User"}, "title": "Clean", "body": ""},
            {"number": 1, "state": "open", "head": {"sha": sha}, "user": {"login": "human", "type": "User"}, "title": "Clean", "body": ""},
            {"number": 1, "state": "open", "head": {"sha": sha}, "user": {"login": "human", "type": "User"}, "title": "Clean", "body": "Co-authored-by: Claude <claude@example.org>"},
        ]
        event = {"number": 1, "pull_request": {"head": {"sha": sha}}}
        path = self.root / "event.json"
        path.write_text(json.dumps(event))
        status_attempts = 0

        def api(endpoint, *args):
            nonlocal status_attempts
            if endpoint.endswith("/pulls/1"):
                return pulls.pop(0) if len(pulls) > 1 else pulls[0]
            if "workflows/build.yml/runs?" in endpoint:
                return {"workflow_runs": [{"id": 7, "head_sha": sha, "status": "completed", "html_url": "https://example.org/build"}]}
            if endpoint.endswith("/jobs?per_page=100"):
                return {"jobs": [{"name": "Compile and test APK", "status": "completed", "conclusion": "success"}]}
            if "/statuses/" in endpoint:
                status_attempts += 1
                if status_attempts == 1:
                    raise subprocess.CalledProcessError(1, ["gh", "api", endpoint], "", "gh: You have exceeded a secondary rate limit.")
                self.statuses.append(dict(value.split("=", 1) for value in args if "=" in value))
                return {}
            raise AssertionError(endpoint)

        env = {"GITHUB_EVENT_PATH": str(path), "GITHUB_REPOSITORY": "owner/repo",
               "GITHUB_EVENT_NAME": "pull_request_target", "GITHUB_RUN_ID": "8"}
        with contextlib.chdir(self.root), patch.dict(os.environ, env), \
                patch.object(validate_apk, "api", side_effect=api), \
                patch.object(validate_apk, "fetch_source"), \
                patch.object(validate_apk, "rate_limit_wait", return_value=7), \
                patch.object(validate_apk.time, "sleep") as sleep:
            validate_apk.main()
        self.assertEqual(status_attempts, 2)
        sleep.assert_called_once_with(60)
        self.assertEqual(self.statuses[-1]["state"], "failure")

    def test_renaming_code_to_documentation_still_requires_compilation(self):
        (self.root / "source.kt").write_text("source")
        self.git("add", "source.kt")
        self.git("commit", "-qm", "Add source")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")
        self.git("mv", "source.kt", "README.md")
        self.git("commit", "-qm", "Rename source")
        with contextlib.chdir(self.root):
            self.assertFalse(validate_apk.documentation_only(self.git("rev-parse", "HEAD")))


class RateLimitTest(unittest.TestCase):
    def call(self, results):
        replies = list(results)
        runs = []

        def run(args, **kwargs):
            runs.append(args)
            code, out, err = replies.pop(0)
            return subprocess.CompletedProcess(args, code, out, err)

        with patch.object(validate_apk.subprocess, "run", side_effect=run), \
                patch.object(validate_apk, "rate_limit_wait", return_value=7), \
                patch.object(validate_apk.time, "sleep") as sleep, \
                contextlib.redirect_stderr(open(os.devnull, "w")):
            try:
                return validate_apk.api("repos/x/pulls/1"), runs, sleep
            except subprocess.CalledProcessError as error:
                return error, runs, sleep

    def test_waits_for_the_rate_limit_then_retries(self):
        result, runs, sleep = self.call([(1, "", "gh: API rate limit exceeded for installation."), (0, '{"number": 1}', "")])
        self.assertEqual(result, {"number": 1})
        self.assertEqual(len(runs), 2)
        sleep.assert_called_once_with(7)

    def test_secondary_rate_limit_waits_at_least_one_minute(self):
        result, runs, sleep = self.call([(1, "", "gh: You have exceeded a secondary rate limit."), (0, '{"number": 1}', "")])
        self.assertEqual(result, {"number": 1})
        self.assertEqual(len(runs), 2)
        sleep.assert_called_once_with(60)

    def test_other_errors_fail_without_waiting(self):
        result, runs, sleep = self.call([(1, "", "gh: Not Found (HTTP 404)")])
        self.assertIsInstance(result, subprocess.CalledProcessError)
        self.assertEqual(len(runs), 1)
        sleep.assert_not_called()

    def test_gives_up_after_the_last_attempt(self):
        result, runs, sleep = self.call([(1, "", "API rate limit exceeded")] * 3)
        self.assertIsInstance(result, subprocess.CalledProcessError)
        self.assertEqual(len(runs), 3)
        self.assertEqual(sleep.call_count, 2)
