from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


RES = Path(__file__).resolve().parents[2] / "app/src/main/res"
KEYS = ("frame_gen_scope_game", "frame_gen_scope_global")


class FrameGenLocalizationTest(unittest.TestCase):
    def test_every_locale_defines_distinct_translated_scopes(self):
        paths = sorted(RES.glob("values-*/strings.xml"))
        self.assertGreaterEqual(len(paths), 32)
        english = {
            node.attrib["name"]: node.text
            for node in ET.parse(RES / "values/strings.xml").getroot()
            if node.get("name") in KEYS
        }
        self.assertEqual(set(KEYS), set(english))
        for path in paths:
            with self.subTest(locale=path.parent.name):
                root = ET.parse(path).getroot()
                values = []
                for key in KEYS:
                    nodes = [node for node in root if node.get("name") == key]
                    self.assertEqual(len(nodes), 1, key)
                    value = "".join(nodes[0].itertext()).strip()
                    self.assertTrue(value, key)
                    self.assertNotEqual(value.casefold(), english[key].casefold(), key)
                    self.assertNotIn("%", value, key)
                    values.append(value)
                self.assertNotEqual(*values)


if __name__ == "__main__":
    unittest.main()
