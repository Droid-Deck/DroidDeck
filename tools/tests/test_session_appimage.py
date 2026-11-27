import fcntl
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
SESSION = BIN / "droiddeck-session"
EXEC = BIN / "droiddeck-appimage-exec"
URUNTIME = "/usr/local/lib/droiddeck/uruntime"
FEX = "/usr/local/bin/droiddeck-fex"
REPORT = "#!/bin/sh\nprintf '%%s\\n' \"TARGET=${TARGET_APPIMAGE:-} EXTRACT=${APPIMAGE_EXTRACT_AND_RUN:-}\" %s \"$@\"\n"


def target_case():
    match = re.search(r"^    case \$\{target,,\} in\n.*?^    esac\n", SESSION.read_text(), re.M | re.S)
    return match.group(0)


class AppImageExecTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.image = self.dir / "rpcs3.AppImage"
        self.machine(183)
        self.uruntime = self.tool("uruntime", "uruntime")
        self.fex = self.tool("droiddeck-fex", "fex")

    def machine(self, number):
        self.image.write_bytes(b"\x7fELF" + bytes(14) + number.to_bytes(2, "little") + bytes(44))

    def tool(self, name, label):
        path = self.dir / name
        path.write_text(REPORT % label)
        path.chmod(0o755)
        return path

    def launch(self, **extra):
        script = self.dir / "exec"
        script.write_text(EXEC.read_text().replace(URUNTIME, str(self.uruntime)).replace(FEX, str(self.fex)))
        env = {k: v for k, v in os.environ.items() if k not in ("TARGET_APPIMAGE", "APPIMAGE_EXTRACT_AND_RUN", "DDNATV_ROOT")}
        env.update(extra)
        return subprocess.run(["bash", str(script), str(self.image), "--no-gui"], env=env,
                              capture_output=True, text=True, timeout=30).stdout.splitlines()

    def native(self, **extra):
        return self.launch(DDNATV_ROOT=str(self.dir / "rootfs"), XDG_CACHE_HOME=str(self.cache), **extra)

    def unpacker(self, works=True, apprun=None):
        self.cache = self.dir / "cache"
        self.unpacks = self.dir / "unpacks"
        apprun = apprun or "#!/bin/sh\nprintf '%s\\n' \"APPIMAGE=$APPIMAGE APPDIR=$APPDIR TARGET=${TARGET_APPIMAGE:-}\" apprun \"$@\"\n"
        self.fex.write_text(
            "#!/bin/sh\n[ \"$1\" = extract ] || exit 9\necho \"$2\" >> %s\n" % self.unpacks +
            ("mkdir -p squashfs-root\ncat > squashfs-root/AppRun <<'EOF'\n%sEOF\nchmod +x squashfs-root/AppRun\n" % apprun if works else "exit 1\n"))

    def unpacked_dirs(self):
        return [d for d in self.cache.glob("droiddeck-appimage/*") if d.is_dir()]

    def test_an_arm64_image_runs_through_the_shipped_runtime(self):
        self.assertEqual(["TARGET=%s EXTRACT=1" % self.image, "uruntime", "--no-gui"], self.launch())

    def test_without_the_shipped_runtime_the_image_runs_itself(self):
        self.uruntime.unlink()
        self.image.write_text(REPORT % "own")
        self.assertEqual(["TARGET= EXTRACT=1", "own", "--no-gui"], self.launch())

    def test_an_x86_image_still_starts_under_fex(self):
        self.machine(62)
        self.assertEqual(["TARGET= EXTRACT=1", "fex", "run", "--mode", "on", "--", str(self.image), "--no-gui"], self.launch())

    def test_an_x86_image_without_fex_uses_the_shipped_runtime(self):
        self.machine(62)
        self.fex.unlink()
        self.assertEqual(["TARGET=%s EXTRACT=1" % self.image, "uruntime", "--no-gui"], self.launch())

    def test_without_proot_an_arm64_image_is_unpacked_once_and_runs_from_its_apprun(self):
        self.unpacker()
        expected = ["== appimage: unpacking %s" % self.image]
        ran = ["APPIMAGE=%s APPDIR=%s/squashfs-root TARGET=" % (self.image, "%s"), "apprun", "--no-gui"]
        first = self.native()
        self.assertEqual(expected, first[:1])
        [unpacked] = self.unpacked_dirs()
        ran[0] = ran[0] % unpacked
        self.assertEqual(ran, first[1:])
        self.assertEqual(ran, self.native())
        self.assertEqual([str(self.image)], self.unpacks.read_text().splitlines())

    def test_without_proot_a_changed_image_is_unpacked_again(self):
        self.unpacker()
        self.native()
        os.utime(self.image, (1, 1))
        self.assertEqual("apprun", self.native()[2])
        self.assertEqual(2, len(self.unpacks.read_text().splitlines()))
        self.assertEqual(1, len(self.unpacked_dirs()))

    def test_without_proot_a_same_size_change_within_one_second_is_unpacked_again(self):
        self.unpacker()
        os.utime(self.image, ns=(1_000_000_100_000_000, 1_000_000_100_000_000))
        self.native()
        os.utime(self.image, ns=(1_000_000_900_000_000, 1_000_000_900_000_000))
        self.assertEqual("apprun", self.native()[2])
        self.assertEqual(2, len(self.unpacks.read_text().splitlines()))
        self.assertEqual(1, len(self.unpacked_dirs()))

    def hold(self, unpacked):
        handle = open(unpacked / "source")
        self.addCleanup(handle.close)
        fcntl.flock(handle, fcntl.LOCK_SH)
        return handle

    def test_without_proot_a_running_copy_survives_an_update_until_it_exits(self):
        self.unpacker()
        self.native()
        [old] = self.unpacked_dirs()
        running = self.hold(old)
        os.utime(self.image, (1, 1))
        self.assertEqual("apprun", self.native()[2])
        self.assertTrue((old / "squashfs-root/AppRun").exists())
        [new] = [d for d in self.unpacked_dirs() if d != old]
        running.close()
        self.assertEqual("apprun", self.native()[1])
        self.assertEqual([new], self.unpacked_dirs())

    def test_without_proot_a_running_copy_of_a_removed_image_is_kept(self):
        self.unpacker()
        self.native()
        [old] = self.unpacked_dirs()
        self.hold(old)
        self.image.unlink()
        self.image = self.dir / "dolphin.AppImage"
        self.machine(183)
        self.native()
        self.assertTrue((old / "squashfs-root/AppRun").exists())
        self.assertEqual(2, len(self.unpacked_dirs()))

    def test_without_proot_the_running_application_holds_its_copy(self):
        self.unpacker(apprun="#!/bin/sh\nflock -n -x \"$APPDIR/../source\" true && echo free || echo held\n")
        self.assertEqual(["held"], self.native()[1:])

    def test_without_proot_nothing_runs_when_the_cache_cannot_be_locked(self):
        self.unpacker()
        tools = self.dir / "tools"
        tools.mkdir()
        (tools / "flock").write_text("#!/bin/sh\nexit 1\n")
        (tools / "flock").chmod(0o755)
        self.assertEqual([], self.native(PATH="%s:%s" % (tools, os.environ["PATH"])))
        self.assertFalse(self.unpacks.exists())

    def test_without_proot_the_unpacked_copy_of_a_removed_image_is_dropped(self):
        self.unpacker()
        self.native()
        [old] = self.unpacked_dirs()
        self.image.unlink()
        self.image = self.dir / "dolphin.AppImage"
        self.machine(183)
        self.native()
        self.assertFalse(old.exists())
        self.assertEqual(1, len(self.unpacked_dirs()))

    def test_without_proot_a_linked_cache_entry_and_its_target_survive(self):
        self.unpacker()
        outside = self.dir / "outside"
        outside.mkdir()
        (outside / "source").write_text("%s\n" % (self.dir / "gone.AppImage"))
        (outside / "keep").write_text("data")
        link = self.cache / "droiddeck-appimage" / "linked"
        link.parent.mkdir(parents=True)
        link.symlink_to(outside, target_is_directory=True)
        self.assertEqual("apprun", self.native()[2])
        self.assertTrue(link.is_symlink())
        self.assertEqual("data", (outside / "keep").read_text())

    def test_without_proot_an_image_that_cannot_be_unpacked_runs_nothing(self):
        self.unpacker(works=False)
        out = self.native()
        self.assertNotIn("uruntime", out)
        self.assertNotIn("apprun", out)
        self.assertEqual([], self.unpacked_dirs())
        self.assertEqual([], list(self.cache.glob("droiddeck-appimage/.unpack.*")))

    def test_without_proot_an_x86_image_still_starts_under_fex(self):
        self.machine(62)
        self.cache = self.dir / "cache"
        self.assertEqual(["TARGET= EXTRACT=1", "fex", "run", "--mode", "on", "--", str(self.image), "--no-gui"], self.native())

    def test_the_session_starts_images_through_the_shared_launcher(self):
        script = ("set -u\n"
                  "run_program() { printf '%%s\\n' \"$@\"; exit 0; }\n"
                  "target=%s\nfex=()\nset -- --no-gui\n" % self.image + target_case())
        out = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=30).stdout.splitlines()
        self.assertEqual(["/usr/local/bin/droiddeck-appimage-exec", str(self.image), "--no-gui"], out)


if __name__ == "__main__":
    unittest.main()
