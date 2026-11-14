// Run inside a session, with and without the fast path: a seccomp filter the guest installs
// itself must reach its own SIGSYS handler, which answers the trapped openat, before and after
// the process starts a thread. The filter mirrors Firefox's content sandbox: it allows
// rt_sigprocmask and set_robust_list and traps seccomp, so the guard's trace on rt_sigprocmask
// must still fire after the install.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <pthread.h>
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

static void answer(void *context) {
  ucontext_t *uc = context;
#if defined(__x86_64__)
  uc->uc_mcontext.gregs[REG_RAX] = ANSWER;
#else
  uc->uc_mcontext.regs[0] = ANSWER;
#endif
}

static void on_sigsys(int sig, siginfo_t *info, void *context) {
  (void)sig;
  traps++;
  data = info->si_errno;
  answer(context);
}

static void *idle(void *arg) { return arg; }

int main(void) {
  struct sigaction sa;
  memset(&sa, 0, sizeof sa);
  sa.sa_sigaction = on_sigsys;
  sa.sa_flags = SA_SIGINFO;
  sigaction(SIGSYS, &sa, NULL);
  struct sock_filter code[] = {
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_rt_sigprocmask, 3, 0),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_set_robust_list, 2, 0),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat, 2, 0),
#ifdef __NR_seccomp
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_seccomp, 1, 0),
#else
    BPF_JUMP(BPF_JMP | BPF_JA | BPF_K, 0, 0, 0),
#endif
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP | 7),
  };
  struct sock_fprog filter = {.len = sizeof code / sizeof *code, .filter = code};
  if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) || prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &filter, 0, 0)) {
    perror("seccomp");
    return 77;
  }
  int fd = open("/etc/hostname", O_RDONLY);
  int err = errno;
  printf("open=%d errno=%d traps=%d data=%d\n", fd, fd < 0 ? err : 0, traps, data);
  // glibc starts the thread with every signal blocked and calls set_robust_list, which
  // Android's policy traps; the kernel resets a blocked SIGSYS's handler when it traps.
  pthread_t thread;
  if (pthread_create(&thread, NULL, idle, NULL) || pthread_join(thread, NULL)) {
    perror("pthread_create");
    return 77;
  }
  int after = open("/etc/hostname", O_RDONLY);
  printf("after a thread: open=%d traps=%d\n", after, traps);
  return fd == ANSWER && after == ANSWER && traps == 2 && data == 7 ? 0 : 1;
}
