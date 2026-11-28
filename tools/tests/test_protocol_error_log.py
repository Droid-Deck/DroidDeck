import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]


class ProtocolErrorLogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if compiler is None:
            raise unittest.SkipTest('A host C compiler is required')
        source = (REPO / 'app/src/main/cpp/waylandcomp/src/compositor.c').read_text()
        start = source.index('static void log_protocol_error(')
        end = source.index('\n}\n', start) + 3
        cls.temp = tempfile.TemporaryDirectory(prefix='droiddeck-protocol-error-')
        cls.addClassCleanup(cls.temp.cleanup)
        folder = Path(cls.temp.name)
        (folder / 'log_protocol_error.inc').write_text(source[start:end])
        cls.binary = folder / 'protocol-error-test'
        subprocess.run([compiler, '-std=c11', '-Wall', '-Wno-unused-parameter', '-I', str(folder),
                        str(REPO / 'tools/tests/protocol_error_log_harness.c'),
                        '-o', str(cls.binary)], check=True, capture_output=True, text=True)

    def check_case(self, case):
        result = subprocess.run([str(self.binary), case], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_error_names_client_object_code_and_message(self):
        self.check_case('error')

    def test_error_without_object_or_message_still_logs(self):
        self.check_case('anonymous')

    def test_requests_and_other_events_are_not_logged(self):
        self.check_case('quiet')


class ProtocolErrorLoggerRegistrationTest(unittest.TestCase):
    def test_logger_is_registered_with_the_display(self):
        source = (REPO / 'app/src/main/cpp/waylandcomp/src/compositor.c').read_text()
        self.assertIn('wl_display_add_protocol_logger(display, log_protocol_error, NULL);', source)


if __name__ == '__main__':
    unittest.main()
