#define _GNU_SOURCE
#include <setjmp.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static sigjmp_buf main_env, inner_env;
static volatile int depth, nested_mode;
static volatile long escapes, nested, returns, wrong;

static void fault(long i) {
  *(volatile int *)(long)(8 + (i & 7)) = 1;
}

static void segv(int sig, siginfo_t *info, void *context) {
  (void)sig;
  (void)info;
  (void)context;
  if (!nested_mode) {
    escapes++;
    siglongjmp(main_env, 1);
  }
  if (depth == 1) {
    nested++;
    siglongjmp(inner_env, 1);
  }
  depth = 1;
  for (volatile int k = 0; k < 3; k++)
    if (sigsetjmp(inner_env, 0) == 0)
      fault(k);
  depth = 0;
  escapes++;
  siglongjmp(main_env, 1);
}

static void trap(int sig, siginfo_t *info, void *context) {
  (void)sig;
  (void)info;
  (void)context;
  returns++;
}

static void install(int onstack) {
  struct sigaction action;
  memset(&action, 0, sizeof(action));
  action.sa_sigaction = segv;
  action.sa_flags = SA_SIGINFO | SA_NODEFER | (onstack ? SA_ONSTACK : 0);
  sigaction(SIGSEGV, &action, NULL);
}

static long run(long count, int with_trap) {
  long start = escapes;
  for (volatile long i = 0; i < count; i++) {
    if (with_trap) {
      volatile long before = i * 3;
      __asm__ volatile("int3" ::: "memory");
      if (before != i * 3)
        wrong++;
    }
    if (sigsetjmp(main_env, 1) == 0)
      fault(i);
  }
  return escapes - start;
}

int main(int argc, char **argv) {
  long want = argc > 1 ? atol(argv[1]) : 50000;
  stack_t ss = {.ss_sp = malloc(262144), .ss_size = 262144};
  struct sigaction action;
  sigaltstack(&ss, NULL);
  memset(&action, 0, sizeof(action));
  action.sa_sigaction = trap;
  action.sa_flags = SA_SIGINFO;
  sigaction(SIGTRAP, &action, NULL);

  install(1);
  long onstack = run(want, 0);
  install(0);
  long offstack = run(want, 0);
  install(1);
  long mixed = run(want, 1);
  nested_mode = 1;
  long outer = run(want, 0);

  printf("onstack=%ld offstack=%ld mixed=%ld returns=%ld outer=%ld nested=%ld wrong=%ld\n", onstack, offstack, mixed, returns,
         outer, nested, wrong);
  return onstack == want && offstack == want && mixed == want && returns == want && outer == want && nested == 3 * want && !wrong ? 0
                                                                                                                                 : 1;
}
