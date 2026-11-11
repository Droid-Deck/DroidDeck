// Part of libddnatv-hook.so, included by ddnatv-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// Static Go programs: they make their system calls themselves, so Ubuntu's paths mean nothing to
// them. The code of gosys/gosys.c is added to their file.

// ---- Static Go programs -------------------------------------------------------------------
// A Go program built without cgo (all of Ubuntu's, and the single files downloaded from a project's
// releases) has no C library: the preloaded hook is never loaded into it, so "/etc/resolv.conf" is
// a file of Android's that does not exist, and so is every program it starts. Go makes all of its
// system calls but a few of its runtime's in one assembly function, internal/runtime/syscall.
// Syscall6, which loads the number and the arguments from the stack and executes "svc #0".
//
// Before such a program (AArch64, Go 1.19 or newer) starts, The native userland changes its file in place, once:
//   - the code of gosys/gosys.c is put after the program's code, in the zero padding that the Go
//     linker leaves between its segments (the segment is made longer, and executable if it only
//     was readable),
//   - zero-filled memory for it is added to the end of the program's data segment,
//   - the "svc #0" of Syscall6 becomes a branch to a few instructions in that code, which call it
//     and go back to the instruction after the "svc".
// The code maps the paths of the calls into the rootfs, as the hook does for C programs, and starts
// the programs of the rootfs through the hook. The file keeps its size, its times and its mode;
// only the program headers, the padding and one instruction differ. The file is changed back and
// patched again when the code of a newer The native userland differs (the trailer says what to undo).

#ifdef DDNATV_HAVE_GOSYS

#define GO_MAX_PH 32
#define GO_PF_X 1u
#define GO_PF_W 2u
#define GO_ET_EXEC 2
#define GO_ET_DYN 3

struct go_ph {
    uint32_t type, flags;
    uint64_t offset, vaddr, filesz, memsz;
    uint64_t pos; // where the entry is in the file
};

static uint64_t go_rd64(const unsigned char *p) { return (uint64_t)rd32(p) | ((uint64_t)rd32(p + 4) << 32); }
static void go_wr64(unsigned char *p, uint64_t v) { wr32(p, (uint32_t)v); wr32(p + 4, (uint32_t)(v >> 32)); }

// The program headers of a 64-bit little-endian ELF; their number, or -1
static int go_read_phdrs(int fd, struct go_ph *ph, uint16_t *etype, uint16_t *machine) {
    unsigned char eh[64];
    if (pread(fd, eh, sizeof(eh), 0) != (ssize_t)sizeof(eh) || memcmp(eh, "\x7f" "ELF", 4) != 0 || eh[4] != 2 || eh[5] != 1)
        return -1;
    *etype = rd16(eh + 16);
    *machine = rd16(eh + 18);
    uint64_t phoff = go_rd64(eh + 32);
    uint16_t phentsize = rd16(eh + 54), phnum = rd16(eh + 56);
    if (phentsize != 56 || phnum == 0 || phnum > GO_MAX_PH) return -1;
    for (int i = 0; i < phnum; i++) {
        unsigned char e[56];
        uint64_t pos = phoff + (uint64_t)i * 56;
        if (pread(fd, e, sizeof(e), (off_t)pos) != (ssize_t)sizeof(e)) return -1;
        ph[i].type = rd32(e);
        ph[i].flags = rd32(e + 4);
        ph[i].offset = go_rd64(e + 8);
        ph[i].vaddr = go_rd64(e + 16);
        ph[i].filesz = go_rd64(e + 32);
        ph[i].memsz = go_rd64(e + 40);
        ph[i].pos = pos;
    }
    return phnum;
}

// The instructions of Syscall6 up to its system call: x8 and x0 to x5 loaded from the stack
// (ldr x8, [sp, #8]; ldr x0, [sp, #16] ... ldr x5, [sp, #56]), then "svc #0" and "cmn x0, #4095"
static const uint32_t k_go_syscall6[7] = {
    0xf94007e8u, 0xf9400be0u, 0xf9400fe1u, 0xf94013e2u, 0xf94017e3u, 0xf9401be4u, 0xf9401fe5u,
};
#define GO_SVC 0xd4000001u
#define GO_CMN_X0_4095 0xb13ffc1fu

// Where Syscall6 makes its call: the file offset of the instruction after the seven loads (the
// "svc #0", or the branch that replaced it). *found counts the places; the first is returned.
static int64_t go_find_syscall6(int fd, const struct go_ph *ph, int n, int *found) {
    int64_t first = -1;
    *found = 0;
    enum { CHUNK = 1 << 20, KEEP = 36 };
    unsigned char *buf = (unsigned char *)malloc(CHUNK + KEEP);
    if (!buf) return -1;
    for (int i = 0; i < n; i++) {
        if (ph[i].type != 1 /* PT_LOAD */ || !(ph[i].flags & GO_PF_X) || ph[i].filesz == 0) continue;
        uint64_t pos = 0;
        while (pos < ph[i].filesz) {
            uint64_t want = ph[i].filesz - pos;
            if (want > CHUNK + KEEP) want = CHUNK + KEEP;
            ssize_t got = pread(fd, buf, (size_t)want, (off_t)(ph[i].offset + pos));
            if (got <= KEEP) break;
            for (ssize_t o = 0; o + 36 <= got; o += 4) {
                if (rd32(buf + o) != k_go_syscall6[0]) continue;
                int same = 1;
                for (int k = 1; k < 7 && same; k++) same = rd32(buf + o + 4 * k) == k_go_syscall6[k];
                if (!same || rd32(buf + o + 32) != GO_CMN_X0_4095) continue;
                if (*found == 0) first = (int64_t)(ph[i].offset + pos + (uint64_t)o + 28);
                (*found)++;
            }
            if (pos + (uint64_t)got >= ph[i].filesz) break;
            pos += (uint64_t)got - (KEEP - 4); // the next chunk starts where the last place of this could begin, no place is split or counted twice
        }
    }
    free(buf);
    return first;
}

// "b <delta>" / "bl <delta>" for a distance in bytes, or 0 when it does not fit
static uint32_t go_branch(uint32_t base, int64_t delta) {
    if ((delta & 3) != 0 || delta >= (1LL << 27) || delta < -(1LL << 27)) return 0;
    return base | (uint32_t)((delta >> 2) & 0x3ffffff);
}

static uint64_t go_align_up(uint64_t v, uint64_t a) { return (v + a - 1) & ~(a - 1); }

// The strings the added code needs, as they are now
static int go_make_header(struct gosys_hdr *h) {
    if (g_ddnatv_root_len == 0 || g_ddnatv_root_len >= GOSYS_ROOT_MAX) return -1;
    memcpy(h->root, g_ddnatv_root, g_ddnatv_root_len);
    h->root_len = (uint32_t)g_ddnatv_root_len;

    char buf[PATH_MAX];
    if (find_dynamic_linker_for_machine(g_ddnatv_root, EM_AARCH64, buf, sizeof(buf)) && strlen(buf) < GOSYS_PATH_MAX) {
        h->ldso_len = (uint32_t)strlen(buf);
        memcpy(h->ldso, buf, h->ldso_len);
    }
    static const char *const hooks[] = { "/usr/lib/libddnatv-hook.so", "/lib/libddnatv-hook.so" };
    for (size_t i = 0; i < sizeof(hooks) / sizeof(hooks[0]); i++) {
        size_t n = ddnatv_append(buf, sizeof(buf), 0, g_ddnatv_root, g_ddnatv_root_len);
        n = ddnatv_append(buf, sizeof(buf), n, hooks[i], strlen(hooks[i]));
        if (n < GOSYS_PATH_MAX && access(buf, F_OK) == 0) {
            h->hook_len = (uint32_t)n;
            memcpy(h->hook, buf, n);
            break;
        }
    }
    // The program that starts the others: bash, which can give them the argv[0] they are asked for
    static const char *const runners[] = { "/usr/bin/bash", "/bin/bash", "/usr/bin/env", "/bin/env" };
    for (size_t i = 0; i < sizeof(runners) / sizeof(runners[0]); i++) {
        size_t n = ddnatv_append(buf, sizeof(buf), 0, g_ddnatv_root, g_ddnatv_root_len);
        n = ddnatv_append(buf, sizeof(buf), n, runners[i], strlen(runners[i]));
        if (n < GOSYS_PATH_MAX && access(buf, F_OK) == 0) {
            h->runner_len = (uint32_t)n;
            memcpy(h->runner, buf, n);
            if (i < 2) h->flags |= GOSYS_FLAG_RUNNER_IS_BASH;
            break;
        }
    }
    // The directories the programs of an AArch64 rootfs load their libraries from
    static const int dirs[] = { 0, 1, 4, 5, 6 };
    size_t used = 0;
    for (size_t i = 0; i < sizeof(dirs) / sizeof(dirs[0]); i++) {
        const char *d = k_rootfs_lib_dirs[dirs[i]];
        if (i > 0) used = ddnatv_append(buf, sizeof(buf), used, ":", 1);
        used = ddnatv_append(buf, sizeof(buf), used, g_ddnatv_root, g_ddnatv_root_len);
        used = ddnatv_append(buf, sizeof(buf), used, d, strlen(d));
    }
    if (used < GOSYS_LIBS_MAX) {
        h->libs_len = (uint32_t)used;
        memcpy(h->libs, buf, used);
    }
    if (h->ldso_len == 0 || h->runner_len == 0) h->runner_len = 0; // Ubuntu programs cannot be started
    return 0;
}

static int go_pwrite_all(int fd, const void *buf, size_t len, uint64_t off) {
    return pwrite(fd, buf, len, (off_t)off) == (ssize_t)len ? 0 : -1;
}

static int go_open_for_write(int fd, const struct stat *st, const char *path, int *made_writable) {
    int wfd = open(path, O_WRONLY | O_CLOEXEC);
    if (wfd < 0 && errno == EACCES && fchmod(fd, (st->st_mode | S_IWUSR) & 07777) == 0) {
        *made_writable = 1;
        wfd = open(path, O_WRONLY | O_CLOEXEC);
    }
    return wfd; // ETXTBSY: the program is running right now, patched at its next start
}

// The bytes of the added code for a given place: the code with its header (the strings, and where
// the scratch memory is), the instructions that connect the call site to it, and the trailer that
// says what was changed to put it there.
static void go_build_image(unsigned char *img, const struct gosys_hdr *strings, uint64_t cave_v, uint64_t scratch_v,
                           uint32_t to_entry, uint32_t back, const struct gosys_trailer *info) {
    memcpy(img, gosys_blob, GOSYS_BLOB_SIZE);
    struct gosys_hdr h = *strings;
    h.scratch_rel = (int64_t)scratch_v - (int64_t)cave_v;
    memcpy(img, &h, sizeof(h));
    unsigned char *th = img + GOSYS_THUNKS_OFF;
    wr32(th + 0, 0xd10043ffu);  // sub  sp, sp, #16
    wr32(th + 4, 0xf90003feu);  // str  x30, [sp]
    wr32(th + 8, 0xaa0803e7u);  // mov  x7, x8        (the number is the eighth argument)
    wr32(th + 12, to_entry);    // bl   entry
    wr32(th + 16, 0xf94003feu); // ldr  x30, [sp]
    wr32(th + 20, 0x910043ffu); // add  sp, sp, #16
    wr32(th + 24, back);        // b    <the instruction after the call>
    struct gosys_trailer t;
    memcpy(&t, img + GOSYS_BLOB_SIZE - sizeof(t), sizeof(t));
    t.version = GOSYS_VERSION;
    t.total_size = GOSYS_BLOB_SIZE;
    t.text_orig_filesz = info->text_orig_filesz;
    t.text_orig_flags = info->text_orig_flags;
    t.rw_orig_memsz = info->rw_orig_memsz;
    t.text_index = info->text_index;
    t.rw_index = info->rw_index;
    t.site_file_off = info->site_file_off;
    memcpy(img + GOSYS_BLOB_SIZE - sizeof(t), &t, sizeof(t));
}

// A short fingerprint of the added code, so that a program patched by an older The native userland is looked at
// again (the marker of a prepared program has it)
static uint32_t go_blob_fingerprint(void) {
    static uint32_t fp;
    if (fp == 0) {
        uint32_t h = 2166136261u;
        for (size_t i = 0; i < GOSYS_BLOB_SIZE; i++) h = (h ^ gosys_blob[i]) * 16777619u;
        fp = h ? h : 1;
    }
    return fp;
}

// Gives the program its old segments and call back: the added code is zeroed, the data segment
// is shortened, the instruction is a "svc #0" again.
static int go_undo(int wfd, struct go_ph *ph, int cave_seg, const struct gosys_trailer *tr, int64_t site_off) {
    uint64_t cave_off = ph[cave_seg].offset + ph[cave_seg].filesz - tr->total_size;
    unsigned char word[4];
    wr32(word, GO_SVC);
    if (go_pwrite_all(wfd, word, 4, (uint64_t)site_off) != 0) return -1;
    unsigned char sz[16];
    go_wr64(sz, tr->text_orig_filesz);
    go_wr64(sz + 8, tr->text_orig_filesz);
    if (go_pwrite_all(wfd, sz, 16, ph[cave_seg].pos + 32) != 0) return -1;
    wr32(word, tr->text_orig_flags);
    if (go_pwrite_all(wfd, word, 4, ph[cave_seg].pos + 4) != 0) return -1;
    if (tr->rw_index < GO_MAX_PH) {
        unsigned char mem[8];
        go_wr64(mem, tr->rw_orig_memsz);
        if (go_pwrite_all(wfd, mem, 8, ph[tr->rw_index].pos + 40) != 0) return -1;
    }
    unsigned char *zero = (unsigned char *)calloc(1, tr->total_size);
    if (!zero) return -1;
    int rc = go_pwrite_all(wfd, zero, tr->total_size, cave_off);
    free(zero);
    return rc;
}

// DDNATV_NO_GOSYS=1 (any value but empty or 0): no code is added to Go programs, and the code that is
// in one is taken out again, so that a program can be run as it was built, to see whether a fault
// is the program's or the code's. The marker of a prepared program says it (static_marker).
static int go_disabled(void) {
    const char *v = getenv("DDNATV_NO_GOSYS");
    return v && v[0] != '\0' && strcmp(v, "0") != 0;
}

// The three branches that connect the call site and the code: site -> thunk, thunk -> entry (bl),
// thunk -> the instruction after the site. 0 when one of them is too far.
static int go_branches(uint64_t cave_v, uint64_t site_v, uint32_t *to_thunk, uint32_t *to_entry, uint32_t *back) {
    uint64_t thunk_v = cave_v + GOSYS_THUNKS_OFF;
    *to_thunk = go_branch(0x14000000u, (int64_t)thunk_v - (int64_t)site_v);
    *to_entry = go_branch(0x94000000u, (int64_t)(cave_v + GOSYS_ENTRY_OFF) - (int64_t)(thunk_v + 12));
    *back = go_branch(0x14000000u, (int64_t)(site_v + 4) - (int64_t)(thunk_v + 24));
    return *to_thunk && *to_entry && *back;
}

// Adds the code to the program, or brings it up to date. Returns 1 when it changed the file, 0 when
// there was nothing to do (not a Go program of a kind the code can serve, or already done), -1 when
// the file could not be changed (it is started as it is).
static int prepare_go_program(int fd, const char *path, const struct stat *st) {
    struct go_ph ph[GO_MAX_PH];
    uint16_t etype = 0, machine = 0;
    int n = go_read_phdrs(fd, ph, &etype, &machine);
    if (n < 0) return -1;
    if (machine != EM_AARCH64 || (etype != GO_ET_EXEC && etype != GO_ET_DYN)) return 0;

    int found = 0;
    int64_t site_off = go_find_syscall6(fd, ph, n, &found);
    if (found != 1) return 0; // not a Go of 1.19 or newer (or its function is not there once)

    // The loadable segments, by address
    int order[GO_MAX_PH], nl = 0;
    for (int i = 0; i < n; i++) {
        if (ph[i].type != 1 /* PT_LOAD */) continue;
        int k = nl++;
        while (k > 0 && ph[order[k - 1]].vaddr > ph[i].vaddr) {
            order[k] = order[k - 1];
            k--;
        }
        order[k] = i;
    }
    if (nl < 2) return 0;

    // The call site must be in a segment that is mapped as code
    uint64_t site_v = 0;
    for (int i = 0; i < nl; i++) {
        const struct go_ph *s = &ph[order[i]];
        if ((s->flags & GO_PF_X) && (uint64_t)site_off >= s->offset && (uint64_t)site_off < s->offset + s->filesz)
            site_v = s->vaddr + ((uint64_t)site_off - s->offset);
    }
    if (site_v == 0) return 0;

    struct gosys_hdr strings;
    memset(&strings, 0, sizeof(strings));
    memcpy(strings.magic, "CXGOSYS1", 8);
    strings.version = GOSYS_VERSION;
    if (go_make_header(&strings) != 0) return 0;

    unsigned char *img = (unsigned char *)malloc(GOSYS_BLOB_SIZE);
    unsigned char *have = (unsigned char *)malloc(GOSYS_BLOB_SIZE);
    if (!img || !have) {
        free(img);
        free(have);
        return -1;
    }
    int wfd = -1, made_writable = 0, changed = 0, rc = 0;
    unsigned char word[4];

    // An earlier run's code: it ends the segment it is in, and says what it changed
    int cave_seg = -1;
    struct gosys_trailer tr;
    memset(&tr, 0, sizeof(tr));
    for (int i = 0; i < nl && cave_seg < 0; i++) {
        const struct go_ph *s = &ph[order[i]];
        if (s->filesz < sizeof(tr) + sizeof(struct gosys_hdr)) continue;
        struct gosys_trailer t;
        if (pread(fd, &t, sizeof(t), (off_t)(s->offset + s->filesz - sizeof(t))) != (ssize_t)sizeof(t)) continue;
        if (memcmp(t.magic, "CXGOEND1", 8) != 0 || t.total_size > s->filesz || t.total_size < sizeof(t) + sizeof(struct gosys_hdr)) continue;
        tr = t;
        cave_seg = order[i];
    }

    if (go_disabled()) {
        if (cave_seg >= 0) {
            wfd = go_open_for_write(fd, st, path, &made_writable);
            if (wfd < 0) {
                rc = -1;
            } else {
                rc = go_undo(wfd, ph, cave_seg, &tr, site_off);
                changed = rc == 0;
            }
        }
        goto done;
    }

    if (cave_seg >= 0) {
        uint64_t cave_off = ph[cave_seg].offset + ph[cave_seg].filesz - tr.total_size;
        uint64_t cave_v = ph[cave_seg].vaddr + (cave_off - ph[cave_seg].offset);
        int same = 0, site_ok = 0;
        uint32_t to_thunk = 0, to_entry = 0, back = 0;
        // Is it what would be put there now? (the code of an older The native userland differs)
        if (tr.total_size == GOSYS_BLOB_SIZE && tr.rw_index < (uint32_t)n && ph[tr.rw_index].type == 1 &&
            go_branches(cave_v, site_v, &to_thunk, &to_entry, &back)) {
            uint64_t scratch_v = go_align_up(ph[tr.rw_index].vaddr + tr.rw_orig_memsz, 64);
            go_build_image(img, &strings, cave_v, scratch_v, to_entry, back, &tr);
            same = pread(fd, have, GOSYS_BLOB_SIZE, (off_t)cave_off) == (ssize_t)GOSYS_BLOB_SIZE &&
                   memcmp(have, img, GOSYS_BLOB_SIZE) == 0 &&
                   ph[tr.rw_index].memsz == scratch_v - ph[tr.rw_index].vaddr + GOSYS_SCRATCH_SIZE;
        }
        unsigned char cur[4];
        site_ok = same && pread(fd, cur, 4, (off_t)site_off) == 4 && rd32(cur) == to_thunk;
        if (same && site_ok) goto done;

        wfd = go_open_for_write(fd, st, path, &made_writable);
        if (wfd < 0) {
            rc = -1;
            goto done;
        }
        if (same) {
            // The code is there, only the branch is not (a patch that was cut short)
            wr32(word, to_thunk);
            rc = go_pwrite_all(wfd, word, 4, (uint64_t)site_off);
            changed = rc == 0;
            goto done;
        }
        // An older code, or other strings: back to the plain program, then patch again
        if (go_undo(wfd, ph, cave_seg, &tr, site_off) != 0) {
            rc = -1;
            goto done;
        }
        close(wfd);
        wfd = -1;
        if (made_writable) fchmod(fd, st->st_mode & 07777);
        free(img);
        free(have);
        return prepare_go_program(fd, path, st);
    }

    // A place for the code: the end of a segment that is followed by padding up to the next one
    uint64_t total = GOSYS_BLOB_SIZE;
    int text = -1;
    uint64_t cave_v = 0, cave_off = 0;
    for (int pass = 0; pass < 2 && text < 0; pass++) {
        for (int k = 0; k + 1 < nl && text < 0; k++) {
            const struct go_ph *s = &ph[order[k]], *nx = &ph[order[k + 1]];
            if (pass == 0 ? !(s->flags & GO_PF_X) : (s->flags & GO_PF_W)) continue;
            if (s->memsz != s->filesz || s->filesz == 0) continue;
            uint64_t v = go_align_up(s->vaddr + s->filesz, 64);
            uint64_t off = s->offset + (v - s->vaddr);
            if (v + total > nx->vaddr || (nx->vaddr & 0x3fff) != 0) continue;
            if (off + total > nx->offset || off + total > (uint64_t)st->st_size) continue;
            // Only padding may be written over
            uint64_t pad_from = s->offset + s->filesz, pad_len = off + total - pad_from;
            unsigned char *pad = (unsigned char *)malloc(pad_len);
            if (!pad) continue;
            int zero = pread(fd, pad, pad_len, (off_t)pad_from) == (ssize_t)pad_len;
            for (uint64_t i = 0; zero && i < pad_len; i++) zero = pad[i] == 0;
            free(pad);
            if (!zero) continue;
            text = order[k];
            cave_v = v;
            cave_off = off;
        }
    }
    int rw = order[nl - 1];
    uint32_t to_thunk = 0, to_entry = 0, back = 0;
    if (text < 0 || !(ph[rw].flags & GO_PF_W) || rw == text || !go_branches(cave_v, site_v, &to_thunk, &to_entry, &back)) goto done;

    uint64_t scratch_v = go_align_up(ph[rw].vaddr + ph[rw].memsz, 64);
    struct gosys_trailer info;
    memset(&info, 0, sizeof(info));
    info.text_orig_filesz = ph[text].filesz;
    info.text_orig_flags = ph[text].flags;
    info.rw_orig_memsz = ph[rw].memsz;
    info.text_index = (uint32_t)text;
    info.rw_index = (uint32_t)rw;
    info.site_file_off = (uint64_t)site_off;
    go_build_image(img, &strings, cave_v, scratch_v, to_entry, back, &info);

    wfd = go_open_for_write(fd, st, path, &made_writable);
    if (wfd < 0) {
        rc = -1;
        goto done;
    }
    // The code first and the call site last: whatever is cut short leaves a program that runs
    unsigned char sz[16];
    unsigned char mem[8];
    go_wr64(mem, scratch_v - ph[rw].vaddr + GOSYS_SCRATCH_SIZE);
    go_wr64(sz, cave_v + GOSYS_BLOB_SIZE - ph[text].vaddr);
    go_wr64(sz + 8, cave_v + GOSYS_BLOB_SIZE - ph[text].vaddr);
    rc = go_pwrite_all(wfd, img, GOSYS_BLOB_SIZE, cave_off);
    if (rc == 0) rc = go_pwrite_all(wfd, mem, 8, ph[rw].pos + 40);
    if (rc == 0) rc = go_pwrite_all(wfd, sz, 16, ph[text].pos + 32);
    if (rc == 0 && !(ph[text].flags & GO_PF_X)) {
        wr32(word, ph[text].flags | GO_PF_X);
        rc = go_pwrite_all(wfd, word, 4, ph[text].pos + 4);
    }
    if (rc == 0) {
        wr32(word, to_thunk);
        rc = go_pwrite_all(wfd, word, 4, (uint64_t)site_off);
    }
    changed = rc == 0;

done:
    free(img);
    free(have);
    if (wfd >= 0) {
        // Keep the times: make and friends must not see a rebuilt program.
        const struct timespec times[2] = { st->st_atim, st->st_mtim };
        futimens(wfd, times);
        close(wfd);
    }
    if (made_writable) fchmod(fd, st->st_mode & 07777);
    if (rc != 0) return -1;
    return changed ? 1 : 0;
}

#endif /* DDNATV_HAVE_GOSYS */
