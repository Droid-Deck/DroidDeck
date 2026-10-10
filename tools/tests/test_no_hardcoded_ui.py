"""Detect untranslated direct UI literals in production Kotlin and Java.

This is deliberately a narrow, high-confidence guard: it catches static
Text/BasicText/drawText literals that contain English words. It does NOT
pretend to recognize all possible dynamically assembled UI strings, which
still require a separate manual review.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2] / "app/src/main/java"
DIRECT_TEXT = re.compile(
    r'\b(?:Text|BasicText|drawText)\s*\(\s*"((?:[^"\\]|\\.)*)"'
)
DIRECT_DESCRIPTION = re.compile(
    r'\bcontentDescription\s*=\s*"((?:[^"\\]|\\.)*)"'
)
ENGLISH_WORD = re.compile(r"[A-Za-z]{3,}")


class HardcodedUiLiteralTest(unittest.TestCase):
    def test_no_english_literals_in_direct_ui_text(self) -> None:
        results = []
        for file in ROOT.rglob("*"):
            if file.suffix not in (".kt", ".java"):
                continue
            content = file.read_text(encoding="utf-8")
            for regex in (DIRECT_TEXT, DIRECT_DESCRIPTION):
                for match in regex.finditer(content):
                    literal = match.group(1)
                    # Dynamic expressions, identifiers and UI symbols are
                    # not fixed English copy. Their render paths need review.
                    if "$" in literal or not ENGLISH_WORD.search(literal):
                        continue
                    line = content.count("\n", 0, match.start()) + 1
                    results.append(f"{file.relative_to(ROOT)}:{line}: {literal}")
        self.assertFalse(results, "Hard-coded visible English UI labels:\n" + "\n".join(results))


if __name__ == "__main__":
    unittest.main()
