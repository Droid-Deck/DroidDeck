from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]


class VitureSdkContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        api = (ROOT / "app/src/main/cpp/viture/sdk_api.h").read_text(encoding="utf-8")
        required, optional = api.split("#define VITURE_SDK_OPTIONAL_FUNCTIONS(F)", 1)
        names = re.compile(r"\bF\([^,]+,\s*([A-Za-z_]\w*)\s*,")
        cls.native_required = set(names.findall(required))
        cls.native_optional = set(names.findall(optional.split("struct VitureSdkApi", 1)[0]))
        importer = (ROOT / "app/src/main/java/com/droiddeck/launcher/glasses/VitureSdkImport.kt").read_text(encoding="utf-8")
        exports = re.search(r'"libglasses\.so"\s+to\s+setOf\((.*?)\)', importer, re.S)
        if exports is None:
            raise AssertionError("Importer libglasses.so export validation was not found")
        cls.import_required = set(re.findall(r'"([A-Za-z_]\w*)"', exports[1]))

    def test_preparation_requires_exactly_the_native_loader_mandatory_exports(self):
        self.assertTrue(self.native_required)
        self.assertEqual(self.native_required, self.import_required)

    def test_optional_native_exports_are_not_required_during_preparation(self):
        self.assertTrue(self.native_optional)
        self.assertTrue(self.native_required.isdisjoint(self.native_optional))
        self.assertTrue(self.import_required.isdisjoint(self.native_optional))


if __name__ == "__main__":
    unittest.main()
