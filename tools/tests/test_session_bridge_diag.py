import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


def gate_source():
    """The frame-diagnostics gate and the cleaner it calls, as the session script defines them."""
    text = SESSION.read_text()
    parts = []
    for name in ("dd_vkbridge_env_file", "dd_vkbridge_diag_clean", "dd_vkbridge_diag_gate"):
        match = re.search(r"^(%s=[^\n]*|%s\(\) \{\n.*?^\}\n)" % (name, name), text, re.M | re.S)
        if not match:
            raise AssertionError("%s is not in droiddeck-session" % name)
        parts.append(match.group(1))
    return "\n".join(parts)


class BridgeDiagGateTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)
        self.env_file = self.root / ".vkbridge-env"
        self.env_file.write_text("VKBRIDGE_CLIENT_LOG=/tmp/client.log\nVKBRIDGE_STATS=1\nVKBRIDGE_FRAME_TRACE=1\n")

    def run_gate(self, **env):
        script = gate_source() + "\nset -u\ndd_vkbridge_diag_gate\n" + \
            'printf "STATS=%s TRACE=%s SPLIT=%s KEEP=%s\\n" "${VKBRIDGE_STATS:-}" "${VKBRIDGE_FRAME_TRACE:-}" ' \
            '"${VKBRIDGE_STATS_SPLIT:-}" "${MALIDECK_KEEP_DIAG:-}"\n'
        full = {"PATH": os.environ["PATH"], "TMPDIR": str(self.root),
                "DD_VKBRIDGE_ENV_FILE": str(self.env_file), "DD_INSIDE": "1"}
        full.update(env)
        return subprocess.run(["bash", "-c", script], env=full, capture_output=True, text=True, timeout=30)

    def test_off_by_default_drops_what_the_guest_left(self):
        result = self.run_gate()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "STATS= TRACE= SPLIT= KEEP=")
        left = self.env_file.read_text()
        self.assertIn("VKBRIDGE_CLIENT_LOG=/tmp/client.log", left)
        for key in ("VKBRIDGE_STATS", "VKBRIDGE_FRAME_TRACE"):
            self.assertNotIn(key + "=", left)

    def test_the_switch_turns_stats_on_and_keeps_the_file(self):
        before = self.env_file.read_text()
        result = self.run_gate(DD_VKBRIDGE_DIAG="1", VKBRIDGE_STATS="1")
        self.assertEqual(result.returncode, 0, result.stderr)
        # the switch turns the round-trip stats on; the per-frame trace stays an explicit setting
        self.assertEqual(result.stdout.strip(), "STATS=1 TRACE= SPLIT= KEEP=")
        self.assertEqual(self.env_file.read_text(), before)

    def test_an_explicit_value_is_kept_when_the_switch_is_on(self):
        result = self.run_gate(DD_VKBRIDGE_DIAG="1", VKBRIDGE_STATS="0")
        self.assertEqual(result.stdout.strip(), "STATS=0 TRACE= SPLIT= KEEP=")

    def test_no_file_is_not_an_error(self):
        self.env_file.unlink()
        result = self.run_gate()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "STATS= TRACE= SPLIT= KEEP=")


if __name__ == "__main__":
    unittest.main()
