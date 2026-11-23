#define _GNU_SOURCE
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

volatile long base;
static volatile long hits, matched, stale, stop;
static long want;
static pid_t target;
extern char probe_loop_start[], probe_loop_end[];

static void handler(int sig, siginfo_t *info, void *context) {
  greg_t *g = ((ucontext_t *)context)->uc_mcontext.gregs;
  (void)sig;
  (void)info;
  if (g[REG_RIP] >= (greg_t)probe_loop_start && g[REG_RIP] < (greg_t)probe_loop_end) {
    long below = base - g[REG_RSP];
    if (g[REG_RBX] == 0x1111111111111111L && g[REG_R12] == 0x2222222222222222L && g[REG_R13] == 0x3333333333333333L &&
        g[REG_R14] == 0x4444444444444444L && g[REG_R15] == 0x5555555555555555L && (unsigned long)g[REG_RCX] <= 4096 &&
        (below == 0 || below == 8))
      matched++;
    else
      stale++;
  }
  hits++;
}

static void *sender(void *arg) {
  (void)arg;
  while (!stop) {
    syscall(SYS_tgkill, getpid(), target, SIGUSR1);
    usleep(50);
  }
  return NULL;
}

int main(int argc, char **argv) {
  struct sigaction action = {0};
  pthread_t thread;
  want = argc > 1 ? atol(argv[1]) : 2000;
  action.sa_sigaction = handler;
  action.sa_flags = SA_SIGINFO | SA_RESTART;
  sigaction(SIGUSR1, &action, NULL);
  target = syscall(SYS_gettid);
  if (pthread_create(&thread, NULL, sender, NULL) != 0) {
    fprintf(stderr, "cannot start the signal sender\n");
    return 2;
  }
  while (matched + stale < want) {
    __asm__ volatile("mov $0x1111111111111111, %%rbx\n"
                     "mov $0x2222222222222222, %%r12\n"
                     "mov $0x3333333333333333, %%r13\n"
                     "mov $0x4444444444444444, %%r14\n"
                     "mov $0x5555555555555555, %%r15\n"
                     "mov %%rsp, base(%%rip)\n"
                     "mov $4096, %%ecx\n"
                     ".globl probe_loop_start\n"
                     "probe_loop_start:\n"
                     "push %%rax\n"
                     "1: dec %%ecx\n"
                     "jnz 1b\n"
                     "pop %%rax\n"
                     "mov $4096, %%ecx\n"
                     "2: dec %%ecx\n"
                     "jnz 2b\n"
                     ".globl probe_loop_end\n"
                     "probe_loop_end:\n"
                     :
                     :
                     : "rbx", "rcx", "r12", "r13", "r14", "r15", "memory");
  }
  stop = 1;
  pthread_join(thread, NULL);
  printf("signals=%ld matched=%ld stale=%ld\n", hits, matched, stale);
  return stale ? 1 : 0;
}
