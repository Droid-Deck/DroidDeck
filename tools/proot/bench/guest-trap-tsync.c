// Run inside a session: a TSYNC install while other threads already exist (Firefox installs
// its sandbox late, into a multithreaded process) must guard every thread. Each worker keeps
// starting threads and making trapped opens; every open must be answered by the handler.
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
#define WORKERS 4
#define ROUNDS 50
static volatile int traps;
static volatile long bad;
static pthread_barrier_t barrier;

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

static void *idle(void *arg) { return arg; }

static void *worker(void *arg) {
  (void)arg;
  pthread_barrier_wait(&barrier);
  for (int i = 0; i < ROUNDS; i++) {
    pthread_t thread;
    if (pthread_create(&thread, NULL, idle, NULL)) {
      __atomic_add_fetch(&bad, 1, __ATOMIC_RELAXED);
      continue;
    }
    if (open("/etc/hostname", O_RDONLY) != ANSWER)
      __atomic_add_fetch(&bad, 1, __ATOMIC_RELAXED);
    if (pthread_join(thread, NULL))
      __atomic_add_fetch(&bad, 1, __ATOMIC_RELAXED);
  }
  return NULL;
}

int main(void) {
  struct sigaction sa;
  memset(&sa, 0, sizeof sa);
  sa.sa_sigaction = on_sigsys;
  sa.sa_flags = SA_SIGINFO | SA_NODEFER;
  sigaction(SIGSYS, &sa, NULL);
  struct sock_filter code[] = {
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat, 0, 1),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP | 7),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
  };
  struct sock_fprog filter = {.len = sizeof code / sizeof *code, .filter = code};
  prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
  pthread_barrier_init(&barrier, NULL, WORKERS + 1);
  pthread_t w[WORKERS];
  for (int i = 0; i < WORKERS; i++)
    if (pthread_create(&w[i], NULL, worker, NULL)) {
      printf("worker %d did not start\n", i);
      return 2;
    }
  // Workers already exist, blocked above, so TSYNC covers them; they only leave
  // the barrier once the filter is in.
  if (syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &filter)) {
    perror("seccomp");
    return 77;
  }
  pthread_barrier_wait(&barrier);
  for (int i = 0; i < WORKERS; i++)
    if (pthread_join(w[i], NULL)) {
      printf("worker %d could not be joined\n", i);
      return 2;
    }
  printf("bad=%ld traps=%d\n", bad, traps);
  return bad == 0 && traps >= WORKERS * ROUNDS ? 0 : 1;
}
