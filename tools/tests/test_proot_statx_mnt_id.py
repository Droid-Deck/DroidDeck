import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
import unittest

PROOT = Path(__file__).resolve().parents[1] / "proot"

PROGRAM = r"""
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "statx_mnt_id.h"

static int fdinfo_mnt_id(int fd, uint64_t *out) {
  char path[64], line[256];
  snprintf(path, sizeof path, "/proc/self/fdinfo/%d", fd);
  FILE *f = fopen(path, "r");
  if (!f) return 0;
  int found = 0;
  while (fgets(line, sizeof line, f)) {
    unsigned long long id;
    if (sscanf(line, "mnt_id:%llu", &id) == 1) {
      *out = (uint64_t)id;
      found = 1;
      break;
    }
  }
  fclose(f);
  return found;
}

int main(void) {
  char name[] = "/tmp/droiddeck-statx-mnt-id-XXXXXX";
  int fd = mkstemp(name);
  if (fd < 0) return 1;
  unlink(name);

  struct stat st;
  if (fstat(fd, &st) < 0) return 2;
  uint64_t expected;
  if (!fdinfo_mnt_id(fd, &expected) || expected == 0) return 3;

  /* Model a successful pre-5.8 statx: inode fields are present, MNT_ID is absent. */
  struct statx result = {0};
  result.stx_mask = STATX_TYPE | STATX_INO;
  result.stx_mode = st.st_mode;
  result.stx_ino = st.st_ino;
  unsigned flags = AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW;
  unsigned mask = STATX_TYPE | STATX_INO | STATX_MNT_ID;
  if (!droiddeck_proot_statx_fill_mnt_id(getpid(), fd, flags, "", mask, &result)) return 4;
  if (!(result.stx_mask & STATX_MNT_ID) || result.stx_mnt_id != expected) return 5;

  /* Do not turn an inode-mismatched, stale descriptor into a plausible result. */
  result.stx_mask = STATX_TYPE | STATX_INO;
  result.stx_ino = st.st_ino + 1;
  if (droiddeck_proot_statx_fill_mnt_id(getpid(), fd, flags, "", mask, &result)) return 6;
  if (result.stx_mask & STATX_MNT_ID) return 7;

  /* Only ordinary MNT_ID is reconstructed; never claim the newer UNIQUE bit. */
  result.stx_ino = st.st_ino;
  if (droiddeck_proot_statx_fill_mnt_id(getpid(), fd, flags, "", 0x4000U, &result)) return 8;
  if (result.stx_mask & STATX_MNT_ID) return 9;

  if (droiddeck_proot_statx_fill_mnt_id(getpid(), AT_FDCWD, flags, "", mask, &result)) return 10;
  if (droiddeck_proot_statx_fill_mnt_id(getpid(), -1, flags, "", mask, &result)) return 11;
  if (droiddeck_proot_statx_fill_mnt_id(getpid(), fd, AT_SYMLINK_NOFOLLOW, "", mask, &result)) return 12;
  if (droiddeck_proot_statx_fill_mnt_id(getpid(), fd, flags, "file", mask, &result)) return 13;
  int stale = dup(fd);
  if (stale < 0 || close(stale) < 0) return 14;
  if (droiddeck_proot_statx_fill_mnt_id(getpid(), stale, flags, "", mask, &result)) return 15;

  close(fd);
  return 0;
}
"""


@unittest.skipUnless(shutil.which("cc") and sys.platform.startswith("linux"), "requires Linux and a C compiler")
class ProotStatxMountIdTest(unittest.TestCase):
    def test_missing_mount_id_is_recovered_only_for_the_same_open_inode(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "test.c"
            program = Path(tmp) / "test"
            source.write_text(PROGRAM)
            subprocess.run(["cc", "-O2", "-Wall", "-Werror", "-I", str(PROOT), "-o", str(program), str(source)], check=True)
            subprocess.run([str(program)], check=True)


if __name__ == "__main__":
    unittest.main()
