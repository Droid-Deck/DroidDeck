import importlib.util
from pathlib import Path
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

    def test_missing_author_fails_closed(self):
        self.assertTrue(check.commit_violations("abc", b"tree 000\n\nmessage\n"))


if __name__ == "__main__":
    unittest.main()
