from pathlib import Path
import re
import unittest

PATCH = Path(__file__).resolve().parents[1] / "fex/patches/0015-gl-thunk-overlays-libopengl.patch"


def hunk(sign):
    lines = [line for line in PATCH.read_text().splitlines() if not line.startswith(("diff ", "index ", "--- ", "+++ ", "@@"))]
    return re.findall(r'"([^"]+)"', "\n".join(line[1:] for line in lines if line[:1] in (" ", sign)))


class FexGlThunkOverlayTest(unittest.TestCase):
    def test_libopengl_joins_the_gl_overlay(self):
        before, after = hunk("-"), hunk("+")
        self.assertNotIn("@PREFIX_LIB@/libOpenGL.so.0", before)
        self.assertIn("@PREFIX_LIB@/libOpenGL.so.0", after)
        self.assertIn("@PREFIX_LIB@/libOpenGL.so.0.0.0", after)
        self.assertEqual([path for path in before if path.startswith("@PREFIX_LIB@/libGL.")],
                         [path for path in after if path.startswith("@PREFIX_LIB@/libGL.")])
        self.assertLess(after.index("@PREFIX_LIB@/libGL.so.1.7.0"), after.index("Vulkan"))
        self.assertLess(after.index("@PREFIX_LIB@/libOpenGL.so.0.0.0"), after.index("Vulkan"))

    def test_patch_touches_only_the_thunks_database(self):
        self.assertEqual(["Data/ThunksDB.json"], re.findall(r"^diff --git a/(\S+) ", PATCH.read_text(), re.M))


if __name__ == "__main__":
    unittest.main()
