/*
 * libssbs.so - keep PSTATE.SSBS set across signal returns, without rebuilding Wine.
 *
 * Wine's arm64 signal code writes PSTATE back from a Windows CONTEXT, whose Cpsr never carries
 * SSBS (bit 12). Every signal return through that code (exception fixups, NtSetContextThread on
 * another thread, the SIGUSR2 suspend path) therefore resumes the thread with SSBS clear, so the
 * CPU runs it with speculative store bypass disabled - slower on cores such as Oryon.
 * force_ssbs.diff (Sonicadvance1, FEX) fixes this inside ntdll; this library does the same from
 * outside, for Protons that cannot be rebuilt: the Valve, GE and CachyOS ARM64 builds DroidDeck
 * runs, which come prebuilt from Steam. Same source as Bannerlator's adapter (bionic), built here
 * for glibc.
 *
 * How: interpose sigaction()/signal() via LD_PRELOAD. Every handler is installed behind one
 * SA_SIGINFO trampoline that calls the real handler and then sets SSBS in the ucontext it is
 * about to return through. The kernel accepts SSBS from userspace on sigreturn and context-
 * switches it per thread, so the bit sticks until the next sigreturn. Handlers that never return
 * (siglongjmp) are unaffected either way.
 *
 * Env:
 *   SSBS_ADAPTER_DISABLE=1  pass everything through untouched.
 *   SSBS_ADAPTER_OBSERVE=1  wrap and count, but leave SSBS as the handler left it (the baseline).
 *   SSBS_ADAPTER_DEBUG=1    log to stderr (implied by OBSERVE):
 *     - "returned with SSBS clear": a handler was about to resume a thread without SSBS.
 *     - "arrived with SSBS clear": a thread was RUNNING without SSBS when a signal reached it.
 *       Under the adapter this should stop after each thread's first fixed return; if it keeps
 *       counting, something other than a signal return is clearing the bit.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ucontext.h>

#define PSTATE_SSBS 0x1000ULL
#define MAX_SIG 65

typedef void (*sigact_fn)(int, siginfo_t *, void *);
typedef void (*sighand_fn)(int);
typedef int (*sigaction_fn)(int, const struct sigaction *, struct sigaction *);

/* Per signal: the handler the program asked for, and whether it wanted SA_SIGINFO. */
static _Atomic(void *) real_handler[MAX_SIG];
static atomic_bool real_siginfo[MAX_SIG];

static sigaction_fn next_sigaction;
static int disabled;
static int observe;
static int debug;
static atomic_ulong n_returns, n_fixed, n_arrived, n_arrived_clear;

static void init(void)
{
    if (next_sigaction) return;
    next_sigaction = (sigaction_fn)dlsym(RTLD_NEXT, "sigaction");
    const char *e = getenv("SSBS_ADAPTER_DISABLE");
    disabled = e && *e == '1';
    e = getenv("SSBS_ADAPTER_OBSERVE");
    observe = e && *e == '1';
    e = getenv("SSBS_ADAPTER_DEBUG");
    debug = observe || (e && *e == '1');
}

__attribute__((constructor)) static void ctor(void)
{
    init();
    if (debug) fprintf(stderr, "libssbs: loaded (%s)\n", disabled ? "disabled" : observe ? "observing only" : "active");
}

/* 1, 2, 4, 8, ...: enough lines to see a trend without flooding the log. */
static int milestone(unsigned long n) { return (n & (n - 1)) == 0; }

static void trampoline(int sig, siginfo_t *info, void *uc_v)
{
    ucontext_t *uc = uc_v;
    if (debug) {
        unsigned long a = atomic_fetch_add(&n_arrived, 1) + 1;
        if (!(uc->uc_mcontext.pstate & PSTATE_SSBS)) {
            unsigned long c = atomic_fetch_add(&n_arrived_clear, 1) + 1;
            if (milestone(c))
                fprintf(stderr, "libssbs: sig %d arrived with SSBS clear (%lu of %lu arrivals)\n", sig, c, a);
        }
    }
    void *h = atomic_load_explicit(&real_handler[sig], memory_order_acquire);
    if (h) {
        if (atomic_load_explicit(&real_siginfo[sig], memory_order_relaxed))
            ((sigact_fn)h)(sig, info, uc_v);
        else
            ((sighand_fn)h)(sig);
    }
    if (debug) {
        unsigned long r = atomic_fetch_add(&n_returns, 1) + 1;
        if (!(uc->uc_mcontext.pstate & PSTATE_SSBS)) {
            unsigned long f = atomic_fetch_add(&n_fixed, 1) + 1;
            if (milestone(f))
                fprintf(stderr, "libssbs: sig %d returned with SSBS clear, %s (%lu of %lu returns)\n",
                        sig, observe ? "left as is" : "set it", f, r);
        }
    }
    if (!observe) uc->uc_mcontext.pstate |= PSTATE_SSBS;
}

static int wrapped(void *h)
{
    return h != (void *)SIG_DFL && h != (void *)SIG_IGN && h != (void *)SIG_ERR;
}

int sigaction(int sig, const struct sigaction *act, struct sigaction *oldact)
{
    init();
    if (disabled || sig <= 0 || sig >= MAX_SIG)
        return next_sigaction(sig, act, oldact);

    struct sigaction mine;
    const struct sigaction *pass = act;
    void *new_h = NULL;
    int new_info = 0;
    if (act) {
        new_info = (act->sa_flags & SA_SIGINFO) != 0;
        new_h = new_info ? (void *)act->sa_sigaction : (void *)act->sa_handler;
        if (wrapped(new_h)) {
            mine = *act;
            mine.sa_flags |= SA_SIGINFO;
            mine.sa_sigaction = trampoline;
            pass = &mine;
        }
    }

    void *prev_h = atomic_load(&real_handler[sig]);
    int prev_info = atomic_load(&real_siginfo[sig]);
    /* Publish before installing so the trampoline never runs with a stale handler. */
    if (act && wrapped(new_h)) {
        atomic_store(&real_siginfo[sig], new_info);
        atomic_store_explicit(&real_handler[sig], new_h, memory_order_release);
    }

    int ret = next_sigaction(sig, pass, oldact);
    if (ret != 0 && act && wrapped(new_h)) {
        atomic_store(&real_siginfo[sig], prev_info);
        atomic_store(&real_handler[sig], prev_h);
    }

    /* Report the caller's own handler, not the trampoline, so handler chaining keeps working. */
    if (ret == 0 && oldact && oldact->sa_sigaction == trampoline && prev_h) {
        if (prev_info) {
            oldact->sa_sigaction = (sigact_fn)prev_h;
        } else {
            oldact->sa_flags &= ~SA_SIGINFO;
            oldact->sa_handler = (sighand_fn)prev_h;
        }
    }
    return ret;
}

sighandler_t signal(int sig, sighandler_t handler)
{
    struct sigaction sa, old;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = handler;
    sa.sa_flags = SA_RESTART;
    sigemptyset(&sa.sa_mask);
    if (sigaction(sig, &sa, &old) != 0) return SIG_ERR;
    return (old.sa_flags & SA_SIGINFO) ? (sighandler_t)(void *)old.sa_sigaction : old.sa_handler;
}

sighandler_t bsd_signal(int sig, sighandler_t handler) { return signal(sig, handler); }

/* glibc's System V flavour: a one-shot handler with no restart. */
sighandler_t sysv_signal(int sig, sighandler_t handler)
{
    struct sigaction sa, old;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = handler;
    sa.sa_flags = SA_RESETHAND | SA_NODEFER;
    sigemptyset(&sa.sa_mask);
    if (sigaction(sig, &sa, &old) != 0) return SIG_ERR;
    return (old.sa_flags & SA_SIGINFO) ? (sighandler_t)(void *)old.sa_sigaction : old.sa_handler;
}
