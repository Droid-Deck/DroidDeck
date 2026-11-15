import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'com.droiddeck.launcher'


class SignApkRenamesTest(unittest.TestCase):
    def test_variants_rename_every_authority_and_task_named_after_the_package(self):
        # sign-apk.sh renames a fixed list of names for the pubg/antutu/ludashi variants and fails the
        # main build on any authority or task affinity it leaves on the base package; PR builds do not sign.
        manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
        names = set()
        for attribute in ('authorities', 'taskAffinity'):
            for value in re.findall(r'android:%s="([^"]*)"' % attribute, manifest):
                self.assertNotIn(';', value, 'sign-apk.sh renames whole values, so give each authority its own provider')
                name = value.strip().replace('${applicationId}', PACKAGE)
                if name.startswith(PACKAGE + '.'):
                    names.add(name[len(PACKAGE):])
        self.assertIn('.agent', names)
        script = (ROOT / 'tools/release/sign-apk.sh').read_text()
        renamed = set(re.findall(r'"\$P(\.[A-Za-z0-9_.-]+)=\$pkg\1"', script))
        self.assertEqual(set(), names - renamed, 'add these to the axml_rename call in tools/release/sign-apk.sh')


if __name__ == '__main__':
    unittest.main()
