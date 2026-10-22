import contextlib
import hashlib
import io
import os
from pathlib import Path
import runpy
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
EXTRA = runpy.run_path(str(BIN / "droiddeck-proton-extra"))
GLOBALS = EXTRA["pick_asset"].__globals__


def asset(name):
    return {"name": name, "browser_download_url": "https://github.com/o/r/releases/download/t/" + name}


def droiddeck_release(ver, prerelease=False, draft=False):
    stem = "DroidDeck-Proton-%s-aarch64" % ver
    return {"tag_name": "DroidDeck-Proton-" + ver, "prerelease": prerelease, "draft": draft,
            "assets": [asset(stem + ".build-info.json"), asset(stem + ".sha512sum"), asset(stem + ".tar.gz")]}


class ProtonExtraTest(unittest.TestCase):
    def test_sources_and_their_arm64_assets(self):
        self.assertEqual(list(EXTRA["SOURCES"]), ["ge", "cachyos", "droiddeck"])
        repo, pattern = EXTRA["SOURCES"]["droiddeck"]
        self.assertEqual(repo, "Droid-Deck/DroidDeck-Proton")
        self.assertTrue(pattern.search("DroidDeck-Proton-11-7.5-aarch64.tar.gz"))
        self.assertTrue(pattern.search("DroidDeck-Proton-11-8-aarch64.tar.xz"))
        self.assertFalse(pattern.search("DroidDeck-Proton-11-7.5-aarch64.sha512sum"))
        self.assertFalse(pattern.search("DroidDeck-Proton-11-7.5-aarch64.build-info.json"))

    def test_the_newest_full_release_with_its_sha512_file(self):
        releases = [droiddeck_release("11-8", prerelease=True), droiddeck_release("11-7.9", draft=True), droiddeck_release("11-7.5")]
        with mock.patch.dict(GLOBALS, {"fetch_json": lambda url: releases}):
            tag, url, sums = EXTRA["pick_asset"]("droiddeck")
        self.assertEqual(tag, "DroidDeck-Proton-11-7.5")
        self.assertTrue(url.endswith("/DroidDeck-Proton-11-7.5-aarch64.tar.gz"))
        self.assertTrue(sums.endswith("/DroidDeck-Proton-11-7.5-aarch64.sha512sum"))
        with mock.patch.dict(GLOBALS, {"fetch_json": lambda url: [droiddeck_release("11-8", prerelease=True)]}):
            self.assertEqual(EXTRA["pick_asset"]("droiddeck"), (None, None, None))

    def test_a_tagged_release_is_fetched_by_its_tag(self):
        asked = []
        with mock.patch.dict(GLOBALS, {"fetch_json": lambda url: asked.append(url) or droiddeck_release("11-7.5")}):
            tag, url, sums = EXTRA["release_asset"]("droiddeck", "DroidDeck-Proton-11-7.5")
        self.assertEqual(asked, ["https://api.github.com/repos/Droid-Deck/DroidDeck-Proton/releases/tags/DroidDeck-Proton-11-7.5"])
        self.assertTrue(url.endswith(".tar.gz") and sums.endswith(".sha512sum"))

    def test_a_ge_style_sha512_file_verifies_the_download(self):
        with tempfile.TemporaryDirectory() as tmp:
            tarball = Path(tmp) / "DroidDeck-Proton-11-7.5-aarch64.tar.gz"
            tarball.write_bytes(b"tarball")
            line = "%s  %s\n" % (hashlib.sha512(b"tarball").hexdigest(), tarball.name)

            class Reply(io.BytesIO):
                def __exit__(self, *exc):
                    return False

            with mock.patch.object(GLOBALS["urllib"].request, "urlopen", lambda req, timeout: Reply(line.encode())), \
                    contextlib.redirect_stdout(io.StringIO()) as said:
                EXTRA["verify"](str(tarball), "https://example/sums")
            self.assertIn("checksum ok", said.getvalue())
            self.assertTrue(tarball.exists())
            bad = "%s  %s\n" % ("0" * 128, tarball.name)
            with mock.patch.object(GLOBALS["urllib"].request, "urlopen", lambda req, timeout: Reply(bad.encode())), \
                    contextlib.redirect_stdout(io.StringIO()), self.assertRaises(SystemExit):
                EXTRA["verify"](str(tarball), "https://example/sums")
            self.assertFalse(tarball.exists())

    def test_a_top_folder_with_a_space_is_installed_under_its_own_name(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "src" / "DroidDeck-Proton 11-7.5"
            (src / "files/bin").mkdir(parents=True)
            (src / "proton").write_text("#!/usr/bin/python3\n")
            (src / "toolmanifest.vdf").write_text('"manifest" { "commandline" "/proton %verb%" }')
            (src / "compatibilitytool.vdf").write_text(
                '"compatibilitytools" { "compat_tools" { "DroidDeck-Proton-11-7.5" { "install_path" "." '
                '"display_name" "DroidDeck-Proton 11-7.5" } } }')
            (src / "version").write_text("1791000000 DroidDeck-Proton-11-7.5\n")
            tarball = Path(tmp) / "DroidDeck-Proton-11-7.5-aarch64.tar.gz"
            with tarfile.open(tarball, "w:gz") as tar:
                tar.add(src, arcname=src.name)
            steam = Path(tmp) / "Steam"
            adopted = []
            real = subprocess.call
            fake = lambda args, **kw: adopted.append(args) or 0 if args[0] == "steam-compatibility" else real(args, **kw)
            with mock.patch.object(GLOBALS["subprocess"], "call", fake), contextlib.redirect_stdout(io.StringIO()) as said:
                with mock.patch.object(GLOBALS["sys"], "argv", ["droiddeck-proton-extra", str(steam), str(tarball)]):
                    EXTRA["main"]()
            tools = steam / "compatibilitytools.d"
            self.assertEqual(sorted(os.listdir(tools)), [".droiddeck-download", "DroidDeck-Proton 11-7.5"])
            self.assertTrue((tools / "DroidDeck-Proton 11-7.5" / "toolmanifest.vdf").is_file())
            self.assertEqual(adopted, [["steam-compatibility", str(steam)]])
            self.assertIn("droiddeck-proton-extra: installed DroidDeck-Proton 11-7.5\n", said.getvalue())
            self.assertEqual(EXTRA["installed"](str(tools)), ["DroidDeck-Proton 11-7.5"])

    def test_archive_rejects_a_link_escaping_the_staging_tree(self):
        with tempfile.TemporaryDirectory() as tmp:
            tarball = Path(tmp) / "unsafe.tar.gz"
            with tarfile.open(tarball, "w:gz") as tar:
                link = tarfile.TarInfo("Proton/link")
                link.type = tarfile.SYMTYPE
                link.linkname = "../../outside"
                tar.addfile(link)
            with self.assertRaisesRegex(SystemExit, "unsafe archive link"):
                EXTRA["validate_archive"](str(tarball))

    def test_source_identity_namespaces_the_internal_steam_tool(self):
        with tempfile.TemporaryDirectory() as tmp:
            tree = Path(tmp) / "GE-Proton"
            tree.mkdir()
            config = tree / "compatibilitytool.vdf"
            config.write_text('"compatibilitytools" { "compat_tools" { "GE-Proton" { "install_path" "." } } }')
            identity = "%s-%s" % ("a" * 64, "b" * 64)
            EXTRA["namespace_tool"](str(tree), identity)
            rewritten = config.read_text()
            self.assertIn('"GE-Proton--dd-%s"' % identity, rewritten)
            self.assertNotIn('"GE-Proton" {', rewritten)


if __name__ == "__main__":
    unittest.main()
