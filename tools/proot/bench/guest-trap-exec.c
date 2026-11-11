// Run inside a session: the guard and the guest's filter survive execve (the kernel keeps
// seccomp filters; the child reinstalls its handler, as sandboxed processes do). Only
// O_CLOEXEC-less openat is trapped, so the loader's own opens stay allowed.
#define _GNU_SOURCE
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
static volatile int traps;

static void on_sigsys(int sig, siginfo_t *info, void *context) {
  (void)sig;
  (void)info;
  ucontext_t *uc = context;
  __atomic_add_fetch(&traps, 1, __ATOMIC_RELAXED);
#if defined(__x86_64__)
  uc->uc_mcontext.gregs[REG_RAX] = ANSWER;
#else
  uc->uc_mcontext.regs[0] = ANSWER;
#endif
}

static void install_handler(void) {
  struct sigaction sa;
  memset(&sa, 0, sizeof sa);
  sa.sa_sigaction = on_sigsys;
  sa.sa_flags = SA_SIGINFO | SA_NODEFER;
  sigaction(SIGSYS, &sa, NULL);
}

static void *idle(void *arg) { return arg; }

static void install_filter(void) {
  struct sock_filter code[] = {
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat, 0, 2),
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[2])),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 0, 1, 0),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP | 7),
  };
  struct sock_fprog filter = {.len = sizeof code / sizeof *code, .filter = code};
  prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
  if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &filter)) {
    perror("seccomp");
    _exit(77);
  }
}

int main(int argc, char **argv) {
  install_handler();
  if (argc > 1) {
    pthread_t thread;
    pthread_create(&thread, NULL, idle, NULL);
    pthread_join(thread, NULL);
    int fd = open("/etc/hostname", O_RDONLY);
    printf("child: open=%d traps=%d\n", fd, traps);
    return fd == ANSWER && traps == 1 ? 0 : 1;
  }
  install_filter();
  char *args[] = {argv[0], "child", NULL};
  execv(argv[0], args);
  perror("execv");
  return 77;
}
