// Part of libddnatv-hook.so, included by ddnatv-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// Statically linked programs: their seccomp-blocked system calls are patched before they run.

// ---- Static programs ----------------------------------------------------------------------
// A statically linked program (gcc -static) carries its own copy of glibc, whose system calls the
// preloaded hook never sees. The ones Android's seccomp filter answers with SIGSYS therefore kill it:
// set_robust_list at start-up, clone3 for every thread, rseq, and the others the hook's SIGSYS
// handler catches for dynamic programs. Before such a program runs, The native userland patches those call sites
// in its file to what that handler answers (0 for set_robust_list and the set*id family, -ENOSYS,
// which glibc falls back from, for the rest), as scripts/patch-bootstrap-ld.py does to the rootfs
// ld.so and libc at build time. The scanning is a pure function over a buffer, so tests/native can
// check it against the script.

#define DDNATV_STATIC_WINDOW 16 // instructions between "mov x8, #nr" and its "svc #0"

// AArch64: what the SIGSYS handler answers for syscall `nr`: 1 for 0, 2 for -ENOSYS, 0 to leave it.
// set_robust_list keeps the script's no-op (x0 then holds its non-error first argument).
static int a64_blocked_syscall(unsigned int nr) {
    switch (nr) {
    case 99: // set_robust_list
        return 3;
    case 100: // get_robust_list
    case 143: case 144: case 145: case 146: case 147: case 149: // setregid setgid setreuid setuid setresuid setresgid
    case 151: case 152: case 159: // setfsuid setfsgid setgroups
        return 1;
    case 97: case 217: case 218: case 219: // unshare add_key request_key keyctl
    case 268: case 277: case 280: case 293: // setns seccomp bpf rseq
        return 2;
    default:
        return (nr >= 424 && nr < 512) ? 2 : 0; // newer than Android's filter (clone3, close_range, ...)
    }
}

static uint32_t rd32(const unsigned char *p) { return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24); }
static uint16_t rd16(const unsigned char *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static void wr32(unsigned char *p, uint32_t v) { p[0] = v & 0xff; p[1] = (v >> 8) & 0xff; p[2] = (v >> 16) & 0xff; p[3] = (v >> 24) & 0xff; }
static void wr16(unsigned char *p, uint16_t v) { p[0] = v & 0xff; p[1] = (v >> 8) & 0xff; }

// An AArch64 instruction that ends the search for the "svc #0" of a "mov x8, #nr": a branch, another
// svc, or anything with x8 in its first register field (another write to x8, most likely).
static int a64_ends_syscall_window(uint32_t insn) {
    if ((insn & 0x1f) == 8) return 1;
    if ((insn & 0xffe0001f) == 0xd4000001u) return 1;  // svc #imm
    if ((insn & 0x7c000000u) == 0x14000000u) return 1; // b, bl
    if ((insn & 0xff000010u) == 0x54000000u) return 1; // b.cond
    if ((insn & 0x7e000000u) == 0x34000000u) return 1; // cbz, cbnz
    if ((insn & 0x7e000000u) == 0x36000000u) return 1; // tbz, tbnz
    if ((insn & 0xfe000000u) == 0xd6000000u) return 1; // br, blr, ret
    return 0;
}

static size_t a64_patch_seccomp_syscalls(unsigned char *data, size_t start, size_t end) {
    size_t patched = 0;
    start = (start + 3) & ~(size_t)3;
    for (size_t pos = start; pos + 4 <= end; pos += 4) {
        uint32_t insn = rd32(data + pos);
        // movz x8, #nr / movz w8, #nr
        if ((insn & 0x7fe0001fu) != 0x52800008u) continue;
        int result = a64_blocked_syscall((insn >> 5) & 0xffff);
        if (!result) continue;
        for (size_t s = pos + 4, i = 0; s + 4 <= end && i < DDNATV_STATIC_WINDOW; s += 4, i++) {
            uint32_t next = rd32(data + s);
            if (next == 0xd4000001u) { // svc #0
                wr32(data + s, result == 3 ? 0xd503201fu  /* nop */
                             : result == 1 ? 0xd2800000u  /* mov x0, #0 */
                                           : 0x928004a0u); /* mov x0, #-38 */
                patched++;
                break;
            }
            if (a64_ends_syscall_window(next)) break;
        }
    }
    return patched;
}

// 32-bit ARM: the three calls glibc makes on its own, with the exact patterns of the script
// (ARM mode, Thumb-2 with r7, and Thumb-2 through __libc_do_syscall with r12).
static long thumb_bl_target(const unsigned char *data, size_t size, size_t off) {
    if (off + 4 > size) return -1;
    uint16_t hw1 = rd16(data + off), hw2 = rd16(data + off + 2);
    if ((hw1 & 0xf800) != 0xf000 || (hw2 & 0xd000) != 0xd000) return -1;
    long s = (hw1 >> 10) & 1;
    long imm10 = hw1 & 0x3ff;
    long j1 = (hw2 >> 13) & 1, j2 = (hw2 >> 11) & 1;
    long imm11 = hw2 & 0x7ff;
    long i1 = (j1 ^ s) ^ 1, i2 = (j2 ^ s) ^ 1;
    long imm25 = (s << 24) | (i1 << 23) | (i2 << 22) | (imm10 << 12) | (imm11 << 1);
    if (s) imm25 -= (1L << 25);
    return (long)off + 4 + imm25;
}

static size_t arm_patch_seccomp_syscalls(unsigned char *data, size_t start, size_t end) {
    static const struct { uint32_t movw_r7; uint32_t repl; } arm[] = {
        { 0xe3007152u, 0xe320f000u }, // movw r7, #338 (set_robust_list) -> nop
        { 0xe30071b3u, 0xe3e00025u }, // movw r7, #435 (clone3) -> mvn r0, #37
        { 0xe300718eu, 0xe3e00025u }, // movw r7, #398 (rseq) -> mvn r0, #37
    };
    static const struct { uint16_t hw1, hw2; int nop_only; } thumb_r7[] = {
        { 0xf240, 0x1752, 1 }, // movw r7, #338
        { 0xf240, 0x17b3, 0 }, // movw r7, #435
        { 0xf240, 0x178e, 0 }, // movw r7, #398
    };
    static const struct { uint16_t hw1, hw2; uint16_t repl1, repl2; } thumb_r12[] = {
        { 0xf44f, 0x7ca9, 0xf04f, 0x0000 }, // mov.w r12, #338 -> mov.w r0, #0
        { 0xf44f, 0x7cc7, 0xf06f, 0x0025 }, // mov.w r12, #398 -> mvn.w r0, #37
        { 0xf240, 0x1cb3, 0xf06f, 0x0025 }, // movw r12, #435 -> mvn.w r0, #37
    };
    static const unsigned char do_syscall_stub[6] = { 0x80, 0xb5, 0x67, 0x46, 0x00, 0xdf };
    size_t patched = 0;

    size_t a4 = (start + 3) & ~(size_t)3, e4 = end & ~(size_t)3;
    for (size_t pos = a4; pos + 4 < e4; pos += 4) {
        uint32_t insn = rd32(data + pos);
        for (size_t k = 0; k < sizeof(arm) / sizeof(arm[0]); k++) {
            if (insn != arm[k].movw_r7) continue;
            for (size_t s = pos + 4; s < e4 && s < pos + 68; s += 4) {
                if (rd32(data + s) == 0xef000000u) { // svc #0
                    wr32(data + s, arm[k].repl);
                    patched++;
                    break;
                }
            }
        }
    }

    size_t a2 = (start + 1) & ~(size_t)1, e2 = end & ~(size_t)1;
    for (size_t t = a2; t + 4 < e2; t += 2) {
        uint16_t hw1 = rd16(data + t), hw2 = rd16(data + t + 2);
        int matched_r7 = 0;
        for (size_t k = 0; k < sizeof(thumb_r7) / sizeof(thumb_r7[0]); k++) {
            if (hw1 != thumb_r7[k].hw1 || hw2 != thumb_r7[k].hw2) continue;
            matched_r7 = 1;
            size_t limit = (e2 - 2 < t + 64 ? e2 - 2 : t + 64) + 2;
            for (size_t s = t + 4; s < limit; s += 2) {
                if (rd16(data + s) == 0xdf00) { // svc #0
                    if (!thumb_r7[k].nop_only) {
                        wr16(data + t, 0xf06f); // mvn.w r0, #37 in place of the movw
                        wr16(data + t + 2, 0x0025);
                    }
                    wr16(data + s, 0xbf00); // nop
                    patched++;
                    break;
                }
            }
            break;
        }
        if (matched_r7) continue;
        for (size_t k = 0; k < sizeof(thumb_r12) / sizeof(thumb_r12[0]); k++) {
            if (hw1 != thumb_r12[k].hw1 || hw2 != thumb_r12[k].hw2) continue;
            size_t limit = (e2 - 4 < t + 48 ? e2 - 4 : t + 48) + 2;
            for (size_t s = t + 4; s < limit; s += 2) {
                long target = thumb_bl_target(data, end, s);
                if (target >= (long)start && target + 6 <= (long)end && memcmp(data + target, do_syscall_stub, 6) == 0) {
                    wr16(data + s, thumb_r12[k].repl1);
                    wr16(data + s + 2, thumb_r12[k].repl2);
                    patched++;
                    break;
                }
            }
            break;
        }
    }
    return patched;
}

#ifndef EM_ARM
#define EM_ARM 40
#endif
#ifndef EM_AARCH64
#define EM_AARCH64 183
#endif

// Patches the blocked syscalls in data[start, end), code of an ELF for `e_machine`; returns how many.
static size_t patch_seccomp_syscalls(unsigned char *data, size_t start, size_t end, uint16_t e_machine) {
    if (!data || end <= start) return 0;
    if (e_machine == EM_AARCH64) return a64_patch_seccomp_syscalls(data, start, end);
    if (e_machine == EM_ARM) return arm_patch_seccomp_syscalls(data, start, end);
    return 0;
}

// Reads the program headers of an ELF file into `ph` (as 64-bit fields); returns their number, or -1.
struct ddnatv_phdr { uint32_t type, flags; uint64_t offset, filesz; };
static int read_elf_phdrs(int fd, uint16_t *machine, struct ddnatv_phdr *ph, int max) {
    unsigned char eh[64];
    if (pread(fd, eh, sizeof(eh), 0) < 52 || memcmp(eh, "\x7f" "ELF", 4) != 0 || eh[5] != 1) return -1;
    int is64 = eh[4] == 2;
    uint64_t phoff = is64 ? ((uint64_t)rd32(eh + 32) | ((uint64_t)rd32(eh + 36) << 32)) : rd32(eh + 28);
    uint16_t phentsize = rd16(eh + (is64 ? 54 : 42));
    uint16_t phnum = rd16(eh + (is64 ? 56 : 44));
    *machine = rd16(eh + 18);
    if (phentsize < (is64 ? 56 : 32) || phnum == 0 || phnum > max) return -1;
    for (int i = 0; i < phnum; i++) {
        unsigned char e[64];
        if (pread(fd, e, phentsize < sizeof(e) ? phentsize : sizeof(e), (off_t)(phoff + (uint64_t)i * phentsize)) < (is64 ? 56 : 32)) return -1;
        ph[i].type = rd32(e);
        if (is64) {
            ph[i].flags = rd32(e + 4);
            ph[i].offset = (uint64_t)rd32(e + 8) | ((uint64_t)rd32(e + 12) << 32);
            ph[i].filesz = (uint64_t)rd32(e + 32) | ((uint64_t)rd32(e + 36) << 32);
        } else {
            ph[i].offset = rd32(e + 4);
            ph[i].filesz = rd32(e + 16);
            ph[i].flags = rd32(e + 24);
        }
    }
    return phnum;
}

// Whether the ELF has a Go build-ID note: Go programs make their own system calls and never these.
static int elf_is_go(int fd, const struct ddnatv_phdr *ph, int n) {
    for (int i = 0; i < n; i++) {
        if (ph[i].type != 4 /* PT_NOTE */ || ph[i].filesz < 16) continue;
        unsigned char note[16];
        if (pread(fd, note, sizeof(note), (off_t)ph[i].offset) != sizeof(note)) continue;
        if (rd32(note) == 4 && memcmp(note + 12, "Go\0\0", 4) == 0) return 1;
    }
    return 0;
}

#define DDNATV_STATIC_XATTR "user.ddnatv.seccomp"
#define DDNATV_STATIC_MAX_CODE (256u * 1024 * 1024)

#ifdef DDNATV_HAVE_GOSYS
static int prepare_go_program(int fd, const char *path, const struct stat *st);
static uint32_t go_blob_fingerprint(void);
static int go_disabled(void);
#endif

// What was done to a file is recorded with its size and time, with the rootfs (a Go program carries
// the paths of it) and with the code Go programs are given (go_programs.c): a newer The native userland looks at
// the programs again.
static void static_marker(const struct stat *st, char *buf, size_t size) {
    unsigned fingerprint = 0;
    const char *off = "";
#ifdef DDNATV_HAVE_GOSYS
    fingerprint = go_blob_fingerprint();
    if (go_disabled()) off = " off";
#endif
    snprintf(buf, size, "3 %lld %lld.%09ld %08x %s%s", (long long)st->st_size, (long long)st->st_mtim.tv_sec,
             (long)st->st_mtim.tv_nsec, fingerprint, g_ddnatv_root, off);
}

// Makes a static program safe to start (see above). Runs before every start of one, so a program
// already handled (marked by an extended attribute that holds its size and modification time) costs
// one fstat and one getxattr; without extended attributes it is scanned again each time. Returns the
// number of patched calls, 0 when there was nothing to do, -1 when the file could not be handled
// (it is then started as it is).
// go_only: leave the file alone unless it is an AArch64 Go program. That is for a program that asks for
// Android's linker (GOOS=android): it is started like the dynamic ones, but it makes its own system
// calls, so it gets the code of the static Go programs (#42).
static int prepare_static_program_ex(const char *path, int go_only) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    struct stat st;
    char marker[PATH_MAX + 96], seen[PATH_MAX + 96];
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode)) {
        close(fd);
        return -1;
    }
    static_marker(&st, marker, sizeof(marker));
    ssize_t seen_len = fgetxattr(fd, DDNATV_STATIC_XATTR, seen, sizeof(seen) - 1);
    if (seen_len > 0) {
        seen[seen_len] = '\0';
        if (strcmp(seen, marker) == 0) {
            close(fd);
            return 0;
        }
    }

    struct ddnatv_phdr ph[64];
    uint16_t machine = 0;
    int n = read_elf_phdrs(fd, &machine, ph, 64);
    if (go_only && !(n >= 0 && machine == EM_AARCH64 && elf_is_go(fd, ph, n))) {
        close(fd);
        return 0;
    }
    if (n < 0 || (machine != EM_AARCH64 && machine != EM_ARM) || elf_is_go(fd, ph, n)) {
        // A Go program has no C library for the hook to be loaded into: it gets code of its own
        int go_rc = 0;
#ifdef DDNATV_HAVE_GOSYS
        if (n >= 0 && machine == EM_AARCH64 && elf_is_go(fd, ph, n)) go_rc = prepare_go_program(fd, path, &st);
#endif
        if (n >= 0 && go_rc >= 0) {
            struct stat after;
            if (fstat(fd, &after) == 0) static_marker(&after, marker, sizeof(marker));
            fsetxattr(fd, DDNATV_STATIC_XATTR, marker, strlen(marker), 0);
        }
        close(fd);
        return n < 0 || go_rc < 0 ? -1 : 0;
    }

    int total = 0;
    int wfd = -1;
    int made_writable = 0;
    for (int i = 0; i < n; i++) {
        if (ph[i].type != 1 /* PT_LOAD */ || !(ph[i].flags & 1 /* PF_X */) || ph[i].filesz == 0) continue;
        if (ph[i].filesz > DDNATV_STATIC_MAX_CODE || ph[i].offset > (uint64_t)st.st_size ||
            ph[i].filesz > (uint64_t)st.st_size - ph[i].offset) continue;
        size_t len = (size_t)ph[i].filesz;
        unsigned char *code = (unsigned char *)malloc(len);
        if (!code) continue;
        if (pread(fd, code, len, (off_t)ph[i].offset) != (ssize_t)len) {
            free(code);
            continue;
        }
        size_t patched = patch_seccomp_syscalls(code, 0, len, machine);
        if (patched > 0) {
            if (wfd < 0) {
                wfd = open(path, O_WRONLY | O_CLOEXEC);
                if (wfd < 0 && errno == EACCES && fchmod(fd, (st.st_mode | S_IWUSR) & 07777) == 0) {
                    made_writable = 1;
                    wfd = open(path, O_WRONLY | O_CLOEXEC);
                }
            }
            // ETXTBSY: the program is running right now, so it was handled when it started.
            if (wfd < 0 || pwrite(wfd, code, len, (off_t)ph[i].offset) != (ssize_t)len) {
                free(code);
                total = -1;
                break;
            }
            total += (int)patched;
        }
        free(code);
    }
    if (wfd >= 0) {
        // Keep the times: make and friends must not see a rebuilt program.
        const struct timespec times[2] = { st.st_atim, st.st_mtim };
        futimens(wfd, times);
        close(wfd);
    }
    if (made_writable) fchmod(fd, st.st_mode & 07777);
    if (total >= 0) {
        struct stat after;
        if (fstat(fd, &after) == 0) {
            static_marker(&after, marker, sizeof(marker));
            fsetxattr(fd, DDNATV_STATIC_XATTR, marker, strlen(marker), 0);
        }
    }
    close(fd);
    return total;
}

static int prepare_static_program(const char *path) { return prepare_static_program_ex(path, 0); }
