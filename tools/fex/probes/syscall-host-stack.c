#define _GNU_SOURCE
#include <cpuid.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/syscall.h>

#define YMM_STEP(a, b) "vpaddq %%ymm" #b ", %%ymm" #a ", %%ymm" #a "\n"
#define MMX_STEP(a, b) "paddq %%mm" #b ", %%mm" #a "\n"

static int has_avx(void) {
  unsigned a, b, c, d;
  if (!__get_cpuid(1, &a, &b, &c, &d) || !(c & bit_AVX) || !(c & bit_OSXSAVE))
    return 0;
  unsigned lo, hi;
  __asm__ volatile("xgetbv" : "=a"(lo), "=d"(hi) : "c"(0));
  return (lo & 6) == 6;
}

static long ymm_round(long n) {
  long pid = 0;
  for (long i = 0; i < n; i++) {
    __asm__ volatile(
        YMM_STEP(0, 1) YMM_STEP(1, 2) YMM_STEP(2, 3) YMM_STEP(3, 4) YMM_STEP(4, 5) YMM_STEP(5, 6) YMM_STEP(6, 7) YMM_STEP(7, 8)
        YMM_STEP(8, 9) YMM_STEP(9, 10) YMM_STEP(10, 11) YMM_STEP(11, 12) YMM_STEP(12, 13) YMM_STEP(13, 14) YMM_STEP(14, 15)
        YMM_STEP(15, 0) YMM_STEP(0, 15) YMM_STEP(1, 14) YMM_STEP(2, 13) YMM_STEP(3, 12) YMM_STEP(4, 11) YMM_STEP(5, 10)
        YMM_STEP(6, 9) YMM_STEP(7, 8)
        "mov %1, %%eax\n"
        "syscall\n"
        : "=a"(pid)
        : "i"(SYS_getpid)
        : "rcx", "r11", "memory", "xmm0", "xmm1", "xmm2", "xmm3", "xmm4", "xmm5", "xmm6", "xmm7", "xmm8", "xmm9", "xmm10",
          "xmm11", "xmm12", "xmm13", "xmm14", "xmm15");
  }
  __asm__ volatile("vzeroupper" ::: "memory");
  return pid;
}

static long mmx_round(long n) {
  long pid = 0;
  for (long i = 0; i < n; i++) {
    __asm__ volatile(
        MMX_STEP(0, 1) MMX_STEP(1, 2) MMX_STEP(2, 3) MMX_STEP(3, 4) MMX_STEP(4, 5) MMX_STEP(5, 6) MMX_STEP(6, 7) MMX_STEP(7, 0)
        "mov %1, %%eax\n"
        "syscall\n"
        : "=a"(pid)
        : "i"(SYS_getpid)
        : "rcx", "r11", "memory", "mm0", "mm1", "mm2", "mm3", "mm4", "mm5", "mm6", "mm7");
  }
  __asm__ volatile("emms" ::: "memory");
  return pid;
}

int main(int argc, char **argv) {
  long want = argc > 1 ? atol(argv[1]) : 2000000;
  int avx = has_avx();
  long done = 0;
  for (; done < want; done += 1000) {
    if (avx ? ymm_round(1000) <= 0 : mmx_round(1000) <= 0)
      return 2;
  }
  printf("mode=%s syscalls=%ld\n", avx ? "avx" : "mmx", done);
  return 0;
}
