import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from tools.tests.test_usr2_preload import runner

HOOK_BUILD = Path(__file__).resolve().parents[1] / "nativeguest/build.sh"

OWNERS = r"""
#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <string.h>
#include <sys/stat.h>

static void owner(const char *path, unsigned *uid, unsigned *gid) {
  if (!path) return;
  if (strstr(path, "shared")) { *uid = 4242; *gid = 1023; }
  else if (strstr(path, "other")) { *uid = 4242; *gid = 4242; }
}

int stat(const char *path, struct stat *st) {
  int (*next)(const char *, struct stat *) = dlsym(RTLD_NEXT, "stat");
  int ret = next(path, st);
  if (ret == 0) owner(path, &st->st_uid, &st->st_gid);
  return ret;
}

int lstat(const char *path, struct stat *st) {
  int (*next)(const char *, struct stat *) = dlsym(RTLD_NEXT, "lstat");
  int ret = next(path, st);
  if (ret == 0) owner(path, &st->st_uid, &st->st_gid);
  return ret;
}

int fstatat(int dirfd, const char *path, struct stat *st, int flags) {
  int (*next)(int, const char *, struct stat *, int) = dlsym(RTLD_NEXT, "fstatat");
  int ret = next(dirfd, path, st, flags);
  if (ret == 0) owner(path, &st->st_uid, &st->st_gid);
  return ret;
}

int statx(int dirfd, const char *path, int flags, unsigned mask, struct statx *sx) {
  int (*next)(int, const char *, int, unsigned, struct statx *) = dlsym(RTLD_NEXT, "statx");
  int ret = next(dirfd, path, flags, mask, sx);
  if (ret == 0) owner(path, &sx->stx_uid, &sx->stx_gid);
  return ret;
}
"""

PROBE = r"""
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdio.h>
#include <sys/stat.h>

int main(int argc, char **argv) {
  struct stat st;
  struct statx sx;
  for (int i = 1; i < argc; i++) {
    if (stat(argv[i], &st) != 0) return 2;
    printf("%s\tstat\t%u\t%u\n", argv[i], st.st_uid, st.st_gid);
    if (lstat(argv[i], &st) != 0) return 3;
    printf("%s\tlstat\t%u\t%u\n", argv[i], st.st_uid, st.st_gid);
    if (fstatat(AT_FDCWD, argv[i], &st, 0) != 0) return 4;
    printf("%s\tfstatat\t%u\t%u\n", argv[i], st.st_uid, st.st_gid);
    if (statx(AT_FDCWD, argv[i], 0, STATX_BASIC_STATS, &sx) != 0) return 5;
    printf("%s\tstatx\t%u\t%u\n", argv[i], sx.stx_uid, sx.stx_gid);
  }
  return 0;
}
"""


class NativeHookStorageOwnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cc, cls.prefix = runner()
        if cls.cc is None:
            raise unittest.SkipTest("no aarch64 compiler and runner")
        cls.tmp = tempfile.TemporaryDirectory(dir="/tmp")
        tmp = Path(cls.tmp.name)
        cls.hook = tmp / "hook.so"
        subprocess.run(["bash", str(HOOK_BUILD), str(cls.hook), cls.cc], check=True, capture_output=True)
        (tmp / "owners.c").write_text(OWNERS)
        cls.owners = tmp / "owners.so"
        subprocess.run([cls.cc, "-shared", "-fPIC", "-O1", "-o", str(cls.owners), str(tmp / "owners.c"), "-ldl"], check=True)
        (tmp / "probe.c").write_text(PROBE)
        cls.probe = tmp / "probe"
        subprocess.run([cls.cc, "-O1", "-o", str(cls.probe), str(tmp / "probe.c")], check=True)
        cls.root = tmp / "root"
        cls.root.mkdir()
        for name in ("shared", "other", "own"):
            (cls.root / name).write_text("")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def owners_of(self, *paths):
        hook_env = {"DDNATV_ROOT": str(self.root), "LD_PRELOAD": f"{self.hook} {self.owners}"}
        env = {k: v for k, v in os.environ.items() if k not in ("LD_PRELOAD", "DDNATV_ROOT")}
        if self.prefix:
            cmd = list(self.prefix) + [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
        else:
            cmd = []
            env.update(hook_env)
        out = subprocess.run(cmd + [str(self.probe), *paths], env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(0, out.returncode, out.stdout + out.stderr)
        result = {}
        for line in out.stdout.splitlines():
            path, call, uid, gid = line.split("\t")
            result.setdefault(path, {})[call] = (int(uid), int(gid))
        return result

    def test_shared_storage_files_are_owned_by_root_and_keep_their_group(self):
        for call, ids in self.owners_of("/shared")["/shared"].items():
            self.assertEqual((0, 1023), ids, call)

    def test_other_owners_and_the_apps_own_files_are_unchanged(self):
        result = self.owners_of("/other", "/own")
        for call, ids in result["/other"].items():
            self.assertEqual((4242, 4242), ids, call)
        for call, ids in result["/own"].items():
            self.assertEqual((0, 0), ids, call)


if __name__ == "__main__":
    unittest.main()
