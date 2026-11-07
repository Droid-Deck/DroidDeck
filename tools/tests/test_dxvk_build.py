"""Exercise output isolation with a synthetic toolchain; no network/DLL build."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

# Substitute only external source/compiler/archive commands. The real build.sh
# owns output checks, architecture iteration and manifest inputs.
FAKE_TOOL = r'''
import hashlib, json, os
from pathlib import Path
import sys
name = Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ['TOOL_CALLS'], 'a') as stream:
    stream.write(name + '\n')
if name == 'git':
    if args[0] == 'init':
        Path(args[-1]).mkdir()
        (Path(args[-1]) / 'LICENSE').write_text('synthetic public license')
    elif 'rev-parse' in args:
        print(os.environ['TEST_REVISION'])
elif name == 'meson':
    if args[0] == 'setup':
        p = Path(args[1]); p.mkdir()
        p.joinpath('config.json').write_text(json.dumps({
            'out': args[args.index('--prefix') + 1],
            'arch': args[args.index('--bindir') + 1]}))
    elif args[0] == 'install':
        config = json.loads(Path(args[2], 'config.json').read_text())
        out = Path(config['out'], config['arch']); out.mkdir(exist_ok=True)
        out.joinpath('current.dll').write_bytes(b'synthetic current pinned DLL')
        if config['arch'] == os.environ.get('FAIL_INSTALL_ARCH'):
            sys.exit(5)
elif name == 'tar':
    Path(args[args.index('-cf') + 1]).write_bytes(b'synthetic source archive')
elif name == 'sha256sum':
    for arg in args:
        print(hashlib.sha256(Path(arg).read_bytes()).hexdigest() + '  ' + arg)
elif name != 'python3':
    raise AssertionError(name)
'''


class DxvkBuildTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.bin = self.base / 'bin'; self.bin.mkdir()
        executable = self.bin / 'fake-tool'
        executable.write_text('#!' + sys.executable + '\n' + FAKE_TOOL)
        executable.chmod(0o755)
        for name in ('git', 'meson', 'tar', 'sha256sum', 'python3'):
            (self.bin / name).symlink_to(executable)
        self.calls = self.base / 'calls'
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        TOOL_CALLS=str(self.calls))
        source_env = ROOT / 'tools/dxvk/source.env'
        self.env['TEST_REVISION'] = next(line.split('=', 1)[1].strip('"\'')
                                         for line in source_env.read_text().splitlines()
                                         if line.startswith('DXVK_REVISION='))
        self.output = self.base / 'component'

    def build(self):
        return subprocess.run(['bash', str(ROOT / 'tools/dxvk/build.sh'), str(self.output)],
                              env=self.env, capture_output=True, text=True, timeout=10)

    def test_stale_dll_is_rejected_untouched_before_fetch(self):
        stale = self.output / 'x32/stale.dll'; stale.parent.mkdir(parents=True)
        stale.write_bytes(b'prior pinned build')
        result = self.build()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('output directory must be empty', result.stderr)
        self.assertEqual(stale.read_bytes(), b'prior pinned build')
        self.assertFalse(self.calls.exists())
        self.assertFalse((self.output / 'SHA256SUMS').exists())

    def test_hidden_contents_are_also_rejected(self):
        self.output.mkdir(); (self.output / '.previous').write_text('retained')
        self.assertNotEqual(self.build().returncode, 0)
        self.assertFalse(self.calls.exists())
        self.assertEqual((self.output / '.previous').read_text(), 'retained')

    def test_empty_or_new_output_contains_only_current_dlls(self):
        for existing in (False, True):
            with self.subTest(existing=existing):
                self.output = self.base / ('existing' if existing else 'new')
                if existing: self.output.mkdir()
                result = self.build()
                self.assertEqual(result.returncode, 0, result.stderr)
                rows = (self.output / 'SHA256SUMS').read_text().splitlines()
                self.assertEqual([row.split('  ')[1] for row in rows],
                                 ['x32/current.dll', 'x64/current.dll'])
                for row in rows:
                    digest, path = row.split('  ')
                    self.assertEqual(digest, hashlib.sha256((self.output / path).read_bytes()).hexdigest())

    def test_partial_build_is_not_reused_or_deleted(self):
        self.env['FAIL_INSTALL_ARCH'] = 'x64'
        result = self.build()
        self.assertEqual(result.returncode, 5, result.stderr)
        before = {str(p.relative_to(self.output)): p.read_bytes()
                  for p in self.output.rglob('*') if p.is_file()}
        self.calls.unlink(); del self.env['FAIL_INSTALL_ARCH']
        result = self.build()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.calls.exists())
        after = {str(p.relative_to(self.output)): p.read_bytes()
                 for p in self.output.rglob('*') if p.is_file()}
        self.assertEqual(after, before)


if __name__ == '__main__':
    unittest.main()
