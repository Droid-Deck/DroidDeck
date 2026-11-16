import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest

WRAPPER = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-steam-taskset"


class SteamHelperAffinityTest(unittest.TestCase):
    def launch(self, status, form="helper", configured=True):
        with tempfile.TemporaryDirectory(prefix="droiddeck-helper-") as temporary:
            root = Path(temporary)
            helper = root / "steamwebhelper"
            helper.write_text(f"#!{sys.executable}\n" +
                              'import json, os, sys\n'
                              'with open(os.environ["STARTS"],"a") as f: f.write(json.dumps(sys.argv[1:])+"\\n")\n'
                              'sys.exit(23)\n')
            helper.chmod(0o755)
            real = root / "real-taskset"
            real.write_text(f"#!{sys.executable}\n" +
                            'import json, os, sys\n'
                            'with open(os.environ["TRACE"],"a") as f: f.write(json.dumps(sys.argv[1:])+"\\n")\n'
                            f'sys.exit({status})\n')
            real.chmod(0o755)
            wrapper = root / "taskset"
            wrapper.write_text(WRAPPER.read_text().replace("/usr/bin/taskset", shlex.quote(str(real))))
            helper_path = helper
            if form == "alias":
                helper_path = root / "helper symlink"
                helper_path.symlink_to(helper)
            elif form == "other":
                helper_path = root / "other/steamwebhelper"
                helper_path.parent.mkdir()
                helper_path.write_text(helper.read_text())
                helper_path.chmod(0o755)
            args = ["0x7c", str(helper_path), "arg with spaces", "--literal=$HOME"]
            if form == "query":
                args = ["-p", "12345"]
            elif form == "invalid":
                args[0] = "not-a-mask"
            elif form == "empty":
                args = []
            env = dict(os.environ, TRACE=str(root / "trace"), STARTS=str(root / "starts"))
            env.pop("BL_STEAM_WEBHELPER", None)
            if configured:
                env["BL_STEAM_WEBHELPER"] = str(helper)
            result = subprocess.run(["bash", str(wrapper), *args], env=env,
                                    capture_output=True, text=True, timeout=5)
            trace = [json.loads(x) for x in (root / "trace").read_text().splitlines()]
            starts = ([json.loads(x) for x in (root / "starts").read_text().splitlines()]
                      if (root / "starts").exists() else [])
            return result, trace, starts, args

    def test_denied_pinning_still_executes_helper_once(self):
        result, trace, starts, args = self.launch(1)
        self.assertEqual(result.returncode, 23)
        self.assertEqual(starts, [args[2:]])
        self.assertEqual(len(trace), 1)
        self.assertEqual(trace[0][:2], ["-p", "0x7c"])
        self.assertRegex(trace[0][2], r"^[1-9][0-9]*$")
        self.assertIn("using the allowed CPUs", result.stderr)

    def test_successful_pinning_preserves_helper_exit_status(self):
        result, trace, starts, args = self.launch(0)
        self.assertEqual(result.returncode, 23)
        self.assertEqual(starts, [args[2:]])
        self.assertEqual(trace[0][:2], ["-p", "0x7c"])
        self.assertNotIn("pinning unavailable", result.stderr)

    def test_symlink_to_actual_helper_is_recognized(self):
        result, _, starts, args = self.launch(1, "alias")
        self.assertEqual(result.returncode, 23)
        self.assertEqual(starts, [args[2:]])

    def test_other_taskset_forms_and_targets_pass_through(self):
        for form, configured in [("other", True), ("query", True), ("invalid", True),
                                 ("empty", True), ("helper", False)]:
            with self.subTest(form=form, configured=configured):
                result, trace, starts, args = self.launch(47, form, configured)
                self.assertEqual(result.returncode, 47)
                self.assertEqual(trace, [args])
                self.assertEqual(starts, [])
                self.assertNotIn("pinning unavailable", result.stderr)


if __name__ == "__main__":
    unittest.main()
