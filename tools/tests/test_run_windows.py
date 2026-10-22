"""Exercise the Windows launcher without building an APK or contacting a phone."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


POWERSHELL = shutil.which('pwsh') or shutil.which('powershell')
SCRIPT = Path(__file__).resolve().parents[1] / 'run_windows.ps1'


@unittest.skipUnless(os.name == 'nt' and POWERSHELL, 'Windows PowerShell launcher')
class WindowsLauncherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='droiddeck windows launcher ')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'tools').mkdir()
        shutil.copyfile(SCRIPT, self.root / 'tools/run_windows.ps1')
        self.calls = self.root / 'calls.txt'
        (self.root / 'gradlew.bat').write_text(
            '@echo off\necho gradle JAVA_HOME=%JAVA_HOME%>>"%DROIDDECK_TEST_CALLS%"\n'
            'exit /b %DROIDDECK_TEST_GRADLE_EXIT%\n')
        (self.root / 'adb.cmd').write_text(
            '@echo off\necho adb %*>>"%DROIDDECK_TEST_CALLS%"\nexit /b 0\n')
        self.env = dict(os.environ, JAVA_HOME=r'C:\provided-java',
                        DROIDDECK_TEST_CALLS=str(self.calls), DROIDDECK_TEST_GRADLE_EXIT='0')
        self.env['PATH'] = str(self.root) + os.pathsep + self.env.get('PATH', '')

    def launch(self):
        return subprocess.run(
            [POWERSHELL, '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
             '-File', str(self.root / 'tools/run_windows.ps1'), '-Serial', 'test-phone'],
            env=self.env, cwd=self.root, capture_output=True, text=True, timeout=30)

    def test_missing_optional_configs_keep_environment_java_and_base_package(self):
        result = self.launch()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = self.calls.read_text().splitlines()
        self.assertEqual(calls[0], r'gradle JAVA_HOME=C:\provided-java')
        self.assertEqual(calls[1], 'adb -s test-phone install -r app/build/outputs/apk/debug/app-debug.apk')
        self.assertEqual(calls[2], 'adb -s test-phone shell am start -W -n '
                         'com.droiddeck.launcher/com.droiddeck.launcher.MainActivity')

    def test_existing_configs_select_java_and_suffixed_package(self):
        (self.root / '.gradle').mkdir()
        (self.root / '.gradle/config.properties').write_text('java.home=C\\:\\\\configured-java\n')
        (self.root / 'local.properties').write_text('droiddeck.debugApplicationIdSuffix=.xr\n')
        result = self.launch()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = self.calls.read_text().splitlines()
        self.assertEqual(calls[0], r'gradle JAVA_HOME=C:\configured-java')
        self.assertTrue(calls[2].endswith('com.droiddeck.launcher.xr/com.droiddeck.launcher.MainActivity'))

    def test_empty_configs_use_defaults(self):
        (self.root / '.gradle').mkdir()
        (self.root / '.gradle/config.properties').touch()
        (self.root / 'local.properties').touch()
        result = self.launch()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = self.calls.read_text().splitlines()
        self.assertEqual(calls[0], r'gradle JAVA_HOME=C:\provided-java')
        self.assertTrue(calls[2].endswith('com.droiddeck.launcher/com.droiddeck.launcher.MainActivity'))

    def test_build_failure_still_stops_before_installation(self):
        self.env['DROIDDECK_TEST_GRADLE_EXIT'] = '1'
        result = self.launch()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Android build failed.', result.stderr)
        self.assertEqual(self.calls.read_text().splitlines(), [r'gradle JAVA_HOME=C:\provided-java'])


if __name__ == '__main__':
    unittest.main()
