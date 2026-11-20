from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/gamescope/patches/0139-hdr-switch-live.patch"
APP = ROOT / "app/src/main/java/com/droiddeck/launcher"
RES = ROOT / "app/src/main/res"


def lines(prefix):
    return [line[1:] for line in PATCH.read_text().splitlines()
            if line.startswith(prefix) and not line.startswith(prefix * 3)]


class HdrLiveSwitchTest(unittest.TestCase):
    def test_display_capability_does_not_depend_on_the_setting(self):
        added = [line for line in lines("+") if "bExposeHDRSupport   =" in line]
        self.assertEqual(len(added), 1)
        self.assertNotIn("cv_hdr_enabled", added[0])
        self.assertIn("m_uMaxTargetLuminance > m_pConnector->m_uReferenceLuminance", added[0])

    def test_sdr_keeps_the_8_bit_output(self):
        added = [line for line in lines("+") if "*pPrimaryPlaneFormat" in line]
        self.assertEqual(len(added), 1)
        self.assertRegex(added[0], r"cv_hdr_enabled && u10BitFormat != DRM_FORMAT_INVALID \) \? u10BitFormat : u8BitFormat")

    def test_remade_output_images_pick_the_format_again(self):
        text = PATCH.read_text()
        hunk = text[text.index("src/rendervulkan.cpp"):]
        added = [line[1:] for line in hunk.splitlines() if line.startswith("+") and not line.startswith("+++")]
        self.assertEqual([line.strip() for line in added],
                         ["GetBackend()->GetPreferredOutputFormat( &pOutput->uOutputFormat, &pOutput->uOutputFormatOverlay );"])
        self.assertIn("if ( vulkan_defer_output_images() )", hunk[:hunk.index(added[0])])

    def test_capable_display_does_not_force_dxvk_hdr_off(self):
        added = "\n".join(lines("+"))
        self.assertIn('unsetenv( "DXVK_HDR" )', added)
        off = added.index('setenv( "DXVK_HDR", "0", true )')
        self.assertIn("!GetCurrentConnector()->GetHDRInfo().bExposeHDRSupport", added[:off])

    def test_the_gate_follows_the_display_not_the_setting(self):
        text = (APP / "SessionActivity.kt").read_text()
        start = text.index("val attached = CompositorHost.isStarted")
        block = text[start:text.index("DriverSetupLog.write", start)]
        request = block[block.index("nativeSetHdrRequest("):block.index("Log.i", block.index("nativeSetHdrRequest("))]
        self.assertNotIn("SessionPrefs.hdr", block[:block.index("if (!SessionState.running)")])
        self.assertIn("capable, false", request)
        self.assertIn("SessionState.hdrCapable && SessionPrefs.hdr(this, mode)", block)

    def test_both_switches_apply_to_the_running_session(self):
        helper = (APP / "session/SessionHdr.kt").read_text()
        self.assertIn('setGamescopeConvar(app, "hdr_enabled", if (on) 1 else 0)', helper)
        self.assertIn("nativeSetHdrOutput(on)", helper)
        self.assertIn("nativeSetZeroCopy(on || SessionPrefs.zeroCopy(context, mode))", helper)
        self.assertIn("SessionPrefs.prefMode(SessionState.mode) != mode", helper)
        for name in ("SessionActivity.kt", "FrontEndActivity.kt"):
            text = (APP / name).read_text()
            self.assertEqual(len(re.findall(r"onHdr = \{ on -> com\.droiddeck\.launcher\.session\.SessionHdr\.set\(", text)), 1, name)
            self.assertNotIn("SessionPrefs.setHdr(", text, name)

    def test_a_saved_opt_out_applies_before_the_gate_opens(self):
        native = (ROOT / "app/src/main/cpp/waylandcomp/src/wl_color_mgmt.c").read_text()
        switch = native[native.index("void droiddeck_color_set_output(int on)"):]
        self.assertLess(switch.index("atomic_store(&g_output_wanted, on)"), switch.index("gate != 1"))
        opened = native[native.index("atomic_store(&g_gate, 1);"):]
        self.assertIn("if (!atomic_load(&g_output_wanted)) atomic_store(&g_output_on, 0);", opened[:opened.index("if (mode == 3)")])
        text = (APP / "SessionActivity.kt").read_text()
        self.assertNotIn("if (attached) WaylandCompositor.nativeSetHdrOutput", text)

    def test_retries_stay_with_the_session_that_asked(self):
        helper = (APP / "session/SessionHdr.kt").read_text()
        self.assertIn("val session = SessionState.sessionId", helper)
        self.assertIn("SessionState.sessionId == session", helper)

    def test_no_restart_hint_is_left(self):
        for path in list(RES.glob("values*/strings.xml")) + list(APP.rglob("*.kt")):
            self.assertNotIn("mode_hdr_restart", path.read_text(), str(path))


if __name__ == "__main__":
    unittest.main()
