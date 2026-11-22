"""Regression tests for the checked-in binary allowlist and hash gate."""
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / "provenance" / "check.py"
spec = importlib.util.spec_from_file_location("prebuilt_check", SOURCE)
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class PrebuiltInventoryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        (self.root / "note.txt").write_text("not binary", encoding="utf-8")
        self.binary = self.root / "vendor.so"
        self.binary.write_bytes(b"\x7fELF\x00example")
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        sha = check.discover(self.root)["vendor.so"]
        self.record = {
            "path": "vendor.so",
            "sha256": sha,
            "status": "prebuilt-exception",
            "origin": "https://example.org/source",
            "source_revision": "unverified",
            "note": "An existing vendor binary awaiting source build.",
        }
        self.manifest = self.root / "prebuilt.json"
        self.write_manifest([self.record])

    def write_manifest(self, entries):
        self.manifest.write_text(
            json.dumps({"schema": 1, "artifacts": entries}), encoding="utf-8"
        )

    def test_known_binary_passes(self):
        self.assertEqual(check.validate(self.root, self.manifest), [])

    def test_added_binary_is_rejected(self):
        (self.root / "new.exe").write_bytes(b"MZadditional")
        subprocess.run(["git", "add", "new.exe"], cwd=self.root, check=True)
        self.assertTrue(any("UNREVIEWED binary" in x for x in check.validate(self.root, self.manifest)))

    def test_changed_hash_is_rejected(self):
        self.binary.write_bytes(b"\x7fELF\x00tampered")
        self.assertTrue(any("CHANGED binary" in x for x in check.validate(self.root, self.manifest)))

    def test_disguised_extensionless_elf_is_detected(self):
        (self.root / "ordinary").write_bytes(b"\x7fELF\x00hidden")
        subprocess.run(["git", "add", "ordinary"], cwd=self.root, check=True)
        self.assertTrue(any("ordinary" in x for x in check.validate(self.root, self.manifest)))

    def test_compressed_archive_is_detected(self):
        (self.root / "payload.dat").write_bytes(b"\x1f\x8b\x08\x00compressed")
        subprocess.run(["git", "add", "payload.dat"], cwd=self.root, check=True)
        self.assertTrue(any("payload.dat" in x for x in check.validate(self.root, self.manifest)))

    def test_untracked_file_is_ignored(self):
        (self.root / "untracked.so").write_bytes(b"\x7fELF\x00local")
        self.assertEqual(check.validate(self.root, self.manifest), [])

    def test_sparse_missing_binary_is_checked_from_git(self):
        self.binary.unlink()
        self.assertEqual(check.validate(self.root, self.manifest), [])

    def test_sparse_unlisted_binary_is_rejected(self):
        hidden = self.root / "docs" / "releases" / "media" / "payload.exe"
        hidden.parent.mkdir(parents=True)
        hidden.write_bytes(b"MZhidden")
        subprocess.run(["git", "add", "docs/releases/media/payload.exe"], cwd=self.root, check=True)
        hidden.unlink()  # Mimics a sparse-checkout file missing on disk.
        self.assertTrue(any("UNREVIEWED binary" in x for x in check.validate(self.root, self.manifest)))

    def test_removed_index_entry_is_rejected(self):
        subprocess.run(["git", "rm", "--cached", "vendor.so"], cwd=self.root, check=True, stdout=subprocess.DEVNULL)
        self.assertTrue(any("missing/untracked" in x for x in check.validate(self.root, self.manifest)))

    def test_duplicate_manifest_entry_is_rejected(self):
        self.write_manifest([self.record, self.record])
        self.assertTrue(any("duplicate manifest" in x for x in check.validate(self.root, self.manifest)))

    def test_missing_provenance_is_rejected(self):
        self.write_manifest([{**self.record, "source_revision": ""}])
        self.assertTrue(any("source_revision required" in x for x in check.validate(self.root, self.manifest)))


if __name__ == "__main__":
    unittest.main()
