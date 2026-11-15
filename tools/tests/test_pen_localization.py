from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"


def strings(path):
    return {
        element.attrib["name"]: "".join(element.itertext())
        for element in ET.parse(path).getroot()
        if element.tag == "string" and element.get("translatable") != "false"
    }


class PenLocalizationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.required = [key for key in strings(RES / "values/strings.xml") if key.startswith("mode_pen")]
        language_source = (ROOT / "app/src/main/java/com/droiddeck/launcher/core/AppLanguage.kt").read_text(encoding="utf-8")
        tags = re.findall(r'"([a-zA-Z0-9-]+)"', re.search(r"val supported:.*?listOf\((.*?)\)", language_source, re.S).group(1))
        special = {"id": "in", "es-419": "b+es+419", "pt-BR": "pt"}
        cls.locales = {
            tag: strings(RES / f"values-{special.get(tag, tag.replace('-', '-r'))}/strings.xml")
            for tag in tags if tag != "en"
        }

    def test_every_supported_language_translates_the_pen_settings(self):
        for locale, translated in self.locales.items():
            for key in self.required:
                with self.subTest(locale=locale, key=key):
                    self.assertTrue(translated.get(key, "").strip(), "Missing translation would fall back to English")


if __name__ == "__main__":
    unittest.main()
