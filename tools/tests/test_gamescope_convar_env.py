from pathlib import Path
import re
import shutil
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/gamescope/patches/0936-convar-env-bool-zero.patch"
LUA = shutil.which("lua") or shutil.which("lua5.4") or shutil.which("luajit")


def override_script(name, value):
    """Rebuild the Lua line main.cpp hands to the script engine, from the patch itself."""
    added = [line[1:] for line in PATCH.read_text().splitlines() if line.startswith("+") and not line.startswith("+++")]
    start = next(i for i, line in enumerate(added) if "auto override_script = std::format(" in line)
    block = []
    for line in added[start:]:
        block.append(line)
        if ", name, value );" in line:
            break
    literals = re.findall(r'"((?:[^"\\]|\\.)*)"', "\n".join(block))
    template = "".join(literals).replace("\\\\", "\\")
    return template.replace("{0}", name).replace("{1}", value)


def run(initial, value):
    """Run the override against a fake convar table; return the resulting value as Lua prints it."""
    script = override_script("thing", value)
    program = (
        f"gamescope = {{ convars = {{ thing = {{ value = {initial} }} }} }}\n"
        f"{script}\n"
        "local r = gamescope.convars.thing.value\n"
        "io.write(type(r), ':', tostring(r))\n"
    )
    out = subprocess.run([LUA, "-e", program], capture_output=True, text=True, check=True)
    return out.stdout


@unittest.skipUnless(LUA, "needs a lua interpreter")
class GamescopeConvarEnvTest(unittest.TestCase):
    def test_zero_switches_a_bool_convar_off(self):
        self.assertEqual(run("true", "0"), "boolean:false")

    def test_nonzero_switches_a_bool_convar_on(self):
        self.assertEqual(run("false", "1"), "boolean:true")
        self.assertEqual(run("false", "2"), "boolean:true")

    def test_explicit_booleans_still_work(self):
        self.assertEqual(run("true", "false"), "boolean:false")
        self.assertEqual(run("false", "true"), "boolean:true")

    def test_non_bool_convars_keep_the_raw_value(self):
        self.assertEqual(run("5", "0"), "number:0")
        self.assertEqual(run("5", "7"), "number:7")
        self.assertEqual(run("'a'", "'b'"), "string:b")

    def test_the_patch_still_targets_main_cpp(self):
        self.assertIn("diff --git a/src/main.cpp b/src/main.cpp", PATCH.read_text())


if __name__ == "__main__":
    unittest.main()
