import hashlib
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

REMEMBER = Path(__file__).resolve().parents[1] / "ubuntu/overlay-runtime/usr/share/droiddeck/apt/remember"


class AptRememberTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.top = Path(temp.name)
        self.dir = self.top / "share/apt"
        self.state = self.top / "state"
        self.etc = self.top / "etc/apt"
        self.keys = self.top / "keyrings"
        self.autostart = self.top / "autostart"
        for d in (self.dir, self.etc / "sources.list.d", self.keys, self.autostart, self.top / "bin"):
            d.mkdir(parents=True)
        (self.dir / "system-manual.txt").write_text("base\n")
        (self.dir / "runtime-id").write_text("new\n")
        system = self.keys / "ubuntu-archive.gpg"
        system.write_text("system key")
        (self.dir / "system-sources.txt").write_text("%s %s\n" % (system, hashlib.sha256(b"system key").hexdigest()))
        apt_mark = self.top / "bin/apt-mark"
        apt_mark.write_text("#!/bin/sh\nprintf 'base\\nvlc\\n'\n")
        apt_mark.chmod(0o755)
        script = REMEMBER.read_text()
        script = script.replace("/usr/share/droiddeck/apt", str(self.dir))
        script = script.replace("/root/.local/share/droiddeck/apt", str(self.state))
        script = script.replace("/etc/xdg/autostart", str(self.autostart))
        script = script.replace("/usr/share/keyrings", str(self.keys))
        script = script.replace("/etc/apt", str(self.etc))
        self.script = self.top / "remember"
        self.script.write_text(script)

    def run_remember(self):
        env = dict(os.environ, PATH="%s:%s" % (self.top / "bin", os.environ["PATH"]))
        env.pop("DROIDDECK_APT_RESTORING", None)
        subprocess.run(["bash", str(self.script)], env=env, check=True, timeout=30)

    def test_user_sources_and_keys_outside_etc_apt_are_kept(self):
        (self.etc / "sources.list").write_text("deb http://example.invalid stable main\n")
        (self.keys / "example.gpg").write_text("user key")
        self.run_remember()
        self.assertEqual("vlc\n", (self.state / "packages.txt").read_text())
        self.assertTrue((self.state / "sources/sources.list").is_file())
        self.assertEqual("user key", (self.state / "keyrings" / ("%s_example.gpg" % str(self.keys).lstrip("/").replace("/", "_"))).read_text())
        self.assertFalse(any("ubuntu-archive" in p.name for p in (self.state / "keyrings").iterdir()))

    def test_the_previous_runtime_snapshot_waits_for_the_desktop_restore(self):
        (self.autostart / "droiddeck-apt-restore.desktop").write_text("")
        self.state.mkdir()
        (self.state / "packages.txt").write_text("old-app\n")
        (self.state / "restored").write_text("old\n")
        self.run_remember()
        self.assertEqual("old-app\n", (self.state / "packages.txt").read_text())
        (self.state / "attempted").write_text("new\n")
        self.run_remember()
        self.assertEqual("vlc\n", (self.state / "packages.txt").read_text())

    def test_without_the_desktop_the_snapshot_follows_the_runtime(self):
        self.state.mkdir()
        (self.state / "packages.txt").write_text("old-app\n")
        (self.state / "restored").write_text("old\n")
        self.run_remember()
        self.assertEqual("vlc\n", (self.state / "packages.txt").read_text())


if __name__ == "__main__":
    unittest.main()
