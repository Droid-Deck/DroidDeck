#!/usr/bin/env python3
"""Fixture checks for tools/check_conventions.py."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_conventions as conv


BODY = textwrap.dedent(
    """\
    ## What

    One change.

    ## Tested

    The checker.

    ## Checklist

    - [x] One topic, branched from current `origin/main`
    - [x] Searched open pull requests for these files
    - [x] `python tools/check_conventions.py` passed
    - [x] No notes, images, logs, or extra agent instruction files

    Depends on: none
    AI-assisted: yes
    """
)


def change(path: str, status: str = "M", patch: str = "", old: str | None = None, utf8: str | None = None):
    return conv.Change(path, status, old, patch, utf8)


class ConventionsTest(unittest.TestCase):
    def evaluate(self, changes=None, commits=None, title="fix: keep the session log", body=BODY, tree=None):
        return conv.evaluate(changes or [], commits if commits is not None else ["fix: keep the session log\n"], title, body, tree or set())

    def rules(self, failures):
        return [failure.rule for failure in failures]

    def test_accepts_a_conventional_change(self):
        self.assertEqual(self.evaluate(), [])

    def test_rejects_a_non_conventional_title_and_subject(self):
        failures = self.evaluate(commits=["Fix: something\n"], title="Add a log")
        self.assertEqual(self.rules(failures), ["commit-subject", "pr-title"])

    def test_rejects_an_empty_section_and_an_open_box(self):
        body = BODY.replace("One change.", "   ").replace("[x]", "[ ]", 1)
        failures = self.evaluate(body=body)
        self.assertIn("pr-template", self.rules(failures))
        self.assertTrue(any("empty" in failure.detail or "still open" in failure.detail for failure in failures))

    def test_rejects_a_bom_and_a_bad_depends_line(self):
        failures = self.evaluate(body="\ufeff" + BODY.replace("Depends on: none", "Depends on: later"))
        details = [failure.detail for failure in failures]
        self.assertTrue(any("BOM" in detail for detail in details))
        self.assertTrue(any("Depends on" in detail for detail in details))

    def test_rejects_notes_images_and_extra_agent_files(self):
        changes = [
            change("docs/progress-log.md", "M", "+ note\n"),
            change("docs/development/look.md", "A", "+ look\n"),
            change("session.log", "A"),
            change("notes.patch", "A"),
            change("docs/CLAUDE.md", "A"),
            change("src/shot.png", "A"),
            change("artwork/icon.png", "A"),
            change("docs/releases/media/0.5.0/clip.gif", "A"),
            change(".cursor/rules/extra.mdc", "A"),
        ]
        rules = self.rules(self.evaluate(changes))
        self.assertEqual(rules.count("notes-file"), 6)
        self.assertEqual(rules.count("binary"), 1)

    def test_allows_the_canonical_entry_points(self):
        changes = [
            change("AGENTS.md", "M", "+ rule\n"),
            change("CLAUDE.md", "A", "+ @AGENTS.md\n"),
            change("GEMINI.md", "A", "+ @AGENTS.md\n"),
            change(".github/copilot-instructions.md", "A", "+ Follow AGENTS.md.\n"),
            change(".cursor/rules/contribute.mdc", "A", "+ stop\n"),
        ]
        self.assertEqual(self.evaluate(changes), [])

    def test_rejects_an_ai_coauthor_and_a_mixed_version_bump(self):
        commits = ["fix: bump\n\nCo-authored-by: Cursor <cursor@example.com>\n"]
        changes = [
            change("app/build.gradle", "M", "@@ -1 +1 @@\n+        versionCode 15\n"),
            change("README.md", "M", "+ more\n"),
        ]
        rules = self.rules(self.evaluate(changes, commits))
        self.assertEqual(rules, ["co-author", "version-isolation"])

    def test_allows_an_isolated_version_bump(self):
        changes = [change(
            "app/build.gradle",
            "M",
            "@@ -108 +108 @@\n-        versionCode 14\n+        versionCode 15\n",
        )]
        self.assertEqual(self.evaluate(changes, ["chore: bump the version\n"], "chore: bump the version"), [])

    def test_rejects_a_missing_relative_link(self):
        changes = [change("docs/quest.md", "M", "+ See [the map](missing-map.md).\n")]
        failures = self.evaluate(changes, tree={"docs/quest.md"})
        self.assertEqual(self.rules(failures), ["doc-link"])

    def test_allows_a_link_that_is_in_the_tree(self):
        changes = [change("docs/quest.md", "M", "+ See [glasses](smart-glasses-support.md).\n")]
        tree = {"docs/quest.md", "docs/smart-glasses-support.md"}
        self.assertEqual(self.evaluate(changes, tree=tree), [])

    def test_cli_rejects_a_new_development_note(self):
        script = Path(__file__).resolve().parent / "check_conventions.py"
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            body = repo / "body.md"
            body.write_text(BODY, encoding="utf-8")
            self.git(repo, "init", "-b", "main")
            (repo / "README.md").write_text("start\n", encoding="utf-8")
            self.git(repo, "add", "README.md")
            self.git(repo, "commit", "-m", "feat: start")
            note = repo / "docs" / "development"
            note.mkdir(parents=True)
            (note / "look.md").write_text("look\n", encoding="utf-8")
            self.git(repo, "add", "docs/development/look.md")
            self.git(repo, "commit", "-m", "feat: record a look")
            result = subprocess.run(
                [sys.executable, str(script), "--base", "main~1", "--head", "HEAD", "--title", "feat: record a look", "--body", str(body)],
                cwd=repo,
                check=False,
                capture_output=True,
                text=True,
            )
        self.assertNotEqual(result.returncode, 0, result.stderr)
        self.assertIn("notes-file:", result.stdout)

    def git(self, repo: Path, *args: str) -> None:
        result = subprocess.run(
            ["git", "-C", str(repo), "-c", "user.name=test", "-c", "user.email=test@example.com", *args],
            check=False,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            raise AssertionError(result.stderr or result.stdout)


if __name__ == "__main__":
    unittest.main()
