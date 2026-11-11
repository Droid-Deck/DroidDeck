// Part of libddnatv-hook.so, included by ddnatv-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// The system calls that Android's seccomp filter answers with SIGSYS, and which of them the
// SIGSYS handler (signals.c) turns into a fake success.

static int is_sigsys_synthetic_success_syscall(int sys_nr) {
#if defined(__NR_setuid)
    if (sys_nr == __NR_setuid) return 1;
#endif
#if defined(__NR_setuid32)
    if (sys_nr == __NR_setuid32) return 1;
#endif
#if defined(__NR_setgid)
    if (sys_nr == __NR_setgid) return 1;
#endif
#if defined(__NR_setgid32)
    if (sys_nr == __NR_setgid32) return 1;
#endif
#if defined(__NR_setreuid)
    if (sys_nr == __NR_setreuid) return 1;
#endif
#if defined(__NR_setreuid32)
    if (sys_nr == __NR_setreuid32) return 1;
#endif
#if defined(__NR_setregid)
    if (sys_nr == __NR_setregid) return 1;
#endif
#if defined(__NR_setregid32)
    if (sys_nr == __NR_setregid32) return 1;
#endif
#if defined(__NR_setresuid)
    if (sys_nr == __NR_setresuid) return 1;
#endif
#if defined(__NR_setresuid32)
    if (sys_nr == __NR_setresuid32) return 1;
#endif
#if defined(__NR_setresgid)
    if (sys_nr == __NR_setresgid) return 1;
#endif
#if defined(__NR_setresgid32)
    if (sys_nr == __NR_setresgid32) return 1;
#endif
#if defined(__NR_setfsuid)
    if (sys_nr == __NR_setfsuid) return 1;
#endif
#if defined(__NR_setfsuid32)
    if (sys_nr == __NR_setfsuid32) return 1;
#endif
#if defined(__NR_setfsgid)
    if (sys_nr == __NR_setfsgid) return 1;
#endif
#if defined(__NR_setfsgid32)
    if (sys_nr == __NR_setfsgid32) return 1;
#endif
#if defined(__NR_setgroups)
    if (sys_nr == __NR_setgroups) return 1;
#endif
#if defined(__NR_setgroups32)
    if (sys_nr == __NR_setgroups32) return 1;
#endif
#if defined(__NR_set_robust_list)
    if (sys_nr == __NR_set_robust_list) return 1;
#endif
#if defined(__NR_get_robust_list)
    if (sys_nr == __NR_get_robust_list) return 1;
#endif
    return 0;
}

static int is_handled_sigsys_syscall(int sys_nr) {
    if (is_sigsys_synthetic_success_syscall(sys_nr)) {
        return 1;
    }

    // Unified Linux 5.1+ syscall numbers across architectures
    if (sys_nr >= 424 && sys_nr < 512) {
        return 1;
    }

    // Architecture-specific syscall numbers guarded per target ABI
#if defined(__aarch64__)
    if (sys_nr == 97  /* __NR_unshare */ ||
        sys_nr == 217 /* __NR_add_key */ ||
        sys_nr == 218 /* __NR_request_key */ ||
        sys_nr == 219 /* __NR_keyctl */ ||
        sys_nr == 268 /* __NR_setns */ ||
        sys_nr == 277 /* __NR_seccomp */ ||
        sys_nr == 280 /* __NR_bpf */ ||
        sys_nr == 293 /* __NR_rseq */) {
        return 1;
    }
#elif defined(__arm__)
    if (sys_nr == 309 /* __NR_add_key */ ||
        sys_nr == 310 /* __NR_request_key */ ||
        sys_nr == 311 /* __NR_keyctl */ ||
        sys_nr == 337 /* __NR_unshare */ ||
        sys_nr == 375 /* __NR_setns */ ||
        sys_nr == 383 /* __NR_seccomp */ ||
        sys_nr == 386 /* __NR_bpf */ ||
        sys_nr == 398 /* __NR_rseq */) {
        return 1;
    }
#elif defined(__x86_64__)
    if (sys_nr == 248 /* __NR_add_key */ ||
        sys_nr == 249 /* __NR_request_key */ ||
        sys_nr == 250 /* __NR_keyctl */ ||
        sys_nr == 272 /* __NR_unshare */ ||
        sys_nr == 308 /* __NR_setns */ ||
        sys_nr == 317 /* __NR_seccomp */ ||
        sys_nr == 321 /* __NR_bpf */ ||
        sys_nr == 334 /* __NR_rseq */) {
        return 1;
    }
#elif defined(__i386__)
    if (sys_nr == 286 /* __NR_add_key */ ||
        sys_nr == 287 /* __NR_request_key */ ||
        sys_nr == 288 /* __NR_keyctl */ ||
        sys_nr == 310 /* __NR_unshare */ ||
        sys_nr == 346 /* __NR_setns */ ||
        sys_nr == 354 /* __NR_seccomp */ ||
        sys_nr == 357 /* __NR_bpf */ ||
        sys_nr == 386 /* __NR_rseq */) {
        return 1;
    }
#endif
    return 0;
}
