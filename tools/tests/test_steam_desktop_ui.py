import runpy
from pathlib import Path
import unittest
from unittest.mock import Mock, patch


select_desktop = runpy.run_path(str(Path(__file__).resolve().parents[1] /
    'linuxfs/overlay/usr/local/bin/droiddeck-steam-desktop-ui'))['select_desktop']


class SteamDesktopUiTest(unittest.TestCase):
    def test_waits_for_cef_and_native_api_then_selects_once(self):
        evaluate = Mock(side_effect=[ConnectionError(), {'value': False}, {'value': True}])
        with patch('time.sleep'):
            self.assertTrue(select_desktop(evaluate))
        self.assertEqual(evaluate.call_count, 3)
        self.assertIn('SteamClient.UI.ExitBigPictureMode()', evaluate.call_args.args[0])

    def test_a_client_that_never_starts_has_a_bounded_wait(self):
        evaluate = Mock()
        with patch('time.monotonic', side_effect=[0, 91]):
            self.assertFalse(select_desktop(evaluate))
        evaluate.assert_not_called()
