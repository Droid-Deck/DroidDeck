from pathlib import Path
import runpy
import shutil
import subprocess
import tempfile
import unittest

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
LIVE = runpy.run_path(str(BIN / "droiddeck-steam-compat"))

HARNESS = """
const reports = [];
global.appStore = {
  BHasCachedLibraryAsset(appid, version, type) {
    if ((version ?? 0) & (1 << type)) return true;
    reports.push([appid, type]);
    return false;
  },
};
const first = eval(process.argv[2]);
const second = eval(process.argv[2]);
const check = (name, actual, expected) => { if (actual !== expected) throw new Error(name + ": " + actual); };
check("install", first, "installed");
check("reinstall", second, "present");
check("no logon", appStore.BHasCachedLibraryAsset(10, undefined, 3), true);
check("zero version", appStore.BHasCachedLibraryAsset(10, 0, 3), true);
check("reported", reports.length, 2);
check("bit set", appStore.BHasCachedLibraryAsset(10, 1 << 3, 3), true);
check("bit missing", appStore.BHasCachedLibraryAsset(10, 1 << 2, 3), false);
check("reported again", reports.length, 3);
"""


@unittest.skipUnless(shutil.which("node"), "node is not installed")
class LibraryArtTest(unittest.TestCase):
    def run_node(self, harness):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory, "harness.js")
            script.write_text("global.window = {};\n" + harness)
            return subprocess.run(["node", str(script), LIVE["LIBRARY_ART_JS"]], capture_output=True, text=True, timeout=20)

    def test_apps_without_a_client_cache_report_read_from_the_local_library_cache(self):
        result = self.run_node(HARNESS)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_a_page_without_the_app_store_is_left_alone(self):
        result = self.run_node("""
if (eval(process.argv[2]) !== "unavailable") throw new Error("installed without an app store");
""")
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
