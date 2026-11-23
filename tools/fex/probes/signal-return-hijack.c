#define _GNU_SOURCE
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

#define REGIONS 11

extern char victim[], v_e1[], v_e2[], v_e3[], v_e4[], v_body[], v_x0[], v_x1[], v_x2[], v_x3[], v_x4[], v_end[];
extern char caller_ret[], hijack_stub[], redirect_stub[];
extern uint64_t caller_loop(uint64_t n);

volatile uint64_t saved_ret, saved_rip, stub_hits, redirect_hits;
volatile uint64_t pending_hijack, pending_redirect, started;
volatile uint64_t bad_rbp, bad_rbx, bad_r15;

__asm__(
    ".text\n"
    ".globl victim\n"
    "victim:\n"
    "  push %rbp\n"
    ".globl v_e1\nv_e1:\n  push %r15\n"
    ".globl v_e2\nv_e2:\n  push %rbx\n"
    ".globl v_e3\nv_e3:\n  sub $0x18, %rsp\n"
    ".globl v_e4\nv_e4:\n  lea 0x28(%rsp), %rbp\n"
    ".globl v_body\nv_body:\n"
    "  mov $0x5a5a5a5a, %rbx\n"
    "  mov $0xa5a5a5a5, %r15\n"
    "  mov $24, %ecx\n"
    "1:\n"
    "  mov %rcx, (%rsp)\n"
    "  mov %rbx, 8(%rsp)\n"
    "  push %rcx\n"
    "  push %rbx\n"
    "  pop %rbx\n"
    "  pop %rcx\n"
    "  mov -0x20(%rbp), %rdx\n"
    "  add %rdx, %rbx\n"
    "  dec %ecx\n"
    "  jnz 1b\n"
    ".globl v_x0\nv_x0:\n  add $0x18, %rsp\n"
    ".globl v_x1\nv_x1:\n  pop %rbx\n"
    ".globl v_x2\nv_x2:\n  pop %r15\n"
    ".globl v_x3\nv_x3:\n  pop %rbp\n"
    ".globl v_x4\nv_x4:\n  ret\n"
    ".globl v_end\nv_end:\n"
    "  int3\n"

    ".globl caller_loop\n"
    "caller_loop:\n"
    "  push %rbp\n  push %rbx\n  push %r15\n  push %r12\n"
    "  mov %rdi, %r12\n"
    "  movq $1, started(%rip)\n"
    "  movabs $0x1122334455667788, %rbp\n"
    "  movabs $0x2233445566778899, %rbx\n"
    "  movabs $0x33445566778899aa, %r15\n"
    "2:\n"
    "  call victim\n"
    ".globl caller_ret\ncaller_ret:\n"
    "  movabs $0x1122334455667788, %rax\n"
    "  cmp %rax, %rbp\n  jne 3f\n"
    "  movabs $0x2233445566778899, %rax\n"
    "  cmp %rax, %rbx\n  jne 3f\n"
    "  movabs $0x33445566778899aa, %rax\n"
    "  cmp %rax, %r15\n  jne 3f\n"
    "  dec %r12\n  jnz 2b\n"
    "  xor %eax, %eax\n  jmp 4f\n"
    "3:\n"
    "  mov %rbp, bad_rbp(%rip)\n  mov %rbx, bad_rbx(%rip)\n  mov %r15, bad_r15(%rip)\n"
    "  mov %r12, %rax\n"
    "4:\n"
    "  pop %r12\n  pop %r15\n  pop %rbx\n  pop %rbp\n  ret\n"

    ".globl hijack_stub\n"
    "hijack_stub:\n"
    "  push saved_ret(%rip)\n"
    "  pushfq\n"
    "  lock incq stub_hits(%rip)\n"
    "  movq $0, pending_hijack(%rip)\n"
    "  popfq\n"
    "  ret\n"

    ".globl redirect_stub\n"
    "redirect_stub:\n"
    "  push saved_rip(%rip)\n"
    "  pushfq\n"
    "  lock incq redirect_hits(%rip)\n"
    "  movq $0, pending_redirect(%rip)\n"
    "  popfq\n"
    "  ret\n");

struct region {
  char *at;
  char *end;
  long slot;
  const char *name;
};

static struct region regions[REGIONS];
static volatile uint64_t seen[REGIONS], mismatch[REGIONS], frame_bad, outside, handled, hijacks, redirects;
static int mode;
static volatile pid_t worker_tid;
static volatile int done;
static uint64_t iters, result;

static void setup_regions(void) {
  struct region r[REGIONS] = {
      {victim, v_e1, 0, "e0"},   {v_e1, v_e2, 8, "e1"},    {v_e2, v_e3, 16, "e2"},  {v_e3, v_e4, 24, "e3"},
      {v_e4, v_body, 48, "e4"},  {v_body, v_x0, 48, "body"}, {v_x0, v_x1, 48, "x0"}, {v_x1, v_x2, 24, "x1"},
      {v_x2, v_x3, 16, "x2"},    {v_x3, v_x4, 8, "x3"},    {v_x4, v_end, 0, "x4"}};
  memcpy(regions, r, sizeof(r));
}

static void on_signal(int sig, siginfo_t *si, void *ctx) {
  (void)sig;
  (void)si;
  handled++;
  greg_t *g = ((ucontext_t *)ctx)->uc_mcontext.gregs;
  char *rip = (char *)g[REG_RIP];
  uint64_t rsp = g[REG_RSP];
  int i;
  for (i = 0; i < REGIONS; i++) {
    if (rip >= regions[i].at && rip < regions[i].end) {
      break;
    }
  }
  if ((mode & 2) && !pending_redirect && ((handled & 1) || !(mode & 1))) {
    pending_redirect = 1;
    saved_rip = (uint64_t)rip;
    g[REG_RIP] = (greg_t)redirect_stub;
    redirects++;
    if (i == REGIONS) {
      outside++;
    }
    return;
  }
  if (i == REGIONS) {
    outside++;
    return;
  }
  seen[i]++;
  uint64_t *slot = (uint64_t *)(rsp + regions[i].slot);
  if (i == 5) {
    uint64_t rbp = g[REG_RBP];
    slot = (uint64_t *)(rbp + 8);
    if (rsp != rbp - 0x28 && rsp != rbp - 0x30 && rsp != rbp - 0x38) {
      frame_bad++;
    }
  }
  uint64_t val = *slot;
  if (val != (uint64_t)caller_ret && val != (uint64_t)hijack_stub) {
    mismatch[i]++;
    return;
  }
  if ((mode & 1) && !pending_hijack && val == (uint64_t)caller_ret) {
    pending_hijack = 1;
    saved_ret = val;
    *slot = (uint64_t)hijack_stub;
    hijacks++;
  }
}

static void on_fault(int sig, siginfo_t *si, void *ctx) {
  greg_t *g = ((ucontext_t *)ctx)->uc_mcontext.gregs;
  char line[256];
  int n = snprintf(line, sizeof(line), "fault sig=%d addr=%p rip=%#llx rsp=%#llx victim=%p saved_rip=%#llx saved_ret=%#llx\n", sig, si->si_addr,
                   (unsigned long long)g[REG_RIP], (unsigned long long)g[REG_RSP], (void *)victim, (unsigned long long)saved_rip,
                   (unsigned long long)saved_ret);
  if (n > 0 && write(1, line, (size_t)n) < 0) {
    _exit(4);
  }
  _exit(3);
}

static int worker(void *arg) {
  (void)arg;
  worker_tid = syscall(SYS_gettid);
  result = caller_loop(iters);
  done = 1;
  return 0;
}

int main(int argc, char **argv) {
  iters = argc > 1 ? strtoull(argv[1], 0, 0) : 300000;
  mode = argc > 2 ? atoi(argv[2]) : 3;
  setup_regions();
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_sigaction = on_signal;
  sa.sa_flags = SA_SIGINFO | SA_RESTART;
  sigemptyset(&sa.sa_mask);
  sigaction(SIGRTMIN, &sa, NULL);
  sa.sa_sigaction = on_fault;
  sigaction(SIGSEGV, &sa, NULL);
  sigaction(SIGBUS, &sa, NULL);
  sigaction(SIGILL, &sa, NULL);
  sigaction(SIGTRAP, &sa, NULL);
  static char stack[1 << 20] __attribute__((aligned(16)));
  if (clone(worker, stack + sizeof(stack), CLONE_VM | CLONE_FS | CLONE_FILES | CLONE_SIGHAND | CLONE_THREAD | CLONE_SYSVSEM, NULL) < 0) {
    perror("clone");
    return 2;
  }
  while (!worker_tid || !started) {
  }
  uint64_t sent = 0, seed = 88172645463325252ULL;
  pid_t pid = getpid();
  while (!done) {
    uint64_t before = handled;
    syscall(SYS_tgkill, pid, worker_tid, SIGRTMIN);
    sent++;
    while (handled == before && !done) {
    }
    seed = seed * 6364136223846793005ULL + 1442695040888963407ULL;
    for (volatile int k = (int)((seed >> 33) % 4000); k > 0; k--) {
    }
  }
  uint64_t bad = 0;
  for (int i = 0; i < REGIONS; i++) {
    printf("%s seen=%llu mismatch=%llu\n", regions[i].name, (unsigned long long)seen[i], (unsigned long long)mismatch[i]);
    bad += mismatch[i];
  }
  printf("sent=%llu outside=%llu hijacks=%llu stub_hits=%llu redirects=%llu redirect_hits=%llu frame_bad=%llu left=%llu\n",
         (unsigned long long)sent, (unsigned long long)outside, (unsigned long long)hijacks, (unsigned long long)stub_hits,
         (unsigned long long)redirects, (unsigned long long)redirect_hits, (unsigned long long)frame_bad, (unsigned long long)result);
  int fail = result || bad || frame_bad || hijacks > stub_hits + 1 || redirects > redirect_hits + 1;
  if (result) {
    printf("corrupt rbp=%#llx rbx=%#llx r15=%#llx\n", (unsigned long long)bad_rbp, (unsigned long long)bad_rbx, (unsigned long long)bad_r15);
  }
  printf("%s\n", fail ? "FAIL" : "PASS");
  return fail;
}
