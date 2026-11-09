#define _GNU_SOURCE
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ucontext.h>

#define MAX_THREADS 8
#define ROUNDS 20000

static uint64_t counter64;
static uint32_t counter32;
static uint16_t counter16;
static uint8_t counter8;
static uint64_t spin;

static void check(int ok, const char *what) {
  if (!ok) {
    printf("FAIL %s\n", what);
    exit(1);
  }
}

static uint64_t ldaddal64(uint64_t *p, uint64_t v) {
  uint64_t old;
  __asm__ volatile("ldaddal %x1, %x0, [%2]" : "=r"(old) : "r"(v), "r"(p) : "memory");
  return old;
}

static uint32_t ldadd32(uint32_t *p, uint32_t v) {
  uint32_t old;
  __asm__ volatile("ldadd %w1, %w0, [%2]" : "=r"(old) : "r"(v), "r"(p) : "memory");
  return old;
}

static uint16_t ldaddlh(uint16_t *p, uint16_t v) {
  uint16_t old;
  __asm__ volatile("ldaddlh %w1, %w0, [%2]" : "=r"(old) : "r"(v), "r"(p) : "memory");
  return old;
}

static uint8_t ldaddab(uint8_t *p, uint8_t v) {
  uint8_t old;
  __asm__ volatile("ldaddab %w1, %w0, [%2]" : "=r"(old) : "r"(v), "r"(p) : "memory");
  return old;
}

static uint64_t casal64(uint64_t *p, uint64_t expected, uint64_t desired) {
  __asm__ volatile("casal %x0, %x2, [%1]" : "+r"(expected) : "r"(p), "r"(desired) : "memory");
  return expected;
}

static void *worker(void *arg) {
  (void)arg;
  for (int i = 0; i < ROUNDS; i++) {
    ldaddal64(&counter64, 1);
    ldadd32(&counter32, 1);
    ldaddlh(&counter16, 1);
    ldaddab(&counter8, 1);
    uint64_t seen = __atomic_load_n(&spin, __ATOMIC_RELAXED);
    while (casal64(&spin, seen, seen + 1) != seen) seen = __atomic_load_n(&spin, __ATOMIC_RELAXED);
  }
  return NULL;
}

static volatile sig_atomic_t app_handler_ran;

static void app_sigill(int sig, siginfo_t *info, void *context) {
  (void)sig;
  (void)info;
  app_handler_ran = 1;
  ((ucontext_t *)context)->uc_mcontext.pc += 4;
}

static __attribute__((noinline)) void single_forms(void) {
  uint64_t v = 0xF0;
  uint64_t old;
  __asm__ volatile("ldclral %x1, %x0, [%2]" : "=r"(old) : "r"((uint64_t)0x30), "r"(&v) : "memory");
  check(old == 0xF0 && v == 0xC0, "ldclral");
  __asm__ volatile("ldeora %x1, %x0, [%2]" : "=r"(old) : "r"((uint64_t)0xFF), "r"(&v) : "memory");
  check(old == 0xC0 && v == 0x3F, "ldeora");
  __asm__ volatile("ldsetl %x1, %x0, [%2]" : "=r"(old) : "r"((uint64_t)0x100), "r"(&v) : "memory");
  check(old == 0x3F && v == 0x13F, "ldsetl");
  __asm__ volatile("swpal %x1, %x0, [%2]" : "=r"(old) : "r"((uint64_t)7), "r"(&v) : "memory");
  check(old == 0x13F && v == 7, "swpal");

  int64_t s = -5;
  int64_t sold;
  __asm__ volatile("ldsmax %x1, %x0, [%2]" : "=r"(sold) : "r"((int64_t)-9), "r"(&s) : "memory");
  check(sold == -5 && s == -5, "ldsmax keeps the larger value");
  uint32_t u = 10;
  uint32_t uold;
  __asm__ volatile("ldumin %w1, %w0, [%2]" : "=r"(uold) : "r"((uint32_t)3), "r"(&u) : "memory");
  check(uold == 10 && u == 3, "ldumin");

  uint64_t pair[2] __attribute__((aligned(16))) = {1, 2};
  register uint64_t e0 __asm__("x4") = 1, e1 __asm__("x5") = 2, d0 __asm__("x6") = 3, d1 __asm__("x7") = 4;
  __asm__ volatile("caspal x4, x5, x6, x7, [%4]" : "+r"(e0), "+r"(e1) : "r"(d0), "r"(d1), "r"(pair) : "memory");
  check(e0 == 1 && e1 == 2 && pair[0] == 3 && pair[1] == 4, "caspal");

  uint64_t missed = casal64(&v, 99, 1);
  check(missed == 7 && v == 7, "casal leaves memory alone on a mismatch");

  uint32_t m = 1;
  uint32_t mold;
  uint64_t eq;
  __asm__ volatile("cmp %w3, %w3\n\tldumax %w2, %w0, [%4]\n\tcset %1, eq"
                   : "=&r"(mold), "=&r"(eq) : "r"((uint32_t)5), "r"((uint32_t)0), "r"(&m) : "memory", "cc");
  check(mold == 1 && m == 5 && eq == 1, "ldumax leaves the condition flags alone");
  int8_t b = -3;
  int8_t bold;
  __asm__ volatile("cmp %w3, %w3\n\tldsmaxb %w2, %w0, [%4]\n\tcset %1, eq"
                   : "=&r"(bold), "=&r"(eq) : "r"((int32_t)-7), "r"((uint32_t)0), "r"(&b) : "memory", "cc");
  check(bold == -3 && b == -3 && eq == 1, "ldsmaxb leaves the condition flags alone");
}

int main(int argc, char **argv) {
  int threads_wanted = argc > 1 ? atoi(argv[1]) : 4;
  check(threads_wanted >= 1 && threads_wanted <= MAX_THREADS, "thread count");
  single_forms();
  single_forms();

  pthread_t threads[MAX_THREADS];
  for (int i = 0; i < threads_wanted; i++) pthread_create(&threads[i], NULL, worker, NULL);
  for (int i = 0; i < threads_wanted; i++) pthread_join(threads[i], NULL);
  uint64_t total = (uint64_t)threads_wanted * ROUNDS;
  check(counter64 == total, "64-bit counter");
  check(counter32 == (uint32_t)total, "32-bit counter");
  check(counter16 == (uint16_t)total, "16-bit counter");
  check(counter8 == (uint8_t)total, "8-bit counter");
  check(spin == total, "casal loop");

  struct sigaction sa;
  memset(&sa, 0, sizeof sa);
  sa.sa_sigaction = app_sigill;
  sa.sa_flags = SA_SIGINFO;
  sigaction(SIGILL, &sa, NULL);
  __asm__ volatile("udf #0");
  check(app_handler_ran, "the program's own SIGILL handler still runs");

  printf("lse ok\n");
  return 0;
}
