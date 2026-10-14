from pathlib import Path
import runpy
import tempfile
import unittest


BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'
MODULE = runpy.run_path(str(BIN / 'droiddeck-steam-ui-scale'))
initialize = MODULE['initialize']
seed_text = MODULE['seed_text']
parse = MODULE['parse']
initial_scale = MODULE['initial_scale']
DISPLAY = MODULE['DISPLAY']
seed_native_resolution = MODULE['seed_native_resolution']
initialize_native_resolution = MODULE['initialize_native_resolution']
NATIVE_RESOLUTION = MODULE['NATIVE_RESOLUTION']
seed_no_screensaver_in_game = MODULE['seed_no_screensaver_in_game']
initialize_screensaver = MODULE['initialize_screensaver']


class SteamUiScaleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = self.root / 'config/config.vdf'
        self.config.parent.mkdir()
        self.marker = self.config.with_name('droiddeck-ui-scale-initialized')

    def scale(self, text):
        return parse(text).get('InstallConfigStore').get('UI').get('display').get(DISPLAY).get('ScaleFactor')

    def test_matches_the_thor_setting_and_scales_with_session_resolution(self):
        self.assertAlmostEqual(1.3281567096710205, initial_scale(1280, 720), places=6)
        self.assertAlmostEqual(2 * initial_scale(1280, 720), initial_scale(2560, 1440))

    def test_fresh_client_gets_native_display_setting(self):
        self.assertTrue(initialize(self.root, 1280, 720))
        self.assertAlmostEqual(1.328156709, float(self.scale(self.config.read_text())))
        self.assertTrue(self.marker.exists())

    def test_current_automatic_record_does_not_count_as_a_user_choice(self):
        self.config.write_text('''"InstallConfigStore" {
"UI" { "display" { "Current" { "ScaleFactor" "0.948683321475982666" } } }
}''')
        self.assertTrue(initialize(self.root, 1280, 720))
        self.assertAlmostEqual(initial_scale(1280, 720), float(self.scale(self.config.read_text())))

    def test_existing_manual_scale_is_preserved_exactly(self):
        text = f'"InstallConfigStore" {{ "UI" {{ "display" {{ "{DISPLAY}" {{ "ScaleFactor" "1.7" }} }} }} }}'
        self.config.write_text(text)
        self.assertFalse(initialize(self.root, 1280, 720))
        self.assertEqual(text, self.config.read_text())
        self.assertTrue(self.marker.exists())

    def test_user_changes_after_initialization_survive_restart_and_resolution_change(self):
        initialize(self.root, 1280, 720)
        text = self.config.read_text().replace(self.scale(self.config.read_text()), '1.6')
        self.config.write_text(text)
        self.assertFalse(initialize(self.root, 1920, 1080))
        self.assertEqual(text, self.config.read_text())

    def test_user_returning_to_auto_does_not_get_the_default_again(self):
        initialize(self.root, 1280, 720)
        text = '"InstallConfigStore" { "UI" { "display" { "Current" { "ScaleFactor" "0.95" } } } }'
        self.config.write_text(text)
        self.assertFalse(initialize(self.root, 1280, 720))
        self.assertEqual(text, self.config.read_text())

    def test_existing_choices_for_other_displays_are_also_preserved(self):
        text = '"InstallConfigStore" { "UI" { "display" { "External: other" { "ScaleFactor" "-1" } } } }'
        self.assertEqual((text, False), seed_text(text, 1.328))

    def test_unrelated_settings_comments_and_escaped_strings_keep_their_bytes(self):
        text = r'''// Keep this comment
"InstallConfigStore" {
"Software" { "Valve" { "Steam" { "keep" "quoted \"text\" and { braces }" } } }
"UI" { // comment inside UI
"other" "unchanged"
}
}'''
        updated, changed = seed_text(text, 1.328)
        self.assertTrue(changed)
        self.assertAlmostEqual(1.328, float(self.scale(updated)))
        self.assertIn('"other" "unchanged"', updated)
        self.assertIn('"keep" "quoted \\\"text\\\" and { braces }"', updated)
        self.assertIn('// comment inside UI', updated)
        at = parse(text).get('InstallConfigStore').get('UI').end
        self.assertEqual(text, updated[:at] + updated[at + len(updated) - len(text):])

    def test_invalid_config_is_never_rewritten_or_marked_initialized(self):
        for text in ('"InstallConfigStore" {', 'garbage', '"InstallConfigStore" { "UI" "bad" }'):
            self.config.write_text(text)
            with self.assertRaises(ValueError):
                initialize(self.root, 1280, 720)
            self.assertEqual(text, self.config.read_text())
            self.assertFalse(self.marker.exists())

    def test_invalid_output_size_is_rejected(self):
        with self.assertRaises(ValueError):
            initialize(self.root, 0, 720)
        self.assertFalse(self.config.exists())
        self.assertFalse(self.marker.exists())


class SteamNativeResolutionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = self.root / 'config/config.vdf'
        self.config.parent.mkdir()
        self.marker = self.config.with_name('droiddeck-native-resolution-initialized')

    def native(self, text):
        return parse(text).get('InstallConfigStore').get('Gamescope').get(NATIVE_RESOLUTION)

    def test_fresh_client_renders_at_the_session_size(self):
        self.assertTrue(initialize_native_resolution(self.root))
        self.assertEqual('1', self.native(self.config.read_text()))
        self.assertTrue(self.marker.exists())

    def test_existing_client_gets_it_once_beside_its_settings(self):
        text = '"InstallConfigStore"\n{\n\t"Gamescope"\n\t{\n\t\t"HDREnabled"\t\t"1"\n\t}\n}\n'
        self.config.write_text(text)
        self.assertTrue(initialize_native_resolution(self.root))
        updated = self.config.read_text()
        self.assertEqual('1', self.native(updated))
        self.assertEqual('1', parse(updated).get('InstallConfigStore').get('Gamescope').get('HDREnabled'))

    def test_users_choice_is_kept(self):
        text = f'"InstallConfigStore" {{ "Gamescope" {{ "{NATIVE_RESOLUTION}" "0" }} }}'
        self.assertEqual((text, False), seed_native_resolution(text))

    def test_turning_it_off_after_initialization_survives_restart(self):
        initialize_native_resolution(self.root)
        text = self.config.read_text().replace('"1"', '"0"')
        self.config.write_text(text)
        self.assertFalse(initialize_native_resolution(self.root))
        self.assertEqual(text, self.config.read_text())

    def test_ui_scale_and_native_resolution_seed_the_same_config(self):
        initialize(self.root, 2340, 1080)
        initialize_native_resolution(self.root)
        text = self.config.read_text()
        self.assertEqual('1', self.native(text))
        self.assertIsNotNone(parse(text).get('InstallConfigStore').get('UI').get('display').get(DISPLAY))

    def test_invalid_config_is_never_rewritten_or_marked_initialized(self):
        for text in ('"InstallConfigStore" {', '"InstallConfigStore" { "Gamescope" "bad" }'):
            self.config.write_text(text)
            with self.assertRaises(ValueError):
                initialize_native_resolution(self.root)
            self.assertEqual(text, self.config.read_text())
            self.assertFalse(self.marker.exists())


class SteamScreensaverTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = self.root / 'config/config.vdf'
        self.config.parent.mkdir()
        self.marker = self.config.with_name('droiddeck-screensaver-initialized')

    def in_game(self, text):
        return parse(text).get('InstallConfigStore').get('Customization').get('Screensaver').get('WhileInGame')

    def test_fresh_client_shows_no_screensaver_over_a_game(self):
        self.assertTrue(initialize_screensaver(self.root))
        self.assertEqual('0', self.in_game(self.config.read_text()))
        self.assertTrue(self.marker.exists())

    def test_it_goes_beside_the_screensaver_the_user_picked(self):
        text = ('"InstallConfigStore"\n{\n\t"Customization"\n\t{\n\t\t"Screensaver"\n\t\t{\n'
                '\t\t\t"CurrentScreensaver"\t\t"steam-bouncinglogo"\n\t\t}\n\t}\n}\n')
        updated, changed = seed_no_screensaver_in_game(text)
        self.assertTrue(changed)
        screensaver = parse(updated).get('InstallConfigStore').get('Customization').get('Screensaver')
        self.assertEqual('steam-bouncinglogo', screensaver.get('CurrentScreensaver'))
        self.assertEqual('0', screensaver.get('WhileInGame'))
        self.assertIn('\t\t\t"WhileInGame"\t\t"0"\n\t\t}\n', updated)

    def test_users_choice_is_kept(self):
        text = '"InstallConfigStore" { "Customization" { "Screensaver" { "WhileInGame" "1" } } }'
        self.assertEqual((text, False), seed_no_screensaver_in_game(text))

    def test_turning_it_back_on_survives_restart(self):
        initialize_screensaver(self.root)
        text = '"InstallConfigStore" { "Customization" { } }'
        self.config.write_text(text)
        self.assertFalse(initialize_screensaver(self.root))
        self.assertEqual(text, self.config.read_text())

    def test_every_seeded_setting_lands_in_one_valid_config(self):
        initialize(self.root, 2340, 1080)
        initialize_native_resolution(self.root)
        initialize_screensaver(self.root)
        store = parse(self.config.read_text()).get('InstallConfigStore')
        self.assertIsNotNone(store.get('UI').get('display').get(DISPLAY))
        self.assertEqual('1', store.get('Gamescope').get(NATIVE_RESOLUTION))
        self.assertEqual('0', store.get('Customization').get('Screensaver').get('WhileInGame'))

    def test_invalid_config_is_never_rewritten_or_marked_initialized(self):
        for text in ('"InstallConfigStore" {', '"InstallConfigStore" { "Customization" "bad" }',
                     '"InstallConfigStore" { "Customization" { "Screensaver" "bad" } }'):
            self.config.write_text(text)
            with self.assertRaises(ValueError):
                initialize_screensaver(self.root)
            self.assertEqual(text, self.config.read_text())
            self.assertFalse(self.marker.exists())
