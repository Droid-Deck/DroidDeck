import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('diagnostics', Path(__file__).resolve().parents[1] / 'test_androidxr_diagnostics.py')
DIAGNOSTICS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DIAGNOSTICS)

class AndroidXrCommandsTest(unittest.TestCase):
    def test_device_abi(self):
        self.assertEqual('x86_64', DIAGNOSTICS.device_abi('x86_64,x86\n'))
        self.assertEqual('arm64-v8a', DIAGNOSTICS.device_abi('arm64-v8a,armeabi-v7a'))
        with self.assertRaises(ValueError):
            DIAGNOSTICS.device_abi('armeabi-v7a')
