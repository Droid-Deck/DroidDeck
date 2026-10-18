import collections
from pathlib import Path
import re
import unicodedata
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
FORMAT = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")


def strings(path):
    return {
        element.attrib["name"]: "".join(element.itertext())
        for element in ET.parse(path).getroot()
        if element.tag == "string" and element.get("translatable") != "false"
    }


class GlassesLocalizationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.english = strings(RES / "values/strings.xml")
        cls.required = {
            key: value for key, value in cls.english.items()
            if key.startswith(("glasses_", "spatial_", "tracking_recovery_", "setup_glasses_", "phone_camera_tracking_"))
            or key in ("setup_tab_glasses", "hand_camera_prompt_title", "hand_camera_prompt_body", "hand_camera_setting_hint")
        }
        language_source = (ROOT / "app/src/main/java/com/droiddeck/launcher/core/AppLanguage.kt").read_text(encoding="utf-8")
        tags = re.findall(r'"([a-zA-Z0-9-]+)"', re.search(r"val supported:.*?listOf\((.*?)\)", language_source, re.S).group(1))
        special = {"id": "in", "es-419": "b+es+419", "pt-BR": "pt"}
        cls.locales = {}
        for tag in tags:
            if tag == "en":
                continue
            qualifier = special.get(tag, tag.replace("-", "-r"))
            cls.locales[tag] = strings(RES / f"values-{qualifier}/strings.xml")

    def test_every_supported_language_has_nonempty_glasses_and_calibration_text(self):
        for locale, translated in self.locales.items():
            for key in self.required:
                with self.subTest(locale=locale, key=key):
                    self.assertTrue(translated.get(key, "").strip(), "Missing translation would fall back to English")

    def test_translations_preserve_format_arguments_and_explicit_line_breaks(self):
        for locale, translated in self.locales.items():
            for key, source in self.required.items():
                with self.subTest(locale=locale, key=key):
                    target = translated.get(key, "")
                    self.assertEqual(collections.Counter(FORMAT.findall(source)), collections.Counter(FORMAT.findall(target)))
                    self.assertEqual(source.count(r"\n"), target.count(r"\n"))

    def test_printed_board_and_distance_guidance_preserve_measurements(self):
        keys = ("spatial_print_hint", "spatial_print_scale", "spatial_print_check", "spatial_distance_12in",
                "spatial_distance_30cm", "spatial_distance_20in", "spatial_distance_50cm",
                "spatial_distance_hint", "spatial_distance_corners")

        def numbers(value):
            value = "".join(str(unicodedata.decimal(char)) if char.isdecimal() else char for char in value)
            return collections.Counter(number.replace(",", ".") for number in re.findall(r"\d+(?:[.,]\d+)?", value))

        for locale, translated in self.locales.items():
            for key in keys:
                with self.subTest(locale=locale, key=key):
                    self.assertEqual(numbers(self.english[key]), numbers(translated.get(key, "")))


if __name__ == "__main__":
    unittest.main()
