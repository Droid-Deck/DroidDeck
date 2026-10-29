import contextlib
import os
from pathlib import Path
import runpy
import struct
import sys
import tempfile
import unittest
from unittest import mock


BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
SOURCE = BIN / "steam-compatibility"
COMPAT = runpy.run_path(str(SOURCE))
RUN = runpy.run_path(str(BIN / "droiddeck-proton-run"))["main"].__globals__
APPINFO = runpy.run_path(str(Path(__file__).with_name("test_droiddeck_fex.py")))["AppInfoTest"].appinfo
FORMATS = (0x07564427, 0x07564428, 0x07564429)


def entry(name):
    return {
        "common": {"type": "Game", "oslist": "windows,linux", "osarch": "64",
                   "steam_deck_compatibility": {"configuration": {"recommended_runtime": "proton_experimental"}}},
        "config": {"launch": {
            "0": {"executable": name + ".exe", "arguments": "--profile default", "workingdir": ".",
                  "type": "default", "config": {"oslist": "windows"}},
            "1": {"executable": name + ".x86_64", "config": {"oslist": "linux"}},
        }},
    }


@contextlib.contextmanager
def decoded_sections():
    calls = []
    previous = sys.getprofile()

    def profile(frame, event, _arg):
        if event == "call" and frame.f_code.co_name == "section" and frame.f_code.co_filename == str(SOURCE):
            calls.append(1)

    sys.setprofile(profile)
    try:
        yield calls
    finally:
        sys.setprofile(previous)


class AppInfoLookupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "appinfo.vdf"
        COMPAT["APPINFO_CACHE"].clear()
        self.addCleanup(COMPAT["APPINFO_CACHE"].clear)

    def write(self, records, magic=FORMATS[-1]):
        self.path.write_bytes(APPINFO(records, magic))

    def lookup(self, wanted=None):
        return COMPAT["appinfo_entries"](str(self.path), wanted=wanted)

    def test_requested_metadata_matches_complete_results_in_each_format(self):
        for magic in FORMATS:
            for wanted in ({"1"}, {"2", "3"}, {"9"}, {"1", "9"}, set()):
                with self.subTest(magic=magic, wanted=wanted):
                    self.write({1: entry("One"), 2: entry("Two"), 3: entry("Three")}, magic)
                    COMPAT["APPINFO_CACHE"].clear()
                    full = self.lookup()
                    COMPAT["APPINFO_CACHE"].clear()
                    selected = self.lookup(wanted)
                    self.assertEqual(selected, {app: full[app] for app in wanted if app in full})
                    if "1" in selected:
                        self.assertEqual(selected["1"]["oslist"], {"windows", "linux"})
                        self.assertEqual(selected["1"]["launch"], ["one.exe", "one.x86_64"])
                        self.assertEqual(selected["1"]["launch_options"][0]["arguments"], "--profile default")

    def test_partial_then_complete_query_returns_every_entry(self):
        self.write({1: entry("One"), 2: entry("Two"), 3: entry("Three")})
        first = self.lookup({"1"})
        self.assertIsInstance(COMPAT["APPINFO_CACHE"][str(self.path)][3], bytes)
        with mock.patch("builtins.open", side_effect=AssertionError("snapshot must be reused")):
            full = self.lookup()
            self.assertEqual(set(full), {"1", "2", "3"})
            self.assertEqual(full["1"], first["1"])
            self.assertEqual(self.lookup({"2"}), {"2": full["2"]})
        self.assertIsNone(COMPAT["APPINFO_CACHE"][str(self.path)][3])

    def test_complete_then_partial_query_uses_the_complete_cache(self):
        self.write({1: entry("One"), 2: entry("Two")})
        full = self.lookup()
        with mock.patch("builtins.open", side_effect=AssertionError("cached lookup must not open the file")):
            with decoded_sections() as calls:
                self.assertEqual(self.lookup({"2"}), {"2": full["2"]})
                self.assertEqual(self.lookup({"9"}), {})
            self.assertEqual(calls, [])

    def test_partial_hits_missing_ids_and_overlapping_queries_are_cached(self):
        self.write({1: entry("One"), 2: entry("Two"), 3: entry("Three")})
        one = self.lookup({"1"})
        with mock.patch("builtins.open", side_effect=AssertionError("partial snapshot must be reused")):
            with decoded_sections() as calls:
                two = self.lookup({"2"})
            self.assertTrue(calls)
            with decoded_sections() as calls:
                self.assertEqual(self.lookup({"1", "2"}), {**one, **two})
                self.assertEqual(self.lookup({"9"}), {})
                self.assertEqual(self.lookup({"1", "9"}), one)
            self.assertEqual(calls, [])
            with decoded_sections() as calls:
                three = self.lookup({"2", "3"})
            self.assertTrue(calls)
            self.assertEqual(set(three), {"2", "3"})
            with decoded_sections() as calls:
                self.assertEqual(self.lookup({"3"}), {"3": three["3"]})
            self.assertEqual(calls, [])

    def test_partial_queries_share_one_snapshot_until_the_stamp_changes(self):
        self.write({1: entry("OldOne"), 2: entry("OldTwo")})
        old_stamp = self.path.stat().st_mtime_ns
        old_one = self.lookup({"1"})
        self.write({1: entry("NewOne"), 2: entry("NewTwo"), 3: entry("NewThree")})
        os.utime(self.path, ns=(old_stamp, old_stamp))
        with mock.patch("builtins.open", side_effect=AssertionError("must not mix snapshots")):
            old_two = self.lookup({"2"})
            self.assertEqual(old_two["2"]["launch"][0], "oldtwo.exe")
            full = self.lookup()
            self.assertEqual(set(full), {"1", "2"})
            self.assertEqual(full["1"], old_one["1"])
        os.utime(self.path, ns=(old_stamp + 1_000_000_000, old_stamp + 1_000_000_000))
        self.assertEqual(self.lookup({"1"})["1"]["launch"][0], "newone.exe")
        self.assertEqual(self.lookup()["3"]["launch"][0], "newthree.exe")

    def test_file_changes_invalidate_partial_complete_and_missing_entries(self):
        for complete in (False, True):
            with self.subTest(complete=complete):
                COMPAT["APPINFO_CACHE"].clear()
                self.write({1: entry("Old")})
                self.lookup(None if complete else {"1", "2"})
                stamp = self.path.stat().st_mtime_ns
                self.write({1: entry("New"), 2: entry("Added")})
                os.utime(self.path, ns=(stamp + 1_000_000_000, stamp + 1_000_000_000))
                selected = self.lookup({"1", "2"})
                self.assertEqual(selected["1"]["launch"][0], "new.exe")
                self.assertEqual(selected["2"]["launch"][0], "added.exe")

    def test_duplicate_records_keep_the_last_valid_metadata_in_all_formats(self):
        for magic in FORMATS:
            for corrupt_last in (False, True):
                with self.subTest(magic=magic, corrupt_last=corrupt_last):
                    self.write([(1, entry("First")), (2, entry("Other")),
                                (1, entry("Last")), (1, entry("Final"))], magic)
                    if corrupt_last:
                        data = bytearray(self.path.read_bytes())
                        offset = 16 if magic == 0x07564429 else 8
                        payload = None
                        while offset + 8 <= len(data):
                            app, size = struct.unpack_from("<II", data, offset)
                            if app == 0:
                                break
                            if app == 1:
                                payload = offset + 8 + (60 if magic >= 0x07564428 else 40)
                            offset += 8 + size
                        assert payload is not None
                        data[payload] = 0xFF
                        self.path.write_bytes(data)
                    COMPAT["APPINFO_CACHE"].clear()
                    full = self.lookup()
                    COMPAT["APPINFO_CACHE"].clear()
                    selected = self.lookup({"1"})
                    self.assertEqual(selected, {"1": full["1"]})
                    self.assertEqual(selected["1"]["launch"][0], "last.exe" if corrupt_last else "final.exe")

    def test_requested_steam_play_manifest_keeps_extended_mappings(self):
        manifests = COMPAT["STEAM_PLAY_MANIFESTS"]
        record = {"common": {"type": "Tool"}, "extended": {"app_mappings": {
            "0": {"appid": "42", "tool": "proton_experimental"},
        }}}
        for magic in FORMATS:
            with self.subTest(magic=magic):
                COMPAT["APPINFO_CACHE"].clear()
                self.write({1: entry("One"), manifests: record}, magic)
                self.assertEqual(self.lookup({str(manifests)})[str(manifests)]["app_mappings"],
                                 {"42": "proton_experimental"})

    def test_empty_requests_do_not_read_or_stat_the_file(self):
        with mock.patch("builtins.open", side_effect=AssertionError("no file should be read")):
            with mock.patch.object(COMPAT["os"], "stat", side_effect=AssertionError("no stat is needed")):
                self.assertEqual(self.lookup(set()), {})
        self.assertEqual(COMPAT["APPINFO_CACHE"], {})

    def test_missing_and_invalid_files_keep_empty_results(self):
        self.assertEqual(self.lookup({"1"}), {})
        for data in (b"", b"\x29", struct.pack("<II", 0, 1),
                     struct.pack("<IIqI", 0x07564429, 1, 16, 1)):
            with self.subTest(data=data):
                self.path.write_bytes(data)
                COMPAT["APPINFO_CACHE"].clear()
                self.assertEqual(self.lookup({"1"}), {})
                self.assertEqual(self.lookup(), {})

    def test_unrelated_records_are_not_structurally_decoded(self):
        counts = []
        for magic in FORMATS:
            for count in (16, 512):
                with self.subTest(magic=magic, count=count):
                    self.write({app: entry("Game" + str(app)) for app in range(1, count + 1)}, magic)
                    COMPAT["APPINFO_CACHE"].clear()
                    with decoded_sections() as calls:
                        result = self.lookup({str(count)})
                    self.assertEqual(set(result), {str(count)})
                    self.assertGreater(len(calls), 0)
                    self.assertLessEqual(len(calls), 16)
                    counts.append(len(calls))
        self.assertEqual(len(set(counts)), 1)


class LaunchLookupTest(unittest.TestCase):
    def test_launch_resolution_and_membership_decode_only_the_target(self):
        for magic in FORMATS:
            with self.subTest(magic=magic), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp) / "Steam"
                cache = root / "appcache/appinfo.vdf"
                cache.parent.mkdir(parents=True)
                cache.write_bytes(APPINFO({app: entry("Game" + str(app)) for app in range(1, 129)}, magic))
                install = root / "steamapps/common/Fixture"
                install.mkdir(parents=True)
                program = install / "Game128.exe"
                program.write_bytes(b"MZ")
                (root / "steamapps/appmanifest_128.acf").write_text(
                    '"AppState" { "appid" "128" "installdir" "Fixture" }')
                COMPAT["APPINFO_CACHE"].clear()
                with mock.patch.dict(RUN, _compat=COMPAT, EXTRA_LIBRARIES=()):
                    with decoded_sections() as calls:
                        target, selected, args = RUN["steam_launch"]("128", str(root))
                    self.assertLessEqual(len(calls), 16)
                    self.assertEqual(target.appid, "128")
                    self.assertEqual(selected, str(program))
                    self.assertEqual(args, ["--profile", "default"])
                    self.assertEqual(target.start, str(install))
                    with decoded_sections() as calls:
                        self.assertTrue(RUN["steam_launches"](target, str(program), str(root)))
                        self.assertFalse(RUN["steam_launches"](target, str(install / "other.exe"), str(root)))
                    self.assertEqual(calls, [])
                COMPAT["APPINFO_CACHE"].clear()


if __name__ == "__main__":
    unittest.main()
