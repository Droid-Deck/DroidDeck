// The code The native userland adds to a static Go program so that it sees the rootfs (see gosys_abi.h).
//
// Built freestanding (scripts/build-gosys.sh) as a flat, position-independent binary with no
// writable data, no libc and no relocations: the hook copies it into the program's file. It is
// entered from the one place where Go's system calls are made, in place of the "svc #0" of
// internal/runtime/syscall.Syscall6, with the registers of that call: arguments in x0 to x5,
// the number in x8 (passed again as the eighth argument). It returns what the kernel would, and it
// may use every register but x28 (the current goroutine), x29, x30 and sp, which it keeps.
//
// It does for Go what libddnatv-hook.so does for C programs: maps absolute Ubuntu paths into the
// rootfs (the same rules as hook/paths.c), shortens the Unix socket paths that do not fit, shows
// the program the paths it expects from getcwd and /proc/self/exe, and starts Ubuntu programs
// through the rootfs's dynamic linker and env, which then go through the hook.

#include "gosys_abi.h"

typedef int64_t i64;
typedef uint64_t u64;
typedef uint32_t u32;
typedef uint16_t u16;

#define AT_FDCWD (-100)
#define SLOT_PATH_A 0u
#define SLOT_PATH_B 4096u
#define SLOT_ARGV 8192u
#define SLOT_ENV 16384u
#define SLOT_PATH_CAP 4096u
#define ARGV_CAP 1000u   // entries of the argv array that starts a program
#define ENV_CAP 2000u

// The numbers of AArch64 Linux (the same for every kernel)
enum {
    NR_SETXATTR = 5, NR_LSETXATTR = 6, NR_GETXATTR = 8, NR_LGETXATTR = 9, NR_LISTXATTR = 11,
    NR_LLISTXATTR = 12, NR_REMOVEXATTR = 14, NR_LREMOVEXATTR = 15, NR_GETCWD = 17,
    NR_INOTIFY_ADD_WATCH = 27, NR_MKNODAT = 33, NR_MKDIRAT = 34, NR_UNLINKAT = 35, NR_SYMLINKAT = 36,
    NR_LINKAT = 37, NR_RENAMEAT = 38, NR_STATFS = 43, NR_TRUNCATE = 45, NR_FACCESSAT = 48,
    NR_CHDIR = 49, NR_CHROOT = 51, NR_FCHMODAT = 53, NR_FCHOWNAT = 54, NR_OPENAT = 56,
    NR_READLINKAT = 78, NR_NEWFSTATAT = 79, NR_UTIMENSAT = 88, NR_BIND = 200, NR_CONNECT = 203,
    NR_EXECVE = 221, NR_NAME_TO_HANDLE_AT = 264, NR_RENAMEAT2 = 276, NR_STATX = 291,
    NR_OPENAT2 = 437, NR_FACCESSAT2 = 439, NR_FCHMODAT2 = 452,
};

__attribute__((section(".hdr"), used))
static const volatile struct gosys_hdr g_hdr = { .magic = "CXGOSYS1", .version = GOSYS_VERSION };

// Filled by the patcher; the instructions that connect the call site to cxgo_entry
__attribute__((section(".thunks"), used))
static const volatile u32 g_thunks[GOSYS_THUNK_BYTES / 4];

__attribute__((section(".trailer"), used))
static const volatile struct gosys_trailer g_trailer = { .magic = "CXGOEND1", .version = GOSYS_VERSION };

static inline const struct gosys_hdr *hdr(void) {
    const struct gosys_hdr *h = (const struct gosys_hdr *)&g_hdr;
    __asm__("" : "+r"(h)); // the compiler must not assume it knows the contents
    return h;
}

static inline i64 sc(i64 nr, i64 a0, i64 a1, i64 a2, i64 a3, i64 a4, i64 a5) {
    register i64 x8 __asm__("x8") = nr;
    register i64 x0 __asm__("x0") = a0;
    register i64 x1 __asm__("x1") = a1;
    register i64 x2 __asm__("x2") = a2;
    register i64 x3 __asm__("x3") = a3;
    register i64 x4 __asm__("x4") = a4;
    register i64 x5 __asm__("x5") = a5;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory", "cc");
    return x0;
}

static u64 slen(const char *s) {
    u64 n = 0;
    while (s[n]) n++;
    return n;
}

static void mcopy(char *d, const char *s, u64 n) {
    for (u64 i = 0; i < n; i++) d[i] = s[i];
}

// Moves n bytes down (d < s)
static void mmove_down(char *d, const char *s, u64 n) {
    for (u64 i = 0; i < n; i++) d[i] = s[i];
}

static int pre(const char *path, const char *lit, u64 n) {
    for (u64 i = 0; i < n; i++)
        if (path[i] != lit[i]) return 0;
    return path[n] == '/' || path[n] == '\0';
}

// Whether path is the rootfs or in it
static int in_root(const struct gosys_hdr *h, const char *p) {
    u64 n = h->root_len;
    if (n == 0) return 0;
    for (u64 i = 0; i < n; i++)
        if (p[i] != h->root[i]) return 0;
    return p[n] == '/' || p[n] == '\0';
}

// Android's own mounts and files, which must never be redirected into the rootfs (hook/paths.c)
static int is_host_path(const char *path) {
    switch (path[1]) {
    case 'a': return pre(path, "/apex", 5);
    case 'c': return pre(path, "/config", 7);
    case 'd': return pre(path, "/dev", 4) || pre(path, "/d", 2) || pre(path, "/data", 5);
    case 'l': return pre(path, "/linkerconfig", 13);
    case 'o': return pre(path, "/odm", 4) || pre(path, "/oem", 4);
    case 'p': return pre(path, "/proc", 5) || pre(path, "/product", 8);
    case 's': return pre(path, "/sbin/su", 8) || pre(path, "/su", 3) || pre(path, "/sys", 4) ||
                      pre(path, "/system", 7) || pre(path, "/system_ext", 11) ||
                      pre(path, "/sdcard", 7) || pre(path, "/storage", 8);
    case 'v': return pre(path, "/vendor", 7);
    default:  return 0;
    }
}

// An absolute Ubuntu path mapped into the rootfs: `path` itself when it needs no change, else buf
// (hook/paths.c rewrite_path_in_root). A result that would not fit is the path unchanged: the
// kernel then answers as it would for a path that long.
static const char *rw_path(const struct gosys_hdr *h, const char *path, char *buf, u64 cap) {
    if (path[0] != '/' || h->root_len == 0) return path;
    while (path[0] == '/' && path[1] == '/') path++;
    while (path[0] == '/' && path[1] == '.' && (path[2] == '/' || path[2] == '\0')) {
        if (path[2] == '/') {
            path += 2;
        } else {
            path = "/";
            break;
        }
    }
    u64 rl = h->root_len;
    if (path[1] == '\0') {
        mcopy(buf, h->root, rl);
        buf[rl] = '\0';
        return buf;
    }
    if (in_root(h, path)) return path;
    if (pre(path, "/dev/shm", 8)) {
        u64 rest = slen(path + 8);
        if (rl + 8 + rest + 1 > cap) return path;
        mcopy(buf, h->root, rl);
        mcopy(buf + rl, "/tmp/shm", 8);
        mcopy(buf + rl + 8, path + 8, rest + 1);
        return buf;
    }
    if (is_host_path(path)) return path;
    u64 pl = slen(path);
    if (rl + pl + 1 > cap) return path;
    mcopy(buf, h->root, rl);
    mcopy(buf + rl, path, pl + 1);
    return buf;
}

// Whether the rootfs is where the code was told it is. In root mode the program runs in a chroot of
// that very rootfs, where the real paths are the Ubuntu ones and the rootfs's own path does not
// exist: then nothing is mapped and the program works as any other. Looked at once for each process
// (the scratch memory starts as zero, and a forked child keeps what its parent found).
static int rewriting(const struct gosys_hdr *h) {
    volatile u32 *mode = (volatile u32 *)((char *)h + h->scratch_rel + 4);
    u32 m = *mode;
    if (m == 0) {
        m = sc(NR_FACCESSAT, AT_FDCWD, (i64)h->root, 0, 0, 0, 0) == 0 ? 1u : 2u;
        *mode = m;
    }
    return m == 1;
}

#define NO_SLOT 0xffffffffu
#define NR_SCHED_YIELD 124

// Who is making the call: the goroutine (x28 holds it, and the code is built not to touch x28). A
// goroutine makes one call at a time, and the vfork child that a goroutine makes runs with the same
// x28 as the goroutine does. (TPIDR_EL0 does not tell the threads of a Go program apart: its runtime
// leaves it unset without cgo.)
static inline u64 thread_tag(void) {
    u64 t;
    __asm__ volatile("mov %0, x28" : "=r"(t));
    return t;
}

// How many execve calls have not returned (state area, offset 8). One that works never does: it is
// made by a vfork child, and the slot its path was in stays marked as the goroutine's, which goes on
// when the child is gone (the same x28, a call of its own). Without this, goroutines that each
// started a program held a slot each for good, and after the sixty-fourth all the others waited.
static inline u32 *execs_on_their_way(const struct gosys_hdr *h) {
    return (u32 *)((char *)h + h->scratch_rel + 8);
}

static void count_down(u32 *count) {
    u32 n = __atomic_load_n(count, __ATOMIC_RELAXED);
    while (n != 0 && !__atomic_compare_exchange_n(count, &n, n - 1, 0, __ATOMIC_RELAXED, __ATOMIC_RELAXED)) {
    }
}

// Gives back what this goroutine's vfork child left (see above). Every call looks at one counter
// for it; the list of slots is only looked through while an execve is on its way.
static void reclaim_left_slots(const struct gosys_hdr *h) {
    u32 *count = execs_on_their_way(h);
    if (__atomic_load_n(count, __ATOMIC_RELAXED) == 0) return;
    u64 *owner = (u64 *)((char *)h + h->scratch_rel + 64);
    u64 me = thread_tag() | 1;
    for (u32 i = 0; i < GOSYS_SLOTS; i++) {
        if (__atomic_load_n(&owner[i], __ATOMIC_RELAXED) != me) continue;
        __atomic_store_n(&owner[i], 0, __ATOMIC_RELEASE);
        count_down(count);
    }
}

// A slot for the call this goroutine is making; *held is its number, to give back when the call is
// done. Each slot has a word of its own that holds who has it (0: nobody), so that taking one is one
// compare-and-swap, and no other goroutine can ever mistake it for its own.
static char *take_slot(const struct gosys_hdr *h, u32 *held) {
    char *base = (char *)h + h->scratch_rel;
    u64 *owner = (u64 *)(base + 64);
    u64 me = thread_tag() | 1; // never 0
    u32 mine = NO_SLOT;
    // A slot of this goroutine that is still taken now (cxgo_entry gives back those of its vfork
    // children) is its own again
    for (u32 i = 0; i < GOSYS_SLOTS; i++) {
        if (__atomic_load_n(&owner[i], __ATOMIC_RELAXED) != me) continue;
        if (mine == NO_SLOT)
            mine = i;
        else
            __atomic_store_n(&owner[i], 0, __ATOMIC_RELEASE);
    }
    while (mine == NO_SLOT) {
        for (u32 i = 0; i < GOSYS_SLOTS && mine == NO_SLOT; i++) {
            u64 none = 0;
            if (__atomic_load_n(&owner[i], __ATOMIC_RELAXED) == 0 &&
                __atomic_compare_exchange_n(&owner[i], &none, me, 0, __ATOMIC_ACQUIRE, __ATOMIC_RELAXED))
                mine = i;
        }
        if (mine == NO_SLOT) sc(NR_SCHED_YIELD, 0, 0, 0, 0, 0, 0); // all taken: the others finish soon
    }
    *held = mine;
    return base + GOSYS_STATE_SIZE + (u64)mine * GOSYS_SLOT_SIZE;
}

static void put_slot(const struct gosys_hdr *h, u32 held) {
    if (held == NO_SLOT) return;
    char *base = (char *)h + h->scratch_rel;
    __atomic_store_n((u64 *)(base + 64) + held, 0, __ATOMIC_RELEASE);
}

// A path as the program expects to see it, from one of the kernel's: the rootfs prefix removed.
// Returns the new length of the n bytes at buf (a path, not NUL-terminated unless `nul`).
static i64 unmap_prefix(const struct gosys_hdr *h, char *buf, i64 n, int nul) {
    u64 rl = h->root_len;
    if (rl == 0 || n <= 0 || (u64)n < rl + (nul ? 1 : 0)) return n;
    for (u64 i = 0; i < rl; i++)
        if (buf[i] != h->root[i]) return n;
    u64 end = (u64)n - (nul ? 1 : 0); // the path's own length
    if (end == rl) { // exactly the rootfs
        buf[0] = '/';
        if (nul) {
            buf[1] = '\0';
            return 2;
        }
        return 1;
    }
    if (buf[rl] != '/') return n;
    mmove_down(buf, buf + rl, (u64)n - rl);
    return n - (i64)rl;
}

// ---- Unix sockets ------------------------------------------------------------------------

static void put_hex16(char *out, u64 v) {
    static const char digits[] = "0123456789abcdef";
    for (int i = 15; i >= 0; i--) {
        out[i] = digits[v & 15];
        v >>= 4;
    }
}

// bind() and connect() of a Unix socket with a path: mapped like hook/setup.c does, and hashed
// into /tmp when it does not fit the 108 bytes of sun_path (the same name, so a Go server and a
// program using the C library find each other)
static i64 with_sockaddr(const struct gosys_hdr *h, u32 *held, i64 nr, i64 a0, i64 a1, i64 a2) {
    const unsigned char *sa = (const unsigned char *)a1;
    if (!sa || a2 < 4 || a2 > 128 || sa[0] != 1 /* AF_UNIX */ || sa[1] != 0 || sa[2] != '/')
        return sc(nr, a0, a1, a2, 0, 0, 0);
    char *slot = take_slot(h, held);
    char *orig = slot + SLOT_PATH_A;
    u64 max = (u64)a2 - 2;
    if (max > 108) max = 108;
    mcopy(orig, (const char *)sa + 2, max);
    orig[max] = '\0';
    const char *rw = rw_path(h, orig, slot + SLOT_PATH_B, SLOT_PATH_CAP);
    u64 rl = slen(rw);
    char *out = slot + SLOT_ARGV;
    out[0] = 1;
    out[1] = 0;
    if (rl < 108) {
        mcopy(out + 2, rw, rl + 1);
        return sc(nr, a0, (i64)out, (i64)(2 + rl + 1), 0, 0, 0);
    }
    u64 hash = 14695981039346656037ULL;
    for (const char *p = rw; *p; p++) {
        hash ^= (unsigned char)*p;
        hash *= 1099511628211ULL;
    }
    u64 n = h->root_len;
    if (n + 5 + 11 + 16 + 1 > 108) return sc(nr, a0, a1, a2, 0, 0, 0);
    mcopy(out + 2, h->root, n);
    mcopy(out + 2 + n, "/tmp/.ctx_sock_", 15);
    put_hex16(out + 2 + n + 15, hash);
    out[2 + n + 15 + 16] = '\0';
    return sc(nr, a0, (i64)out, (i64)(2 + n + 15 + 16 + 1), 0, 0, 0);
}

// ---- Starting programs -------------------------------------------------------------------

static int starts_with(const char *s, const char *lit) {
    for (u64 i = 0; lit[i]; i++)
        if (s[i] != lit[i]) return 0;
    return 1;
}

// A program of Android's gets the environment it expects, as from hook/env.c clean_env_for_system():
// without the hook and the rootfs's libraries, and with Android's directories in its PATH
static i64 exec_host(const struct gosys_hdr *h, char *slot, const char *p, i64 a1, char *const *envp) {
    (void)h;
    static const char android_path[] = "PATH=/system/bin:/system/xbin:/sbin:/vendor/bin";
    u32 envc = 0;
    while (envp && envp[envc]) envc++;
    if (envc + 2 > ENV_CAP) return sc(NR_EXECVE, (i64)p, a1, (i64)envp, 0, 0, 0);
    char **ne = (char **)(slot + SLOT_ENV);
    char *path_str = slot + SLOT_PATH_B;
    u32 n = 0;
    int has_path = 0;
    for (u32 i = 0; i < envc; i++) {
        char *e = envp[i];
        if (starts_with(e, "LD_PRELOAD=") || starts_with(e, "LD_LIBRARY_PATH=") || starts_with(e, "GLIBC_TUNABLES="))
            continue;
        if (starts_with(e, "PATH=")) {
            u64 len = slen(e);
            has_path = 1;
            if (len + sizeof(android_path) + 1 < SLOT_PATH_CAP) {
                mcopy(path_str, android_path, sizeof(android_path) - 1);
                path_str[sizeof(android_path) - 1] = ':';
                mcopy(path_str + sizeof(android_path), e + 5, len - 5 + 1);
                ne[n++] = path_str;
                continue;
            }
        }
        ne[n++] = e;
    }
    if (!has_path) ne[n++] = (char *)android_path;
    ne[n] = 0;
    return sc(NR_EXECVE, (i64)p, a1, (i64)ne, 0, 0, 0);
}

// execve(): a program of the rootfs is started as
//   <ld.so> --preload <hook> --library-path <dirs> <bash> -c 'exec -a "$0" "$@"' <argv0> <path> <args>...
// so that bash, a program of the rootfs with the hook loaded, starts it the way every program of
// The native userland is started (Ubuntu's dynamic programs through ld.so, scripts through their interpreter,
// static programs patched first), with the argv[0] the caller chose. Without bash it is env,
// which cannot set argv[0]. The path is passed as the caller wrote it.
static i64 with_execve(const struct gosys_hdr *h, u32 *held, i64 a0, i64 a1, i64 a2) {
    const char *path = (const char *)a0;
    char *const *argv = (char *const *)a1;
    char *const *envp = (char *const *)a2;
    if (!path || path[0] == '\0') return sc(NR_EXECVE, a0, a1, a2, 0, 0, 0);

    char *slot = take_slot(h, held);
    const char *p = path[0] == '/' ? rw_path(h, path, slot + SLOT_PATH_A, SLOT_PATH_CAP) : path;
    int wrap = h->ldso_len != 0 && h->runner_len != 0 && (path[0] != '/' || in_root(h, p));
    if (!wrap) {
        if (path[0] == '/' && !in_root(h, p)) return exec_host(h, slot, p, a1, envp);
        return sc(NR_EXECVE, (i64)p, a1, a2, 0, 0, 0);
    }

    // A program that is not there is reported to the caller, as execve() does
    i64 r = sc(NR_FACCESSAT, AT_FDCWD, (i64)p, 0, 0, 0, 0);
    if (r < 0) return r;

    u32 argc = 0;
    while (argv && argv[argc]) argc++;
    if (argc + 12 > ARGV_CAP) return sc(NR_EXECVE, (i64)p, a1, a2, 0, 0, 0);

    // DDNATV_ROOT must reach env even when the caller built an environment of its own
    char *const *env = envp;
    u32 envc = 0;
    int has_root = 0;
    while (envp && envp[envc]) {
        const char *e = envp[envc];
        if (e[0] == 'D' && e[1] == 'D' && e[2] == 'N' && e[3] == 'A' && e[4] == 'T' && e[5] == 'V' &&
            e[6] == '_' && e[7] == 'R' && e[8] == 'O' && e[9] == 'O' && e[10] == 'T' && e[11] == '=')
            has_root = 1;
        envc++;
    }
    if (!has_root) {
        if (envc + 2 > ENV_CAP) return sc(NR_EXECVE, (i64)p, a1, a2, 0, 0, 0);
        char **ne = (char **)(slot + SLOT_ENV);
        char *kv = slot + SLOT_PATH_B;
        mcopy(kv, "DDNATV_ROOT=", 12);
        mcopy(kv + 12, h->root, h->root_len + 1);
        for (u32 i = 0; i < envc; i++) ne[i] = envp[i];
        ne[envc] = kv;
        ne[envc + 1] = 0;
        env = ne;
    }

    char **v = (char **)(slot + SLOT_ARGV);
    u32 n = 0;
    v[n++] = (char *)h->ldso;
    if (h->hook_len) {
        v[n++] = (char *)"--preload";
        v[n++] = (char *)h->hook;
    }
    v[n++] = (char *)"--library-path";
    v[n++] = (char *)h->libs;
    v[n++] = (char *)h->runner;
    if (h->flags & GOSYS_FLAG_RUNNER_IS_BASH) {
        v[n++] = (char *)"-c";
        v[n++] = (char *)"exec -a \"$0\" \"$@\"";
        v[n++] = (argc > 0 && argv[0]) ? argv[0] : (char *)path;
    } else {
        v[n++] = (char *)"--";
    }
    v[n++] = (char *)path;
    for (u32 i = 1; i < argc; i++) v[n++] = argv[i];
    v[n] = 0;
    return sc(NR_EXECVE, (i64)h->ldso, (i64)v, (i64)env, 0, 0, 0);
}

// ---- The entry ---------------------------------------------------------------------------

// Rewrites the path arguments `i1` and `i2` (an index into a[], -1: none) that are absolute, makes
// the call, and shows the program the paths it expects in what the call returns.
static i64 with_paths(const struct gosys_hdr *h, u32 *held, i64 nr, i64 *a, int i1, int i2) {
    char *slot = 0;
    int proc_link = 0;
    int idx[2] = { i1, i2 };
    for (int k = 0; k < 2; k++) {
        int i = idx[k];
        if (i < 0) continue;
        const char *p = (const char *)a[i];
        if (!p || p[0] != '/') continue;
        if (!slot) slot = take_slot(h, held);
        if (nr == NR_READLINKAT && k == 0 && pre(p, "/proc", 5)) proc_link = 1;
        a[i] = (i64)rw_path(h, p, slot + (k ? SLOT_PATH_B : SLOT_PATH_A), SLOT_PATH_CAP);
    }
    i64 r = sc(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
    if (proc_link && r > 0) r = unmap_prefix(h, (char *)a[2], r, 0);
    return r;
}

// The call itself; a slot that it takes is given back by cxgo_entry
static i64 handle(const struct gosys_hdr *h, u32 *held, i64 nr, i64 a0, i64 a1, i64 a2, i64 a3, i64 a4, i64 a5) {
    i64 a[6] = { a0, a1, a2, a3, a4, a5 };
    switch (nr) {
    case NR_OPENAT: case NR_FACCESSAT: case NR_FACCESSAT2: case NR_FCHMODAT: case NR_FCHMODAT2:
    case NR_FCHOWNAT: case NR_NEWFSTATAT: case NR_STATX: case NR_READLINKAT: case NR_MKDIRAT:
    case NR_MKNODAT: case NR_UNLINKAT: case NR_UTIMENSAT: case NR_OPENAT2: case NR_NAME_TO_HANDLE_AT:
    case NR_INOTIFY_ADD_WATCH: case NR_CHDIR: case NR_CHROOT: case NR_STATFS: case NR_TRUNCATE:
    case NR_SETXATTR: case NR_LSETXATTR: case NR_GETXATTR: case NR_LGETXATTR: case NR_LISTXATTR:
    case NR_LLISTXATTR: case NR_REMOVEXATTR: case NR_LREMOVEXATTR: case NR_RENAMEAT: case NR_RENAMEAT2:
    case NR_LINKAT: case NR_SYMLINKAT: case NR_GETCWD: case NR_BIND: case NR_CONNECT: case NR_EXECVE:
        // The calls that carry paths: not mapped where the rootfs is not in its own place (above)
        if (!rewriting(h)) return sc(nr, a0, a1, a2, a3, a4, a5);
        break;
    default:
        return sc(nr, a0, a1, a2, a3, a4, a5);
    }
    switch (nr) {
    case NR_OPENAT: case NR_FACCESSAT: case NR_FACCESSAT2: case NR_FCHMODAT: case NR_FCHMODAT2:
    case NR_FCHOWNAT: case NR_NEWFSTATAT: case NR_STATX: case NR_READLINKAT: case NR_MKDIRAT:
    case NR_MKNODAT: case NR_UNLINKAT: case NR_UTIMENSAT: case NR_OPENAT2: case NR_NAME_TO_HANDLE_AT:
    case NR_INOTIFY_ADD_WATCH:
        return with_paths(h, held, nr, a, 1, -1);
    case NR_CHDIR: case NR_CHROOT: case NR_STATFS: case NR_TRUNCATE: case NR_SETXATTR:
    case NR_LSETXATTR: case NR_GETXATTR: case NR_LGETXATTR: case NR_LISTXATTR: case NR_LLISTXATTR:
    case NR_REMOVEXATTR: case NR_LREMOVEXATTR:
        return with_paths(h, held, nr, a, 0, -1);
    case NR_RENAMEAT: case NR_RENAMEAT2: case NR_LINKAT:
        return with_paths(h, held, nr, a, 1, 3);
    case NR_SYMLINKAT: // the target is data, only the link is a path
        return with_paths(h, held, nr, a, 2, -1);
    case NR_GETCWD: {
        i64 r = sc(nr, a0, a1, 0, 0, 0, 0);
        return r > 0 ? unmap_prefix(h, (char *)a0, r, 1) : r;
    }
    case NR_BIND: case NR_CONNECT:
        return with_sockaddr(h, held, nr, a0, a1, a2);
    case NR_EXECVE: {
        // Counted while it is on its way: when it works it does not return (see execs_on_their_way)
        u32 *count = execs_on_their_way(h);
        __atomic_fetch_add(count, 1, __ATOMIC_RELAXED);
        i64 r = with_execve(h, held, a0, a1, a2);
        count_down(count); // it did return: it failed, and cxgo_entry gives its slot back
        return r;
    }
    default:
        return sc(nr, a0, a1, a2, a3, a4, a5);
    }
}

__attribute__((section(".text.cxgo_entry"), used, noinline))
i64 cxgo_entry(i64 a0, i64 a1, i64 a2, i64 a3, i64 a4, i64 a5, i64 unused, i64 nr) {
    (void)unused;
    const struct gosys_hdr *h = hdr();
    u32 held = NO_SLOT;
    reclaim_left_slots(h);
    i64 r = handle(h, &held, nr, a0, a1, a2, a3, a4, a5);
    put_slot(h, held);
    return r;
}
