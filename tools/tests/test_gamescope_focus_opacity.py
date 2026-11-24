from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/gamescope/patches/0140-focus-follows-window-opacity.patch"


def added_lines():
    return [line[1:] for line in PATCH.read_text().splitlines() if line.startswith("+") and not line.startswith("+++")]


def patched_rule():
    text = "\n".join(added_lines())
    start = text.index("static bool")
    return text[start:text.index("\n}\n", start) + 3]


PREAMBLE = r'''
#include <cassert>
#include <cstdio>

#define TRANSLUCENT 0x00000000
#define OPAQUE 0xffffffff
'''

MAIN = r'''
int main() {
    assert(opacity_changes_focus_candidacy(TRANSLUCENT, OPAQUE));
    assert(opacity_changes_focus_candidacy(TRANSLUCENT, 0x01010101u));
    assert(opacity_changes_focus_candidacy(OPAQUE, TRANSLUCENT));
    assert(opacity_changes_focus_candidacy(0x80808080u, TRANSLUCENT));
    assert(!opacity_changes_focus_candidacy(0x40404040u, 0xc0c0c0c0u));
    assert(!opacity_changes_focus_candidacy(OPAQUE, 0x80808080u));
    assert(!opacity_changes_focus_candidacy(TRANSLUCENT, TRANSLUCENT));
    assert(!opacity_changes_focus_candidacy(OPAQUE, OPAQUE));
    puts("ok");
    return 0;
}
'''


@unittest.skipUnless(shutil.which("c++"), "needs a C++ compiler")
class FocusOpacityRuleTest(unittest.TestCase):
    def test_only_crossing_fully_transparent_changes_focus_candidacy(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "opacity.cpp"
            binary = Path(tmp) / "opacity"
            source.write_text(PREAMBLE + patched_rule() + MAIN)
            subprocess.run(["c++", "-std=c++17", "-Wall", "-Werror", str(source), "-o", str(binary)], check=True)
            self.assertEqual(subprocess.run([str(binary)], capture_output=True, text=True, check=True).stdout.strip(), "ok")


class FocusOpacityHandlerTest(unittest.TestCase):
    def test_opacity_property_change_reevaluates_focus_when_candidacy_changes(self):
        hunks = PATCH.read_text().split("\n@@ ")
        handler = next(hunk for hunk in hunks if "handle_property_notify(" in hunk.split("\n", 1)[0])
        added = [line[1:].strip() for line in handler.splitlines() if line.startswith("+")]
        self.assertIn("const bool bFocusCandidacyChanged = opacity_changes_focus_candidacy( w->opacity, newOpacity );", added)
        self.assertIn("MakeFocusDirty();", added)
        context = [line[1:].strip() for line in handler.splitlines() if line.startswith(" ")]
        self.assertIn("w->opacity = newOpacity;", context)
        self.assertLess(added.index("const bool bFocusCandidacyChanged = opacity_changes_focus_candidacy( w->opacity, newOpacity );"), added.index("MakeFocusDirty();"))


if __name__ == "__main__":
    unittest.main()
