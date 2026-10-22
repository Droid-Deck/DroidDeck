import re
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


def read(path):
    return path.read_text(encoding='utf-8')


BUILD = ROOT / 'tools/desktop-kde/build.sh'
WORKFLOW = ROOT / '.github/workflows/build-desktop-kde.yml'


class DesktopLanguageBuildTest(unittest.TestCase):
    def setUp(self):
        self.build = read(BUILD)

    def test_the_main_package_keeps_english_and_every_other_language_becomes_a_pack(self):
        loop = self.build.split('usr/share/locale/*/', 1)[1].split('rm -rf "$work/root/usr/share/doc"', 1)[0]
        self.assertIn("case $loc in en*) continue ;; esac", loop)
        self.assertIn('[ -d "$dir/LC_MESSAGES" ] || continue', loop)
        self.assertIn('desktop-kde-i18n-$loc.tar.zst', loop)
        self.assertIn('> "$out/desktop-kde.i18n.txt"', loop)
        self.assertIn('find "$work/root/usr/share/locale" -mindepth 1 -maxdepth 1 -type d ! -name \'en*\'',
                      self.build.split('i18n.txt"', 1)[1])

    def test_the_index_lines_carry_what_the_app_verifies(self):
        record = re.search(r"printf '%s %s %s\\n' \"\$loc\" \"\$\(sha256sum \"\$file\" \| cut -d' ' -f1\)\" \"\$\(stat -c %s \"\$file\"\)\"",
                           self.build)
        self.assertIsNotNone(record)
        line = 'de ' + 'a' * 64 + ' 262144'
        fields = line.split()
        self.assertEqual(3, len(fields))
        self.assertTrue(re.fullmatch(r'[0-9a-f]{64}', fields[1]))
        self.assertEqual(262144, int(fields[2]))

    def test_the_workflow_ships_the_index_and_the_packs(self):
        workflow = read(WORKFLOW)
        self.assertIn('out/desktop-kde.i18n.txt', workflow)
        self.assertEqual(1, len([line for line in workflow.splitlines()
                                 if line.strip() == 'out/desktop-kde-i18n-*.tar.zst']))
        release = workflow.split('gh release create', 1)[1]
        self.assertIn('out/desktop-kde.i18n.txt', release)
        self.assertIn('out/desktop-kde-i18n-*.tar.zst', release)


if __name__ == '__main__':
    unittest.main()
