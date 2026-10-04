import os
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch


HELPER = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-desktop-bookmarks"
MODULE = runpy.run_path(str(HELPER))


class DesktopBookmarksTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.home = Path(temporary.name)
        self.config = self.home / ".config"
        self.bookmarks = self.config / "gtk-3.0/bookmarks"

    def test_add_and_update_preserve_user_bookmarks_without_duplicates(self):
        self.bookmarks.parent.mkdir(parents=True)
        self.bookmarks.write_text("file:///root/Documents My Documents\nfile:///root/Downloads Downloads")
        MODULE["update_bookmark"](self.bookmarks, "SD Card")
        expected = "file:///root/Documents My Documents\nfile:///root/Downloads Downloads\nfile:///mnt/droiddeck-sd SD Card\n"
        self.assertEqual(self.bookmarks.read_text(), expected)
        before = self.bookmarks.stat().st_mtime_ns
        MODULE["update_bookmark"](self.bookmarks, "SD Card")
        self.assertEqual(self.bookmarks.stat().st_mtime_ns, before)
        MODULE["update_bookmark"](self.bookmarks, "Games\nfile:///unexpected Injected")
        self.assertEqual(len(self.bookmarks.read_text().splitlines()), 3)
        self.assertIn("file:///mnt/droiddeck-sd Games file:///unexpected Injected\n", self.bookmarks.read_text())

    def test_removing_unavailable_library_keeps_personal_bookmarks(self):
        MODULE["update_bookmark"](self.bookmarks, "SD Card")
        with self.bookmarks.open("a") as stream:
            stream.write("file:///root/Documents My Documents\n")
        MODULE["update_bookmark"](self.bookmarks, None)
        self.assertEqual(self.bookmarks.read_text(), "file:///root/Documents My Documents\n")
        absent = self.home / "missing/bookmarks"
        MODULE["update_bookmark"](absent, None)
        self.assertFalse(absent.exists())

    def test_file_selection_matches_libfm_legacy_fallback(self):
        legacy = self.home / ".gtk-bookmarks"
        legacy.write_text("file:///root/Documents My Documents\n")
        self.assertEqual(MODULE["bookmarks_file"](self.home, self.config), legacy)
        self.bookmarks.parent.mkdir(parents=True)
        self.bookmarks.touch()
        self.assertEqual(MODULE["bookmarks_file"](self.home, self.config), legacy)
        self.bookmarks.write_text("file:///root/Downloads Downloads\n")
        self.assertEqual(MODULE["bookmarks_file"](self.home, self.config), self.bookmarks)

    def test_main_requires_selected_library_and_usable_bind(self):
        library = self.home / "library"
        library.mkdir()
        with patch.object(Path, "home", return_value=self.home), patch.dict(os.environ, {"XDG_CONFIG_HOME": str(self.config), "BL_LIBRARY_LABEL": "SD Card"}), patch.dict(MODULE["main"].__globals__, {"LIBRARY": library}):
            MODULE["main"]()
            self.assertFalse(self.bookmarks.exists())
            (library / "steamapps").mkdir()
            MODULE["main"]()
            self.assertEqual(self.bookmarks.read_text(), "file:///mnt/droiddeck-sd SD Card\n")
            del os.environ["BL_LIBRARY_LABEL"]
            MODULE["main"]()
            self.assertEqual(self.bookmarks.read_text(), "")


if __name__ == "__main__":
    unittest.main()
