import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'com.droiddeck.launcher'


class SignApkRenamesTest(unittest.TestCase):
    def test_variants_rename_every_authority_and_task_named_after_the_package(self):
        application = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot().find('application')
        self.assertIsNotNone(application)
        names = set()
        for attribute in ('authorities', 'taskAffinity'):
            key = '{http://schemas.android.com/apk/res/android}' + attribute
            for element in application.iter():
                value = element.get(key)
                if value is None:
                    continue
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
