import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


TOOLS = Path(__file__).resolve().parents[1]
WRAPPER_SOURCE = TOOLS / "hybris/run-proton.py"
DLL_NAMES = ("d3d8.dll", "d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll")
DXVK_X64_ANCHOR = 'g_proton.arch_pe_dir("wine/dxvk", False) + f + ".dll"'
DXVK_X32_ANCHOR = 'g_proton.arch_pe_dir("wine/dxvk", True) + f + ".dll"'
PREFIX_ANCHOR = "            # check whether any prefix config has changed"


PROTON_SOURCE = '''import json
import os
from pathlib import Path
import sys

from neighbor import NEIGHBOR


class Proton:
    def arch_pe_dir(self, directory, is32):
        return str(Path(__file__).parent / "stock" / ("x32" if is32 else "x64")) + "/"


g_proton = Proton()


def prefix_marker():
    if True:
            prefix_info = "stock"
            # check whether any prefix config has changed
            return prefix_info


def payloads():
    result = {"x64": {}, "x32": {}}
    for f in ("d3d8", "d3d9", "d3d10core", "d3d11", "dxgi"):
        x64 = g_proton.arch_pe_dir("wine/dxvk", False) + f + ".dll"
        x32 = g_proton.arch_pe_dir("wine/dxvk", True) + f + ".dll"
        result["x64"][f + ".dll"] = Path(x64).read_text()
        result["x32"][f + ".dll"] = Path(x32).read_text()
    return result


def main():
    record = {
        "argv0": sys.argv[0],
        "argv": sys.argv[1:],
        "file": __file__,
        "neighbor": NEIGHBOR,
        "prefix": prefix_marker(),
        "payloads": payloads(),
        "openvr": g_proton.arch_pe_dir("wine/dxvk", False) + "openvr_api_dxvk.dll",
    }
    Path(os.environ["PROBE"]).write_text(json.dumps(record))
    raise SystemExit(int(os.environ.get("PROTON_EXIT", "0")))


main()
'''


class ProtonWrapperTest(unittest.TestCase):
    def fixture(self, root):
        wrapper_dir = root / 'DXVK profile "quotes" $dollar `backticks`'
        wrapper_dir.mkdir(parents=True)
        wrapper = wrapper_dir / "run-proton.py"
        shutil.copy2(WRAPPER_SOURCE, wrapper)

        dxvk = wrapper_dir / "dxvk"
        for architecture in ("x32", "x64"):
            payload = dxvk / architecture
            payload.mkdir(parents=True)
            for name in DLL_NAMES:
                (payload / name).write_text(f"{architecture}:{name}")

        depot = root / "Proton depot with spaces"
        depot.mkdir()
        proton = depot / "selected proton.py"
        proton.write_text(PROTON_SOURCE)
        (depot / "neighbor.py").write_text('NEIGHBOR = "imported from selected depot"\n')
        return {"wrapper": wrapper, "dxvk": dxvk, "depot": depot, "proton": proton,
                "probe": root / "proton-ran.json"}

    def run_wrapper(self, fixture, *proton_args, check=False, exit_status=0, env=None):
        command = [sys.executable, str(fixture["wrapper"])]
        if check:
            command.append("--check")
        command.extend((str(fixture["proton"]), *proton_args))
        return subprocess.run(
            command,
            cwd=fixture["wrapper"].parent,
            env={"PATH": os.defpath, "PROBE": str(fixture["probe"]),
                 "PROTON_EXIT": str(exit_status), **(env or {})},
            text=True,
            capture_output=True,
        )

    def test_launch_uses_both_staged_payloads_and_selected_proton_context(self):
        with tempfile.TemporaryDirectory() as tmp:
            fixture = self.fixture(Path(tmp))
            original_source = fixture["proton"].read_bytes()
            arguments = ("waitforexitandrun", "game with spaces.exe", 'quoted "value"',
                         "$literal-dollar", "`literal-backticks`")
            result = self.run_wrapper(fixture, *arguments)

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(fixture["proton"].read_bytes(), original_source)
            record = json.loads(fixture["probe"].read_text())
            self.assertEqual(record["argv0"], str(fixture["proton"]))
            self.assertEqual(record["argv"], list(arguments))
            self.assertEqual(record["file"], str(fixture["proton"]))
            self.assertEqual(record["neighbor"], "imported from selected depot")
            self.assertEqual(record["prefix"], "stock\ndroiddeck-dxvk-profile-v1")
            self.assertEqual(record["openvr"], str(fixture["depot"] / "stock/x64/openvr_api_dxvk.dll"))
            for architecture in ("x32", "x64"):
                self.assertEqual(
                    record["payloads"][architecture],
                    {name: f"{architecture}:{name}" for name in DLL_NAMES},
                )

    def test_named_sibling_payload_replaces_the_default(self):
        with tempfile.TemporaryDirectory() as tmp:
            fixture = self.fixture(Path(tmp))
            for architecture in ("x32", "x64"):
                payload = fixture["wrapper"].parent / "dxvk-2.6.2" / architecture
                payload.mkdir(parents=True)
                for name in DLL_NAMES:
                    (payload / name).write_text(f"2.6.2 {architecture}:{name}")
            result = self.run_wrapper(fixture, "waitforexitandrun", "game.exe",
                                      env={"DROIDDECK_HYBRIS_DXVK": "dxvk-2.6.2"})

            self.assertEqual(result.returncode, 0, result.stderr)
            record = json.loads(fixture["probe"].read_text())
            for architecture in ("x32", "x64"):
                self.assertEqual(
                    record["payloads"][architecture],
                    {name: f"2.6.2 {architecture}:{name}" for name in DLL_NAMES},
                )

    def test_payload_names_outside_the_wrapper_directory_are_refused(self):
        for name in ("../dxvk", "dxvk/../../x", "/tmp/dxvk", "other", ".dxvk"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as tmp:
                fixture = self.fixture(Path(tmp))
                result = self.run_wrapper(fixture, "waitforexitandrun", "game.exe",
                                          env={"DROIDDECK_HYBRIS_DXVK": name})

                self.assertEqual(result.returncode, 1)
                self.assertIn("invalid payload name", result.stderr)
                self.assertFalse(fixture["probe"].exists())

    def test_check_validates_without_executing_proton(self):
        with tempfile.TemporaryDirectory() as tmp:
            fixture = self.fixture(Path(tmp))
            result = self.run_wrapper(fixture, check=True)

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("payload present and Proton layout supported", result.stdout)
            self.assertFalse(fixture["probe"].exists())

    def test_check_rejects_malformed_source_before_its_top_level_code_runs(self):
        with tempfile.TemporaryDirectory() as tmp:
            fixture = self.fixture(Path(tmp))
            source = fixture["proton"].read_text()
            source = source.replace(
                "import sys\n", 'import sys\n\nPath(os.environ["PROBE"]).write_text("top-level")\n', 1)
            fixture["proton"].write_text(source + "\nthis is not valid =\n")
            result = self.run_wrapper(fixture, check=True)

            self.assertEqual(result.returncode, 1, result.stderr)
            self.assertIn("DroidDeck DXVK profile:", result.stderr)
            self.assertFalse(fixture["probe"].exists())

    def test_preflight_rejects_each_missing_or_empty_payload_before_proton_runs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for state in ("missing", "empty"):
                for architecture in ("x32", "x64"):
                    for name in DLL_NAMES:
                        with self.subTest(state=state, architecture=architecture, name=name):
                            fixture = self.fixture(root / f"{state}-{architecture}-{name}")
                            payload = fixture["dxvk"] / architecture / name
                            if state == "missing":
                                payload.unlink()
                            else:
                                payload.write_bytes(b"")
                            result = self.run_wrapper(fixture, "waitforexitandrun", "game.exe")
                            self.assertEqual(result.returncode, 1, result.stderr)
                            self.assertIn(f"{state} DXVK file", result.stderr)
                            self.assertFalse(fixture["probe"].exists())

    def test_preflight_rejects_changed_proton_layout_or_syntax_before_proton_runs(self):
        mutations = []
        for label, anchor in (("x64", DXVK_X64_ANCHOR), ("x32", DXVK_X32_ANCHOR),
                              ("prefix", PREFIX_ANCHOR)):
            mutations.extend(((f"missing-{label}", lambda text, anchor=anchor: text.replace(anchor, "missing_anchor", 1)),
                              (f"duplicate-{label}", lambda text, anchor=anchor: text + "\n# " + anchor + "\n")))
        mutations.append(("syntax-error", lambda text: text + "\nthis is not valid =\n"))

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for label, mutate in mutations:
                with self.subTest(layout=label):
                    fixture = self.fixture(root / label)
                    fixture["proton"].write_text(mutate(fixture["proton"].read_text()))
                    result = self.run_wrapper(fixture, "waitforexitandrun", "game.exe")
                    self.assertEqual(result.returncode, 1, result.stderr)
                    self.assertIn("DroidDeck DXVK profile:", result.stderr)
                    self.assertFalse(fixture["probe"].exists())

    def test_proton_exit_status_is_propagated(self):
        with tempfile.TemporaryDirectory() as tmp:
            fixture = self.fixture(Path(tmp))
            result = self.run_wrapper(fixture, "waitforexitandrun", "game.exe", exit_status=23)

            self.assertEqual(result.returncode, 23, result.stderr)
            self.assertTrue(fixture["probe"].exists())


if __name__ == "__main__":
    unittest.main()
