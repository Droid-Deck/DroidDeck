#define _GNU_SOURCE
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/time.h>
#include <ucontext.h>

#define MARK 0x5eed

static volatile sig_atomic_t alarms;
static volatile sig_atomic_t give_up;

static void count_alarm(void) {
  if (++alarms > 100) {
    give_up = 1;
  }
}

static void on_alarm(int sig, siginfo_t *si, void *ctx) {
  (void)sig;
  (void)si;
  ucontext_t *uc = ctx;
  count_alarm();
#ifdef __x86_64__
  uc->uc_mcontext.gregs[REG_R12] = MARK;
#else
  uc->uc_mcontext.gregs[REG_ESI] = MARK;
#endif
}

#ifdef __i386__
static void on_alarm_legacy(int sig, struct sigcontext sc) {
  (void)sig;
  count_alarm();
  ((volatile struct sigcontext *)&sc)->esi = MARK;
}
#endif

int main(int argc, char **argv) {
  int legacy = argc > 1 && strcmp(argv[1], "legacy") == 0;
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_flags = SA_RESTART;
#ifdef __i386__
  if (legacy) {
    sa.sa_handler = (void (*)(int))on_alarm_legacy;
  } else
#endif
  {
    legacy = 0;
    sa.sa_sigaction = on_alarm;
    sa.sa_flags |= SA_SIGINFO;
  }
  sigaction(SIGALRM, &sa, NULL);
  struct itimerval it = {{0, 20000}, {0, 20000}};
  setitimer(ITIMER_REAL, &it, NULL);

  unsigned long spins, seen;
#ifdef __x86_64__
  __asm__ volatile("xor %%r12, %%r12\n"
                   "xor %0, %0\n"
                   "1: inc %0\n"
                   "test %%r12, %%r12\n"
                   "jnz 2f\n"
                   "cmpl $0, %2\n"
                   "je 1b\n"
                   "2: mov %%r12, %1\n"
                   : "=&r"(spins), "=&r"(seen)
                   : "m"(give_up)
                   : "r12", "cc");
#else
  __asm__ volatile("xor %%esi, %%esi\n"
                   "xor %0, %0\n"
                   "1: inc %0\n"
                   "test %%esi, %%esi\n"
                   "jnz 2f\n"
                   "cmpl $0, %2\n"
                   "je 1b\n"
                   "2: mov %%esi, %1\n"
                   : "=&r"(spins), "=&r"(seen)
                   : "m"(give_up)
                   : "esi", "cc");
#endif
  struct itimerval off = {{0, 0}, {0, 0}};
  setitimer(ITIMER_REAL, &off, NULL);
  printf("frame=%s seen=%d alarms=%d spins=%lu\n", legacy ? "legacy" : "rt", seen == MARK, (int)alarms, spins);
  return seen == MARK ? 0 : 1;
}
