import json
import runpy
import tempfile
import unittest
from pathlib import Path

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
MSI = runpy.run_path(str(BIN / "droiddeck-msi-install"))
COMPONENTS = runpy.run_path(str(BIN / "droiddeck-wincomponents"))


class FakePackage:
    """A package's tables in memory, as msiinfo would give them."""

    def __init__(self, tables, summary=None):
        self.path = "fake.msi"
        self.tables = tables
        self.table_names = set(tables)
        self.summary = summary or {"template": "Intel;1033"}

    def table(self, name):
        return self.tables.get(name, [])

    def stream(self, name, target):
        raise AssertionError("no cabinets in a fake package")


def rows(columns, *values):
    names = columns.split()
    return [dict(zip(names, value)) for value in values]


def installer(tables, out="/tmp/unused", summary=None):
    return MSI["Installer"]("fake.msi", out, FakePackage(tables, summary))


class ConditionsTest(unittest.TestCase):
    def evaluate(self, text, props=None, default="default"):
        return MSI["Conditions"](props or {"VersionNT": "603", "VersionNT64": "603"}).evaluate(text, default)

    def test_windows_10_64_bit_passes_the_usual_checks(self):
        self.assertTrue(self.evaluate("(VersionNT > 501) OR (VersionNT = 501 AND ServicePackLevel >= 2)"))
        self.assertTrue(self.evaluate("VersionNT64"))
        self.assertTrue(self.evaluate("VersionNT AND (VersionNT >= 500)"))
        self.assertFalse(self.evaluate("(VersionNT < 501) or Version9X"))
        self.assertFalse(self.evaluate("(VersionNT < 600) or Version9X"))

    def test_unset_properties_are_false_and_not_inverts(self):
        self.assertFalse(self.evaluate("NEWERVERSIONDETECTED"))
        self.assertTrue(self.evaluate("NOT Installed"))
        self.assertFalse(self.evaluate("Installed AND NOT REINSTALL"))

    def test_strings_and_case_folding(self):
        props = {"A": "Hello"}
        self.assertTrue(self.evaluate('A = "Hello"', props))
        self.assertFalse(self.evaluate('A = "hello"', props))
        self.assertTrue(self.evaluate('A ~= "hello"', props))
        self.assertTrue(self.evaluate('A >< "ell"', props))

    def test_component_and_feature_states(self):
        conditions = MSI["Conditions"]({}, lambda f: 3 if f == "Main" else None, lambda c: 3 if c == "Dll" else None)
        self.assertTrue(conditions.evaluate("&Main = 3"))
        self.assertTrue(conditions.evaluate("$Dll = 3"))
        self.assertFalse(conditions.evaluate("$Other = 3"))

    def test_nonsense_gives_the_default(self):
        self.assertEqual(self.evaluate("(VersionNT >"), "default")


class LayoutTest(unittest.TestCase):
    def test_folders_resolve_as_on_64_bit_windows(self):
        inst = installer({
            "Directory": rows("Directory Directory_Parent DefaultDir",
                              ("TARGETDIR", "", "SourceDir"),
                              ("ProgramFilesFolder", "TARGETDIR", "PFiles"),
                              ("INSTALLDIR", "ProgramFilesFolder", "xendvbfk|Microsoft XNA"),
                              ("Same", "INSTALLDIR", "."),
                              ("SystemFolder", "TARGETDIR", "SysFldr"),
                              ("TempFolder", "TARGETDIR", "TmpFldr"),
                              ("Temp2", "TempFolder", "x")),
        })
        self.assertEqual(inst.directory("INSTALLDIR"), "drive_c/Program Files (x86)/Microsoft XNA")
        self.assertEqual(inst.directory("Same"), "drive_c/Program Files (x86)/Microsoft XNA")
        self.assertEqual(inst.directory("SystemFolder"), "drive_c/windows/syswow64")
        self.assertIsNone(inst.directory("Temp2"))

    def test_a_folder_set_by_a_custom_action_wins(self):
        inst = installer({
            "Directory": rows("Directory Directory_Parent DefaultDir",
                              ("TARGETDIR", "", "SourceDir"),
                              ("WinVC", "TARGETDIR", "Win"),
                              ("SysVC", "WinVC", "System")),
            "CustomAction": rows("Action Type Source Target",
                                 ("SetSys", "51", "SysVC", "[SystemFolder]"),
                                 ("RunIt", "3154", "File", "/silent")),
            "InstallExecuteSequence": rows("Action Condition Sequence", ("SetSys", "", "2"), ("RunIt", "NOT Installed", "6000")),
        })
        inst.run_set_actions()
        self.assertEqual(inst.directory("SysVC"), "drive_c/windows/syswow64")
        self.assertIn("RunIt", inst.notes[0])

    def test_conditions_pick_components_and_features(self):
        inst = installer({
            "Feature": rows("Feature Feature_Parent Level", ("Main", "", "1"), ("X64", "", "0"), ("Off", "", "0")),
            "Condition": rows("Feature_ Level Condition", ("X64", "1", "VersionNT64")),
            "Component": rows("Component Directory_ Attributes Condition",
                              ("Up", "TARGETDIR", "0", "(VersionNT >= 600)"),
                              ("Down", "TARGETDIR", "0", "(VersionNT < 600) or Version9X"),
                              ("Wide", "TARGETDIR", "256", ""),
                              ("Never", "TARGETDIR", "0", "")),
            "FeatureComponents": rows("Feature_ Component_", ("Main", "Up"), ("Main", "Down"), ("X64", "Wide"), ("Off", "Never")),
        })
        inst.select()
        self.assertEqual(inst.included_components, {"Up", "Wide"})

    def test_global_assembly_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            dll = Path(tmp) / "a.dll"
            dll.write_bytes(b"MZ....BSJB" + b"\0" * 8 + (12).to_bytes(4, "little") + b"v4.0.30319\0\0")
            inst = installer({})
            folder = inst.gac_folder({"name": "Microsoft.Xna.Framework", "version": "4.0.0.00000", "culture": "neutral",
                                      "publickeytoken": "842CF8BE1DE50553", "processorarchitecture": "x86"}, dll)
            self.assertEqual(folder, "drive_c/windows/Microsoft.NET/assembly/GAC_32/Microsoft.Xna.Framework/v4.0_4.0.0.0__842cf8be1de50553")
            dll.write_bytes(b"MZ....BSJB" + b"\0" * 8 + (12).to_bytes(4, "little") + b"v2.0.50727\0\0")
            folder = inst.gac_folder({"name": "Old", "version": "1.0.0.0", "publickeytoken": "AB", "processorarchitecture": "MSIL"}, dll)
            self.assertEqual(folder, "drive_c/windows/assembly/GAC_MSIL/Old/1.0.0.0__ab")

    def test_side_by_side_names_follow_wines_winsxs(self):
        names = MSI["Installer"].sxs_names
        ident = {"name": "Microsoft.VC80.CRT", "publickeytoken": "1fc8b3b9a1e18e3b", "version": "8.0.50727.762",
                 "processorarchitecture": "x86", "type": "win32"}
        self.assertEqual(names(ident), ("assembly", "x86_microsoft.vc80.crt_1fc8b3b9a1e18e3b_8.0.50727.762_none_deadbeef", "8.0.50727.762"))
        policy = dict(ident, name="policy.8.0.Microsoft.VC80.CRT", type="win32-policy")
        self.assertEqual(names(policy)[1], "drive_c/windows/winsxs/policies/x86_microsoft.vc80.crt_1fc8b3b9a1e18e3b_none_deadbeef")

    def test_registry_values_go_to_the_32_bit_view_for_32_bit_components(self):
        inst = installer({
            "Directory": rows("Directory Directory_Parent DefaultDir", ("TARGETDIR", "", "SourceDir"),
                              ("CommonV2Dir", "TARGETDIR", "XNA")),
            "Component": rows("Component Directory_ Attributes Condition", ("C32", "TARGETDIR", "0", ""),
                              ("C64", "TARGETDIR", "256", "")),
            "Registry": rows("Registry Root Key Name Value Component_",
                             ("r1", "2", "Software\\Microsoft\\XNA\\Framework\\v4.0", "Installed", "#1", "C32"),
                             ("r2", "2", "Software\\Microsoft\\XNA\\Framework\\v4.0", "NativeLibraryPath", "[CommonV2Dir]", "C32"),
                             ("r3", "0", "CLSID\\{X}\\InprocServer32", "", "a[~]b", "C32"),
                             ("r4", "2", "SOFTWARE\\AGEIA", "Bin", "#x0A0b", "C64"),
                             ("r5", "2", "Software\\Keys", "+", "", "C64")),
            "Environment": rows("Environment Name Value Component_", ("e", "=-*PATH", "[CommonV2Dir];[~]", "C32")),
        })
        inst.select()
        inst.install_registry()
        got = {(v["key"], v["name"]): (v["type"], v["data"]) for v in inst.registry}
        self.assertEqual(got[("Software\\Wow6432Node\\Microsoft\\XNA\\Framework\\v4.0", "Installed")], ("dword", 1))
        self.assertEqual(got[("Software\\Wow6432Node\\Microsoft\\XNA\\Framework\\v4.0", "NativeLibraryPath")], ("sz", "C:\\XNA\\"))
        self.assertEqual(got[("Software\\Wow6432Node\\Classes\\CLSID\\{X}\\InprocServer32", "")], ("multi_sz", ["a", "b"]))
        self.assertEqual(got[("SOFTWARE\\AGEIA", "Bin")], ("binary", "0a0b"))
        self.assertEqual(got[("Software\\Keys", None)], ("key", None))
        env = [v for v in inst.registry if v["name"] == "PATH"][0]
        self.assertEqual((env["key"], env["type"], env["data"]),
                         ("System\\CurrentControlSet\\Control\\Session Manager\\Environment", "prepend", "C:\\XNA\\"))


SYSTEM_REG = '''WINE REGISTRY Version 2
;; All keys relative to REGISTRY\\\\Machine

#arch=win64

[Software\\\\Classes\\\\Wow6432Node\\\\CLSID] 1791145014
#time=1dd543d4c84d0a2

[Software\\\\Wow6432Node\\\\Classes] 1791145014
#time=1dd543d4c84d0a2
#link
"SymbolicLinkValue"=hex(6):5c,00,52,00,65,00,67,00,69,00,73,00,74,00,72,00,79,00,\\
  5c,00,4d,00,61,00,63,00,68,00,69,00,6e,00,65,00,5c,00,53,00,6f,00,66,00,74,00,\\
  77,00,61,00,72,00,65,00,5c,00,43,00,6c,00,61,00,73,00,73,00,65,00,73,00,5c,00,\\
  57,00,6f,00,77,00,36,00,34,00,33,00,32,00,4e,00,6f,00,64,00,65,00

[Software\\\\Wow6432Node\\\\Old] 1791145014
#time=1dd543d4c84d0a2
"Kept"="mine"
"Version"="1.0"

[System\\\\ControlSet001\\\\Control\\\\Session Manager\\\\Environment] 1791169864
#time=1dd547728528ad2
"PATH"=str(2):"%SystemRoot%\\\\system32;%SystemRoot%"

[System\\\\CurrentControlSet] 1791145014
#time=1dd543d4c84d0a2
#link
"SymbolicLinkValue"=hex(6):5c,00,52,00,65,00,67,00,69,00,73,00,74,00,72,00,79,00,\\
  5c,00,4d,00,61,00,63,00,68,00,69,00,6e,00,65,00,5c,00,53,00,79,00,73,00,74,00,\\
  65,00,6d,00,5c,00,43,00,6f,00,6e,00,74,00,72,00,6f,00,6c,00,53,00,65,00,74,00,\\
  30,00,30,00,31,00
'''


class RegistryApplyTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.store = root / "store"
        self.compat = root / "compatdata/42"
        self.pfx = self.compat / "pfx"
        self.selection = root / "sel.json"
        folder = self.store / "xna40"
        (folder / "drive_c/Program Files (x86)/Microsoft XNA").mkdir(parents=True)
        (folder / "drive_c/Program Files (x86)/Microsoft XNA/xna.dll").write_bytes(b"xna")
        (folder / "component.json").write_text(json.dumps({"id": "xna40", "overrides": []}))
        (folder / "registry.json").write_text(json.dumps({"version": 1, "values": [
            {"hive": "HKLM", "key": "Software\\Wow6432Node\\Old", "name": "Version", "type": "sz", "data": "4.0"},
            {"hive": "HKLM", "key": "Software\\Wow6432Node\\Classes\\CLSID\\{X}", "name": "", "type": "sz", "data": "Thing"},
            {"hive": "HKLM", "key": "Software\\Wow6432Node\\New", "name": "Installed", "type": "dword", "data": 1},
            {"hive": "HKLM", "key": "System\\CurrentControlSet\\Control\\Session Manager\\Environment", "name": "PATH",
             "type": "prepend", "data": "C:\\PhysX", "separator": ";"},
            {"hive": "HKCU", "key": "Software\\Game", "name": "Opt", "type": "dword", "data": 2},
        ]}))

    def tearDown(self):
        self.tmp.cleanup()

    def make_prefix(self):
        (self.pfx / "drive_c/program files (x86)").mkdir(parents=True)
        (self.pfx / "system.reg").write_text(SYSTEM_REG)
        (self.pfx / "user.reg").write_text("WINE REGISTRY Version 2\n;; All keys relative to REGISTRY\\\\User\\\\S-1-5-21\n\n#arch=win64\n")

    def apply(self, *ids):
        self.selection.write_text(json.dumps({"version": 1, "games": {"42": list(ids)}}))
        return COMPONENTS["apply"](self.compat, "42", self.selection, self.store)

    def test_values_follow_wines_links_and_come_out_again(self):
        self.make_prefix()
        self.apply("xna40")
        text = (self.pfx / "system.reg").read_text()
        self.assertIn('"Version"="4.0"', text)
        self.assertIn('"Kept"="mine"', text)
        # Through the Wow6432Node\Classes link into the shared Classes\Wow6432Node key, never under the link.
        self.assertIn('[Software\\\\Classes\\\\Wow6432Node\\\\CLSID\\\\{X}]', text)
        self.assertNotIn('[Software\\\\Wow6432Node\\\\Classes\\\\CLSID', text)
        self.assertIn('"PATH"=str(2):"C:\\\\PhysX;%SystemRoot%\\\\system32;%SystemRoot%"', text)
        self.assertNotIn("[System\\\\CurrentControlSet\\\\", text)
        self.assertIn('"Opt"=dword:00000002', (self.pfx / "user.reg").read_text())
        # The prefix's own spelling of Program Files (x86) is kept.
        self.assertTrue((self.pfx / "drive_c/program files (x86)/Microsoft XNA/xna.dll").is_file())
        self.assertFalse((self.pfx / "drive_c/Program Files (x86)").exists())

        before = (self.pfx / "system.reg").read_text()
        _, notes = self.apply("xna40")
        self.assertEqual((self.pfx / "system.reg").read_text(), before)
        self.assertEqual(notes, [])

        self.apply()
        text = (self.pfx / "system.reg").read_text()
        self.assertIn('"Version"="1.0"', text)
        self.assertNotIn('"Installed"=dword:00000001', text)
        self.assertNotIn("Thing", text)
        self.assertIn('"PATH"=str(2):"%SystemRoot%\\\\system32;%SystemRoot%"', text)
        self.assertNotIn('"Opt"', (self.pfx / "user.reg").read_text())
        self.assertFalse((self.pfx / "drive_c/program files (x86)/Microsoft XNA/xna.dll").exists())

    def test_turning_off_keeps_what_others_added_to_path_since(self):
        self.make_prefix()
        self.apply("xna40")
        reg = self.pfx / "system.reg"
        reg.write_text(reg.read_text().replace("C:\\\\PhysX;", "C:\\\\PhysX;C:\\\\Other;"))
        self.apply()
        self.assertIn('"PATH"=str(2):"C:\\\\Other;%SystemRoot%\\\\system32;%SystemRoot%"', reg.read_text())

    def test_values_wait_for_a_prefix_proton_has_not_made(self):
        _, notes = self.apply("xna40")
        self.assertTrue(any("wait for the prefix" in note for note in notes))
        self.assertFalse((self.pfx / "system.reg").exists())
        self.make_prefix()
        self.apply("xna40")
        self.assertIn('"Version"="4.0"', (self.pfx / "system.reg").read_text())

    def test_registry_lines_round_trip(self):
        line = COMPONENTS["reg_value_line"]('we"ird\\', "sz", 'a"b\\c\u00e9')
        self.assertEqual(line, '"we\\"ird\\\\"="a\\"b\\\\c\\x00e9"')
        self.assertEqual(COMPONENTS["reg_string"](line), 'a"b\\c\u00e9')
        self.assertEqual(COMPONENTS["reg_value_line"]("m", "multi_sz", ["a", "b"]), '"m"=str(7):"a\\0b\\0"')
        self.assertEqual(COMPONENTS["reg_value_line"]("", "binary", "0a0b"), "@=hex:0a,0b")


if __name__ == "__main__":
    unittest.main()
