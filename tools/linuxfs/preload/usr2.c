#define _GNU_SOURCE
#include <dlfcn.h>
#include <link.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

static uintptr_t ntdll_lo, ntdll_hi;

static int find_ntdll(struct dl_phdr_info *info, size_t size, void *data) {
  (void)size;
  (void)data;
  const char *slash = strrchr(info->dlpi_name, '/');
  if (strcmp(slash ? slash + 1 : info->dlpi_name, "ntdll.so") != 0) return 0;
  for (int i = 0; i < info->dlpi_phnum; i++) {
    const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
    if (ph->p_type != PT_LOAD || !(ph->p_flags & PF_X)) continue;
    uintptr_t lo = info->dlpi_addr + ph->p_vaddr;
    __atomic_store_n(&ntdll_hi, lo + ph->p_memsz, __ATOMIC_RELAXED);
    __atomic_store_n(&ntdll_lo, lo, __ATOMIC_RELEASE);
    return 1;
  }
  return 0;
}

__attribute__((visibility("hidden"))) int dd_from_ntdll(const void *caller) {
  uintptr_t pc = (uintptr_t)caller, lo = __atomic_load_n(&ntdll_lo, __ATOMIC_ACQUIRE);
  if (!lo) {
    dl_iterate_phdr(find_ntdll, NULL);
    lo = __atomic_load_n(&ntdll_lo, __ATOMIC_ACQUIRE);
    if (!lo) return 0;
  }
  return pc >= lo && pc < __atomic_load_n(&ntdll_hi, __ATOMIC_RELAXED);
}

#if defined(__aarch64__)
#include <asm/hwcap.h>
#include <asm/sigcontext.h>

#define DD_STR2(x) #x
#define DD_STR(x) DD_STR2(x)

#define DD_HWCAP_GCS (1UL << 32)
#define DD_FRAME_SIZE 4688
#define DD_REGS 312
#define DD_SP 560
#define DD_PC 568
#define DD_PSTATE 576
#define DD_FPSIMD 592

struct dd_sigframe {
  siginfo_t info;
  ucontext_t uc;
};

struct dd_kernel_sigaction {
  void (*handler)(int, siginfo_t *, void *);
  unsigned long flags;
  void *restorer;
  uint64_t mask;
};

struct dd_usr2_call {
  void (*handler)(int, siginfo_t *, void *);
  uint64_t old_mask;
};

_Static_assert(sizeof(struct dd_sigframe) == DD_FRAME_SIZE, "rt_sigframe size");
_Static_assert(offsetof(struct dd_sigframe, uc.uc_mcontext.regs) == DD_REGS, "regs offset");
_Static_assert(offsetof(struct dd_sigframe, uc.uc_mcontext.sp) == DD_SP, "sp offset");
_Static_assert(offsetof(struct dd_sigframe, uc.uc_mcontext.pc) == DD_PC, "pc offset");
_Static_assert(offsetof(struct dd_sigframe, uc.uc_mcontext.pstate) == DD_PSTATE, "pstate offset");
_Static_assert(offsetof(struct dd_sigframe, uc.uc_mcontext.__reserved) == DD_FPSIMD, "reserved offset");
_Static_assert(sizeof(struct fpsimd_context) + sizeof(struct _aarch64_ctx) <=
                   sizeof(((mcontext_t *)0)->__reserved), "reserved space");

__attribute__((visibility("hidden"))) void dd_usr2_frame(struct dd_usr2_call *call);
__attribute__((visibility("hidden"), used)) void dd_usr2_run(struct dd_usr2_call *call, struct dd_sigframe *frame);

__asm__(".text\n"
        ".p2align 2\n"
        ".type dd_usr2_frame, %function\n"
        "dd_usr2_frame:\n"
        "  mov x16, #" DD_STR(DD_FRAME_SIZE) "\n"
        "  sub sp, sp, x16\n"
        "  add x17, sp, #" DD_STR(DD_REGS) "\n"
        "  stp x0, x1, [x17, #0]\n"
        "  stp x2, x3, [x17, #16]\n"
        "  stp x4, x5, [x17, #32]\n"
        "  stp x6, x7, [x17, #48]\n"
        "  stp x8, x9, [x17, #64]\n"
        "  stp x10, x11, [x17, #80]\n"
        "  stp x12, x13, [x17, #96]\n"
        "  stp x14, x15, [x17, #112]\n"
        "  stp xzr, xzr, [x17, #128]\n"
        "  stp x18, x19, [x17, #144]\n"
        "  stp x20, x21, [x17, #160]\n"
        "  stp x22, x23, [x17, #176]\n"
        "  stp x24, x25, [x17, #192]\n"
        "  stp x26, x27, [x17, #208]\n"
        "  stp x28, x29, [x17, #224]\n"
        "  str x30, [x17, #240]\n"
        "  str xzr, [x17, #0]\n"
        "  add x16, sp, x16\n"
        "  str x16, [sp, #" DD_STR(DD_SP) "]\n"
        "  adr x16, 1f\n"
        "  str x16, [sp, #" DD_STR(DD_PC) "]\n"
        "  mrs x16, nzcv\n"
        "  str x16, [sp, #" DD_STR(DD_PSTATE) "]\n"
        "  add x17, sp, #" DD_STR(DD_FPSIMD) "\n"
        "  mrs x16, fpsr\n"
        "  str w16, [x17, #8]\n"
        "  mrs x16, fpcr\n"
        "  str w16, [x17, #12]\n"
        "  stp q0, q1, [x17, #16]\n"
        "  stp q2, q3, [x17, #48]\n"
        "  stp q4, q5, [x17, #80]\n"
        "  stp q6, q7, [x17, #112]\n"
        "  stp q8, q9, [x17, #144]\n"
        "  stp q10, q11, [x17, #176]\n"
        "  stp q12, q13, [x17, #208]\n"
        "  stp q14, q15, [x17, #240]\n"
        "  stp q16, q17, [x17, #272]\n"
        "  stp q18, q19, [x17, #304]\n"
        "  stp q20, q21, [x17, #336]\n"
        "  stp q22, q23, [x17, #368]\n"
        "  stp q24, q25, [x17, #400]\n"
        "  stp q26, q27, [x17, #432]\n"
        "  stp q28, q29, [x17, #464]\n"
        "  stp q30, q31, [x17, #496]\n"
        "  mov x1, sp\n"
        "  bl dd_usr2_run\n"
        "  mov x8, #" DD_STR(__NR_rt_sigreturn) "\n"
        "  svc #0\n"
        "  brk #0\n"
        "1:\n"
        "  ret\n"
        ".size dd_usr2_frame, .-dd_usr2_frame\n");

static int (*usr2_real_raise)(int);

static long usr2_sys(long nr, long a, long b, long c, long d) {
  register long x8 __asm__("x8") = nr;
  register long x0 __asm__("x0") = a;
  register long x1 __asm__("x1") = b;
  register long x2 __asm__("x2") = c;
  register long x3 __asm__("x3") = d;
  __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory");
  return x0;
}

static uint64_t usr2_ssbs(void) {
  uint64_t value = 0;
  if (getauxval(AT_HWCAP) & HWCAP_SSBS) __asm__ volatile("mrs %0, s3_3_c4_c2_6" : "=r"(value));
  return value & (1UL << 12);
}

void dd_usr2_run(struct dd_usr2_call *call, struct dd_sigframe *frame) {
  struct fpsimd_context *fp = (struct fpsimd_context *)frame->uc.uc_mcontext.__reserved;
  struct _aarch64_ctx *end = (struct _aarch64_ctx *)(fp + 1);

  memset(&frame->info, 0, sizeof(frame->info));
  frame->info.si_signo = SIGUSR2;
  frame->info.si_code = SI_TKILL;
  frame->info.si_pid = (pid_t)usr2_sys(__NR_getpid, 0, 0, 0, 0);
  frame->info.si_uid = (uid_t)usr2_sys(__NR_getuid, 0, 0, 0, 0);
  frame->uc.uc_flags = 0;
  frame->uc.uc_link = NULL;
  if (usr2_sys(__NR_sigaltstack, 0, (long)&frame->uc.uc_stack, 0, 0) != 0) {
    memset(&frame->uc.uc_stack, 0, sizeof(frame->uc.uc_stack));
    frame->uc.uc_stack.ss_flags = SS_DISABLE;
  }
  memset(&frame->uc.uc_sigmask, 0, sizeof(frame->uc.uc_sigmask));
  memcpy(&frame->uc.uc_sigmask, &call->old_mask, sizeof(call->old_mask));
  frame->uc.uc_mcontext.fault_address = 0;
  frame->uc.uc_mcontext.pstate |= usr2_ssbs();
  fp->head.magic = FPSIMD_MAGIC;
  fp->head.size = sizeof(*fp);
  end->magic = 0;
  end->size = 0;
  call->handler(SIGUSR2, &frame->info, &frame->uc);
}

int raise(int sig) {
  int (*real)(int) = __atomic_load_n(&usr2_real_raise, __ATOMIC_ACQUIRE);
  if (!real) {
    real = (int (*)(int))dlsym(RTLD_NEXT, "raise");
    __atomic_store_n(&usr2_real_raise, real, __ATOMIC_RELEASE);
  }
  if (sig != SIGUSR2 || (getauxval(AT_HWCAP) & DD_HWCAP_GCS) || !dd_from_ntdll(__builtin_return_address(0))) {
    return real(sig);
  }

  struct dd_kernel_sigaction action;
  if (usr2_sys(__NR_rt_sigaction, SIGUSR2, 0, (long)&action, sizeof(action.mask)) != 0) return real(sig);
  if (!(action.flags & SA_SIGINFO) || (action.flags & SA_RESETHAND) || (uintptr_t)action.handler <= 1) {
    return real(sig);
  }

  uint64_t bit = 1ULL << (SIGUSR2 - 1);
  uint64_t block = action.mask | ((action.flags & SA_NODEFER) ? 0 : bit);
  struct dd_usr2_call call = {action.handler, 0};
  if (usr2_sys(__NR_rt_sigprocmask, SIG_BLOCK, (long)&block, (long)&call.old_mask, sizeof(block)) != 0) {
    return real(sig);
  }
  if (call.old_mask & bit) {
    usr2_sys(__NR_rt_sigprocmask, SIG_SETMASK, (long)&call.old_mask, 0, sizeof(call.old_mask));
    return real(sig);
  }
  dd_usr2_frame(&call);
  return 0;
}
#endif
