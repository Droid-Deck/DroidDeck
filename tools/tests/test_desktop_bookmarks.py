import os
import json
from pathlib import Path
import runpy
import tempfile
import unittest
import xml.etree.ElementTree as ET
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


class DolphinPlacesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.home = Path(temporary.name)
        self.data = self.home / ".local/share"
        self.steam = self.data / "Steam"
        (self.steam / "steamapps").mkdir(parents=True)
        self.places = self.data / "user-places.xbel"
        self.environment = patch.dict(os.environ, {}, clear=True)
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def registered(self, libraries):
        entries = []
        for index, (path, label) in enumerate(libraries):
            quote = lambda s: json.dumps(str(s), ensure_ascii=False)
            entries.append(f'"{index}" {{ "path" {quote(path)} "label" {quote(label)} "apps" {{ "1" "0" }} }}')
        (self.steam / "steamapps/libraryfolders.vdf").write_text('"libraryfolders" { ' + " ".join(entries) + ' }')

    def libraries(self):
        return MODULE["steam_libraries"](self.home, self.data)

    def update(self, libraries):
        MODULE["update_places"](self.places, libraries)
        return ET.parse(self.places).getroot()

    def test_internal_and_all_available_registered_libraries_with_escaped_names(self):
        external = self.home / 'USB café "Games"'
        (external / "steamapps").mkdir(parents=True)
        missing = self.home / "removed drive"
        self.registered([(self.steam, ""), (external, 'Games "USB"'), (missing, "Gone")])
        self.assertEqual({self.steam.as_uri(): "Steam (Internal Storage)", external.as_uri(): 'Steam (Games "USB")'}, self.libraries())

    def test_app_list_adds_new_library_before_steam_and_removes_unbound_managed_path(self):
        old = self.home / "Games/old"
        new = self.home / "Games/new"
        for library in (old, new):
            (library / "steamapps").mkdir(parents=True)
        self.registered([(old, "Old")])
        listing = self.home / "libraries.json"
        listing.write_text(json.dumps([{"path": str(new), "label": "New\nLibrary"}]))
        os.environ["BL_STEAM_LIBRARIES"] = str(listing)
        self.assertEqual({self.steam.as_uri(): "Steam (Internal Storage)", new.as_uri(): "Steam (New Library)"}, self.libraries())

    def test_unreadable_app_list_falls_back_to_registered_libraries(self):
        external = self.home / "USB"
        (external / "steamapps").mkdir(parents=True)
        self.registered([(external, "Drive")])
        os.environ["BL_STEAM_LIBRARIES"] = str(self.home / "missing.json")
        self.assertIn(external.as_uri(), self.libraries())

    def test_xml_escaping_stable_ids_and_idempotence(self):
        libraries = {self.steam.as_uri(): "Steam (A & <B>)"}
        root = self.update(libraries)
        self.assertEqual("Steam (A & <B>)", root.findtext("bookmark/title"))
        identity = root.findtext("bookmark/info/metadata[@owner='http://www.kde.org']/ID")
        before = self.places.stat().st_mtime_ns
        self.update(libraries)
        self.assertEqual(before, self.places.stat().st_mtime_ns)
        root = self.update({self.steam.as_uri(): "Renamed"})
        self.assertEqual(identity, root.findtext("bookmark/info/metadata[@owner='http://www.kde.org']/ID"))
        self.assertEqual("Renamed", root.findtext("bookmark/title"))

    def test_personal_places_and_metadata_survive_unplugging_and_returning(self):
        personal = '<bookmark href="file:///root/Documents"><title>Mine</title><info><metadata owner="custom"><private>keep</private></metadata></info></bookmark>'
        self.places.write_text('<xbel version="1.0"><!--keep comment-->' + personal + '</xbel>')
        self.update({self.steam.as_uri(): "Steam"})
        root = self.update({})
        self.assertEqual(["file:///root/Documents"], [b.get("href") for b in root.findall("bookmark")])
        self.assertEqual("keep", root.findtext("bookmark/info/metadata/private"))
        self.assertIn("<!--keep comment-->", self.places.read_text())
        self.assertEqual(2, len(self.update({self.steam.as_uri(): "Steam"}).findall("bookmark")))

    def test_personal_place_at_library_url_is_not_duplicated_renamed_or_removed(self):
        self.places.write_text(f'<xbel><bookmark href="{self.steam.as_uri()}"><title>My games</title></bookmark></xbel>')
        before = self.places.read_bytes()
        self.update({self.steam.as_uri(): "Steam"})
        self.update({})
        self.assertEqual(before, self.places.read_bytes())

    def test_user_hidden_state_on_managed_place_is_kept(self):
        root = self.update({self.steam.as_uri(): "Steam"})
        metadata = root.find("bookmark/info/metadata[@owner='http://www.kde.org']")
        ET.SubElement(metadata, "IsHidden").text = "true"
        self.places.write_bytes(ET.tostring(root))
        root = self.update({self.steam.as_uri(): "Renamed"})
        self.assertEqual("true", root.findtext("bookmark/info/metadata/IsHidden"))

    def test_personal_place_with_trailing_slash_is_not_duplicated(self):
        self.places.write_text(f'<xbel><bookmark href="{self.steam.as_uri()}/"><title>My games</title></bookmark></xbel>')
        root = self.update({self.steam.as_uri(): "Steam"})
        self.assertEqual(1, len(root.findall("bookmark")))
        self.assertEqual("My games", root.findtext("bookmark/title"))

    def test_invalid_xml_is_not_overwritten(self):
        self.places.write_text("<xbel>unfinished")
        with self.assertRaises(ET.ParseError):
            self.update({self.steam.as_uri(): "Steam"})
        self.assertEqual("<xbel>unfinished", self.places.read_text())

    def test_new_places_leave_default_version_for_kde_to_initialize(self):
        root = self.update({self.steam.as_uri(): "Steam"})
        self.assertEqual("1.0", root.get("version"))
        self.assertIsNone(root.find("info/metadata/Version"))


if __name__ == "__main__":
    unittest.main()
