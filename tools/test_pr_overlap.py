#!/usr/bin/env python3
"""Fixture checks for tools/pr_overlap.py."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pr_overlap as overlap


def edit(name, status="modified", ranges=(), omitted=False):
    return overlap.FileEdit(name, status, tuple(ranges), omitted)


class OverlapTest(unittest.TestCase):
    def test_parses_added_line_ranges_and_skips_deletions(self):
        patch = "@@ -10,2 +20,3 @@\n@@ -4 +0,0 @@\n"
        self.assertEqual(overlap.parse_ranges(patch), ((20, 22),))

    def test_comments_on_the_newer_pull_request(self):
        pulls = [overlap.Pull(4, "Depends on: none"), overlap.Pull(9, "Depends on: none")]
        files = {
            4: [edit("app/Main.kt", ranges=((10, 20),))],
            9: [edit("app/Main.kt", ranges=((18, 24),))],
        }
        wanted = overlap.plan_comments(pulls, files)
        self.assertEqual(list(wanted), [9])
        self.assertIn("#4", wanted[9])
        self.assertIn("lines 18-24 overlap 10-20", wanted[9])
        self.assertIn(overlap.MARKER, wanted[9])

    def test_same_added_file_overlaps_and_adjacent_lines_do_not(self):
        added = overlap.file_reasons(
            [edit("new.kt", "added")],
            [edit("new.kt", "added")],
        )
        adjacent = overlap.file_reasons(
            [edit("app/Main.kt", ranges=((10, 12),))],
            [edit("app/Main.kt", ranges=((13, 15),))],
        )
        self.assertEqual(added, ["adds `new.kt`"])
        self.assertEqual(adjacent, [])

    def test_depends_on_suppresses_the_pair(self):
        pulls = [overlap.Pull(4, ""), overlap.Pull(9, "Depends on: #4")]
        files = {
            4: [edit("new.kt", "added")],
            9: [edit("new.kt", "added")],
        }
        self.assertEqual(overlap.plan_comments(pulls, files), {})

    def test_omitted_patches_are_reported(self):
        reasons = overlap.file_reasons(
            [edit("big.kt", omitted=True)],
            [edit("big.kt", omitted=True)],
        )
        self.assertEqual(reasons, ["both edit `big.kt`, and the patch was omitted"])


if __name__ == "__main__":
    unittest.main()
