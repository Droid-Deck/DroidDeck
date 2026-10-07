// Diagnostic: on SIGSEGV/SIGBUS/SIGILL, write the faulting address, the instruction pointer and a
// frame-pointer backtrace (module+offset, symbol) to $SEGVLOG_FILE, then die as before. Later
// SIGSEGV handlers (the game's breakpad) are refused so this one sees the fault first.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ucontext.h>
#include <unistd.h>

static int (*real_sigaction)(int, const struct sigaction *, struct sigaction *);
static int out = -1;

static void line(const char *tag, void *pc) {
  char buf[512];
  Dl_info di;
  if (pc && dladdr(pc, &di) && di.dli_fname)
    snprintf(buf, sizeof buf, "%s %p %s+0x%lx %s\n", tag, pc, di.dli_fname,
             (unsigned long)((char *)pc - (char *)di.dli_fbase), di.dli_sname ? di.dli_sname : "?");
  else
    snprintf(buf, sizeof buf, "%s %p ?\n", tag, pc);
  if (out >= 0) write(out, buf, strlen(buf));
}

static void handler(int sig, siginfo_t *si, void *ctx) {
  ucontext_t *uc = ctx;
  char buf[160];
#if defined(__i386__)
  void *pc = (void *)uc->uc_mcontext.gregs[REG_EIP];
  void **fp = (void **)uc->uc_mcontext.gregs[REG_EBP];
#else
  void *pc = (void *)uc->uc_mcontext.gregs[REG_RIP];
  void **fp = (void **)uc->uc_mcontext.gregs[REG_RBP];
#endif
  snprintf(buf, sizeof buf, "=== pid %d signal %d code %d fault addr %p\n", getpid(), sig, si->si_code, si->si_addr);
  if (out >= 0) write(out, buf, strlen(buf));
  line("pc", pc);
  // Leaf functions (strlen, memcpy) keep no frame: the caller's return address is on the stack.
#if defined(__i386__)
  void **sp = (void **)uc->uc_mcontext.gregs[REG_ESP];
#else
  void **sp = (void **)uc->uc_mcontext.gregs[REG_RSP];
#endif
  for (int i = 0; i < 48 && sp; i++) {
    Dl_info di;
    if (sp[i] && dladdr(sp[i], &di) && di.dli_fname) line("  sp", sp[i]);
  }
  for (int i = 0; i < 24 && fp && ((unsigned long)fp & 3) == 0; i++) {
    void *ret = fp[1];
    if (!ret) break;
    line("  at", ret);
    void **next = (void **)fp[0];
    if (next <= fp) break;
    fp = next;
  }
  signal(sig, SIG_DFL);
}

int sigaction(int sig, const struct sigaction *act, struct sigaction *old) {
  if (!real_sigaction) real_sigaction = dlsym(RTLD_NEXT, "sigaction");
  if (act && (sig == SIGSEGV || sig == SIGBUS || sig == SIGILL) && act->sa_sigaction != handler) {
    if (old) real_sigaction(sig, NULL, old);
    return 0;
  }
  return real_sigaction(sig, act, old);
}

__attribute__((constructor)) static void init(void) {
  const char *f = getenv("SEGVLOG_FILE");
  if (!f) return;
  out = open(f, O_WRONLY | O_CREAT | O_APPEND, 0644);
  struct sigaction sa;
  memset(&sa, 0, sizeof sa);
  sa.sa_sigaction = handler;
  sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
  real_sigaction = dlsym(RTLD_NEXT, "sigaction");
  real_sigaction(SIGSEGV, &sa, NULL);
  real_sigaction(SIGBUS, &sa, NULL);
  real_sigaction(SIGILL, &sa, NULL);
}
