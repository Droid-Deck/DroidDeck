import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class CiNdkTest(unittest.TestCase):
    def test_installed_ndk_must_match_the_declared_version(self):
        version = (ROOT / 'tools/ci/toolchains.env').read_text().strip().split('=', 1)[1]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ndk = root / 'ndk' / version
            ndk.mkdir(parents=True)
            properties = ndk / 'source.properties'
            properties.write_text('Pkg.Revision = ' + version + '\n')
            output = root / 'environment'
            env = dict(os.environ, ANDROID_HOME=str(root), GITHUB_ENV=str(output))
            script = ROOT / 'tools/ci/resolve-ndk.sh'
            subprocess.run(['bash', str(script)], env=env, check=True)
            self.assertIn('NDK_VERSION=' + version, output.read_text())
            self.assertIn('ANDROID_NDK_HOME=' + str(ndk), output.read_text())
            properties.write_text('Pkg.Revision = unexpected\n')
            self.assertNotEqual(subprocess.run(['bash', str(script)], env=env).returncode, 0)
