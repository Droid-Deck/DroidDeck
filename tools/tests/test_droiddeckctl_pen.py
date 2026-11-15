import json
import unittest
import unittest.mock

from test_droiddeckctl import FakeAdbTest, ctl


def samples(*argv):
    return ctl.pen_samples(ctl.build_parser().parse_args(["input", "pen", *argv]))


def phases(gesture):
    return [s["phase"] for s in gesture]


def dumpsys_input(rotation=0, size=(1140, 2616), active=1, frame=None):
    other = ("Viewport INTERNAL: displayId=2, uniqueId=local:147, port=147, orientation=0, "
             "logicalFrame=[0, 0, 2248, 2480], physicalFrame=[0, 0, 2248, 2480], deviceSize=[2248, 2480], isActive=[0]")
    shown = (f"Viewport INTERNAL: displayId=0, uniqueId=local:148, port=148, orientation={rotation}, "
             f"logicalFrame=[0, 0, {size[0]}, {size[1]}], physicalFrame=[{', '.join(map(str, frame or (0, 0, *size)))}], "
             f"deviceSize=[{size[0]}, {size[1]}], isActive=[{active}]")
    reader = (f"Input Reader State (Nums of device: 11):\n  Device 6: touchpanel_pen\n      {other}\n"
              f"  Configuration:\n      Viewports:\n        {other}\n        {shown}\n OplusInputReader mMergeEnable =  0\n")
    return reader + "\nINPUT MANAGER (dumpsys input)\n\n" + reader


def frames(commands):
    found, frame = [], {}
    for command in commands:
        if command["command"] == "inject":
            events = command["events"]
            for code, value in zip(events[1::3], events[2::3]):
                if code == "SYN_REPORT":
                    found.append(frame)
                    frame = {}
                else:
                    frame[code] = value
    return found


class PenSamplesTest(unittest.TestCase):
    def test_a_tap_hovers_lands_lifts_and_leaves(self):
        gesture = samples("tap", "0.5", "0.5", "--hold", "20")
        self.assertEqual(["hover"] * 10 + ["down", "move", "up"] + ["hover"] * 10 + ["out"], phases(gesture))
        times = [s["t"] for s in gesture]
        self.assertEqual(sorted(times), times)
        self.assertEqual([0, 8, 80, 100, 180, 180], [times[0], times[1], times[10], times[12], times[-2], times[-1]])
        self.assertEqual({0.5}, {s["pressure"] for s in gesture[10:13]})
        self.assertEqual({0}, {s["pressure"] for s in gesture[:10] + gesture[13:]})
        self.assertEqual({(0.5, 0.5, None)}, {(s["x"], s["y"], s["button"]) for s in gesture})

    def test_without_hover_the_pen_lands_and_leaves_at_once(self):
        gesture = samples("tap", "0.5", "0.5", "--hover-ms", "0", "--hold", "0")
        self.assertEqual([("down", 0), ("up", 0), ("out", 0)], [(s["phase"], s["t"]) for s in gesture])

    def test_a_stroke_ramps_pressure_and_tilt(self):
        gesture = samples("stroke", "0.25", "0.5", "0.75", "1", "--duration", "100", "--hover-ms", "20", "--rate", "100",
                          "--pressure", "0.25:0.75", "--tilt-x=-30:30", "--tilt-y", "10")
        self.assertEqual(["hover", "hover", "down"] + ["move"] * 9 + ["up", "hover", "hover", "out"], phases(gesture))
        contact = gesture[2:13]
        self.assertEqual([(20, 0.25, 0.5, 0.25), (70, 0.5, 0.75, 0.5), (120, 0.75, 1, 0.75)],
                         [(s["t"], s["x"], s["y"], s["pressure"]) for s in (contact[0], contact[5], contact[-1])])
        self.assertEqual((-30, 30), (gesture[0]["tilt_x"], gesture[-1]["tilt_x"]))
        self.assertAlmostEqual(-30 + 60 * 20 / 140, contact[0]["tilt_x"])
        self.assertEqual({10}, {s["tilt_y"] for s in gesture})

    def test_hover_glides_to_the_target(self):
        gesture = samples("hover", "0.25", "0.25", "--to", "0.75", "0.5", "--duration", "300", "--distance", "5")
        self.assertEqual(["hover"] * 37 + ["out"], phases(gesture))
        self.assertEqual([(0.25, 0.25), (0.75, 0.5), (0.75, 0.5)],
                         [(s["x"], s["y"]) for s in (gesture[0], gesture[-2], gesture[-1])])
        self.assertEqual((300, {5}), (gesture[-1]["t"], {s["distance"] for s in gesture}))

    def test_a_side_button_is_held_while_hovering(self):
        gesture = samples("button", "secondary", "0.5", "0.5", "--hold", "40")
        self.assertEqual([80, 88, 96, 104, 112], [s["t"] for s in gesture if s["button"] == "secondary"])
        self.assertEqual(["hover"] * (len(gesture) - 1) + ["out"], phases(gesture))
        self.assertEqual((200, None), (gesture[-1]["t"], gesture[-2]["button"]))


class PenUinputTest(FakeAdbTest):
    def setUp(self):
        super().setUp()
        self.configure({}, dumpsys_input=dumpsys_input())

    def pen(self, *argv):
        return self.run_ctl("input", "pen", *argv)

    def script(self):
        [call] = [c for c in self.calls() if c["args"][:3] == ["shell", "uinput", "-"]]
        return [json.loads(line) for line in call["stdin"].splitlines()]

    def test_a_stroke_draws_with_a_virtual_direct_pen(self):
        code, out = self.pen("stroke", "0.25", "0.5", "0.75", "0.5", "--duration", "100", "--rate", "100",
                             "--hover-ms", "20", "--pressure", "0.2:1")
        self.assertEqual(0, code)
        self.assertEqual({"ok": True, "command": "input", "type": "pen", "samples": 16, "deviceSerial": "fake-1",
                          "viewport": {"display": 0, "rotation": 0, "size": [1140, 2616]}},
                         {k: v for k, v in out.items() if k != "ms"})
        self.assertFalse(self.calls("input"))
        commands = self.script()
        register = commands[0]
        bits = {c["type"]: c["data"] for c in register["configuration"]}
        self.assertEqual(("register", "virtual", ["EV_KEY", "EV_ABS"], ["INPUT_PROP_DIRECT"]),
                         (register["command"], register["bus"], bits["UI_SET_EVBIT"], bits["UI_SET_PROPBIT"]))
        self.assertEqual({"BTN_TOOL_PEN", "BTN_TOOL_RUBBER", "BTN_TOUCH", "BTN_STYLUS", "BTN_STYLUS2"},
                         set(bits["UI_SET_KEYBIT"]))
        ranges = {a["code"]: (a["info"]["minimum"], a["info"]["maximum"]) for a in register["abs_info"]}
        self.assertEqual({"ABS_X": (0, 1140 * 16 - 1), "ABS_Y": (0, 2616 * 16 - 1), "ABS_PRESSURE": (0, 4095),
                          "ABS_DISTANCE": (0, 255), "ABS_TILT_X": (-90, 90), "ABS_TILT_Y": (-90, 90)}, ranges)
        self.assertEqual(set(bits["UI_SET_ABSBIT"]), set(ranges))
        self.assertEqual({"id": 1, "command": "delay", "duration": 1000}, commands[1])
        self.assertNotIn("updateTimeBase", [c["command"] for c in commands])
        self.assertEqual(140, sum(c["duration"] for c in commands[2:-2] if c["command"] == "delay"))
        self.assertEqual([{"id": 1, "command": "delay", "duration": 300},
                          {"id": 1, "command": "sync", "syncToken": ctl.UINPUT_SYNC}], commands[-2:])
        drawn = frames(commands)
        self.assertEqual({"BTN_TOOL_PEN": 1, "ABS_X": 4560, "ABS_Y": 20928, "ABS_PRESSURE": 0, "ABS_DISTANCE": 0,
                          "ABS_TILT_X": 0, "ABS_TILT_Y": 0}, drawn[0])
        self.assertEqual([{"ABS_X": 4561}, {"BTN_TOUCH": 1, "ABS_X": 4560, "ABS_PRESSURE": 819}], drawn[1:3])
        self.assertEqual([{"ABS_X": 13680, "ABS_PRESSURE": 4095}, {"BTN_TOUCH": 0, "ABS_PRESSURE": 0}, {"ABS_X": 13681},
                          {"ABS_X": 13680}, {"BTN_TOOL_PEN": 0}], drawn[-5:])
        pressures = [f["ABS_PRESSURE"] for f in drawn[2:-4]]
        self.assertEqual(sorted(pressures), pressures)

    def test_positions_and_tilt_follow_the_rotation(self):
        cases = {0: ([1000, 2000], 4000, 24000, 30, -10), 1: ([2000, 1000], 3999, 8000, 10, 30),
                 2: ([1000, 2000], 11999, 7999, -30, 10), 3: ([2000, 1000], 12000, 23999, -10, -30)}
        for rotation, (size, x, y, tilt_x, tilt_y) in cases.items():
            with self.subTest(rotation=rotation):
                self.configure({}, dumpsys_input=dumpsys_input(rotation, size))
                self.log.unlink(missing_ok=True)
                code, out = self.pen("tap", "0.25", "0.75", "--tilt-x", "30", "--tilt-y=-10")
                self.assertEqual((0, rotation), (code, out["viewport"]["rotation"]))
                commands = self.script()
                self.assertEqual([15999, 31999], [a["info"]["maximum"] for a in commands[0]["abs_info"][:2]])
                first = frames(commands)[0]
                self.assertEqual((x, y, tilt_x, tilt_y),
                                 tuple(first[key] for key in ("ABS_X", "ABS_Y", "ABS_TILT_X", "ABS_TILT_Y")))

    def test_the_mapping_inverts_aosps_input_coordinates_example(self):
        self.assertEqual((0, 2, 0, 0), ctl.uinput_point(2 / 4, 2 / 3, 0, 0, 1, 3, 4, [0, 0, 4, 3]))
        self.assertEqual((2, 3, 0, 0), ctl.uinput_point(1, 1, 0, 0, 0, 3, 4, [0, 0, 3, 4]))
        self.assertEqual((0, 0, 0, 0), ctl.uinput_point(1, 1, 0, 0, 2, 3, 4, [0, 0, 3, 4]))

    def test_positions_map_onto_the_physical_frame(self):
        cases = {0: ((1000, 2000), (100, 250, 900, 1750), ("1", "0.05"), (14399, 5200)),
                 1: ((2000, 1000), (250, 100, 1750, 900), ("0.05", "1"), (1600, 5200))}
        for rotation, (size, frame, position, first) in cases.items():
            with self.subTest(rotation=rotation):
                self.configure({}, dumpsys_input=dumpsys_input(rotation, size, frame=frame))
                self.log.unlink(missing_ok=True)
                self.assertEqual(0, self.pen("tap", *position)[0])
                drawn = frames(self.script())
                self.assertEqual(first, (drawn[0]["ABS_X"], drawn[0]["ABS_Y"]))
                self.assertTrue(all(1600 <= f.get("ABS_X", 1600) < 14400 for f in drawn))

    def test_eraser_and_buttons_use_the_stylus_keys(self):
        for button, side in (("primary", "BTN_STYLUS"), ("secondary", "BTN_STYLUS2")):
            with self.subTest(button=button):
                self.log.unlink(missing_ok=True)
                code, _ = self.pen("button", button, "0.5", "0.5", "--hold", "40", "--tool", "eraser", "--distance", "12")
                self.assertEqual(0, code)
                drawn = frames(self.script())
                keys = [(key, value) for frame in drawn for key, value in frame.items() if key.startswith("BTN_")]
                self.assertEqual([("BTN_TOOL_RUBBER", 1), (side, 1), (side, 0), ("BTN_TOOL_RUBBER", 0)], keys)
                self.assertEqual(12, drawn[0]["ABS_DISTANCE"])

    def test_a_still_pen_keeps_reporting_at_the_rate(self):
        code, out = self.pen("hover", "1", "0.5", "--duration", "100", "--rate", "50")
        self.assertEqual(0, code)
        commands = self.script()
        drawn = frames(commands)
        self.assertEqual(out["samples"], len(drawn))
        self.assertEqual([18239, 18238, 18239, 18238, 18239, 18238], [f["ABS_X"] for f in drawn[:-1]])
        self.assertEqual([20, 20, 20, 20, 20, 300], [c["duration"] for c in commands[2:] if c["command"] == "delay"])

    def test_a_contact_without_pressure_still_touches(self):
        code, _ = self.pen("tap", "0.5", "0.5", "--pressure", "0", "--hover-ms", "0", "--hold", "0", "--distance", "5")
        self.assertEqual(0, code)
        drawn = frames(self.script())
        self.assertEqual((1, 1, 1, 0),
                         tuple(drawn[0][key] for key in ("BTN_TOOL_PEN", "BTN_TOUCH", "ABS_PRESSURE", "ABS_DISTANCE")))
        self.assertEqual([{"BTN_TOUCH": 0, "ABS_PRESSURE": 0, "ABS_DISTANCE": 5}, {"BTN_TOOL_PEN": 0}], drawn[1:])

    def test_bad_values_are_refused(self):
        cases = [
            (("tap", "1.5", "0.5"), "Positions are fractions of the screen"),
            (("hover", "0.5", "0.5", "--to", "nan", "0.5"), "Positions are fractions of the screen"),
            (("stroke", "0", "0", "1", "1", "--pressure", "0.5:1.5"), "--pressure must be 0..1"),
            (("tap", "0.5", "0.5", "--tilt-x=-91"), "--tilt-x and --tilt-y must be -90..90"),
            (("tap", "0.5", "0.5", "--tilt-y", "120"), "--tilt-x and --tilt-y must be -90..90"),
            (("hover", "0.5", "0.5", "--distance", "256"), "--distance must be 0..255"),
            (("tap", "0.5", "0.5", "--rate", "1e9"), "--rate must be 1..1000 Hz"),
            (("button", "primary", "0.5", "0.5", "--hold", "-5"), "--hold must not be negative"),
            (("tap", "0.5", "0.5", "--hover-ms", "-1"), "--hover-ms must not be negative"),
            (("stroke", "0", "0", "1", "1", "--duration", "40000"), "lasts 40160 ms, more than 30000"),
            (("hover", "0.5", "0.5", "--duration", "1000000000"), "more than 30000"),
        ]
        with unittest.mock.patch.object(ctl, "pen_times", side_effect=AssertionError):
            for argv, message in cases:
                with self.subTest(message=message):
                    code, out = self.pen(*argv)
                    self.assertEqual((2, "INVALID_INPUT"), (code, out["error"]["code"]))
                    self.assertIn(message, out["error"]["message"])
        self.assertFalse(self.calls())
        self.assertEqual(30_000, samples("tap", "1", "1", "--hover-ms", "14970")[-1]["t"])

    def test_a_shell_without_uinput_is_unavailable(self):
        for probe, message in (("no-uinput", "no `uinput` command"), ("no-dev-uinput", "cannot write /dev/uinput")):
            with self.subTest(probe=probe):
                self.configure({}, dumpsys_input=dumpsys_input(), uinput_probe=probe)
                code, out = self.pen("tap", "0.5", "0.5")
                self.assertEqual((3, "UINPUT_UNAVAILABLE"), (code, out["error"]["code"]))
                self.assertIn(message, out["error"]["message"])
        self.assertFalse([c for c in self.calls() if c["args"][:2] == ["shell", "uinput"]])

    def test_a_missing_or_dark_display_is_refused(self):
        for dump, message in (("Input Reader State (Nums of device: 0):\n", "no internal display viewport"),
                              (dumpsys_input(active=0), "Display 0 is off")):
            with self.subTest(message=message):
                self.configure({}, dumpsys_input=dump)
                code, out = self.pen("tap", "0.5", "0.5")
                self.assertEqual((3, "NO_VIEWPORT"), (code, out["error"]["code"]))
                self.assertIn(message, out["error"]["message"])
        self.assertFalse([c for c in self.calls() if c["args"][:2] == ["shell", "uinput"]])

    def test_uinput_that_stops_early_fails(self):
        self.configure({}, dumpsys_input=dumpsys_input(), uinput_stdout="")
        code, out = self.pen("tap", "0.5", "0.5")
        self.assertEqual((3, "UINPUT_FAILED"), (code, out["error"]["code"]))
        self.assertIn("adb logcat -s UINPUT", out["error"]["message"])

    def test_the_viewport_android_maps_touchscreens_to(self):
        self.assertEqual({"display": 0, "rotation": 0, "size": [1140, 2616], "frame": [0, 0, 1140, 2616], "active": True},
                         ctl.input_viewport(dumpsys_input()))
        device = "  Device 7: touchpanel\n      Viewport INTERNAL: displayId=0, orientation=0, deviceSize=[1140, 2616]\n"
        self.assertEqual(1, ctl.input_viewport(device + dumpsys_input(1, (2480, 2248)))["rotation"])
        newest = ("      Viewports:\n        Viewport INTERNAL: displayId=3, uniqueId=local:1, port=<none>, orientation=3, "
                  "densityDpi=420 xDpi=409.432007, yDpi=411.891998, logicalFrame=[0, 0, 2400, 1080], "
                  "physicalFrame=[0, 0, 2400, 1080], deviceSize=[2400, 1080], isActive=[1]\n"
                  "        Viewport INTERNAL: displayId=5, uniqueId=local:3, port=2, orientation=0, "
                  "deviceSize=[1080, 2400], isActive=[1]\n"
                  "        Viewport EXTERNAL: displayId=0, uniqueId=local:2, port=1, orientation=0, "
                  "deviceSize=[1920, 1080], isActive=[1]\n")
        self.assertEqual({"display": 3, "rotation": 3, "size": [2400, 1080], "frame": [0, 0, 2400, 1080], "active": True},
                         ctl.input_viewport(newest))
        older = ("Viewport INTERNAL: displayId=0, orientation=2, logicalFrame=[0, 0, 720, 1280], "
                 "physicalFrame=[-3, 0, 717, 1280], deviceSize=[720, 1280]")
        self.assertEqual({"display": 0, "rotation": 2, "size": [720, 1280], "frame": [-3, 0, 717, 1280], "active": True},
                         ctl.input_viewport(older))


if __name__ == "__main__":
    unittest.main()
