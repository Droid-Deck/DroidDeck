import json
import unittest

from test_droiddeckctl import FakeAdbTest, ctl


def key_frames(commands):
    found = []
    for command in commands:
        if command["command"] == "inject":
            events = command["events"]
            found.append({code: value for code, value in zip(events[1::3], events[2::3]) if code != "SYN_REPORT"})
    return found


class GamepadTest(FakeAdbTest):
    def setUp(self):
        super().setUp()
        self.configure({})

    def gamepad(self, *argv):
        return self.run_ctl("input", "gamepad", *argv)

    def script(self):
        [call] = [c for c in self.calls() if c["args"][:3] == ["shell", "uinput", "-"]]
        return [json.loads(line) for line in call["stdin"].splitlines()]

    def test_buttons_are_pressed_in_order_on_a_real_looking_gamepad(self):
        code, out = self.gamepad("b", "rb", "--hold", "50", "--gap", "100")
        self.assertEqual(0, code)
        self.assertEqual({"ok": True, "command": "input", "type": "gamepad", "buttons": ["b", "rb"], "deviceSerial": "fake-1"},
                         {k: v for k, v in out.items() if k != "ms"})
        self.assertFalse(self.calls("input"))
        commands = self.script()
        register = commands[0]
        bits = {c["type"]: c["data"] for c in register["configuration"]}
        self.assertEqual(("register", "usb", ["EV_KEY", "EV_ABS"]), (register["command"], register["bus"], bits["UI_SET_EVBIT"]))
        self.assertFalse(register["name"].lower().startswith(("uinput-", "uinput_")))
        self.assertTrue({"BTN_SOUTH", "BTN_EAST", "BTN_TL", "BTN_TR", "BTN_START"} <= set(bits["UI_SET_KEYBIT"]))
        self.assertEqual(set(bits["UI_SET_ABSBIT"]), {a["code"] for a in register["abs_info"]})
        self.assertEqual({"id": 1, "command": "delay", "duration": ctl.UINPUT_REGISTER_MS}, commands[1])
        self.assertEqual([{"BTN_EAST": 1}, {"BTN_EAST": 0}, {"BTN_TR": 1}, {"BTN_TR": 0}], key_frames(commands))
        self.assertEqual([50, 100, 50, 100], [c["duration"] for c in commands[2:-2] if c["command"] == "delay"])
        self.assertEqual({"id": 1, "command": "sync", "syncToken": ctl.GAMEPAD_SYNC}, commands[-1])

    def test_a_zero_gap_sends_no_empty_delay_which_android_rejects(self):
        code, _ = self.gamepad("a", "b", "--hold", "40", "--gap", "0")
        self.assertEqual(0, code)
        commands = self.script()
        self.assertEqual([{"BTN_SOUTH": 1}, {"BTN_SOUTH": 0}, {"BTN_EAST": 1}, {"BTN_EAST": 0}], key_frames(commands))
        self.assertEqual([], [c for c in commands if c["command"] == "delay" and c["duration"] <= 0])
        self.assertEqual([40, 40], [c["duration"] for c in commands[2:-2] if c["command"] == "delay"])

    def test_the_dpad_uses_the_hat_axes(self):
        code, _ = self.gamepad("up", "right", "down", "left")
        self.assertEqual(0, code)
        self.assertEqual([{"ABS_HAT0Y": -1}, {"ABS_HAT0Y": 0}, {"ABS_HAT0X": 1}, {"ABS_HAT0X": 0},
                          {"ABS_HAT0Y": 1}, {"ABS_HAT0Y": 0}, {"ABS_HAT0X": -1}, {"ABS_HAT0X": 0}],
                         key_frames(self.script()))

    def test_an_unknown_button_or_bad_timing_is_refused_before_touching_the_device(self):
        for argv, message in ((("nope",), "Unknown gamepad button 'nope'"), (("a", "--hold", "1"), "--hold must be"),
                              (("a", "--gap", "-5"), "--gap 0..5000")):
            with self.subTest(argv=argv):
                code, out = self.gamepad(*argv)
                self.assertEqual((2, "INVALID_INPUT"), (code, out["error"]["code"]))
                self.assertIn(message, out["error"]["message"])
        self.assertFalse(self.calls())

    def test_a_shell_without_uinput_is_unavailable(self):
        self.configure({}, uinput_probe="no-dev-uinput")
        code, out = self.gamepad("a")
        self.assertEqual((3, "UINPUT_UNAVAILABLE"), (code, out["error"]["code"]))
        self.assertFalse([c for c in self.calls() if c["args"][:2] == ["shell", "uinput"]])

    def test_uinput_that_stops_early_fails(self):
        self.configure({}, uinput_stdout="")
        code, out = self.gamepad("a")
        self.assertEqual((3, "UINPUT_FAILED"), (code, out["error"]["code"]))


if __name__ == "__main__":
    unittest.main()
