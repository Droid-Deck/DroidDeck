from importlib.machinery import SourceFileLoader
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch


script = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-bwrap"
loader = SourceFileLoader("droiddeck_bwrap", str(script))
spec = importlib.util.spec_from_loader(loader.name, loader)
bwrap = importlib.util.module_from_spec(spec)
loader.exec_module(bwrap)


class BwrapSessionOwnershipTest(unittest.TestCase):
    def request(self):
        request = bwrap.Spec()
        request.command = ["/app/bin/game"]
        return bwrap.app_request(request)

    def test_session_clients_mark_their_sandboxes_for_session_cleanup(self):
        with patch.dict(os.environ, {"DROIDDECK_SESSION_BWRAP": "1"}):
            self.assertTrue(self.request()["sessionOwned"])

    def test_independent_guest_commands_are_not_session_owned(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertFalse(self.request()["sessionOwned"])


if __name__ == "__main__":
    unittest.main()
