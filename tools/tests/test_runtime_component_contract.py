import json
from pathlib import Path
import unittest

from tools.release import runtime_inputs


ROOT = Path(__file__).resolve().parents[2]
RUNTIME_SHIM = "assets/linuxfs/usr/local/lib/mangoapp/libsysfs-listing-shim.so"


class RuntimeComponentContractTest(unittest.TestCase):
    def test_runtime_recipe_declares_the_attested_mangoapp_sysfs_shim(self):
        recipes = json.loads((ROOT / "tools/components/recipes.json").read_text())
        runtime = recipes["runtime"]
        self.assertIn(RUNTIME_SHIM, runtime_inputs.required())
        self.assertIn("app/src/main/" + RUNTIME_SHIM, runtime["outputs"])
        self.assertIn("tools/mangoapp", runtime["inputs"])


if __name__ == "__main__":
    unittest.main()
