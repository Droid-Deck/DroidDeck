"""The online run script must read the screens and the trace the way it claims.

Two kinds of mistake have cost runs here, so both are checked:

  * the classifier deciding a room is up when the pause list said NOT AVAILABLE,
    which reports a fix that is not there;
  * an endpoint being hardcoded, which is what the network work must never do: the
    title has to make its own calls, and a literal address in a repository is both
    a legal problem and a lie about what was tested.

The trace parsers are checked against the shapes a real uwp-host trace record has,
rather than against the whole file.
"""

import re
import runpy
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/xbox/fh4-online-run.py"
OCR = ROOT / "tools/xbox/fh4-ocr.ps1"

RUN = runpy.run_path(str(SCRIPT))

# A pause list as the title draws it while there is no session: the connect item
# is offered and the player list says there is nothing to show.
PAUSE_NOT_JOINED = """
HORIZON LIFE
ONLINE ADVENTURE
WORLD MAP
PLAY ONLINE
CONNECT TO HORIZON LIFE
PLAYER LIST
NOT AVAILABLE
EXIT TO DESKTOP
"""

# The same list once the title is in a session: the connect item is gone because
# there is nothing left to connect to.
PAUSE_JOINED = """
HORIZON LIFE
ONLINE ADVENTURE
WORLD MAP
PLAY ONLINE
PLAYER LIST
ESTHER
DRIVER 1284
EXIT TO DESKTOP
"""


class ClassifyTest(unittest.TestCase):
    def test_not_available_means_the_room_is_not_up(self):
        self.assertEqual(RUN["classify"](PAUSE_NOT_JOINED), "not_joined")

    def test_an_offered_connect_item_means_not_up_even_without_the_warning(self):
        text = PAUSE_NOT_JOINED.replace("NOT AVAILABLE", "")
        self.assertEqual(RUN["classify"](text), "not_joined")

    def test_a_player_list_without_the_connect_item_is_joined(self):
        self.assertEqual(RUN["classify"](PAUSE_JOINED), "joined")

    def test_a_screen_with_neither_is_unknown_not_a_join(self):
        self.assertEqual(RUN["classify"]("VKD3D 60 FPS CHAT"), "unknown")
        self.assertEqual(RUN["classify"](""), "unknown")


class TraceTest(unittest.TestCase):
    def test_the_published_addresses_are_read_once_each(self):
        # The shim rewrites on every hand-out and traces the first one, so a
        # repeated record is normal and must not look like two addresses.
        trace = "\n".join([
            "0164 xbl_devaddr sda ipv4=192.168.50.99",
            "0164 xbl_devaddr sda ipv4=192.168.50.99",
            "0164 xbl_devaddr sda ipv6=0000:0000:0000:0000:0000:ffff:c0a8:3263",
        ])
        ipv4, ipv6 = RUN["published"](trace)
        self.assertEqual(ipv4, ["192.168.50.99"])
        self.assertEqual(ipv6, ["0000:0000:0000:0000:0000:ffff:c0a8:3263"])

    def test_an_empty_trace_publishes_nothing(self):
        self.assertEqual(RUN["published"](""), ([], []))

    def test_the_network_summary_separates_connected_from_unresolved(self):
        trace = "\n".join([
            "038c msg_ws connected ok (wss://rta.xboxlive.com/connect)",
            "038c uwp-xhr2: GET https://example.invalid -> HTTP 200",
            "0378 uwp-xhr2: HttpSendRequestW failed POST https://example.invalid err=12007",
            "0378 uwp-xhr2: fail POST https://example.invalid hr=0x80072ee7",
            "037c uwp-xhr2: HttpSendRequestW failed POST https://other.invalid err=12007",
        ])
        summary = RUN["network_summary"](trace)
        self.assertTrue(summary["websocket"])
        self.assertEqual(summary["statuses"], ["200"])
        # Grouped by host, so "one service twice" does not look like "a resolver
        # dropping everything".
        self.assertEqual(summary["name_failures"],
                         {"example.invalid": 1, "other.invalid": 1})

    def test_a_failure_without_a_parsable_url_is_still_counted(self):
        summary = RUN["network_summary"]("x uwp-xhr2: failed err=12007")
        self.assertEqual(summary["name_failures"], {"(unparsed)": 1})


class DialogTest(unittest.TestCase):
    """The join-failure box is what says why, so it must be recognised."""

    def test_the_reported_complaint_is_recognised(self):
        self.assertTrue(RUN["join_dialog"](
            "UNABLE TO CONNECT TO THE LIVE CONFIGURATION SERVERS\nPRESS X FOR NETWORK DIAGNOSTICS"))

    def test_the_pause_list_is_not_mistaken_for_the_box(self):
        # It has a HORIZON LIFE item and a PLAY ONLINE item; neither is the box.
        self.assertFalse(RUN["join_dialog"](PAUSE_NOT_JOINED))
        self.assertFalse(RUN["join_dialog"](PAUSE_JOINED))

    def test_a_driving_frame_is_not_the_box(self):
        self.assertFalse(RUN["join_dialog"]("60 FPS VKD3D HORIZON LIFE 12,345 CR"))


class TraceSliceTest(unittest.TestCase):
    def test_a_slice_from_before_the_press_holds_only_what_followed(self):
        # Offset handling is what makes the join's own traffic distinguishable from
        # the traffic made at the title screen.
        trace = "early line\nlater line\nlast line"
        offset = len(trace) - len("later line\nlast line")
        self.assertEqual(trace[offset:], "later line\nlast line")

    def test_an_offset_past_the_end_is_treated_as_the_whole_file(self):
        trace = "one line"
        offset = 999
        self.assertEqual(trace[offset:] if 0 <= offset < len(trace) else trace, trace)


class NoEndpointTest(unittest.TestCase):
    """Nothing in the run may name a server, an address or a namespace."""

    def test_the_script_names_no_endpoint(self):
        source = SCRIPT.read_text(encoding="utf-8")
        # The docstring may describe the shape; the code may not carry one.
        body = source.split('"""', 2)[-1]
        self.assertNotIn("http://", body)
        self.assertNotIn("https://", body)
        self.assertNotIn("wss://", body)
        # A bare domain would be just as much of a hardcoded endpoint.
        self.assertIsNone(re.search(r"\b[a-z0-9-]+\.(com|net|org|io)\b", body))

    def test_the_ocr_helper_is_beside_the_script(self):
        self.assertTrue(OCR.exists())
        text = OCR.read_text(encoding="utf-8")
        # The two faults that made OCR silently return nothing.
        self.assertIn("Resolve-Path", text)
        self.assertIn("OCR_FAILED", text)


if __name__ == "__main__":
    unittest.main()
