// Session-only (needs proot: natively no guard is installed, so the count check fails):
// feature probes with no filter must add nothing, while a real install
// adds the guard and the guest's filter and keeps the parent-death signal. A child forked
// afterwards must survive a thread start.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <ucontext.h>
#include <unistd.h>

#define ANSWER 4242
static volatile int traps;

static void on_sigsys(int sig, siginfo_t *info, void *context) {
  (void)sig;
  (void)info;
  ucontext_t *uc = context;
  traps++;
#if defined(__x86_64__)
  uc->uc_mcontext.gregs[REG_RAX] = ANSWER;
#else
  uc->uc_mcontext.regs[0] = ANSWER;
#endif
}

static void *idle(void *arg) { return arg; }

static int status_fd;

static int filters(void) {
  char buf[4096];
  int n = -1;
  ssize_t len = pread(status_fd, buf, sizeof buf - 1, 0);
  if (len <= 0)
    return -1;
  buf[len] = 0;
  char *line = strstr(buf, "Seccomp_filters:");
  if (line)
    sscanf(line, "Seccomp_filters: %d", &n);
  return n;
}

int main(int argc, char **argv) {
  int use_seccomp = argc > 1 && !strcmp(argv[1], "seccomp");
  struct sigaction sa;
  memset(&sa, 0, sizeof sa);
  sa.sa_sigaction = on_sigsys;
  sa.sa_flags = SA_SIGINFO | SA_NODEFER;
  sigaction(SIGSYS, &sa, NULL);
  status_fd = open("/proc/self/status", O_RDONLY);
  prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
  prctl(PR_SET_PDEATHSIG, SIGUSR1);
  sigset_t all, oldmask;
  // Baseline: an outer wrapper (or nothing) may already hold the flag.
  // A failed install must leave whichever state it found.
  sigfillset(&all);
  pthread_sigmask(SIG_SETMASK, &all, NULL);
  pthread_sigmask(SIG_SETMASK, NULL, &oldmask);
  int blocked_base = sigismember(&oldmask, SIGSYS);
  sigemptyset(&all);
  pthread_sigmask(SIG_SETMASK, &all, NULL);
  int before = filters();
  fprintf(stderr, "PHASE probes\n");
  int p1 = prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, NULL);
  int e1 = errno;
  int p2 = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, NULL);
  int e2 = errno;
  int after_probes = filters();
  struct sock_filter code[] = {
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat, 0, 1),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP | 7),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
  };
  struct sock_fprog prog = {.len = sizeof code / sizeof *code, .filter = code};
  // An invalid nonempty filter must fail and leave no flag: only the guard
  // itself is added. Its jump target lies past the end, so the kernel
  // rejects it without installing anything.
  struct sock_filter bad[] = {
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat, 5, 0),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP | 7),
  };
  struct sock_fprog badprog = {.len = sizeof bad / sizeof *bad, .filter = bad};
  fprintf(stderr, "PHASE invalid\n");
  int brv = use_seccomp ? syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &badprog)
                         : prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &badprog);
  int be = errno;
  int after_invalid = filters();
  // A rejected filter must not change the flag: blocking everything
  // shows the same SIGSYS state as before the attempt.
  sigfillset(&all);
  pthread_sigmask(SIG_SETMASK, &all, NULL);
  pthread_sigmask(SIG_SETMASK, NULL, &oldmask);
  int blocked_before = sigismember(&oldmask, SIGSYS);
  sigemptyset(&all);
  pthread_sigmask(SIG_SETMASK, &all, NULL);
  fprintf(stderr, "PHASE valid\n");
  int rv = use_seccomp ? syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &prog)
                       : prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog);
  int after_install = filters();
  // With the guest's filter in, the guard must keep SIGSYS out of every mask.
  sigfillset(&all);
  pthread_sigmask(SIG_SETMASK, &all, NULL);
  pthread_sigmask(SIG_SETMASK, NULL, &oldmask);
  int blocked_after = sigismember(&oldmask, SIGSYS);
  sigemptyset(&all);
  pthread_sigmask(SIG_SETMASK, &all, NULL);
  int pdeath = -1;
  prctl(PR_GET_PDEATHSIG, &pdeath);
  pid_t child = fork();
  if (child == 0) {
    pthread_t thread;
    if (pthread_create(&thread, NULL, idle, NULL))
      _exit(2);
    pthread_join(thread, NULL);
    _exit(open("/etc/hostname", O_RDONLY) == ANSWER ? 0 : 1);
  }
  int status;
  waitpid(child, &status, 0);
  // Without proot's own filter there is no BPF guard to install; the flag alone
  // still filters masks at the stops proot already takes, so the child survives.
  int noseccomp = getenv("PROOT_NO_SECCOMP") != NULL;
  int want_invalid = noseccomp ? before : before + 1;
  int want = noseccomp ? before + 1 : before + 3;
  printf("%s: probes=%d/%d errno=%d/%d filters %d->%d->%d->%d invalid=%d/%d sigsys_blocked=%d/%d/%d install=%d pdeathsig=%d child=%s\n",
         use_seccomp ? "seccomp" : "prctl", p1, p2, e1, e2, before, after_probes,
         after_invalid, after_install, brv, be, blocked_base, blocked_before, blocked_after, rv, pdeath,
         WIFEXITED(status) ? (WEXITSTATUS(status) ? "wrong answer" : "ok") : "killed");
  return p1 == -1 && e1 == EFAULT && p2 == -1 && e2 == EFAULT && after_probes == before &&
         brv == -1 && be == EINVAL && after_invalid == want_invalid &&
         blocked_before == blocked_base &&
         after_install == want && rv == 0 && blocked_after == 0 && pdeath == SIGUSR1 &&
         WIFEXITED(status) && !WEXITSTATUS(status) ? 0 : 1;
}
