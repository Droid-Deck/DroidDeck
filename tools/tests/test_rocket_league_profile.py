from pathlib import Path
import runpy
import tempfile
import unittest

BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'
MODULE = runpy.run_path(str(BIN / 'droiddeck-game-env'))


def pe(machine, suffix=b''):
    head = bytearray(64)
    head[:2] = b'MZ'
    head[60:64] = (64).to_bytes(4, 'little')
    return bytes(head) + b'PE\0\0' + machine.to_bytes(2, 'little') + suffix


class RocketLeagueProfileTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.game = self.root / 'game'
        self.game.mkdir()
        self.proton = self.root / 'proton'
        self.native = self.root / 'files/lib/wine/dxvk/aarch64-windows/dxgi.dll'
        self.native.parent.mkdir(parents=True)
        self.native.write_bytes(pe(0xAA64))
        self.source = self.root / 'files/lib/wine/dxvk/x86_64-windows'
        self.source.mkdir(parents=True)
        for name in MODULE['ROCKET_DXVK_FILES']:
            (self.source / name).write_bytes(pe(0x8664))
        self.command = [str(self.proton), 'waitforexitandrun', str(self.game / 'RocketLeague.exe')]
        self.env = {'STEAM_COMPAT_DATA_PATH': '/steam/compatdata/252950'}

    def apply(self):
        return MODULE['rocket_league_dxvk'](self.command, self.env)

    def test_installs_bundled_x64_pair_and_updates_after_component_change(self):
        self.assertIn('x64 DXVK', self.apply())
        target = self.game / 'dxgi.dll'
        self.assertEqual(pe(0x8664), target.read_bytes())
        stamp = target.stat().st_mtime_ns
        self.apply()
        self.assertEqual(stamp, target.stat().st_mtime_ns)
        (self.source / 'dxgi.dll').write_bytes(pe(0x8664, b'updated'))
        self.apply()
        self.assertEqual(pe(0x8664, b'updated'), target.read_bytes())

    def test_preserves_unowned_or_modified_dlls(self):
        target = self.game / 'dxgi.dll'
        target.write_bytes(b'user mod')
        self.assertIn('preserving', self.apply())
        self.assertEqual(b'user mod', target.read_bytes())
        self.assertFalse((self.game / 'd3d11.dll').exists())
        target.unlink()
        self.apply()
        target.write_bytes(b'new mod')
        self.assertIn('preserving', self.apply())
        self.assertEqual(b'new mod', target.read_bytes())

    def test_opt_out_removes_only_unchanged_managed_files(self):
        self.apply()
        self.env['DROIDDECK_ROCKET_LEAGUE_X64_DXVK'] = '0'
        self.apply()
        self.assertEqual([], list(self.game.iterdir()))

    def test_does_not_touch_other_games_or_maintenance_commands(self):
        self.env['STEAM_COMPAT_DATA_PATH'] = '/steam/compatdata/123'
        self.assertIsNone(self.apply())
        self.env['STEAM_COMPAT_DATA_PATH'] = '/steam/compatdata/252950'
        self.command[1] = 'run'
        self.assertIsNone(self.apply())
        self.assertEqual([], list(self.game.iterdir()))

    def test_rejects_wrong_source_architecture_before_copying(self):
        (self.source / 'd3d11.dll').write_bytes(pe(0xAA64))
        self.assertIn('unavailable', self.apply())
        self.assertEqual([], list(self.game.iterdir()))

    def test_respects_alternative_graphics_runtimes(self):
        for name, value in [('PROTON_USE_ARM64', '0'), ('PROTON_USE_WINED3D', '1'),
                            ('WINEDLLOVERRIDES', 'dxgi=b'),
                            ('DROIDDECK_POWERVR_RUNTIME', '/runtime')]:
            self.env[name] = value
            self.assertIsNone(self.apply())
            self.env.pop(name)
        self.assertEqual([], list(self.game.iterdir()))
