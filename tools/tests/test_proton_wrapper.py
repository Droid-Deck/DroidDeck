import json
import os
from pathlib import Path
import shutil
import struct
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


def pe_fixture(machine, arm64x=False):
    if not arm64x:
        header = bytearray(64)
        header[:2] = b"MZ"
        struct.pack_into("<I", header, 60, 64)
        return bytes(header + b"PE\0\0" + struct.pack("<H", machine))

    data = bytearray(0x1000)
    data[:2] = b"MZ"
    pe_offset = 0x80
    struct.pack_into("<I", data, 0x3c, pe_offset)
    data[pe_offset:pe_offset + 4] = b"PE\0\0"
    struct.pack_into("<HHIIIHH", data, pe_offset + 4,
                     machine, 4, 0, 0, 0, 0xf0, 0x2022)
    optional = pe_offset + 24
    struct.pack_into("<H", data, optional, 0x20b)
    image_base = 0x180000000
    struct.pack_into("<Q", data, optional + 24, image_base)
    struct.pack_into("<I", data, optional + 56, 0x5000)
    struct.pack_into("<I", data, optional + 108, 16)
    struct.pack_into("<II", data, optional + 112 + 10 * 8, 0x1000, 0x138)

    section_table = optional + 0xf0
    sections = (
        (b".rdata", 0x400, 0x1000, 0x400, 0x400),
        (b".hexpthk", 0x200, 0x2000, 0x200, 0x800),
        (b".a64xrm", 0x200, 0x3000, 0x200, 0xa00),
        (b".reloc", 0x400, 0x4000, 0x400, 0xc00),
    )
    for index, (name, virtual_size, virtual_address, raw_size, raw_offset) in enumerate(sections):
        offset = section_table + index * 40
        data[offset:offset + len(name)] = name
        struct.pack_into("<IIII", data, offset + 8,
                         virtual_size, virtual_address, raw_size, raw_offset)

    load_config = 0x400
    struct.pack_into("<I", data, load_config, 0x138)
    struct.pack_into("<Q", data, load_config + 0xc8, image_base + 0x1200)
    struct.pack_into("<I", data, load_config + 0xe0, 0x100)
    struct.pack_into("<H", data, load_config + 0xe4, 4)
    struct.pack_into("<II", data, 0xc00 + 0x100, 1, 16)
    return bytes(data)


def plain_arm64_fixture():
    data = bytearray(pe_fixture(0xaa64, arm64x=True))
    section_table = 0x80 + 24 + 0xf0
    data[section_table + 40:section_table + 48] = b".text\0\0\0"
    data[section_table + 80:section_table + 88] = b".data\0\0\0"
    struct.pack_into("<Q", data, 0x400 + 0xc8, 0)
    struct.pack_into("<I", data, 0x400 + 0xe0, 0)
    struct.pack_into("<H", data, 0x400 + 0xe4, 0)
    return bytes(data)


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

    def native_fixture(self, root, payload_name="dxvk-mali", arm64x=True):
        fixture = self.fixture(root)
        payload = fixture["wrapper"].parent / payload_name
        for architecture, machine in (("i386-windows", 0x014c),
                                      ("x86_64-windows", 0x8664),
                                      ("aarch64-windows", 0xaa64)):
            directory = payload / architecture
            directory.mkdir(parents=True)
            for name in DLL_NAMES:
                (directory / name).write_bytes(pe_fixture(
                    machine, arm64x=arm64x and architecture == "aarch64-windows"))
        source = fixture["proton"].read_text().replace(
            '("x32" if is32 else "x64")',
            '("i386-windows" if is32 else os.environ["PROBE_ARCH"])')
        source = source.replace("Path(x64).read_text()", "Path(x64).read_bytes().hex()")
        source = source.replace("Path(x32).read_text()", "Path(x32).read_bytes().hex()")
        fixture["proton"].write_text(source)
        fixture["native"] = payload
        return fixture

    def test_native_payload_preserves_protons_selected_architecture(self):
        for architecture in ("aarch64-windows", "x86_64-windows"):
            with self.subTest(architecture=architecture), tempfile.TemporaryDirectory() as tmp:
                fixture = self.native_fixture(Path(tmp))
                result = self.run_wrapper(fixture, "waitforexitandrun", "game.exe", env={
                    "DROIDDECK_HYBRIS_DXVK": "dxvk-mali", "PROBE_ARCH": architecture})
                self.assertEqual(result.returncode, 0, result.stderr)
                record = json.loads(fixture["probe"].read_text())
                for destination, selected in (("x64", architecture), ("x32", "i386-windows")):
                    self.assertEqual(record["payloads"][destination], {
                        name: (fixture["native"] / selected / name).read_bytes().hex()
                        for name in DLL_NAMES})
                self.assertEqual(record["openvr"],
                    str(fixture["depot"] / "stock" / architecture / "openvr_api_dxvk.dll"))

    def test_native_payload_rejects_wrong_architecture_before_proton_runs(self):
        with tempfile.TemporaryDirectory() as tmp:
            fixture = self.native_fixture(Path(tmp))
            shutil.copyfile(fixture["native"] / "x86_64-windows/dxgi.dll",
                            fixture["native"] / "aarch64-windows/dxgi.dll")
            result = self.run_wrapper(fixture, "waitforexitandrun", "game.exe", env={
                "DROIDDECK_HYBRIS_DXVK": "dxvk-mali", "PROBE_ARCH": "aarch64-windows"})
            self.assertEqual(result.returncode, 1)
            self.assertIn("wrong DXVK machine 0x8664, expected 0xaa64", result.stderr)
            self.assertFalse(fixture["probe"].exists())

    def test_mali_payload_rejects_plain_arm64_before_proton_runs(self):
        with tempfile.TemporaryDirectory() as tmp:
            fixture = self.native_fixture(Path(tmp))
            (fixture["native"] / "aarch64-windows/d3d11.dll").write_bytes(plain_arm64_fixture())
            result = self.run_wrapper(fixture, check=True,
                env={"DROIDDECK_HYBRIS_DXVK": "dxvk-mali"})
            self.assertEqual(result.returncode, 1)
            self.assertIn("plain ARM64 DXVK file", result.stderr)
            self.assertFalse(fixture["probe"].exists())

    def test_other_native_payload_can_use_plain_arm64(self):
        with tempfile.TemporaryDirectory() as tmp:
            fixture = self.native_fixture(Path(tmp), payload_name="dxvk-native", arm64x=False)
            result = self.run_wrapper(fixture, check=True,
                env={"DROIDDECK_HYBRIS_DXVK": "dxvk-native"})
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("payload present and Proton layout supported", result.stdout)
            self.assertFalse(fixture["probe"].exists())

    def test_native_payload_requires_every_architecture_and_a_valid_pe_header(self):
        for mode in ("missing", "invalid"):
            for architecture in ("i386-windows", "x86_64-windows", "aarch64-windows"):
                with self.subTest(mode=mode, architecture=architecture), tempfile.TemporaryDirectory() as tmp:
                    fixture = self.native_fixture(Path(tmp))
                    dll = fixture["native"] / architecture / "d3d11.dll"
                    if mode == "missing":
                        dll.unlink()
                    else:
                        dll.write_bytes(b"MZ" + b"\0" * 62)
                    result = self.run_wrapper(fixture, check=True,
                        env={"DROIDDECK_HYBRIS_DXVK": "dxvk-mali"})
                    self.assertEqual(result.returncode, 1)
                    self.assertIn("DXVK file", result.stderr)
                    self.assertFalse(fixture["probe"].exists())

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
