// Part of libddnatv-hook.so, included by ddnatv-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// The SIGSYS handler for system calls Android's seccomp filter blocks, and the signal hooks that
// keep programs from replacing or blocking it.

#ifndef DDNATV_HOST_TEST

#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1
#endif

static int (*get_real_sigaction(void))(int, const struct sigaction *, struct sigaction *);

// Intercept SECCOMP blocked syscalls (SIGSYS), resolve sandbox/landlock gracefully, and advance PC
static void ddnatv_sigsys_handler(int sig, siginfo_t *info, void *ctx) {
    if (!ctx || !info) return;
    if (info->si_code != SYS_SECCOMP) {
        int (*real_sig)(int, const struct sigaction *, struct sigaction *) = get_real_sigaction();
        if (real_sig) {
            struct sigaction sa_dfl;
            memset(&sa_dfl, 0, sizeof(sa_dfl));
            sa_dfl.sa_handler = SIG_DFL;
            sigemptyset(&sa_dfl.sa_mask);
            real_sig(sig, &sa_dfl, NULL);
        }
        raise(sig);
        return;
    }
    ucontext_t *uctx = (ucontext_t *)ctx;

    int sys_nr = info->si_syscall;
    long ret_val = is_sigsys_synthetic_success_syscall(sys_nr) ? 0 : -ENOSYS;

#if defined(__aarch64__)
    uctx->uc_mcontext.regs[0] = (uint64_t)ret_val;
    if (uctx->uc_mcontext.pc >= 4) {
        uint32_t insn_at_pc = 0;
        uint32_t insn_before_pc = 0;
        memcpy(&insn_at_pc, (const void *)uctx->uc_mcontext.pc, sizeof(insn_at_pc));
        memcpy(&insn_before_pc, (const void *)(uctx->uc_mcontext.pc - 4), sizeof(insn_before_pc));
        // On SECCOMP_RET_TRAP, the kernel already advanced PC past svc #0 (*(pc - 4) is svc #0).
        // Only advance PC if PC itself points to svc #0 and *(pc - 4) does not.
        int at_is_svc = ((insn_at_pc & 0xffe0001fU) == 0xd4000001U);
        int prev_is_svc = ((insn_before_pc & 0xffe0001fU) == 0xd4000001U);
        if (at_is_svc && !prev_is_svc) {
            uctx->uc_mcontext.pc += 4;
        }
    }
#elif defined(__arm__)
    uctx->uc_mcontext.arm_r0 = (unsigned long)ret_val;
    if (uctx->uc_mcontext.arm_pc >= 4) {
        if (uctx->uc_mcontext.arm_cpsr & 0x20) {
            uint16_t insn_at_pc = 0;
            uint16_t insn_before_pc = 0;
            memcpy(&insn_at_pc, (const void *)uctx->uc_mcontext.arm_pc, sizeof(insn_at_pc));
            memcpy(&insn_before_pc, (const void *)(uctx->uc_mcontext.arm_pc - 2), sizeof(insn_before_pc));
            int at_is_svc = ((insn_at_pc & 0xff00U) == 0xdf00U);
            int prev_is_svc = ((insn_before_pc & 0xff00U) == 0xdf00U);
            if (at_is_svc && !prev_is_svc) {
                uctx->uc_mcontext.arm_pc += 2;
            }
        } else {
            uint32_t insn_at_pc = 0;
            uint32_t insn_before_pc = 0;
            memcpy(&insn_at_pc, (const void *)uctx->uc_mcontext.arm_pc, sizeof(insn_at_pc));
            memcpy(&insn_before_pc, (const void *)(uctx->uc_mcontext.arm_pc - 4), sizeof(insn_before_pc));
            int at_is_svc = ((insn_at_pc & 0x0f000000U) == 0x0f000000U);
            int prev_is_svc = ((insn_before_pc & 0x0f000000U) == 0x0f000000U);
            if (at_is_svc && !prev_is_svc) {
                uctx->uc_mcontext.arm_pc += 4;
            }
        }
    }
#elif defined(__x86_64__) && defined(REG_RAX)
    uctx->uc_mcontext.gregs[REG_RAX] = (greg_t)ret_val;
    if (uctx->uc_mcontext.gregs[REG_RIP] >= 2) {
        unsigned char insn_at[2] = {0};
        unsigned char insn_prev[2] = {0};
        memcpy(insn_at, (const void *)uctx->uc_mcontext.gregs[REG_RIP], sizeof(insn_at));
        memcpy(insn_prev, (const void *)(uctx->uc_mcontext.gregs[REG_RIP] - 2), sizeof(insn_prev));
        int at_is_sys = (insn_at[0] == 0x0f && insn_at[1] == 0x05);
        int prev_is_sys = (insn_prev[0] == 0x0f && insn_prev[1] == 0x05);
        if (at_is_sys && !prev_is_sys) {
            uctx->uc_mcontext.gregs[REG_RIP] += 2;
        }
    }
#elif defined(__i386__) && defined(REG_EAX)
    uctx->uc_mcontext.gregs[REG_EAX] = (greg_t)ret_val;
    if (uctx->uc_mcontext.gregs[REG_EIP] >= 2) {
        unsigned char insn_at[2] = {0};
        unsigned char insn_prev[2] = {0};
        memcpy(insn_at, (const void *)uctx->uc_mcontext.gregs[REG_EIP], sizeof(insn_at));
        memcpy(insn_prev, (const void *)(uctx->uc_mcontext.gregs[REG_EIP] - 2), sizeof(insn_prev));
        int at_is_sys = (insn_at[0] == 0xcd && insn_at[1] == 0x80);
        int prev_is_sys = (insn_prev[0] == 0xcd && insn_prev[1] == 0x80);
        if (at_is_sys && !prev_is_sys) {
            uctx->uc_mcontext.gregs[REG_EIP] += 2;
        }
    }
#endif
}

static int (*get_real_sigaction(void))(int, const struct sigaction *, struct sigaction *) {
    static int (*real_sigaction)(int, const struct sigaction *, struct sigaction *) = NULL;
    if (!real_sigaction) {
        real_sigaction = (int (*)(int, const struct sigaction *, struct sigaction *))dlsym(RTLD_NEXT, "sigaction");
        if (!real_sigaction) {
            real_sigaction = (int (*)(int, const struct sigaction *, struct sigaction *))dlsym(RTLD_DEFAULT, "sigaction");
        }
    }
    return real_sigaction;
}

__attribute__((constructor(101))) static void install_sigsys_handler(void) {
    int (*real_sig)(int, const struct sigaction *, struct sigaction *) = get_real_sigaction();
    if (!real_sig) return;

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = ddnatv_sigsys_handler;
    sa.sa_flags = SA_SIGINFO | SA_RESTART;
    sigemptyset(&sa.sa_mask);
    real_sig(SIGSYS, &sa, NULL);
}

int sigaction(int signum, const struct sigaction *act, struct sigaction *oldact) {
    int (*real_sig)(int, const struct sigaction *, struct sigaction *) = get_real_sigaction();
    if (!real_sig) return -1;

    if (signum == SIGSYS) {
        if (act && act->sa_sigaction == ddnatv_sigsys_handler) {
            return real_sig(signum, act, oldact);
        }
        if (oldact) {
            memset(oldact, 0, sizeof(*oldact));
            oldact->sa_sigaction = ddnatv_sigsys_handler;
            oldact->sa_flags = SA_SIGINFO | SA_RESTART;
        }
        return 0;
    }
    return real_sig(signum, act, oldact);
}

int rt_sigaction(int signum, const struct sigaction *act, struct sigaction *oldact, size_t sigsetsize) {
    static int (*orig_rt_sigaction)(int, const struct sigaction *, struct sigaction *, size_t) = NULL;
    if (!orig_rt_sigaction) orig_rt_sigaction = (int (*)(int, const struct sigaction *, struct sigaction *, size_t))dlsym(RTLD_NEXT, "rt_sigaction");

    if (signum == SIGSYS) {
        if (act && act->sa_sigaction == ddnatv_sigsys_handler) {
            return orig_rt_sigaction ? orig_rt_sigaction(signum, act, oldact, sigsetsize) : 0;
        }
        if (oldact) {
            memset(oldact, 0, sizeof(*oldact));
            oldact->sa_sigaction = ddnatv_sigsys_handler;
            oldact->sa_flags = SA_SIGINFO | SA_RESTART;
        }
        return 0;
    }
    return orig_rt_sigaction ? orig_rt_sigaction(signum, act, oldact, sigsetsize) : -1;
}

int __libc_sigaction(int signum, const struct sigaction *act, struct sigaction *oldact) {
    return sigaction(signum, act, oldact);
}

typedef void (*sighandler_t)(int);
sighandler_t signal(int signum, sighandler_t handler) {
    static sighandler_t (*orig_signal)(int, sighandler_t) = NULL;
    if (!orig_signal) orig_signal = (sighandler_t (*)(int, sighandler_t))dlsym(RTLD_NEXT, "signal");
    if (signum == SIGSYS) {
        return (sighandler_t)ddnatv_sigsys_handler;
    }
    return orig_signal ? orig_signal(signum, handler) : NULL;
}

int sigprocmask(int how, const sigset_t *set, sigset_t *oldset) {
    static int (*orig_sigprocmask)(int, const sigset_t *, sigset_t *) = NULL;
    if (!orig_sigprocmask) orig_sigprocmask = (int (*)(int, const sigset_t *, sigset_t *))dlsym(RTLD_NEXT, "sigprocmask");
    if (set && (how == SIG_BLOCK || how == SIG_SETMASK)) {
        sigset_t mod_set = *set;
        sigdelset(&mod_set, SIGSYS);
        return orig_sigprocmask ? orig_sigprocmask(how, &mod_set, oldset) : 0;
    }
    return orig_sigprocmask ? orig_sigprocmask(how, set, oldset) : 0;
}

int pthread_sigmask(int how, const sigset_t *set, sigset_t *oldset) {
    static int (*orig_pthread_sigmask)(int, const sigset_t *, sigset_t *) = NULL;
    if (!orig_pthread_sigmask) orig_pthread_sigmask = (int (*)(int, const sigset_t *, sigset_t *))dlsym(RTLD_NEXT, "pthread_sigmask");
    if (set && (how == SIG_BLOCK || how == SIG_SETMASK)) {
        sigset_t mod_set = *set;
        sigdelset(&mod_set, SIGSYS);
        return orig_pthread_sigmask ? orig_pthread_sigmask(how, &mod_set, oldset) : 0;
    }
    return orig_pthread_sigmask ? orig_pthread_sigmask(how, set, oldset) : 0;
}

int rt_sigprocmask(int how, const sigset_t *set, sigset_t *oldset, size_t sigsetsize) {
    static int (*orig_rt_sigprocmask)(int, const sigset_t *, sigset_t *, size_t) = NULL;
    if (!orig_rt_sigprocmask) orig_rt_sigprocmask = (int (*)(int, const sigset_t *, sigset_t *, size_t))dlsym(RTLD_NEXT, "rt_sigprocmask");
    if (set && (how == SIG_BLOCK || how == SIG_SETMASK)) {
        sigset_t mod_set;
        memcpy(&mod_set, set, sizeof(mod_set));
        sigdelset(&mod_set, SIGSYS);
        return orig_rt_sigprocmask ? orig_rt_sigprocmask(how, &mod_set, oldset, sigsetsize) : 0;
    }
    return orig_rt_sigprocmask ? orig_rt_sigprocmask(how, set, oldset, sigsetsize) : 0;
}

#endif /* !DDNATV_HOST_TEST */
