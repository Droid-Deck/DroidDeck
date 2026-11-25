import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"
SWITCHES = ("VKBRIDGE_ASYNC_ACQUIRE", "VKBRIDGE_AQ_LOCKED_WRITES")


def bridge_block():
    match = re.search(r'^if \[ "\$\{DD_VKBRIDGE:-0\}" = 1 \]; then\n.*?^fi\n', SESSION.read_text(), re.M | re.S)
    return match.group(0) if match else None


def defaults_and_overrides(block):
    start = block.index("    export VKBRIDGE_ASYNC_ACQUIRE=")
    match = re.compile(r"^    if \[ -r /root/\.vkbridge-env \]; then\n.*?^    fi\n", re.M | re.S).search(block, start)
    return block[start:match.end()] if match else None


class VkBridgeSwitchesTest(unittest.TestCase):

    def setUp(self):
        self.block = bridge_block()
        self.assertIsNotNone(self.block)
        self.snippet = defaults_and_overrides(self.block)
        self.assertIsNotNone(self.snippet, "the bridge block lacks its switch defaults or the .vkbridge-env loop")
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)
        self.env_file = self.root / "vkbridge-env"

    def run_snippet(self, env=None, env_file=None):
        if env_file is not None:
            self.env_file.write_text(env_file)
        script = "set -u\nDD_INSIDE=1\n" + self.snippet.replace("/root/.vkbridge-env", str(self.env_file))
        script += "".join('printf "%s=%%s\\n" "${%s-unset}"\n' % (k, k) for k in SWITCHES)
        out = subprocess.run(["env", "-i", "PATH=/usr/bin:/bin"] + ["%s=%s" % kv for kv in (env or {}).items()]
                             + ["bash", "-c", script], capture_output=True, text=True, timeout=10, check=True).stdout
        return dict(line.split("=", 1) for line in out.splitlines() if line.split("=", 1)[0] in SWITCHES)

    def test_a_bridge_session_acquires_without_a_round_trip_and_writes_outside_the_lock(self):
        self.assertEqual({"VKBRIDGE_ASYNC_ACQUIRE": "1", "VKBRIDGE_AQ_LOCKED_WRITES": "0"}, self.run_snippet())

    def test_a_value_the_session_was_started_with_is_kept(self):
        got = self.run_snippet(env={"VKBRIDGE_ASYNC_ACQUIRE": "0", "VKBRIDGE_AQ_LOCKED_WRITES": "1"})
        self.assertEqual({"VKBRIDGE_ASYNC_ACQUIRE": "0", "VKBRIDGE_AQ_LOCKED_WRITES": "1"}, got)

    def test_the_development_settings_file_still_wins(self):
        got = self.run_snippet(env_file="VKBRIDGE_ASYNC_ACQUIRE=0\nVKBRIDGE_AQ_LOCKED_WRITES=1\n")
        self.assertEqual({"VKBRIDGE_ASYNC_ACQUIRE": "0", "VKBRIDGE_AQ_LOCKED_WRITES": "1"}, got)

    def test_a_development_file_without_them_keeps_the_defaults(self):
        got = self.run_snippet(env_file="VKBRIDGE_HITCH_MS=50\n")
        self.assertEqual({"VKBRIDGE_ASYNC_ACQUIRE": "1", "VKBRIDGE_AQ_LOCKED_WRITES": "0"}, got)

    def test_only_a_usable_bridge_gets_them(self):
        usable = self.block.index('if [ -z "$dd_vkb_missing" ]; then')
        self.assertLess(usable, self.block.index("export VKBRIDGE_ASYNC_ACQUIRE="))
        text = SESSION.read_text()
        for key in SWITCHES:
            self.assertEqual(1, text.count("%s=${%s:-" % (key, key)), key)
            self.assertIn("%s=${%s:-" % (key, key), self.block)


if __name__ == "__main__":
    unittest.main()
