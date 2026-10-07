import argparse
import importlib.machinery
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
loader = importlib.machinery.SourceFileLoader("droiddeckctl", str(TOOLS / "droiddeckctl"))
spec = importlib.util.spec_from_loader(loader.name, loader)
ctl = importlib.util.module_from_spec(spec)
loader.exec_module(ctl)


class LogExportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.destination = Path(self.tmp.name) / "session.zip"
        self.args = argparse.Namespace(command="logs", timeout=90, destination=str(self.destination))
        self.state = {"session": {"running": True, "id": "session1", "artifactsAvailable": True}}

    def test_logs_fetch_only_the_provider_sanitized_zip(self):
        response = {"ok": True, "sessionId": "session1", "path": "/data/user/0/com.droiddeck.launcher/cache/shared-logs/session.zip"}
        result = subprocess.CompletedProcess([], 0, b"sanitized ZIP", b"")
        with patch.object(ctl, "get_state", return_value=self.state), patch.object(ctl, "provider_call", return_value=response) as provider, patch.object(ctl, "run_adb", return_value=result) as adb:
            output = ctl.execute(self.args, "adb", "device")
        provider.assert_called_once_with("adb", "device", "logs", timeout=120)
        adb.assert_called_once_with("adb", "device", "exec-out", "run-as", "com.droiddeck.launcher", "cat", response["path"], timeout=120)
        self.assertEqual(b"sanitized ZIP", self.destination.read_bytes())
        self.assertEqual(str(self.destination.resolve()), output["path"])

    def test_export_failure_never_falls_back_to_pulling_raw_logs(self):
        response = {"ok": False, "error": {"code": "NO_SESSION_LOGS", "message": "unavailable"}}
        with patch.object(ctl, "get_state", return_value=self.state), patch.object(ctl, "provider_call", return_value=response), patch.object(ctl, "run_adb") as adb:
            with self.assertRaises(ctl.ControlError):
                ctl.execute(self.args, "adb", "device")
        adb.assert_not_called()
        self.assertFalse(self.destination.exists())

    def test_failed_retrieval_does_not_write_a_partial_zip(self):
        response = {"ok": True, "sessionId": "session1", "path": "/private/session.zip"}
        result = subprocess.CompletedProcess([], 1, b"partial data", b"read failure")
        with patch.object(ctl, "get_state", return_value=self.state), patch.object(ctl, "provider_call", return_value=response), patch.object(ctl, "run_adb", return_value=result):
            with self.assertRaises(ctl.ControlError):
                ctl.execute(self.args, "adb", "device")
        self.assertFalse(self.destination.exists())
