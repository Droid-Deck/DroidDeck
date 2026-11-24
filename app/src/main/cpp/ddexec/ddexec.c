// libddexec.so: starts an ELF program from the app's data directory.
//
// usage: libddexec.so <file> <argv0> [args...]
//
// From targetSdk 29, Android refuses execve() of app data files, but still lets the app map them
// executable, and executes the APK's extracted libraries. This executable is one of those libraries:
// it maps <file> (and its PT_INTERP, if it names one) the way the kernel's ELF loader would, builds the
// stack the kernel would have built for "<argv0> [args...]", and jumps to the entry point.
// It is freestanding: no libc, no relocations, so the kernel can run it as a static PIE.

#include <elf.h>
#include <stddef.h>
#include <stdint.h>

#define SYS_faccessat 48
#define SYS_openat 56
#define SYS_close 57
#define SYS_pread64 67
#define SYS_write 64
#define SYS_exit_group 94
#define SYS_munmap 215
#define SYS_mmap 222
#define SYS_mprotect 226
#define SYS_getpid 172

#define AT_FDCWD_ (-100)
#define O_RDONLY_ 0
#define O_CLOEXEC_ 02000000
#define PROT_READ_ 1
#define PROT_WRITE_ 2
#define PROT_EXEC_ 4
#define MAP_PRIVATE_ 0x02
#define MAP_FIXED_ 0x10
#define MAP_ANONYMOUS_ 0x20
#define MAX_PHDRS 64
#define STACK_GAP 65536

static long sys6(long n, long a, long b, long c, long d, long e, long f) {
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory", "cc");
    return x0;
}

static size_t slen(const char *s) {
    size_t n = 0;
    while (s[n]) n++;
    return n;
}

static int seq(const char *a, const char *b) {
    while (*a && *a == *b) a++, b++;
    return *a == *b;
}

static const char *env_get(char **envp, const char *name) {
    size_t n = slen(name);
    for (; *envp; envp++) {
        size_t i = 0;
        while (i < n && (*envp)[i] == name[i]) i++;
        if (i == n && (*envp)[n] == '=') return *envp + n + 1;
    }
    return 0;
}

static void out(const char *s) { sys6(SYS_write, 2, (long)s, (long)slen(s), 0, 0, 0); }

static void out_num(long v) {
    char buf[24];
    int i = (int)sizeof(buf) - 1;
    buf[i] = 0;
    unsigned long u = v < 0 ? (unsigned long)-v : (unsigned long)v;
    do {
        buf[--i] = (char)('0' + u % 10);
        u /= 10;
    } while (u && i > 1);
    if (v < 0) buf[--i] = '-';
    out(buf + i);
}

__attribute__((noreturn)) static void die(const char *what, const char *path, long err) {
    out("libddexec: ");
    if (path) {
        out(path);
        out(": ");
    }
    out(what);
    if (err) {
        out(" (");
        out_num(err);
        out(")");
    }
    out("\n");
    sys6(SYS_exit_group, 127, 0, 0, 0, 0, 0);
    __builtin_unreachable();
}

static long xpread(int fd, void *buf, size_t len, long off) {
    size_t done = 0;
    while (done < len) {
        long n = sys6(SYS_pread64, fd, (long)((char *)buf + done), (long)(len - done), off + (long)done, 0, 0);
        if (n <= 0) return n < 0 ? n : -1;
        done += (size_t)n;
    }
    return (long)done;
}

struct image {
    uintptr_t base;
    uintptr_t phdr;
    uintptr_t entry;
    unsigned long phnum;
    unsigned long phent;
    char interp[256];
};

static uintptr_t trunc_page(uintptr_t v, uintptr_t page) { return v & ~(page - 1); }
static uintptr_t round_page(uintptr_t v, uintptr_t page) { return (v + page - 1) & ~(page - 1); }

static int prot_of(uint32_t flags) {
    return ((flags & PF_R) ? PROT_READ_ : 0) | ((flags & PF_W) ? PROT_WRITE_ : 0) | ((flags & PF_X) ? PROT_EXEC_ : 0);
}

static void map_failed(const char *path, long r) { die("cannot map a segment", path, r); }

static void read_interp(int fd, const Elf64_Phdr *ph, int phnum, char *interp, size_t size, const char *path) {
    interp[0] = 0;
    for (int i = 0; i < phnum; i++) {
        if (ph[i].p_type != PT_INTERP) continue;
        if (ph[i].p_filesz == 0 || ph[i].p_filesz >= size) die("bad PT_INTERP", path, 0);
        if (xpread(fd, interp, ph[i].p_filesz, (long)ph[i].p_offset) != (long)ph[i].p_filesz)
            die("truncated PT_INTERP", path, 0);
        interp[ph[i].p_filesz] = 0;
    }
}

static int read_headers(const char *path, Elf64_Ehdr *eh, Elf64_Phdr *ph) {
    int fd = (int)sys6(SYS_openat, AT_FDCWD_, (long)path, O_RDONLY_ | O_CLOEXEC_, 0, 0, 0);
    if (fd < 0) die("cannot open", path, fd);
    if (xpread(fd, eh, sizeof(*eh), 0) != (long)sizeof(*eh)) die("not an ELF file", path, 0);
    if (eh->e_ident[EI_MAG0] != ELFMAG0 || eh->e_ident[EI_MAG1] != ELFMAG1 || eh->e_ident[EI_MAG2] != ELFMAG2 ||
        eh->e_ident[EI_MAG3] != ELFMAG3 || eh->e_ident[EI_CLASS] != ELFCLASS64 || eh->e_machine != EM_AARCH64 ||
        (eh->e_type != ET_EXEC && eh->e_type != ET_DYN) || eh->e_phentsize != sizeof(Elf64_Phdr) ||
        eh->e_phnum == 0 || eh->e_phnum > MAX_PHDRS)
        die("not a 64-bit ARM ELF program", path, 0);
    long size = (long)eh->e_phnum * (long)sizeof(Elf64_Phdr);
    if (xpread(fd, ph, (size_t)size, (long)eh->e_phoff) != size) die("truncated program headers", path, 0);
    return fd;
}

static void interp_of(const char *path, char *interp, size_t size) {
    Elf64_Ehdr eh;
    Elf64_Phdr ph[MAX_PHDRS];
    int fd = read_headers(path, &eh, ph);
    read_interp(fd, ph, eh.e_phnum, interp, size, path);
    sys6(SYS_close, fd, 0, 0, 0, 0, 0);
}

static void load(const char *path, struct image *im, uintptr_t page) {
    Elf64_Ehdr eh;
    Elf64_Phdr ph[MAX_PHDRS];
    int fd = read_headers(path, &eh, ph);

    uintptr_t lo = UINTPTR_MAX, hi = 0;
    for (int i = 0; i < eh.e_phnum; i++) {
        if (ph[i].p_type != PT_LOAD) continue;
        uintptr_t s = trunc_page(ph[i].p_vaddr, page), e = round_page(ph[i].p_vaddr + ph[i].p_memsz, page);
        if (s < lo) lo = s;
        if (e > hi) hi = e;
    }
    if (lo >= hi) die("no loadable segments", path, 0);

    uintptr_t bias = 0;
    if (eh.e_type == ET_DYN) {
        long r = sys6(SYS_mmap, 0, (long)(hi - lo), 0, MAP_PRIVATE_ | MAP_ANONYMOUS_, -1, 0);
        if (r < 0 && r > -4096) map_failed(path, r);
        bias = (uintptr_t)r - lo;
    }

    for (int i = 0; i < eh.e_phnum; i++) {
        Elf64_Phdr *p = &ph[i];
        if (p->p_type != PT_LOAD || p->p_memsz == 0) continue;
        int prot = prot_of(p->p_flags);
        uintptr_t seg = bias + p->p_vaddr;
        uintptr_t start = trunc_page(seg, page);
        uintptr_t file_end = seg + p->p_filesz;
        uintptr_t mem_end = round_page(seg + p->p_memsz, page);
        int zero_tail = p->p_memsz > p->p_filesz && (file_end & (page - 1)) != 0;
        if (p->p_filesz > 0) {
            uintptr_t len = round_page(file_end, page) - start;
            long r = sys6(SYS_mmap, (long)start, (long)len, prot | (zero_tail ? PROT_WRITE_ : 0),
                          MAP_PRIVATE_ | MAP_FIXED_, fd, (long)trunc_page(p->p_offset, page));
            if (r < 0 && r > -4096) map_failed(path, r);
            if (zero_tail) {
                for (volatile char *z = (volatile char *)file_end; (uintptr_t)z < round_page(file_end, page); z++) *z = 0;
                if (!(prot & PROT_WRITE_)) sys6(SYS_mprotect, (long)trunc_page(file_end, page), (long)page, prot, 0, 0, 0);
            }
        }
        uintptr_t anon = p->p_filesz > 0 ? round_page(file_end, page) : start;
        if (mem_end > anon) {
            long r = sys6(SYS_mmap, (long)anon, (long)(mem_end - anon), prot, MAP_PRIVATE_ | MAP_FIXED_ | MAP_ANONYMOUS_, -1, 0);
            if (r < 0 && r > -4096) map_failed(path, r);
        }
    }

    im->base = bias;
    im->entry = bias + eh.e_entry;
    im->phnum = eh.e_phnum;
    im->phent = eh.e_phentsize;
    im->phdr = 0;
    read_interp(fd, ph, eh.e_phnum, im->interp, sizeof(im->interp), path);
    for (int i = 0; i < eh.e_phnum; i++)
        if (ph[i].p_type == PT_PHDR) im->phdr = bias + ph[i].p_vaddr;
    if (!im->phdr) {
        for (int i = 0; i < eh.e_phnum; i++) {
            Elf64_Phdr *p = &ph[i];
            if (p->p_type == PT_LOAD && eh.e_phoff >= p->p_offset && eh.e_phoff < p->p_offset + p->p_filesz) {
                im->phdr = bias + p->p_vaddr + (eh.e_phoff - p->p_offset);
                break;
            }
        }
    }
    if (!im->phdr) die("program headers are not in a loaded segment", path, 0);
    sys6(SYS_close, fd, 0, 0, 0, 0, 0);
}

static unsigned long auxv_get(const uintptr_t *auxv, unsigned long type, unsigned long fallback) {
    for (; auxv[0] != AT_NULL; auxv += 2)
        if (auxv[0] == type) return auxv[1];
    return fallback;
}

static int is_self_exe(const char *execfn) {
    if (!execfn) return 0;
    if (seq(execfn, "/proc/self/exe") || seq(execfn, "/proc/thread-self/exe")) return 1;
    const char *p = "/proc/";
    while (*p && *p == *execfn) p++, execfn++;
    if (*p) return 0;
    char num[24];
    int i = (int)sizeof(num) - 1;
    num[i] = 0;
    unsigned long pid = (unsigned long)sys6(SYS_getpid, 0, 0, 0, 0, 0, 0);
    do {
        num[--i] = (char)('0' + pid % 10);
        pid /= 10;
    } while (pid && i > 0);
    const char *d = num + i;
    while (*d && *d == *execfn) d++, execfn++;
    return !*d && seq(execfn, "/exe");
}

static const char *guest_interp(char **envp, const char *interp, char *buf, size_t size) {
    const char *root = env_get(envp, "DDNATV_ROOT");
    if (!root || interp[0] != '/') return interp;
    size_t a = slen(root), b = slen(interp);
    if (a + b + 1 > size) return interp;
    for (size_t i = 0; i < a; i++) buf[i] = root[i];
    for (size_t i = 0; i <= b; i++) buf[a + i] = interp[i];
    if (sys6(SYS_faccessat, AT_FDCWD_, (long)buf, 0, 0, 0, 0) != 0) return interp;
    return buf;
}

__attribute__((used, noreturn)) void ddexec_main(uintptr_t *sp) {
    long argc = (long)sp[0];
    char **argv = (char **)(sp + 1);
    char **envp = argv + argc + 1;
    long nenv = 0;
    while (envp[nenv]) nenv++;
    uintptr_t *auxv = (uintptr_t *)(envp + nenv + 1);
    long naux = 0;
    while (auxv[naux * 2] != AT_NULL) naux++;
    int reexec = is_self_exe((const char *)auxv_get(auxv, AT_EXECFN, 0));
    const char *path;
    char **args = argv + 2;
    long nargs = argc - 2;
    char interp_buf[4096];
    char hook_buf[4096];
    if (reexec) {
        path = env_get(envp, "DDNATV_REAL_EXE");
        if (!path || !path[0]) die("re-executed without DDNATV_REAL_EXE", 0, 0);
        args = argv;
        nargs = argc;
        const char *lib_path = env_get(envp, "DDNATV_LIBRARY_PATH");
        char prog_interp[256];
        interp_of(path, prog_interp, sizeof(prog_interp));
        if (prog_interp[0] && lib_path && lib_path[0]) {
            const char *ld_so = guest_interp(envp, prog_interp, interp_buf, sizeof(interp_buf));
            const char *hook = guest_interp(envp, "/usr/lib/libddnatv-hook.so", hook_buf, sizeof(hook_buf));
            if (hook != (const char *)hook_buf) hook = guest_interp(envp, "/lib/libddnatv-hook.so", hook_buf, sizeof(hook_buf));
            long n = argc > 1 ? argc - 1 : 0;
            char **v = (char **)__builtin_alloca((size_t)(n + 9) * sizeof(char *));
            long k = 0;
            v[k++] = (char *)ld_so;
            if (hook == (const char *)hook_buf) {
                v[k++] = (char *)"--preload";
                v[k++] = (char *)hook;
            }
            v[k++] = (char *)"--library-path";
            v[k++] = (char *)lib_path;
            v[k++] = (char *)"--argv0";
            v[k++] = argc > 0 ? argv[0] : (char *)path;
            v[k++] = (char *)path;
            for (long i = 0; i < n; i++) v[k++] = argv[1 + i];
            v[k] = 0;
            args = v;
            nargs = k;
            path = ld_so;
        }
    } else if (argc < 3) {
        die("usage: libddexec.so <file> <argv0> [args...]", 0, 0);
    } else {
        path = argv[1];
    }

    uintptr_t page = auxv_get(auxv, AT_PAGESZ, 4096);
    struct image prog, interp;
    load(path, &prog, page);
    int has_interp = prog.interp[0] != 0;
    if (has_interp) load(reexec ? guest_interp(envp, prog.interp, interp_buf, sizeof(interp_buf)) : prog.interp, &interp, page);

    // The new stack: argc, argv, envp and auxv as the kernel lays them out, below the current one.
    // The strings stay where the kernel put them.
    static const unsigned long replaced[] = {AT_PHDR, AT_PHENT, AT_PHNUM, AT_BASE, AT_ENTRY, AT_EXECFN};
    long nreplaced = (long)(sizeof(replaced) / sizeof(replaced[0]));
    long new_argc = nargs;
    long words = 1 + (new_argc + 1) + (nenv + 1) + (naux + nreplaced + 1) * 2;
    uintptr_t top = (uintptr_t)args < (uintptr_t)sp ? (uintptr_t)args : (uintptr_t)sp;
    uintptr_t *nsp = (uintptr_t *)((top - STACK_GAP - (uintptr_t)words * sizeof(uintptr_t)) & ~(uintptr_t)15);
    uintptr_t *w = nsp;
    *w++ = (uintptr_t)new_argc;
    for (long i = 0; i < new_argc; i++) *w++ = (uintptr_t)args[i];
    *w++ = 0;
    for (long i = 0; i < nenv; i++) *w++ = (uintptr_t)envp[i];
    *w++ = 0;
    for (long i = 0; i < naux; i++) {
        unsigned long type = auxv[i * 2];
        int skip = 0;
        for (long r = 0; r < nreplaced; r++)
            if (type == replaced[r]) skip = 1;
        if (skip) continue;
        *w++ = type;
        *w++ = auxv[i * 2 + 1];
    }
    *w++ = AT_PHDR;
    *w++ = prog.phdr;
    *w++ = AT_PHENT;
    *w++ = prog.phent;
    *w++ = AT_PHNUM;
    *w++ = prog.phnum;
    *w++ = AT_BASE;
    *w++ = has_interp ? interp.base : 0;
    *w++ = AT_ENTRY;
    *w++ = prog.entry;
    *w++ = AT_EXECFN;
    *w++ = (uintptr_t)path;
    *w++ = AT_NULL;
    *w++ = 0;

    uintptr_t entry = has_interp ? interp.entry : prog.entry;
    __asm__ volatile("mov sp, %0\n"
                     "mov x0, #0\n"
                     "br %1\n"
                     :
                     : "r"(nsp), "r"(entry)
                     : "memory");
    __builtin_unreachable();
}

__attribute__((naked, noreturn)) void _start(void) {
    __asm__ volatile("mov x0, sp\n"
                     "and sp, x0, #~15\n"
                     "bl ddexec_main\n");
}
