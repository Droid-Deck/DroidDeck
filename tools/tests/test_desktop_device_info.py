import json
import os
import re
import subprocess
import unittest
from pathlib import Path

DESKTOP = Path(__file__).resolve().parents[1] / "linuxfs/desktop"


class DesktopDeviceInfoTest(unittest.TestCase):
    def run_info(self, what, **env):
        return subprocess.run(["sh", str(DESKTOP / "droiddeck-device-info"), what], capture_output=True, text=True,
                              env={**os.environ, **env}, check=True).stdout

    def test_names_come_from_the_session(self):
        self.assertEqual(self.run_info("host", DD_DEVICE_NAME="Retroid Pocket Duo"), "Retroid Pocket Duo\n")
        self.assertRegex(self.run_info("cpu", DD_SOC_NAME="Moorechip CQ7790S"),
                         r"^Moorechip CQ7790S \(\d+\)( @ \d+\.\d\d GHz)?\n$")

    def test_fastfetch_uses_it_for_host_and_cpu(self):
        text = re.sub(r"^\s*//.*$", "", (DESKTOP / "fastfetch.jsonc").read_text(), flags=re.M)
        modules = {m["key"]: m["text"] for m in json.loads(text)["modules"] if isinstance(m, dict) and m.get("type") == "command"}
        self.assertEqual(modules, {"Host": "droiddeck-device-info host", "CPU": "droiddeck-device-info cpu"})


if __name__ == "__main__":
    unittest.main()
