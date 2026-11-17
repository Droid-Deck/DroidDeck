import unittest

from tools.release.update_catalog import ApiError, in_preview, iso_millis, keeps_test_build
from tools.release.retire_test_builds import retired

MERGED = "2026-11-14T20:00:00Z"
NOW = iso_millis(MERGED) + 3600_000


class FakeSource:
    def __init__(self, status=None):
        self.status = status
        self.asked = []

    def get(self, path):
        self.asked.append(path)
        if self.status is None:
            raise ApiError(404, "no")
        return {"status": self.status}


def pull(state="closed", merged_at=MERGED, draft=False, base="main"):
    return {"state": state, "merged_at": merged_at, "merge_commit_sha": "a" * 40, "draft": draft, "base": {"ref": base}}


class RetireTestBuildsTest(unittest.TestCase):
    def test_an_open_pr_keeps_its_build(self):
        self.assertFalse(retired(pull(state="open", merged_at=None), FakeSource("ahead"), "o/r", "b" * 40))

    def test_a_closed_unmerged_pr_loses_it_at_once(self):
        self.assertTrue(retired(pull(merged_at=None), FakeSource(), "o/r", None))

    def test_a_merged_pr_keeps_it_until_a_preview_carries_the_merge(self):
        self.assertFalse(in_preview(FakeSource("behind"), "o/r", pull(), "b" * 40, NOW))
        self.assertFalse(in_preview(FakeSource(), "o/r", pull(), "b" * 40, NOW))
        self.assertFalse(in_preview(FakeSource("ahead"), "o/r", pull(), None, NOW))
        self.assertTrue(in_preview(FakeSource("ahead"), "o/r", pull(), "b" * 40, NOW))
        self.assertTrue(in_preview(FakeSource("identical"), "o/r", pull(), "a" * 40, NOW))

    def test_a_merge_no_preview_ever_carries_is_let_go_after_the_grace(self):
        self.assertTrue(in_preview(FakeSource(), "o/r", pull(), None, NOW + 4 * 86_400_000))

    def test_the_catalog_lists_open_and_merged_main_prs(self):
        self.assertTrue(keeps_test_build(pull(state="open", merged_at=None)))
        self.assertTrue(keeps_test_build(pull()))
        self.assertFalse(keeps_test_build(pull(merged_at=None)))
        self.assertFalse(keeps_test_build(pull(draft=True)))
        self.assertFalse(keeps_test_build(pull(base="release")))


if __name__ == "__main__":
    unittest.main()
