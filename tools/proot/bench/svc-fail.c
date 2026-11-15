#include "../fastpath/fastpath.c"
#include "checks.h"
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>

int main(int argc, char **argv) {
#if defined(__aarch64__)
  assert(argc == 2 && fp_ready() && !svc_sites && svc_single_thread());
  int nr = !strcmp(argv[1], "mem") ? SYS_openat : !strcmp(argv[1], "write") ? SYS_pwrite64 :
           !strcmp(argv[1], "rx") ? SYS_mprotect : SYS_mmap;
  unsigned offset = nr == SYS_mprotect || nr == SYS_openat ? offsetof(struct seccomp_data, args[2]) : offsetof(struct seccomp_data, args[3]);
  unsigned mask = nr == SYS_mprotect ? PROT_EXEC : nr == SYS_mmap ? MAP_FIXED_NOREPLACE : nr == SYS_openat ? O_RDWR : ~0U;
  struct sock_filter code[] = {
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, nr, 0, 3),
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offset),
    BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, mask, 0, 1),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
  };
  if (nr == SYS_pwrite64) code[2] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_IMM, 1);
  struct sock_fprog filter = {.len = sizeof code / sizeof *code, .filter = code};
  assert(setenv("PROOT_FP_PATCH_SVC", "1", 1) == 0);
  if (sc(SYS_prctl, PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) ||
      sc(SYS_prctl, PR_SET_SECCOMP, SECCOMP_MODE_FILTER, (long)&filter, 0, 0)) return 77;
  assert(svc_single_thread());
  svc_patch_init();
  assert(svc_sites == 0 && svc_pages == 0);
  struct stat st;
  long args[] = {AT_FDCWD, (long)"/etc/os-release", (long)&st, 0, 0, 0, 0, 0, SYS_newfstatat};
  assert(svc_original(args) == 0 && S_ISREG(st.st_mode));
  puts("PASS: denied patch installation leaves the original libc syscall usable");
  return 0;
#else
  (void)argc; (void)argv;
  return 77;
#endif
}
