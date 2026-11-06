import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo
import struct


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("dxvk_component", ROOT / "tools/dxvk/component_record.py")
component = importlib.util.module_from_spec(spec)
spec.loader.exec_module(component)


class DxvkComponentTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.archive = Path(self.temporary.name) / "dxvk.zip"
        self.recipe = component.recipe_inputs(ROOT)

    def pe(self, architecture):
        machine, magic = {"x32": (0x014C, 0x10B), "x64": (0x8664, 0x20B)}[architecture]
        data = bytearray(0x80)
        data[:2] = b"MZ"
        struct.pack_into("<I", data, 0x3C, 0x40)
        data[0x40:0x44] = b"PE\0\0"
        struct.pack_into("<H", data, 0x44, machine)
        struct.pack_into("<H", data, 0x58, magic)
        return bytes(data)

    def write_archive(self, recipe=None, wrong_architecture=None, extra=None):
        members = {f"{arch}/{name}": self.pe(wrong_architecture if name == "d3d8.dll" and wrong_architecture else arch)
                   for arch in component.ARCHES for name in component.DLLS}
        members.update({
            "LICENSE": b"license", "source-commit": (component.source_revision(ROOT) + "\n").encode(),
            "submodules.txt": b"submodules", "downstream.patch": b"patch",
            "source.tar.zst": b"source", "build-recipe.json": component.json_bytes(self.recipe if recipe is None else recipe),
        })
        members["SHA256SUMS"] = "".join(
            component.sha256(members[name]) + "  " + name + "\n"
            for name in sorted(members) if name.endswith(".dll")
        ).encode()
        with ZipFile(self.archive, "w", ZIP_DEFLATED) as archive:
            for name, data in sorted(members.items()):
                archive.writestr(name, data)
            if extra is not None:
                archive.writestr(extra, b"unexpected")

    def record(self):
        return component.record(
            self.archive, ROOT,
            "https://github.com/example/repo/releases/download/dxvk-test/dxvk.zip",
            "example/repo", "dxvk-test", component.checkout_commit(ROOT),
            "https://github.com/example/repo/actions/runs/7",
        )

    def test_recipe_attests_every_patch_and_validation(self):
        for name in (
            "tools/dxvk/patches/0001-check-direct3d-bc-formats.patch",
            "tools/dxvk/patches/0002-present-semaphore-per-swapchain-image.patch",
            "tools/dxvk/patches/0003-hud-explicit-r8-texel-format.patch",
            "tools/dxvk/patches/0004-d3d9-force-single-sample.patch",
            "tools/dxvk/check_present_sync.py",
            "tools/tests/test_dxvk_build.py",
        ):
            self.assertIn(name, self.recipe)

    def test_record_emits_candidate_pin_with_full_provenance(self):
        self.write_archive()
        record = self.record()
        self.assertEqual("dxvk", record["id"])
        self.assertEqual(self.recipe, record["recipe_inputs"])
        self.assertEqual(17, len(record["files"]))
        self.assertTrue(any(file["path"] == "provenance/dxvk-build-recipe.json" for file in record["files"]))

    def test_record_rejects_a_stale_recipe(self):
        self.write_archive({})
        with self.assertRaisesRegex(ValueError, "different recipe"):
            self.record()

    def test_record_rejects_the_wrong_pe_architecture(self):
        self.write_archive(wrong_architecture="x64")
        with self.assertRaisesRegex(ValueError, "wrong PE architecture"):
            self.record()

    def test_record_rejects_unexpected_archive_members(self):
        self.write_archive(extra="x32/extra.dll")
        with self.assertRaisesRegex(ValueError, "members differ"):
            self.record()

    def test_record_rejects_a_noncanonical_publication_url(self):
        self.write_archive()
        with self.assertRaisesRegex(ValueError, "exact GitHub release asset"):
            component.record(
                self.archive, ROOT,
                "https://github.com/example/repo/releases/download/dxvk-test/dxvk.zip?redirect=1",
                "example/repo", "dxvk-test", component.checkout_commit(ROOT),
                "https://github.com/example/repo/actions/runs/7",
            )

    def test_record_rejects_a_symlink_member(self):
        self.write_archive()
        with ZipFile(self.archive, "a", ZIP_DEFLATED) as archive:
            member = ZipInfo("x32/linked.dll")
            member.external_attr = 0o120777 << 16
            archive.writestr(member, b"x32/d3d8.dll")
        with self.assertRaisesRegex(ValueError, "unsafe or duplicate"):
            self.record()

    def test_record_rejects_directory_entries(self):
        self.write_archive()
        with ZipFile(self.archive, "a", ZIP_DEFLATED) as archive:
            archive.writestr("x32/", b"")
        with self.assertRaisesRegex(ValueError, "directory entry"):
            self.record()

    def test_record_rejects_a_mismatched_producer_commit_or_run(self):
        self.write_archive()
        with self.assertRaisesRegex(ValueError, "differs from the clean checkout"):
            component.record(
                self.archive, ROOT,
                "https://github.com/example/repo/releases/download/dxvk-test/dxvk.zip",
                "example/repo", "dxvk-test", "a" * 40,
                "https://github.com/example/repo/actions/runs/7",
            )
        with self.assertRaisesRegex(ValueError, "producer run"):
            component.record(
                self.archive, ROOT,
                "https://github.com/example/repo/releases/download/dxvk-test/dxvk.zip",
                "example/repo", "dxvk-test", component.checkout_commit(ROOT),
                "https://github.com/other/repo/actions/runs/7",
            )


if __name__ == "__main__":
    unittest.main()
