import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

path = Path(__file__).resolve().parents[1] / "release/check_authorship.py"
spec = importlib.util.spec_from_file_location("check_authorship", path)
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class AuthorshipTest(unittest.TestCase):
    def commit(self, author="Human Contributor <human@example.com>", message="Fix a bug\n"):
        return (f"tree {'0' * 40}\nauthor {author} 1 +0000\n"
                "committer GitHub <noreply@github.com> 1 +0000\n\n" + message).encode()

    def test_bot_primary_author_is_rejected_without_a_trailer(self):
        for author in ("OpenAI Codex <codex@openai.com>", "Claude <noreply@anthropic.com>",
                       "github-actions[bot] <actions@example.com>"):
            with self.subTest(author=author):
                self.assertTrue(check.commit_violations("abc", self.commit(author=author)))

    def test_bot_trailers_are_rejected_even_after_human_trailers(self):
        message = "Fix\n\nCo-authored-by: Resul Övür <human@example.com>\nco-AUTHORED-by: OpenAI Codex <codex@openai.com>\n"
        self.assertEqual(1, len(check.commit_violations("abc", self.commit(message=message))))

    def test_human_authors_and_human_coauthors_are_preserved(self):
        message = "Fix the Codex configuration\n\nCo-authored-by: Resul Övür <human@example.com>\n"
        self.assertEqual([], check.commit_violations("abc", self.commit(message=message)))

    def test_record_separator_in_message_does_not_hide_a_bot_trailer(self):
        self.assertTrue(check.commit_violations("abc", self.commit(message="Fix\x1e\nCo-authored-by: Claude <noreply@anthropic.com>\n")))

    def test_pr_metadata_cannot_add_a_bot_to_the_squash_message(self):
        for field in ("title", "body"):
            self.assertTrue(check.event_violations({"pull_request": {field: "Co-authored-by: Copilot <bot@example.com>"}}))

    def test_bot_pr_author_is_rejected(self):
        self.assertTrue(check.event_violations({"pull_request": {"user": {"login": "service", "type": "Bot"}}}))

    def test_people_named_like_assistants_are_not_bots(self):
        for author in ("Claudemirovsky <63046606+Claudemirovsky@users.noreply.github.com>",
                       "Claude Monet <claude@example.com>", "Devin Smith <devin@example.com>",
                       "Cody Allen <cody@example.com>", "Gemini Patel <gp@example.com>"):
            with self.subTest(author=author):
                self.assertEqual([], check.commit_violations("abc", self.commit(author=author)))
        self.assertEqual([], check.event_violations({"pull_request": {"user": {"login": "Claudemirovsky", "type": "User"}}}))

    def test_assistant_identities_are_still_bots(self):
        for author in ("Claude Opus 5.5 <noreply@anthropic.com>", "Claude Code <cc@example.com>",
                       "Cursor Agent <cursoragent@cursor.com>",
                       "Devin AI <devin-ai-integration[bot]@users.noreply.github.com>",
                       "Gemini <gemini@example.com>", "GPT-5 <a@example.com>"):
            with self.subTest(author=author):
                self.assertTrue(check.commit_violations("abc", self.commit(author=author)))
        for login in ("chatgpt-codex-connector", "Copilot", "claude"):
            with self.subTest(login=login):
                self.assertTrue(check.event_violations({"pull_request": {"user": {"login": login, "type": "User"}}}))
        message = "Fix\n\nCo-authored-by: Claude Opus 5.5 <noreply@anthropic.com>\n"
        self.assertEqual(1, len(check.commit_violations("abc", self.commit(message=message))))

    def test_missing_author_fails_closed(self):
        self.assertTrue(check.commit_violations("abc", b"tree 000\n\nmessage\n"))


class InstalledHooksTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.repo = Path(self.directory.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Human Contributor")
        self.git("config", "user.email", "human@example.com")
        installer = path.with_name("install_authorship_hooks.py")
        subprocess.run([sys.executable, str(installer)], cwd=self.repo, check=True, capture_output=True)
        (self.repo / "file").write_text("content")
        self.git("add", "file")

    def tearDown(self):
        self.directory.cleanup()

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.repo, text=True, capture_output=True)

    def test_explicit_bot_author_override_is_blocked(self):
        result = self.git("commit", "--author=OpenAI Codex <codex@openai.com>", "-m", "Fix")
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Bot commit author is forbidden", result.stderr)

    def test_no_verify_does_not_skip_automatic_trailer_scrubbing(self):
        result = self.git("commit", "--no-verify", "-m", "Fix\n\nCo-authored-by: Another Human <other@example.com>\nCo-authored-by: Claude <noreply@anthropic.com>")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        message = self.git("log", "-1", "--format=%B").stdout
        self.assertNotIn("noreply@anthropic.com", message)
        self.assertIn("Co-authored-by: Another Human <other@example.com>", message)

    def test_human_commit_and_human_coauthor_are_allowed(self):
        result = self.git("commit", "-m", "Fix\n\nCo-authored-by: Another Human <other@example.com>")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
