#include "../fastpath/fastpath.c"
#include "checks.h"
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>

static void expect_dir(const char *path) {
  struct statx stx;
  memset(&stx, 0, sizeof stx);
  long rs = fp_statx(AT_FDCWD, path, 0, STATX_BASIC_STATS, &stx);
  if (rs != 0) fprintf(stderr, "statx %s: %s\n", path, strerror((int)-rs));
  assert(rs == 0 && S_ISDIR(stx.stx_mode));
}

int main(int argc, char **argv) {
  assert(argc == 2);
  if (!fp_ready()) return 77;
  struct sock_filter code[] = {
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_statx, 0, 1),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
  };
  struct sock_fprog filter = {.len = sizeof code / sizeof *code, .filter = code};
  if (sc(SYS_prctl, PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) ||
      sc(SYS_prctl, PR_SET_SECCOMP, SECCOMP_MODE_FILTER, (long)&filter, 0, 0)) return 77;
  expect_dir("/");
  expect_dir(argv[1]);
  expect_dir(argv[1]);
  puts("PASS: statx refused by the host policy still answers an existing guest directory");
  return 0;
}
