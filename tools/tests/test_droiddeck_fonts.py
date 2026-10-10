import subprocess
import tempfile
import unittest
from pathlib import Path


FONTS = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-fonts"
SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


class DroidDeckFontsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        defaults = self.root / "usr/share/fontconfig/conf.default"
        defaults.mkdir(parents=True)
        (defaults / "60-latin.conf").write_text("<fontconfig/>")
        dejavu = self.root / "usr/share/fonts/truetype/dejavu"
        dejavu.mkdir(parents=True)
        (dejavu / "DejaVuSans.ttf").write_text("dejavu")

    def run_fonts(self):
        subprocess.run(["bash", str(FONTS), str(self.root)], check=True, capture_output=True)

    def local_conf(self):
        return (self.root / "etc/fonts/local.conf").read_text()

    def owned_conf(self):
        return (self.root / "etc/fonts/conf.d/65-droiddeck-default-fonts.conf").read_text()

    def test_default_rules_linked_and_dejavu_is_the_default(self):
        self.run_fonts()
        self.assertTrue((self.root / "etc/fonts/conf.d/60-latin.conf").is_symlink())
        text = self.owned_conf()
        self.assertIn("<family>sans-serif</family><prefer><family>DejaVu Sans</family>", text)
        self.assertIn("<family>monospace</family><prefer><family>DejaVu Sans Mono</family>", text)
        self.assertNotIn("Noto Sans CJK", text)
        self.assertFalse((self.root / "etc/fonts/local.conf").exists())

    def test_an_existing_conf_d_rule_is_not_replaced(self):
        conf_d = self.root / "etc/fonts/conf.d"
        conf_d.mkdir(parents=True)
        (conf_d / "60-latin.conf").write_text("mine")
        self.run_fonts()
        self.assertFalse((conf_d / "60-latin.conf").is_symlink())
        self.assertEqual("mine", (conf_d / "60-latin.conf").read_text())

    def test_custom_local_conf_survives_and_device_language_fallback_remains_configured(self):
        custom = self.root / "etc/fonts/local.conf"
        custom.parent.mkdir(parents=True, exist_ok=True)
        custom.write_text("<fontconfig><rescan><int>45</int></rescan></fontconfig>")
        self.run_fonts()
        self.assertEqual("<fontconfig><rescan><int>45</int></rescan></fontconfig>", self.local_conf())
        session = SESSION.read_text()
        self.assertIn('device_fonts=/usr/local/share/fonts/android', session)
        self.assertIn('zh-cn=Noto Sans CJK SC', session)
        self.assertIn('ja=Noto Sans CJK JP', session)
        self.assertIn('ko=Noto Sans CJK KR', session)
        self.assertIn('th=Noto Sans Thai', session)
        self.assertIn('ar=Noto Naskh Arabic', session)

    def test_unchanged_fonts_do_not_rewrite_owned_conf(self):
        self.run_fonts()
        owned = self.root / "etc/fonts/conf.d/65-droiddeck-default-fonts.conf"
        before = owned.stat().st_mtime_ns
        self.run_fonts()
        self.assertEqual(before, owned.stat().st_mtime_ns)

    def test_the_session_runs_the_bootstrap_before_it_starts_a_program(self):
        text = SESSION.read_text()
        self.assertIn("/usr/local/bin/droiddeck-fonts", text)
        self.assertLess(text.index("droiddeck-fonts"), text.index("droiddeck-steam-install"))


if __name__ == "__main__":
    unittest.main()
