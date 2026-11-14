// Part of libddnatv-hook.so, included by ddnatv-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// What execve() and posix_spawn() need to know about a program: its ld.so, whether it is a
// dynamic ELF, a script and its interpreter, and the argument lists built from that.

// Relative exec paths ("./configure", "bin/tool", "../x.sh") must go through the same
// DDNATV_ROOT routing as absolute ones. Without this they were mistaken for Android host
// binaries and handed to the kernel unchanged, which then resolved "#!/bin/bash" (or a glibc
// PT_INTERP) literally and failed with "bad interpreter: No such file or directory".
// getcwd() is not virtualised, so cwd + path is a real path that starts with DDNATV_ROOT.
static const char *absolutize_exec_path(const char *path, char *buffer, size_t bufsize) {
    if (!path || path[0] == '\0' || path[0] == '/') return path;
    char cwd[PATH_MAX];
    static char *(*real_getcwd)(char *, size_t) = NULL;
    if (!real_getcwd) real_getcwd = (char *(*)(char *, size_t))dlsym(RTLD_NEXT, "getcwd");
    if (!real_getcwd || !real_getcwd(cwd, sizeof(cwd))) return path;

    while (path[0] == '.' && path[1] == '/') {
        path += 2;
        while (path[0] == '/') path++;
    }
    if (path[0] == '\0' || strcmp(path, ".") == 0) return path;

    size_t cwd_len = strlen(cwd);
    while (cwd_len > 1 && cwd[cwd_len - 1] == '/') cwd[--cwd_len] = '\0';
    int n = snprintf(buffer, bufsize, "%s%s%s", cwd, (cwd_len == 1 && cwd[0] == '/') ? "" : "/", path);
    if (n < 0 || (size_t)n >= bufsize) return path;
    return buffer;
}

static char s_cached_ld_so[PATH_MAX] = {0};
static char s_cached_ld_root[PATH_MAX] = {0};
static uint16_t s_cached_e_machine = 0;

// ld.so inside `ddnatv_root` for an ELF with machine type `e_machine` (0 when unknown).
static int find_dynamic_linker_for_machine(const char *ddnatv_root, uint16_t e_machine, char *out_ld_so, size_t max_len) {
    if (!ddnatv_root || ddnatv_root[0] == '\0' || !out_ld_so || max_len == 0) return 0;

    if (s_cached_ld_so[0] != '\0' && strcmp(s_cached_ld_root, ddnatv_root) == 0 &&
        e_machine != 0 && e_machine == s_cached_e_machine) {
        ddnatv_copy_cstr(out_ld_so, max_len, s_cached_ld_so);
        return 1;
    }

    const char *aarch64_cands[] = {
        "/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
        "/usr/lib/aarch64-linux-gnu/ld-2.39.so",
        "/lib/ld-linux-aarch64.so.1",
        "/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
        "/usr/lib/ld-linux-aarch64.so.1",
        "/usr/lib64/ld-linux-aarch64.so.1",
        "/lib64/ld-linux-aarch64.so.1",
        "/lib/aarch64-linux-gnu/ld-2.39.so",
        NULL
    };

    const char *armhf_cands[] = {
        "/usr/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3",
        "/usr/lib/arm-linux-gnueabihf/ld-2.39.so",
        "/lib/ld-linux-armhf.so.3",
        "/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3",
        "/usr/lib/ld-linux-armhf.so.3",
        "/lib/arm-linux-gnueabihf/ld-2.39.so",
        "/usr/lib/arm-linux-gnueabi/ld-linux.so.3",
        "/lib/arm-linux-gnueabi/ld-linux.so.3",
        NULL
    };

    const char *x86_64_cands[] = {
        "/lib64/ld-linux-x86-64.so.2",
        "/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
        "/usr/lib/x86_64-linux-gnu/ld-2.39.so",
        "/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
        NULL
    };

    const char **primary = NULL;
    const char **secondary = NULL;

    if (e_machine == 183) { // EM_AARCH64
        primary = aarch64_cands;
        secondary = NULL;
    } else if (e_machine == 40) { // EM_ARM
        primary = armhf_cands;
        secondary = NULL;
    } else if (e_machine == 62) { // EM_X86_64
        primary = x86_64_cands;
        secondary = NULL;
    } else if (e_machine == 3) { // EM_386
        primary = NULL;
        secondary = NULL;
    } else {
        #if defined(__aarch64__)
        primary = aarch64_cands;
        secondary = armhf_cands;
        #elif defined(__arm__)
        primary = armhf_cands;
        secondary = aarch64_cands;
        #else
        primary = x86_64_cands;
        #endif
    }

    if (primary) {
        for (int i = 0; primary[i] != NULL; i++) {
            snprintf(out_ld_so, max_len, "%s%s", ddnatv_root, primary[i]);
            if (access(out_ld_so, F_OK) == 0) {
                if (e_machine != 0) {
                    ddnatv_copy_cstr(s_cached_ld_so, sizeof(s_cached_ld_so), out_ld_so);
                    ddnatv_copy_cstr(s_cached_ld_root, sizeof(s_cached_ld_root), ddnatv_root);
                    s_cached_e_machine = e_machine;
                }
                return 1;
            }
        }
    }
    if (secondary) {
        for (int i = 0; secondary[i] != NULL; i++) {
            snprintf(out_ld_so, max_len, "%s%s", ddnatv_root, secondary[i]);
            if (access(out_ld_so, F_OK) == 0) {
                if (e_machine != 0) {
                    ddnatv_copy_cstr(s_cached_ld_so, sizeof(s_cached_ld_so), out_ld_so);
                    ddnatv_copy_cstr(s_cached_ld_root, sizeof(s_cached_ld_root), ddnatv_root);
                    s_cached_e_machine = e_machine;
                }
                return 1;
            }
        }
    }

    return 0;
}

#ifdef DDNATV_HOST_TEST
// Test entry point taking a program instead of its machine type (ddnatv-pty.c, which the tests
// include too, has a find_dynamic_linker of its own).
static int hook_find_dynamic_linker(const char *ddnatv_root, const char *cmd, char *out_ld_so, size_t max_len) {
    if (!ddnatv_root || ddnatv_root[0] == '\0' || !out_ld_so || max_len == 0) return 0;

    uint16_t e_machine = 0;
    if (cmd && cmd[0] != '\0') {
        int fd = open(cmd, O_RDONLY);
        if (fd >= 0) {
            unsigned char ehdr[20];
            ssize_t n = read(fd, ehdr, sizeof(ehdr));
            close(fd);
            if (n >= 20 && ehdr[0] == 0x7f && ehdr[1] == 'E' && ehdr[2] == 'L' && ehdr[3] == 'F') {
                e_machine = (uint16_t)(ehdr[18] | (ehdr[19] << 8));
            }
        }
    }
    return find_dynamic_linker_for_machine(ddnatv_root, e_machine, out_ld_so, max_len);
}
#endif

static int check_elf_dynamic(int fd, const unsigned char *ehdr, ssize_t n) {
    if (n < 52 || ehdr[0] != 0x7f || ehdr[1] != 'E' || ehdr[2] != 'L' || ehdr[3] != 'F') {
        return 0;
    }
    if ((ehdr[4] != 1 && ehdr[4] != 2) || ehdr[5] != 1 /* ELFDATA2LSB */) {
        return 0;
    }
    int is_64 = (ehdr[4] == 2);
    uint64_t phoff = 0;
    uint16_t phentsize = 0;
    uint16_t phnum = 0;
    if (is_64) {
        if (n < 64) return 0;
        memcpy(&phoff, ehdr + 32, sizeof(uint64_t));
        memcpy(&phentsize, ehdr + 54, sizeof(uint16_t));
        memcpy(&phnum, ehdr + 56, sizeof(uint16_t));
    } else {
        uint32_t phoff32 = 0;
        memcpy(&phoff32, ehdr + 28, sizeof(uint32_t));
        phoff = phoff32;
        memcpy(&phentsize, ehdr + 42, sizeof(uint16_t));
        memcpy(&phnum, ehdr + 44, sizeof(uint16_t));
    }
    uint16_t min_phentsize = is_64 ? 56 : 32;
    if (phoff == 0 || phentsize < min_phentsize || phentsize > 4096 || phnum == 0) {
        return 0;
    }
    if ((off_t)phoff < 0 || (uint64_t)(off_t)phoff != phoff) {
        return 0;
    }
    if (lseek(fd, (off_t)phoff, SEEK_SET) < 0) {
        return 0;
    }
    for (int i = 0; i < phnum && i < 128; i++) {
        uint32_t p_type = 0;
        if (read(fd, &p_type, sizeof(p_type)) != sizeof(p_type)) break;
        if (p_type == 3 /* PT_INTERP */) {
            return 1;
        }
        if (lseek(fd, (off_t)(phentsize - sizeof(p_type)), SEEK_CUR) < 0) break;
    }
    return 0;
}

// check_elf_dynamic() on the bytes already read: 1 or 0, or -1 when a program header lies past
// the buffer and the file has to be read after all. Program headers normally follow the ELF
// header directly, so this saves the lseek()/read() pairs.
static int elf_interp_in_buffer(const unsigned char *ehdr, ssize_t n) {
    if (n < 52 || ehdr[0] != 0x7f || ehdr[1] != 'E' || ehdr[2] != 'L' || ehdr[3] != 'F') {
        return 0;
    }
    if ((ehdr[4] != 1 && ehdr[4] != 2) || ehdr[5] != 1 /* ELFDATA2LSB */) {
        return 0;
    }
    int is_64 = (ehdr[4] == 2);
    uint64_t phoff = 0;
    uint16_t phentsize = 0;
    uint16_t phnum = 0;
    if (is_64) {
        if (n < 64) return 0;
        memcpy(&phoff, ehdr + 32, sizeof(uint64_t));
        memcpy(&phentsize, ehdr + 54, sizeof(uint16_t));
        memcpy(&phnum, ehdr + 56, sizeof(uint16_t));
    } else {
        uint32_t phoff32 = 0;
        memcpy(&phoff32, ehdr + 28, sizeof(uint32_t));
        phoff = phoff32;
        memcpy(&phentsize, ehdr + 42, sizeof(uint16_t));
        memcpy(&phnum, ehdr + 44, sizeof(uint16_t));
    }
    uint16_t min_phentsize = is_64 ? 56 : 32;
    if (phoff == 0 || phentsize < min_phentsize || phentsize > 4096 || phnum == 0) {
        return 0;
    }
    if ((off_t)phoff < 0 || (uint64_t)(off_t)phoff != phoff) {
        return 0;
    }
    for (int i = 0; i < phnum && i < 128; i++) {
        uint64_t off = phoff + (uint64_t)i * phentsize;
        if (off > (uint64_t)n || (uint64_t)n - off < sizeof(uint32_t)) return -1;
        uint32_t p_type = 0;
        memcpy(&p_type, ehdr + off, sizeof(p_type));
        if (p_type == 3 /* PT_INTERP */) {
            return 1;
        }
    }
    return 0;
}

// Whether the interpreter of a 64-bit ELF is Android's own linker (/system/bin/linker64). A Go
// program built with GOOS=android is a PIE that asks for it, so it has a PT_INTERP and looks like a
// dynamic program, but it has no C library and makes its own system calls like every Go program
// (#42). `h` holds the `n` first bytes of the file; the program headers usually are among them.
static int elf_interp_is_android(int fd, const unsigned char *h, ssize_t n) {
    if (n < 64 || h[4] != 2) return 0;
    uint64_t phoff = 0;
    uint16_t phentsize = 0, phnum = 0;
    memcpy(&phoff, h + 32, sizeof(phoff));
    memcpy(&phentsize, h + 54, sizeof(phentsize));
    memcpy(&phnum, h + 56, sizeof(phnum));
    if (phoff == 0 || phoff > (1ULL << 40) || phentsize < 56 || phentsize > 4096 || phnum == 0) return 0;
    for (int i = 0; i < phnum && i < 128; i++) {
        unsigned char e[56];
        uint64_t off = phoff + (uint64_t)i * phentsize;
        if (off + sizeof(e) <= (uint64_t)n) {
            memcpy(e, h + off, sizeof(e));
        } else if (pread(fd, e, sizeof(e), (off_t)off) != (ssize_t)sizeof(e)) {
            return 0;
        }
        uint32_t p_type = 0;
        memcpy(&p_type, e, sizeof(p_type));
        if (p_type != 3 /* PT_INTERP */) continue;
        uint64_t p_offset = 0, p_filesz = 0;
        memcpy(&p_offset, e + 8, sizeof(p_offset));
        memcpy(&p_filesz, e + 32, sizeof(p_filesz));
        char interp[48];
        size_t want = p_filesz < sizeof(interp) - 1 ? (size_t)p_filesz : sizeof(interp) - 1;
        if (want == 0 || p_offset > (1ULL << 40)) return 0;
        ssize_t got = pread(fd, interp, want, (off_t)p_offset);
        if (got <= 0) return 0;
        interp[got] = '\0';
        return strncmp(interp, "/system/bin/linker", 18) == 0;
    }
    return 0;
}

// The kernel follows at most four nested "#!" interpreters, then fails with ELOOP.
#define DDNATV_MAX_SHEBANG_DEPTH 4

// What execve()/posix_spawn() need to know about the file they are about to start, from a
// single open()/read() (they used to open it up to three times).
struct exec_probe {
    char hdr[256];      // first bytes of the file, NUL-terminated for "#!" parsing
    ssize_t n;          // bytes read; -1 when the file could not be opened
    int is_elf;
    int is_dynamic;     // ELF with PT_INTERP: has to be started through ld.so
    int android_interp; // AArch64 ELF whose PT_INTERP is Android's linker (a Go program for GOOS=android)
    uint16_t e_machine;
    mode_t mode;        // st_mode, filled in for dynamic ELF files only
    int runpath_is_rpath; // runpath holds a DT_RPATH (searched before LD_LIBRARY_PATH), not a DT_RUNPATH
    char runpath[1024]; // DT_RUNPATH (else DT_RPATH) of a dynamic ELF as written in it, or ""
};

// The DT_RUNPATH of a dynamic ELF (its DT_RPATH when it has none, as ld.so does) into `out`, as
// written in the file. ld.so looks for the program's libraries there, but it opens those
// directories itself, without the hook: an absolute one such as /usr/lib/aarch64-linux-gnu/android
// (Ubuntu's adb and fastboot keep their own libbase, liblog, libssl... there) is looked for in
// Android's tree and the program does not start. The exec adds the rootfs copies to the
// --library-path it gives ld.so (build_ld_library_path). Returns 1 for a DT_RUNPATH, 2 for a
// DT_RPATH, 0 when there is neither. The headers are read in pieces, so that their number does
// not matter; a list longer than `out` keeps its first whole entries (ld.so still has the rest).
static int elf_read_dynamic(int fd, const unsigned char *h, ssize_t n, char *out, size_t outsz,
                            char *needed, size_t needed_size, int *needed_count);

static int elf_read_runpath(int fd, const unsigned char *h, ssize_t n, char *out, size_t outsz) {
    return elf_read_dynamic(fd, h, n, out, outsz, NULL, 0, NULL);
}

// elf_read_runpath(), and with `needed` the DT_NEEDED names too: one after the other, each ended by a
// NUL, their number in *needed_count (as many as fit).
#define ELF_MAX_NEEDED 128
static int elf_read_dynamic(int fd, const unsigned char *h, ssize_t n, char *out, size_t outsz,
                            char *needed, size_t needed_size, int *needed_count) {
    if (needed_count) *needed_count = 0;
    if (!out || outsz < 2) return 0;
    out[0] = '\0';
    if (n < 52 || h[0] != 0x7f || h[1] != 'E' || h[2] != 'L' || h[3] != 'F' || h[5] != 1 /* LSB */) return 0;
    int is_64 = (h[4] == 2);
    if (!is_64 && h[4] != 1) return 0;
    if (is_64 && n < 64) return 0;
    uint64_t phoff = 0;
    uint16_t phentsize = 0, phnum = 0;
    if (is_64) {
        memcpy(&phoff, h + 32, 8);
        memcpy(&phentsize, h + 54, 2);
        memcpy(&phnum, h + 56, 2);
    } else {
        uint32_t off32 = 0;
        memcpy(&off32, h + 28, 4);
        phoff = off32;
        memcpy(&phentsize, h + 42, 2);
        memcpy(&phnum, h + 44, 2);
    }
    size_t ph_size = is_64 ? 56 : 32;
    if (phoff == 0 || phoff > (1ULL << 40) || phentsize < ph_size || phentsize > 1024 || phnum == 0) return 0;

    // PT_DYNAMIC, and the PT_LOAD segments that map an address of the file to its offset
    unsigned char buf[2048];
    uint64_t dyn_off = 0, dyn_size = 0;
    uint64_t load_vaddr[16], load_off[16], load_size[16];
    int loads = 0;
    size_t per_read = sizeof(buf) / phentsize;
    for (size_t first = 0; first < phnum; first += per_read) {
        size_t count = phnum - first < per_read ? phnum - first : per_read;
        size_t bytes = count * phentsize;
        if (pread(fd, buf, bytes, (off_t)(phoff + first * phentsize)) != (ssize_t)bytes) return 0;
        for (size_t i = 0; i < count; i++) {
            const unsigned char *e = buf + i * phentsize;
            uint32_t type = 0;
            memcpy(&type, e, 4);
            uint64_t off = 0, vaddr = 0, filesz = 0;
            if (is_64) {
                memcpy(&off, e + 8, 8);
                memcpy(&vaddr, e + 16, 8);
                memcpy(&filesz, e + 32, 8);
            } else {
                uint32_t v = 0;
                memcpy(&v, e + 4, 4); off = v;
                memcpy(&v, e + 8, 4); vaddr = v;
                memcpy(&v, e + 16, 4); filesz = v;
            }
            if (type == 2 /* PT_DYNAMIC */) {
                dyn_off = off;
                dyn_size = filesz;
            } else if (type == 1 /* PT_LOAD */ && loads < 16) {
                load_vaddr[loads] = vaddr;
                load_off[loads] = off;
                load_size[loads] = filesz;
                loads++;
            }
        }
    }
    if (dyn_size == 0 || dyn_off > (1ULL << 40)) return 0;
    if (dyn_size > (1u << 20)) dyn_size = 1u << 20;

    size_t ent = is_64 ? 16 : 8;
    uint64_t strtab = 0, runpath = 0, rpath = 0;
    int has_strtab = 0, has_runpath = 0, has_rpath = 0, done = 0;
    uint64_t needed_at[ELF_MAX_NEEDED];
    int nneeded = 0;
    for (uint64_t at = 0; at + ent <= dyn_size && !done;) {
        size_t want = dyn_size - at < sizeof(buf) ? (size_t)(dyn_size - at) : sizeof(buf);
        want -= want % ent;
        ssize_t got = pread(fd, buf, want, (off_t)(dyn_off + at));
        if (got < (ssize_t)ent) break;
        for (size_t i = 0; i + ent <= (size_t)got; i += ent) {
            int64_t tag = 0;
            uint64_t val = 0;
            if (is_64) {
                memcpy(&tag, buf + i, 8);
                memcpy(&val, buf + i + 8, 8);
            } else {
                int32_t tv = 0;
                uint32_t vv = 0;
                memcpy(&tv, buf + i, 4);
                memcpy(&vv, buf + i + 4, 4);
                tag = tv;
                val = vv;
            }
            if (tag == 0 /* DT_NULL */) { done = 1; break; }
            if (tag == 5 /* DT_STRTAB */) { strtab = val; has_strtab = 1; }
            else if (tag == 29 /* DT_RUNPATH */) { runpath = val; has_runpath = 1; }
            else if (tag == 15 /* DT_RPATH */) { rpath = val; has_rpath = 1; }
            else if (tag == 1 /* DT_NEEDED */ && needed && nneeded < ELF_MAX_NEEDED) needed_at[nneeded++] = val;
        }
        at += (uint64_t)got - (uint64_t)got % ent;
    }
    if (!has_strtab || (!has_runpath && !has_rpath)) return 0;
    uint64_t str_index = has_runpath ? runpath : rpath;

    // DT_STRTAB is an address: the file offset is that of the PT_LOAD segment holding it
    uint64_t strtab_off = 0;
    int mapped = 0;
    for (int i = 0; i < loads; i++) {
        if (strtab >= load_vaddr[i] && strtab - load_vaddr[i] < load_size[i]) {
            strtab_off = load_off[i] + (strtab - load_vaddr[i]);
            mapped = 1;
            break;
        }
    }
    if (!mapped || str_index > (1ULL << 32)) return 0;
    if (needed && needed_count && needed_size > 0) {
        size_t used = 0;
        for (int i = 0; i < nneeded; i++) {
            char name[256];
            ssize_t got = pread(fd, name, sizeof(name) - 1, (off_t)(strtab_off + needed_at[i]));
            if (got <= 0) continue;
            name[got] = '\0';
            size_t len = strnlen(name, (size_t)got);
            if (len == 0 || len == (size_t)got || used + len + 1 > needed_size) continue;
            memcpy(needed + used, name, len + 1);
            used += len + 1;
            (*needed_count)++;
        }
    }
    ssize_t len = pread(fd, out, outsz - 1, (off_t)(strtab_off + str_index));
    if (len <= 0) {
        out[0] = '\0';
        return 0;
    }
    out[len] = '\0';
    if (strnlen(out, (size_t)len) == (size_t)len) {
        // Longer than `out`: its whole entries up to there
        char *last = strrchr(out, ':');
        if (!last) {
            out[0] = '\0';
            return 0;
        }
        *last = '\0';
    }
    if (out[0] == '\0') return 0;
    return has_runpath ? 1 : 2;
}

// Appends dir[0..len) to the ':'-separated out_buf[0..used) unless it is there already.
static size_t append_lib_dir(char *out_buf, size_t out_size, size_t used, const char *dir, size_t len) {
    if (len == 0 || lib_dir_list_has(out_buf, dir, len)) return used;
    size_t need = (used > 0 ? 1 : 0) + len;
    if (used + need >= out_size) return used;
    if (used > 0) out_buf[used++] = ':';
    memcpy(out_buf + used, dir, len);
    used += len;
    out_buf[used] = '\0';
    return used;
}

// The directories of `runpath` (a DT_RUNPATH or DT_RPATH), in their order, appended to
// out_buf[0..used): the absolute ones that lie in the rootfs mapped into it, and those that start
// with $ORIGIN with it replaced by the directory of `exe` (the program's real path, in the rootfs
// already), so that both keep their places. ld.so opens them itself, without the hook, so
// /usr/lib/aarch64-linux-gnu/android would be looked for in Android's tree. Others ($LIB,
// $PLATFORM, directories outside the rootfs) are left to ld.so. Returns the new length.
static size_t append_runpath_dirs(char *out_buf, size_t out_size, size_t used, const char *runpath, const char *exe) {
    size_t origin_len = 0;
    if (exe) {
        const char *slash = strrchr(exe, '/');
        if (slash) origin_len = slash > exe ? (size_t)(slash - exe) : 1;
    }
    for (const char *p = runpath; p && *p;) {
        const char *end = strchr(p, ':');
        size_t len = end ? (size_t)(end - p) : strlen(p);
        if (len > 0 && len < PATH_MAX) {
            char tok[PATH_MAX];
            memcpy(tok, p, len);
            tok[len] = '\0';
            size_t skip = 0;
            if (strncmp(tok, "$ORIGIN", 7) == 0) skip = 7;
            else if (strncmp(tok, "${ORIGIN}", 9) == 0) skip = 9;
            if (skip && (tok[skip] == '\0' || tok[skip] == '/') && origin_len > 0 && !strchr(tok + skip, '$')) {
                char full[PATH_MAX];
                size_t rest = len - skip;
                if (origin_len + rest < sizeof(full)) {
                    memcpy(full, exe, origin_len);
                    memcpy(full + origin_len, tok + skip, rest);
                    full[origin_len + rest] = '\0';
                    used = append_lib_dir(out_buf, out_size, used, full, origin_len + rest);
                }
            } else if (tok[0] == '/' && len > 1 && !strchr(tok, '$')) {
                char rw_buf[PATH_MAX];
                const char *rw = rewrite_path_in_root(tok, rw_buf, sizeof(rw_buf));
                if (strcmp(rw, tok) != 0) used = append_lib_dir(out_buf, out_size, used, rw, strlen(rw));
            }
        }
        if (!end) break;
        p = end + 1;
    }
    return used;
}

// Whether dir[0..len) is one of the rootfs library directories The native userland adds itself (also what its
// shells export in LD_LIBRARY_PATH).
static int is_rootfs_default_lib_dir(const char *dir, size_t len) {
    if (len <= g_ddnatv_root_len || memcmp(dir, g_ddnatv_root, g_ddnatv_root_len) != 0) return 0;
    const char *rel = dir + g_ddnatv_root_len;
    size_t rel_len = len - g_ddnatv_root_len;
    for (size_t i = 0; i < sizeof(k_rootfs_lib_dirs) / sizeof(k_rootfs_lib_dirs[0]); i++) {
        if (strlen(k_rootfs_lib_dirs[i]) == rel_len && memcmp(k_rootfs_lib_dirs[i], rel, rel_len) == 0) return 1;
    }
    return 0;
}

// Where the program is among the arguments of ld.so started directly (ldd runs "ld.so PROGRAM" with
// LD_TRACE_LOADED_OBJECTS set; "ld.so --list PROGRAM" and "ld.so --verify PROGRAM" are the same): its
// index, or -1 when there is none; *has_library_path says whether the caller gives --library-path itself.
static int ldso_program_index(char *const argv[], int *has_library_path) {
    if (has_library_path) *has_library_path = 0;
    if (!argv || !argv[0]) return -1;
    static const char *const with_value[] = { "--library-path", "--preload", "--argv0", "--inhibit-rpath", "--audit",
                                              "--glibc-hwcaps-prepend", "--glibc-hwcaps-mask" };
    for (int i = 1; argv[i]; i++) {
        const char *a = argv[i];
        if (strcmp(a, "--library-path") == 0 && has_library_path) *has_library_path = 1;
        if (a[0] != '-' || a[1] != '-') return i;
        if (a[2] == '\0') return argv[i + 1] ? i + 1 : -1;
        int takes_value = 0;
        for (size_t k = 0; k < sizeof(with_value) / sizeof(with_value[0]); k++) {
            if (strcmp(a, with_value[k]) == 0) takes_value = 1;
        }
        if (takes_value) {
            if (!argv[i + 1]) return -1;
            i++;
        }
    }
    return -1;
}

// ---- dlopen() of a library with a RUNPATH ---------------------------------------------------
// A library that a program loads with dlopen() looks for its own dependencies in its RUNPATH, which ld.so
// opens without the hook: PulseAudio's modules (RUNPATH /usr/lib/aarch64-linux-gnu/pulseaudio) and many
// plugins did not load. Before the real dlopen(), the dependencies found in the rootfs copies of those
// directories are loaded by their full path, depth first; ld.so then finds them loaded, by their name.
#define DLOPEN_PRELOAD_DEPTH 6

static int dlopen_preload_deps(const char *path, void *(*orig_dlopen)(const char *, int), int depth) {
    if (depth > DLOPEN_PRELOAD_DEPTH || !path || path[0] != '/') return 0;
    int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    unsigned char hdr[64];
    ssize_t n = pread(fd, hdr, sizeof(hdr), 0);
    char runpath[1024];
    char needed[4096];
    int count = 0;
    int kind = n > 0 ? elf_read_dynamic(fd, hdr, n, runpath, sizeof(runpath), needed, sizeof(needed), &count) : 0;
    syscall(SYS_close, fd);
    if (!kind || count == 0) return 0;
    // the RUNPATH's directories, mapped into the rootfs ($ORIGIN from this library's own place)
    char dirs[PATH_MAX * 2];
    dirs[0] = '\0';
    size_t used = append_runpath_dirs(dirs, sizeof(dirs), 0, runpath, path);
    if (used == 0) return 0;
    int loaded = 0;
    const char *name = needed;
    for (int i = 0; i < count; i++, name += strlen(name) + 1) {
        if (strchr(name, '/')) continue;
        void *already = orig_dlopen(name, RTLD_LAZY | RTLD_NOLOAD);
        if (already) {
            dlclose(already);
            continue;
        }
        for (const char *p = dirs; *p;) {
            const char *end = strchr(p, ':');
            size_t len = end ? (size_t)(end - p) : strlen(p);
            char candidate[PATH_MAX];
            if (len + 1 + strlen(name) < sizeof(candidate)) {
                memcpy(candidate, p, len);
                candidate[len] = '/';
                strcpy(candidate + len + 1, name);
                int probe_fd = (int)syscall(SYS_openat, AT_FDCWD, candidate, O_PATH | O_CLOEXEC);
                if (probe_fd >= 0) {
                    syscall(SYS_close, probe_fd);
                    dlopen_preload_deps(candidate, orig_dlopen, depth + 1);
                    if (orig_dlopen(candidate, RTLD_LAZY | RTLD_LOCAL)) loaded++;
                    break;
                }
            }
            if (!end) break;
            p = end + 1;
        }
    }
    return loaded;
}

// ---- /etc/ld.so.conf -------------------------------------------------------------------------
// The library directories of /etc/ld.so.conf and the files it includes (/etc/ld.so.conf.d/*.conf),
// mapped into the rootfs. On Linux, ldconfig turns them into /etc/ld.so.cache, which ld.so reads.
// Here ld.so would read Android's /etc/ld.so.cache, and the paths in the cache are not mapped into
// the rootfs either, so the exec hands the directories to ld.so in --library-path instead. They are
// read again only when /etc/ld.so.conf, /etc/ld.so.conf.d or /etc/ld.so.cache changes: a .conf file
// added or removed counts at once, an edit of one after ldconfig, as on Linux.
#define LDCONF_MAX_DEPTH 4
#define LDCONF_MAX_INCLUDES 64

struct ldconf_sig {
    int64_t t[6]; // mtimes (seconds, nanoseconds) of ld.so.conf, ld.so.conf.d and ld.so.cache; 0 if missing
};

static struct {
    int valid;
    struct ldconf_sig sig;
    char root[PATH_MAX];
    char dirs[PATH_MAX * 2];
} g_ldconf;
static pthread_mutex_t g_ldconf_lock = PTHREAD_MUTEX_INITIALIZER;

// fstatat() behind the hook's stat emulation (which would map the hard link table in a child that is
// about to exec): only the times are wanted.
static int ldconf_stat_time(const char *real, int64_t *sec, int64_t *nsec) {
    static int (*f)(int, const char *, struct stat64 *, int) = NULL;
    int (*fn)(int, const char *, struct stat64 *, int) = __atomic_load_n(&f, __ATOMIC_RELAXED);
    if (!fn) {
        fn = (int (*)(int, const char *, struct stat64 *, int))dlsym(RTLD_NEXT, "fstatat64");
        if (!fn) return -1;
        __atomic_store_n(&f, fn, __ATOMIC_RELAXED);
    }
    struct stat64 st;
    if (fn(AT_FDCWD, real, &st, 0) != 0) return -1;
    *sec = (int64_t)st.st_mtim.tv_sec;
    *nsec = (int64_t)st.st_mtim.tv_nsec;
    return 0;
}

static void ldconf_signature(struct ldconf_sig *sig) {
    static const char *const files[3] = { "/etc/ld.so.conf", "/etc/ld.so.conf.d", "/etc/ld.so.cache" };
    for (int i = 0; i < 3; i++) {
        char path[PATH_MAX];
        size_t n = ddnatv_append(path, sizeof(path), 0, g_ddnatv_root, g_ddnatv_root_len);
        ddnatv_append(path, sizeof(path), n, files[i], strlen(files[i]));
        if (ldconf_stat_time(path, &sig->t[i * 2], &sig->t[i * 2 + 1]) != 0) {
            sig->t[i * 2] = 0;
            sig->t[i * 2 + 1] = 0;
        }
    }
}

// The whole of the rootfs file `logical` (an absolute path inside Ubuntu) into buf, NUL-terminated.
static ssize_t ldconf_read(const char *logical, char *buf, size_t bufsz) {
    char rw_buf[PATH_MAX];
    const char *real = rewrite_path_in_root(logical, rw_buf, sizeof(rw_buf));
    int fd = (int)syscall(SYS_openat, AT_FDCWD, real, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    size_t got = 0;
    while (got + 1 < bufsz) {
        ssize_t n = (ssize_t)syscall(SYS_read, fd, buf + got, bufsz - 1 - got);
        if (n <= 0) break;
        got += (size_t)n;
    }
    syscall(SYS_close, fd);
    buf[got] = '\0';
    return (ssize_t)got;
}

static int ldconf_name_cmp(const void *a, const void *b) {
    return strcmp(*(const char *const *)a, *(const char *const *)b);
}

static void ldconf_parse_file(const char *logical, char *out, size_t outsz, size_t *used, int depth, int *includes);

// include PATTERN: the files of one directory that match a shell pattern, in name order (as glob()).
// Patterns in the directory part are not supported (Ubuntu uses /etc/ld.so.conf.d/*.conf).
static void ldconf_include(const char *pattern, char *out, size_t outsz, size_t *used, int depth, int *includes) {
    const char *slash = strrchr(pattern, '/');
    if (!slash || slash == pattern) return;
    size_t dir_len = (size_t)(slash - pattern);
    if (dir_len >= PATH_MAX || memchr(pattern, '*', dir_len) || memchr(pattern, '?', dir_len) || memchr(pattern, '[', dir_len)) return;
    char dir[PATH_MAX];
    memcpy(dir, pattern, dir_len);
    dir[dir_len] = '\0';
    const char *name_pattern = slash + 1;
    if (!strpbrk(name_pattern, "*?[")) {
        ldconf_parse_file(pattern, out, outsz, used, depth + 1, includes);
        return;
    }
    char rw_buf[PATH_MAX];
    const char *real = rewrite_path_in_root(dir, rw_buf, sizeof(rw_buf));
    int fd = (int)syscall(SYS_openat, AT_FDCWD, real, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) return;
    char names_buf[4096];
    char *names[LDCONF_MAX_INCLUDES];
    int count = 0;
    size_t names_used = 0;
    char dents[4096];
    for (;;) {
        long n = syscall(SYS_getdents64, fd, dents, sizeof(dents));
        if (n <= 0) break;
        for (long at = 0; at < n;) {
            unsigned short reclen;
            memcpy(&reclen, dents + at + 16, sizeof(reclen));
            const char *name = dents + at + 19;
            if (reclen == 0) break;
            size_t len = strnlen(name, (size_t)reclen - 19);
            if (count < LDCONF_MAX_INCLUDES && names_used + len + 1 <= sizeof(names_buf) &&
                fnmatch(name_pattern, name, FNM_PERIOD) == 0) {
                memcpy(names_buf + names_used, name, len + 1);
                names[count++] = names_buf + names_used;
                names_used += len + 1;
            }
            at += reclen;
        }
    }
    syscall(SYS_close, fd);
    qsort(names, (size_t)count, sizeof(names[0]), ldconf_name_cmp);
    for (int i = 0; i < count; i++) {
        char file[PATH_MAX];
        if (dir_len + 1 + strlen(names[i]) >= sizeof(file)) continue;
        snprintf(file, sizeof(file), "%s/%s", dir, names[i]);
        ldconf_parse_file(file, out, outsz, used, depth + 1, includes);
    }
}

// One ld.so.conf-style file: a directory per line, "include PATTERN..." (relative to the file's own
// directory), "#" comments, and the old "hwcap" lines, which are skipped as glibc does.
static void ldconf_parse_file(const char *logical, char *out, size_t outsz, size_t *used, int depth, int *includes) {
    if (depth > LDCONF_MAX_DEPTH || ++*includes > LDCONF_MAX_INCLUDES) return;
    char text[8192];
    if (ldconf_read(logical, text, sizeof(text)) < 0) return;
    for (char *line = text; line && *line;) {
        char *next = strchr(line, '\n');
        if (next) *next++ = '\0';
        char *hash = strchr(line, '#');
        if (hash) *hash = '\0';
        while (*line == ' ' || *line == '\t' || *line == '\r') line++;
        size_t len = strlen(line);
        while (len > 0 && (line[len - 1] == ' ' || line[len - 1] == '\t' || line[len - 1] == '\r')) line[--len] = '\0';
        if (len == 0) {
            line = next;
            continue;
        }
        if (strncmp(line, "include", 7) == 0 && (line[7] == ' ' || line[7] == '\t')) {
            for (char *p = line + 8; *p;) {
                while (*p == ' ' || *p == '\t') p++;
                if (!*p) break;
                char *end = p;
                while (*end && *end != ' ' && *end != '\t') end++;
                char saved = *end;
                *end = '\0';
                char pattern[PATH_MAX];
                if (p[0] == '/') {
                    snprintf(pattern, sizeof(pattern), "%s", p);
                } else {
                    const char *slash = strrchr(logical, '/');
                    int base = slash ? (int)(slash - logical) : 0;
                    snprintf(pattern, sizeof(pattern), "%.*s/%s", base, logical, p);
                }
                ldconf_include(pattern, out, outsz, used, depth, includes);
                *end = saved;
                p = end;
            }
        } else if (strncmp(line, "hwcap", 5) == 0 && (line[5] == ' ' || line[5] == '\t')) {
            // ignored by ldconfig since glibc 2.37
        } else if (line[0] == '/') {
            // "DIR" or the old "DIR=TYPE" / "DIR TYPE"
            size_t dlen = strcspn(line, "= \t");
            while (dlen > 1 && line[dlen - 1] == '/') dlen--;
            if (dlen > 0 && dlen < PATH_MAX) {
                char dir[PATH_MAX];
                memcpy(dir, line, dlen);
                dir[dlen] = '\0';
                char rw_buf[PATH_MAX];
                const char *rw = rewrite_path_in_root(dir, rw_buf, sizeof(rw_buf));
                if (strcmp(rw, dir) != 0) *used = append_lib_dir(out, outsz, *used, rw, strlen(rw));
            }
        }
        line = next;
    }
}

static void ldconf_parse(char *out, size_t outsz) {
    out[0] = '\0';
    size_t used = 0;
    int includes = 0;
    ldconf_parse_file("/etc/ld.so.conf", out, outsz, &used, 0, &includes);
}

// The ld.so.conf directories into out (':'-separated, "" when there are none). Uses the copy of an
// earlier call while the files have not changed: the parent finds it when it first forks, and its
// children only compare the three times. Never waits for the lock: a child forked while another
// thread held it reads the files itself.
static void ldconf_dirs(char *out, size_t outsz) {
    if (!out || outsz == 0) return;
    out[0] = '\0';
    if (g_ddnatv_root[0] == '\0') return;
    struct ldconf_sig sig;
    ldconf_signature(&sig);
    if (sig.t[0] == 0 && sig.t[1] == 0) return; // neither ld.so.conf nor ld.so.conf.d
    if (pthread_mutex_trylock(&g_ldconf_lock) != 0) {
        ldconf_parse(out, outsz);
        return;
    }
    if (!g_ldconf.valid || memcmp(&g_ldconf.sig, &sig, sizeof(sig)) != 0 || strcmp(g_ldconf.root, g_ddnatv_root) != 0) {
        ldconf_parse(g_ldconf.dirs, sizeof(g_ldconf.dirs));
        g_ldconf.sig = sig;
        snprintf(g_ldconf.root, sizeof(g_ldconf.root), "%s", g_ddnatv_root);
        g_ldconf.valid = 1;
    }
    snprintf(out, outsz, "%s", g_ldconf.dirs);
    pthread_mutex_unlock(&g_ldconf_lock);
}

// The --library-path ld.so is given for the rootfs program `exe`, in the order ld.so searches on
// Linux: the program's RPATH, the caller's LD_LIBRARY_PATH (the rootfs paths in it mapped), its
// RUNPATH, the directories of /etc/ld.so.conf (ld.so's cache), and the rootfs defaults last. The native userland's
// shells export the defaults in LD_LIBRARY_PATH too, where Linux has none; when there is anything to
// put before them they move to the end, so that a library the program keeps for itself wins over
// one of the same name in /usr/lib. Runs after the hook's setup, so the
// rewrites are those of rewrite_path().
static void build_ld_library_path(char *const envp[], const char *runpath, int is_rpath, const char *exe,
                                  char *out_buf, size_t out_size) {
    if (!out_buf || out_size == 0) return;
    out_buf[0] = '\0';

    const char *caller_ld = NULL;
    if (envp) {
        for (int i = 0; envp[i] != NULL; i++) {
            if (envp[i][0] == 'L' && strncmp(envp[i], "LD_LIBRARY_PATH=", 16) == 0) {
                caller_ld = envp[i] + 16;
                break;
            }
        }
    }
    if (!caller_ld) {
        caller_ld = getenv("LD_LIBRARY_PATH");
    }
    int has_runpath = runpath && runpath[0];
    char conf[PATH_MAX * 2];
    ldconf_dirs(conf, sizeof(conf));
    // The rootfs defaults that The native userland's shells export in LD_LIBRARY_PATH go to the end, after the
    // program's and ld.so.conf's own directories, as on Linux (where LD_LIBRARY_PATH has none of them)
    int move_defaults = has_runpath || conf[0] != '\0';

    size_t used = 0;
    if (has_runpath && is_rpath) used = append_runpath_dirs(out_buf, out_size, used, runpath, exe);

    // The caller's directories (those inside the rootfs rewritten), written straight into out_buf:
    // this runs in nearly every child a shell forks, where two 8 KB copies of the list on the stack
    // were pages more to copy before the exec.
    size_t cap = used + PATH_MAX * 2 < out_size ? used + PATH_MAX * 2 : out_size; // the caller's part, as before
    for (const char *p = caller_ld; p && *p;) {
        const char *end = strchr(p, ':');
        size_t len = end ? (size_t)(end - p) : strlen(p);
        if (len > 0 && len < PATH_MAX) {
            char tok[PATH_MAX];
            memcpy(tok, p, len);
            tok[len] = '\0';
            char rw_buf[PATH_MAX];
            const char *rw = (tok[0] == '/') ? rewrite_path_in_root(tok, rw_buf, sizeof(rw_buf)) : tok;
            size_t rw_len = strlen(rw);
            size_t need = (used > 0 ? 1 : 0) + rw_len;
            if (move_defaults && is_rootfs_default_lib_dir(rw, rw_len)) {
                // after the program's own directories, with the other defaults
            } else if (used + need < cap) {
                if (used > 0) out_buf[used++] = ':';
                memcpy(out_buf + used, rw, rw_len);
                used += rw_len;
                out_buf[used] = '\0';
            }
        }
        if (!end) break;
        p = end + 1;
    }
    if (has_runpath && !is_rpath) used = append_runpath_dirs(out_buf, out_size, used, runpath, exe);
    // ld.so.conf, as ld.so's cache: after LD_LIBRARY_PATH and RUNPATH, before the default directories
    for (const char *p = conf; *p;) {
        const char *end = strchr(p, ':');
        size_t len = end ? (size_t)(end - p) : strlen(p);
        used = append_lib_dir(out_buf, out_size, used, p, len);
        if (!end) break;
        p = end + 1;
    }
    append_rootfs_lib_dirs_missing_from(out_buf, out_size, used, used > 0, out_buf);
}

// fstat() without the hook's stat emulation: the probe wants the mode bits only, and the
// emulation would map the hard link table and look up the app's ids in a child that is about to
// exec (new pages there, each a copy-on-write fault). Resolved by prepare_exec_in_parent() when the
// process forks, so that its children find it set.
static int (*g_probe_fstat)(int, struct stat *) = NULL;

static int probe_fstat(int fd, struct stat *st) {
    int (*f)(int, struct stat *) = __atomic_load_n(&g_probe_fstat, __ATOMIC_RELAXED);
    if (!f) {
        f = (int (*)(int, struct stat *))dlsym(RTLD_NEXT, "fstat");
        if (!f) return fstat(fd, st);
        __atomic_store_n(&g_probe_fstat, f, __ATOMIC_RELAXED);
    }
    return f(fd, st);
}

static void probe_exec_file(const char *path, struct exec_probe *p) {
    p->hdr[0] = '\0';
    p->n = -1;
    p->is_elf = 0;
    p->is_dynamic = 0;
    p->android_interp = 0;
    p->e_machine = 0;
    p->mode = 0;
    p->runpath[0] = '\0';
    p->runpath_is_rpath = 0;
    if (!path || path[0] == '\0') return;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    ssize_t n = read(fd, p->hdr, sizeof(p->hdr) - 1);
    p->n = n;
    p->hdr[n > 0 ? n : 0] = '\0';
    const unsigned char *h = (const unsigned char *)p->hdr;
    if (n >= 4 && h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F') {
        p->is_elf = 1;
        if (n >= 20) p->e_machine = (uint16_t)(h[18] | (h[19] << 8));
        int dynamic = elf_interp_in_buffer(h, n);
        if (dynamic < 0) dynamic = check_elf_dynamic(fd, h, n);
        p->is_dynamic = dynamic;
        if (dynamic && p->e_machine == 183 /* EM_AARCH64 */) p->android_interp = elf_interp_is_android(fd, h, n);
        if (dynamic && !p->android_interp) p->runpath_is_rpath = elf_read_runpath(fd, h, n, p->runpath, sizeof(p->runpath)) == 2;
        struct stat st;
        if (dynamic && probe_fstat(fd, &st) == 0) p->mode = st.st_mode;
    }
    close(fd);
}

static int is_ld_linux(const char *path) {
    if (!path) return 0;
    const char *base = strrchr(path, '/');
    base = base ? base + 1 : path;
    return (strncmp(base, "ld-linux", 8) == 0 || strncmp(base, "ld-2.", 5) == 0);
}

// Splits a "#!" line (NUL-terminated `hdr`, modified in place) into the interpreter and its
// optional argument, the way execve() always has.
static void parse_shebang(char *hdr, char **interp, char **interp_arg) {
    char *newline = strchr(hdr, '\n');
    if (newline) *newline = '\0';
    char *p = hdr + 2;
    while (*p == ' ' || *p == '\t') p++;
    char *arg = strchr(p, ' ');
    if (arg) {
        *arg = '\0';
        arg++;
        while (*arg == ' ' || *arg == '\t') arg++;
    }
    *interp = p;
    *interp_arg = arg;
}

// Interpreters outside the rootfs (Android's /system/bin/sh) run with Android's environment.
static int is_host_interpreter(const char *interp) {
    return strncmp(interp, "/system", 7) == 0 ||
           (g_ddnatv_root[0] != '\0' && strncmp(interp, g_ddnatv_root, g_ddnatv_root_len) != 0);
}

// argv for running `script` with its interpreter: interp [arg] script args... (the script's own
// argv[0] is dropped, as the kernel does). free() the result.
static char **build_script_argv(const char *interp, const char *interp_arg, const char *script, char *const argv[]) {
    int argc = 0;
    while (argv && argv[argc]) argc++;
    int has_arg = (interp_arg && *interp_arg) ? 1 : 0;
    char **v = (char **)calloc(argc + has_arg + 3, sizeof(char *));
    if (!v) return NULL;
    int n = 0;
    v[n++] = (char *)interp;
    if (has_arg) v[n++] = (char *)interp_arg;
    v[n++] = (char *)script;
    for (int i = 1; i < argc; i++) v[n++] = argv[i];
    v[n] = NULL;
    return v;
}

// argv for "ld.so [--preload hook] --library-path dirs --argv0 name program args...".
// free() the result; the strings stay owned by the caller.
static char **build_ldso_argv(const char *ld_so, const char *hook, const char *lib_path,
                              const char *program, const char *prog_name, char *const argv[]) {
    int argc = 0;
    while (argv && argv[argc]) argc++;
    char **v = (char **)calloc(argc + 16, sizeof(char *));
    if (!v) return NULL;
    int n = 0;
    v[n++] = (char *)ld_so;
    if (hook) {
        v[n++] = (char *)"--preload";
        v[n++] = (char *)hook;
    }
    v[n++] = (char *)"--library-path";
    v[n++] = (char *)lib_path;
    v[n++] = (char *)"--argv0";
    v[n++] = (char *)((argc > 0 && argv[0]) ? argv[0] : prog_name);
    v[n++] = (char *)program;
    for (int i = 1; i < argc; i++) v[n++] = argv[i];
    v[n] = NULL;
    return v;
}

// POSIX_SPAWN_RESETIDS makes glibc's spawn child call setresuid() and setresgid() after it has reset
// every signal handler to its default, which includes the SIGSYS handler that emulates the calls
// Android's seccomp filter blocks, so the child died of SIGSYS (GNU make asks for the flag on every
// command it starts). The native userland shows one fixed user id and never runs set-user-id programs: there is
// no id to reset, so the flag is left out and everything else is passed on.
static const posix_spawnattr_t *spawn_attr_for_android(const posix_spawnattr_t *attrp, posix_spawnattr_t *copy) {
    short flags = 0;
    if (!attrp || posix_spawnattr_getflags(attrp, &flags) != 0 || !(flags & POSIX_SPAWN_RESETIDS)) return attrp;
    memcpy(copy, attrp, sizeof(*copy));
    posix_spawnattr_setflags(copy, (short)(flags & ~POSIX_SPAWN_RESETIDS));
    return copy;
}
