import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-steam-x64'


class SteamX64CpuTest(unittest.TestCase):
    def selection(self, features, *, forced=False, same_build=True, unreadable=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            arm = root / 'arm'
            x64 = root / 'x64'
            for base in (arm, x64):
                (base / 'package').mkdir(parents=True)
            (arm / 'steamrtarm64').mkdir()
            (arm / 'steamrtarm64/steamwebhelper').touch(mode=0o700)
            shim = root / 'arm-shim'
            shim.touch(mode=0o700)
            (x64 / 'package/beta').write_text('publicbeta\n')
            for base, arch, version in ((x64, 'steamrt64', '123'), (arm, 'linuxarm64', '123' if same_build else '122')):
                (base / f'package/steam_client_publicbeta_{arch}.manifest').write_text(f'"version" "{version}"\n')
            cpuinfo = root / 'cpuinfo'
            if not unreadable:
                cpuinfo.write_text(features)
            text = SCRIPT.read_text()
            start = text.find('x64_arm_webhelper_cpu_supported() {')
            helper = text[start:text.index('x64_exec() {')] if start >= 0 else ''
            selection = text.split('x64_exec() {', 1)[1].split('  mkdir -p "$STEAM_X64/logs"', 1)[0]
            shell = helper + '\nx64_env() { :; }\nx64_webhelper_gpu() { return 1; }\nselect_helper() {' + selection + '\nprintf "%s|%s\\n" "${DD_X64_ARM_WEBHELPER:-x86}" "${DD_WEBHELPER_ARGS:-}";\n}\nselect_helper\n'
            shell = shell.replace('/proc/cpuinfo', str(cpuinfo)).replace('/usr/local/bin/droiddeck-x64-arm-webhelper', str(shim))
            env = dict(os.environ, STEAM_X64=str(x64), ARM_STEAM=str(arm), X64_CHANNEL='publicbeta', DD_X64_X86_WEBHELPER='1' if forced else '0')
            env.pop('DD_X64_ARM_WEBHELPER', None)
            env.pop('DD_WEBHELPER_ARGS', None)
            result = subprocess.run(['bash', '-eu', '-c', shell], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            return result.stdout.strip(), str(shim)

    def test_missing_atomics_keeps_x86_helper(self):
        result, _ = self.selection('Features : fp asimd aes crc32\n')
        self.assertEqual(result, 'x86|stock')

    def test_atomics_keeps_same_build_arm_helper(self):
        result, arm = self.selection('Features : fp asimd atomics aes\n')
        self.assertEqual(result, arm + '|')

    def test_exact_token_required(self):
        result, _ = self.selection('Features : fp noatomics atomics_extra\n')
        self.assertEqual(result, 'x86|stock')

    def test_any_explicit_core_without_atomics_keeps_x86_helper(self):
        result, _ = self.selection('processor : 0\nFeatures : fp atomics\nprocessor : 1\nFeatures : fp aes\n')
        self.assertEqual(result, 'x86|stock')

    def test_empty_features_is_explicitly_unsupported(self):
        result, _ = self.selection('Features : \n')
        self.assertEqual(result, 'x86|stock')

    def test_absent_features_preserves_existing_behavior(self):
        result, arm = self.selection('processor : 0\n')
        self.assertEqual(result, arm + '|')

    def test_unreadable_cpuinfo_preserves_existing_behavior(self):
        result, arm = self.selection('', unreadable=True)
        self.assertEqual(result, arm + '|')

    def test_forced_x86_still_wins(self):
        result, _ = self.selection('Features : atomics\n', forced=True)
        self.assertEqual(result, 'x86|stock')

    def test_supported_cpu_still_requires_same_build(self):
        result, _ = self.selection('Features : atomics\n', same_build=False)
        self.assertEqual(result, 'x86|stock')



class SteamX64KernelTest(unittest.TestCase):
    def prepare(self, features):
        with tempfile.TemporaryDirectory() as tmp:
            cpuinfo = Path(tmp) / 'cpuinfo'
            cpuinfo.write_text(features)
            text = SCRIPT.read_text()
            helper = text[text.index('x64_cpu_lacks() {'):text.index('x64_exec() {')]
            start = text.index('x64_prepare() {')
            prepare = text[start:text.index('fex_ready || {', start)] + 'fex_ready\n}\n'
            shell = ('step() { echo "STEP $*"; }\nfex_ready() { echo FEX_READY; exit 0; }\n'
                     + helper.replace('/proc/cpuinfo', str(cpuinfo)) + prepare + 'x64_prepare\n')
            result = subprocess.run(['bash', '-eu', '-c', shell], capture_output=True, text=True)
            return result.returncode, result.stdout

    def test_a_kernel_without_cpuid_stops_before_fex_is_downloaded(self):
        rc, out = self.prepare('processor : 0\nFeatures : fp asimd evtstrm aes pmull sha1 sha2 crc32\n')
        self.assertEqual(rc, 1)
        self.assertIn("STEP FEX can't run here", out)
        self.assertNotIn('FEX_READY', out)

    def test_an_armv80_cpu_with_cpuid_still_gets_fex(self):
        rc, out = self.prepare('Features : fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid\n')
        self.assertEqual(rc, 0)
        self.assertEqual(out.strip(), 'FEX_READY')

    def test_absent_features_preserves_existing_behavior(self):
        rc, out = self.prepare('processor : 0\n')
        self.assertEqual(rc, 0)
        self.assertEqual(out.strip(), 'FEX_READY')


if __name__ == '__main__':
    unittest.main()
