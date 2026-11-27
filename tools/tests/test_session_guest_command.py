import json
from pathlib import Path
import re
import subprocess
import unittest


SERVICE = Path(__file__).resolve().parents[2] / (
    "app/src/main/java/com/droiddeck/launcher/session/SessionService.kt")

VARIANTS = Path(__file__).resolve().parents[1] / "release/variants.txt"
PACKAGES = [line.split()[1] for line in VARIANTS.read_text().splitlines()
            if line.strip() and not line.startswith("#")]


def guest_prefix(package):
    source = SERVICE.read_text()
    before_environment = source.split("val guest = ArrayList<String>()", 1)[1].split(
        "addClientEnvironment(guest,", 1)[0]
    environment = source.split("private fun addClientEnvironment(", 1)[1].split(
        'guest.add("HOME=/root")', 1)[0]
    prefix = []
    for line in (before_environment + environment).splitlines():
        line = line.strip()
        if not line.startswith("guest.add("):
            continue
        expression = line[len("guest.add("):-1]
        match = re.fullmatch(r'("[^"\\]*")(?: \+ (.+))?', expression)
        if match is None:
            raise AssertionError("Launch prefix changed; update its regression harness: " + line)
        value = json.loads(match[1])
        if match[2]:
            if match[2] != "com.droiddeck.launcher.runtime.BwrapSpawner.socket(this)":
                raise AssertionError("Unexpected dynamic launch-prefix argument: " + line)
            value += package + ".bwrap"
        prefix.append(value)
    return prefix


class SessionGuestCommandTest(unittest.TestCase):
    def test_env_is_the_executable_for_every_package(self):
        for package in PACKAGES:
            with self.subTest(package=package):
                self.assertEqual(["/usr/bin/env", "-i"], guest_prefix(package)[:2])

    @unittest.skipUnless(Path("/usr/bin/env").is_file(), "needs /usr/bin/env")
    def test_child_receives_its_package_socket_after_environment_is_cleared(self):
        for package in PACKAGES:
            with self.subTest(package=package):
                result = subprocess.run(
                    guest_prefix(package), capture_output=True, text=True, check=True,
                    timeout=5, env={"DROIDDECK_BWRAP_SOCKET": "wrong.bwrap", "STALE_HOST": "1"})
                self.assertEqual(
                    ["DROIDDECK_BWRAP_SOCKET=" + package + ".bwrap"],
                    result.stdout.splitlines())

    def test_display_buffer_sessions_raise_the_default_image_count_below_games(self):
        source = SERVICE.read_text()
        branch = source.split("if (displayBuffers) {", 1)[1].split("}", 1)[0]
        self.assertIn('guest.add("vk_wsi_override_min_image_count=$DISPLAY_BUFFER_IMAGES")', branch)
        self.assertIn('guest.add("GAMESCOPE_WSI_ENSURE_MIN_IMAGE_COUNT=1")', branch)
        self.assertNotIn("GAMESCOPE_WSI_MIN_IMAGE_COUNT=", branch)
        self.assertRegex(source, r"const val DISPLAY_BUFFER_IMAGES = 4\b")
        self.assertIn("nativeSetDisplayBufferDriver(displayBuffers)", source)


if __name__ == "__main__":
    unittest.main()
