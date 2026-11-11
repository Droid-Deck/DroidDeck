// Run inside a session: the order Firefox's content sandbox uses, while other threads keep
// starting threads. Each child process sets a SIGSYS handler, waits as the policy compile does,
// checks the handler is still set (Firefox crashes there if it is not), installs a TSYNC filter
// that traps openat, and has every worker make trapped opens. A thread that starts with SIGSYS
// blocked and calls set_robust_list, which Android's policy traps, resets the handler. On a host
// the policy stand-in has to sit outside proot's guard, or the guard hides the race.
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
#include <sys/wait.h>
#include <ucontext.h>
#include <unistd.h>

#define ANSWER 4242
#define CHILDREN 20
#define WORKERS 8
#define OPENS 30
static volatile int installed;

static void on_sigsys(int sig, siginfo_t *info, void *context) {
  (void)sig;
  (void)info;
  ucontext_t *uc = context;
#if defined(__x86_64__)
  uc->uc_mcontext.gregs[REG_RAX] = ANSWER;
#else
  uc->uc_mcontext.regs[0] = ANSWER;
#endif
}

static void *idle(void *arg) { return arg; }

static void *worker(void *arg) {
  (void)arg;
  int opens = 0;
  while (opens < OPENS) {
    pthread_t thread;
    if (pthread_create(&thread, NULL, idle, NULL) || pthread_join(thread, NULL))
      return (void *)1;
    if (__atomic_load_n(&installed, __ATOMIC_ACQUIRE)) {
      if (open("/etc/hostname", O_RDONLY) != ANSWER)
        return (void *)1;
      opens++;
    }
  }
  return NULL;
}

static int child(void) {
  struct sock_filter code[] = {
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat, 0, 1),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP | 7),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
  };
  struct sock_fprog filter = {.len = sizeof code / sizeof *code, .filter = code};
  prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
  pthread_t w[WORKERS];
  for (int i = 0; i < WORKERS; i++)
    if (pthread_create(&w[i], NULL, worker, NULL))
      return 2;
  usleep(20000);
  struct sigaction sa, old;
  memset(&sa, 0, sizeof sa);
  sa.sa_sigaction = on_sigsys;
  sa.sa_flags = SA_SIGINFO | SA_NODEFER;
  sigaction(SIGSYS, &sa, NULL);
  usleep(3000);
  sigaction(SIGSYS, NULL, &old);
  if (!(old.sa_flags & SA_SIGINFO) || old.sa_sigaction != on_sigsys)
    return 3;
  if (syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &filter))
    return 77;
  __atomic_store_n(&installed, 1, __ATOMIC_RELEASE);
  long bad = 0;
  for (int i = 0; i < WORKERS; i++) {
    void *r;
    if (pthread_join(w[i], &r))
      return 2;
    bad += (long)r;
  }
  return bad ? 1 : 0;
}

int main(void) {
  int ok = 0, reset_early = 0, killed = 0, other = 0;
  for (int i = 0; i < CHILDREN; i++) {
    pid_t pid = fork();
    if (pid < 0) {
      perror("fork");
      return 2;
    }
    if (pid == 0)
      _exit(child());
    int status;
    if (waitpid(pid, &status, 0) < 0) {
      perror("waitpid");
      return 2;
    }
    if (WIFEXITED(status) && WEXITSTATUS(status) == 0)
      ok++;
    else if (WIFEXITED(status) && WEXITSTATUS(status) == 3)
      reset_early++;
    else if (WIFSIGNALED(status) && WTERMSIG(status) == SIGSYS)
      killed++;
    else
      other++;
  }
  printf("ok=%d/%d handler_reset_before_filter=%d killed_by_sigsys=%d other=%d\n", ok, CHILDREN,
         reset_early, killed, other);
  return ok == CHILDREN ? 0 : 1;
}
