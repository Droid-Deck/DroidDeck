import importlib.util
import io
import json
from pathlib import Path
import struct
import tarfile
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("xclipse_component_record",
    Path(__file__).resolve().parents[1] / "dxvk/xclipse_component_record.py")
component = importlib.util.module_from_spec(spec)
spec.loader.exec_module(component)


class DxvkXclipseComponentTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        dxvk = self.root / "tools/dxvk"
        patches = dxvk / "xclipse-patches"
        patches.mkdir(parents=True)
        values = {
            "DXVK_XCLIPSE_VERSION": "2.6.2",
            "DXVK_XCLIPSE_REVISION": "1" * 40,
            "DXVK_XCLIPSE_CONTAINER_IMAGE": "example.invalid/image@sha256:" + "2" * 64,
        }
        (dxvk / "xclipse-source.env").write_text("".join(
            f"{key}={value}\n" for key, value in values.items()))
        (dxvk / "build_xclipse.sh").write_text("build recipe\n")
        (dxvk / "xclipse_component_record.py").write_text("record recipe\n")
        (patches / "0001-check-direct3d-bc-formats.patch").write_text("patch recipe\n")
        tests = self.root / "tools/tests"
        tests.mkdir()
        (tests / "dxvk_xclipse_toolchain_test.cpp").write_text("toolchain probe\n")
        self.prefix = "dxvk-2.6.2"
        self.entries = {}
        for architecture, (machine, magic) in component.ARCHITECTURES.items():
            for name in component.DLL_NAMES:
                data = bytearray(256)
                data[:2] = b"MZ"
                struct.pack_into("<I", data, 0x3C, 0x80)
                data[0x80:0x84] = b"PE\0\0"
                struct.pack_into("<H", data, 0x84, machine)
                struct.pack_into("<H", data, 0x98, magic)
                self.entries[f"{self.prefix}/{architecture}/{name}"] = (0o755, bytes(data))
        metadata = {
            "LICENSE": b"zlib license\n",
            "source-commit": ("1" * 40 + "\n").encode(),
            "patches/0001-check-direct3d-bc-formats.patch": b"patch recipe\n",
            "provenance/build-recipe.json": component.json_bytes(component.recipe_inputs(self.root)),
            "provenance/compiler.txt": b"pinned compiler\n",
            "provenance/downstream.patch": b"source patch\n",
            "provenance/sourcearchive.tar.zst": b"source archive\n",
            "provenance/submodules.txt": b"pinned submodules\n",
            "provenance/tool-versions.txt": b"tool versions\n",
        }
        self.entries.update({f"{self.prefix}/{name}": (0o644, data)
                             for name, data in metadata.items()})
        manifest = {
            "container_image": "example.invalid/image@sha256:" + "2" * 64,
            "producer_commit": "3" * 40,
            "source_commit": "1" * 40,
            "source_version": "2.6.2",
        }
        self.entries[f"{self.prefix}/provenance/build-manifest.json"] = (
            0o644, component.json_bytes(manifest))
        self.refresh_inventory()

    def refresh_inventory(self):
        inventory = []
        for name, (mode, data) in sorted(self.entries.items()):
            if name.endswith(".dll"):
                inventory.append({"file": name, "sha256": component.sha256(data)})
        self.entries[f"{self.prefix}/provenance/pe-inventory.json"] = (
            0o644, component.json_bytes(inventory))
        checksum_name = f"{self.prefix}/provenance/SHA256SUMS"
        checksums = "".join(component.sha256(data) + "  " + name + "\n"
                            for name, (_, data) in sorted(self.entries.items())
                            if name != checksum_name)
        self.entries[checksum_name] = (0o644, checksums.encode())

    def archive(self):
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode="w:gz") as archive:
            for name, (mode, data) in sorted(self.entries.items()):
                info = tarfile.TarInfo(name)
                info.mode = mode
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))
        return output.getvalue()

    def record(self):
        return component.record(self.archive(), self.root,
            "https://example.com/dxvk-2.6.2.tar.gz", "https://example.com/actions/1")

    def test_emits_exact_dll_pin_and_ignored_provenance_inventory(self):
        result = self.record()
        self.assertEqual(result["id"], "dxvk-xclipse")
        self.assertEqual(len(result["files"]), 10)
        self.assertTrue(all(item["name"].endswith(".dll") for item in result["files"]))
        self.assertEqual(set(result["ignored"]), set(self.entries) - {
            item["name"] for item in result["files"]})
        self.assertEqual(result["provenance"]["upstream_source_commit"], "1" * 40)

    def test_rejects_wrong_source_recipe_and_pe_architecture(self):
        source_name = f"{self.prefix}/source-commit"
        self.entries[source_name] = (0o644, ("4" * 40 + "\n").encode())
        self.refresh_inventory()
        with self.assertRaisesRegex(ValueError, "different DXVK revision"):
            self.record()
        self.entries[source_name] = (0o644, ("1" * 40 + "\n").encode())
        recipe_name = f"{self.prefix}/provenance/build-recipe.json"
        self.entries[recipe_name] = (0o644, b"{}\n")
        self.refresh_inventory()
        with self.assertRaisesRegex(ValueError, "different recipe"):
            self.record()
        self.entries[recipe_name] = (0o644, component.json_bytes(component.recipe_inputs(self.root)))
        dll = f"{self.prefix}/x32/d3d9.dll"
        mode, data = self.entries[dll]
        changed = bytearray(data)
        struct.pack_into("<H", changed, 0x84, 0x8664)
        self.entries[dll] = (mode, bytes(changed))
        self.refresh_inventory()
        with self.assertRaisesRegex(ValueError, "wrong PE architecture"):
            self.record()

    def test_rejects_unlisted_archive_members(self):
        self.entries[f"{self.prefix}/unexpected"] = (0o644, b"unexpected")
        self.refresh_inventory()
        with self.assertRaisesRegex(ValueError, "member set differs"):
            self.record()


if __name__ == "__main__":
    unittest.main()
