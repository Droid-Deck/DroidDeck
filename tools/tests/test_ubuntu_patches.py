import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
PATCHES = ROOT / "tools/ubuntu/patches"
HUNK = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")


def hunks(text):
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        m = HUNK.match(lines[i])
        if not m:
            i += 1
            continue
        old = int(m.group(2) or 1)
        new = int(m.group(4) or 1)
        seen_old = seen_new = 0
        i += 1
        while i < len(lines) and (seen_old < old or seen_new < new):
            line = lines[i]
            if line.startswith("\\"):
                i += 1
                continue
            tag = line[:1]
            if tag in (" ", ""):
                seen_old += 1
                seen_new += 1
            elif tag == "-":
                seen_old += 1
            elif tag == "+":
                seen_new += 1
            else:
                break
            i += 1
        yield m.group(0), old, new, seen_old, seen_new


class UbuntuPatchesTest(unittest.TestCase):
    def patch_files(self):
        files = sorted(PATCHES.glob("*/*.patch"))
        self.assertTrue(files)
        return files

    def test_every_hunk_matches_its_line_counts(self):
        for path in self.patch_files():
            text = path.read_text()
            found = list(hunks(text))
            self.assertTrue(found, path.name)
            for header, old, new, seen_old, seen_new in found:
                with self.subTest(patch=path.name, hunk=header):
                    self.assertEqual((old, new), (seen_old, seen_new))

    def test_every_patch_names_files_for_p1(self):
        for path in self.patch_files():
            text = path.read_text()
            sources = re.findall(r"^--- (\S+)", text, re.M)
            targets = re.findall(r"^\+\+\+ (\S+)", text, re.M)
            with self.subTest(patch=path.name):
                self.assertTrue(sources)
                self.assertEqual(len(sources), len(targets))
                for name in sources + targets:
                    self.assertRegex(name, r"^(a|b)/")

    def test_patch_numbers_are_unique(self):
        for folder in sorted(p for p in PATCHES.iterdir() if p.is_dir()):
            numbers = [p.name.split("-", 1)[0] for p in folder.glob("*.patch")]
            self.assertEqual(len(numbers), len(set(numbers)), folder.name)

    def test_zink_never_imports_into_a_missing_semaphore(self):
        text = (PATCHES / "mesa/0006-zink-skip-dmabuf-implicit-sync-without-a-semaphore.patch").read_text()
        added = "\n".join(l[1:] for l in text.splitlines() if l.startswith("+") and not l.startswith("+++"))
        self.assertIn("if (unlikely(!sem))", added)
        self.assertIn("close(export.fd);", added)
        self.assertIn("return VK_NULL_HANDLE;", added)
        context = text.split("sem = zink_create_exportable_semaphore(screen);", 1)
        self.assertEqual(len(context), 2)
        self.assertLess(context[1].index("if (unlikely(!sem))"), context[1].index("VkImportSemaphoreFdInfoKHR"))


if __name__ == "__main__":
    unittest.main()
