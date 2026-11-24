import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from tools.tests.test_usr2_preload import runner

TOOLS = Path(__file__).resolve().parents[1]
STUB = TOOLS / 'linuxfs/overlay/usr/local/bin/droiddeck-diversion-stub'
PROBE = r'''
#include <errno.h>
#include <fcntl.h>
#include <sys/xattr.h>
#include <unistd.h>
int main(int argc, char **argv) {
  int fd = open(argv[1], O_RDWR);
  char value[] = "test", out[8];
  if (fd < 0) return 1;
  if (setxattr(argv[1], "security.capability", value, 4, 0) ||
      lsetxattr(argv[1], "security.capability", value, 4, 0) ||
      fsetxattr(fd, "security.capability", value, 4, 0)) return 2;
  if (getxattr(argv[1], "security.capability", out, 8) != -1 || errno != ENODATA ||
      lgetxattr(argv[1], "security.capability", out, 8) != -1 || errno != ENODATA ||
      fgetxattr(fd, "security.capability", out, 8) != -1 || errno != ENODATA) return 3;
  if (removexattr(argv[1], "security.capability") ||
      lremovexattr(argv[1], "security.capability") ||
      fremovexattr(fd, "security.capability")) return 4;
  if (fsetxattr(fd, "user.ddtest", value, 4, 0) ||
      fgetxattr(fd, "user.ddtest", out, 8) != 4 ||
      fremovexattr(fd, "user.ddtest")) return 5;
  close(fd);
  return 0;
}
'''

class DiversionCapabilitiesTest(unittest.TestCase):
    def test_stubs_and_migration(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'entry'
            target = Path(d) / 'target'
            target.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n')
            target.chmod(0o755)
            path.symlink_to(target.name)
            subprocess.run(['sh', str(STUB), str(path), str(target), 'migrate'], check=True)
            self.assertFalse(path.is_symlink())
            self.assertEqual(path.stat().st_mode & 0o777, 0o755)
            self.assertEqual(subprocess.check_output([str(path), 'one two'], text=True), 'one two\n')
            before = path.read_bytes()
            subprocess.run(['sh', str(STUB), str(path), str(target), 'migrate'], check=True)
            self.assertEqual(path.read_bytes(), before)
            path.unlink()
            path.symlink_to('other')
            subprocess.run(['sh', str(STUB), str(path), str(target), 'migrate'], check=True)
            self.assertEqual(os.readlink(path), 'other')
            subprocess.run(['sh', str(STUB), str(path), str(target), 'install'], check=True)
            self.assertFalse(path.is_symlink())
            self.assertEqual(path.read_bytes(), before)

    def test_capability_xattrs_and_other_attributes(self):
        cc, prefix = runner()
        if cc is None:
            self.skipTest('no aarch64 compiler and runner')
        if not shutil.which('aarch64-linux-gnu-gcc') and not (shutil.which('clang') and shutil.which('ld.lld')):
            self.skipTest('no aarch64 cross compiler for the hook build')
        with tempfile.TemporaryDirectory(dir='/tmp') as d:
            root = Path(d)
            hook, source, probe, target = (root / name for name in ('hook.so', 'probe.c', 'probe', 'file'))
            subprocess.run(['bash', str(TOOLS / 'nativeguest/build.sh'), str(hook), cc], check=True, capture_output=True)
            source.write_text(PROBE)
            subprocess.run([cc, '-o', str(probe), str(source)], check=True)
            target.touch()
            env = {k: v for k, v in os.environ.items() if k not in ('LD_PRELOAD', 'DDNATV_ROOT')}
            variables = {'LD_PRELOAD': str(hook), 'DDNATV_ROOT': str(root)}
            if prefix:
                command = list(prefix) + [arg for k, v in variables.items() for arg in ('-E', f'{k}={v}')]
            else:
                command = []
                env.update(variables)
            result = subprocess.run(command + [str(probe), str(target)], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertNotIn('security.capability', os.listxattr(target))
