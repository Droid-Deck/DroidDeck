// Run inside a session, with and without the fast path: a seccomp filter the guest installs
// itself must reach its own SIGSYS handler, which answers the trapped openat.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

#define ANSWER 4242
static volatile int traps, data;

static void on_sigsys(int sig, siginfo_t *info, void *context) {
  (void)sig;
  ucontext_t *uc = context;
  traps++;
  data = info->si_errno;
  uc->uc_mcontext.regs[0] = ANSWER;
}

int main(void) {
  struct sigaction sa;
  memset(&sa, 0, sizeof sa);
  sa.sa_sigaction = on_sigsys;
  sa.sa_flags = SA_SIGINFO;
  sigaction(SIGSYS, &sa, NULL);
  struct sock_filter code[] = {
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat, 0, 1),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP | 7),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
  };
  struct sock_fprog filter = {.len = sizeof code / sizeof *code, .filter = code};
  if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) || prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &filter, 0, 0)) {
    perror("seccomp");
    return 77;
  }
  int fd = open("/etc/hostname", O_RDONLY);
  int err = errno;
  printf("open=%d errno=%d traps=%d data=%d\n", fd, fd < 0 ? err : 0, traps, data);
  return fd == ANSWER && traps == 1 && data == 7 ? 0 : 1;
}
