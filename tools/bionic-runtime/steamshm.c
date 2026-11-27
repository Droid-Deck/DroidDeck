// Valve's Android libsteamclient keeps its named shared memory ("/u<uid>-Shm_<crc>" streams and the
// "/u<uid>-ValveIPCSharedObj-Steam" object that holds the named locks and events) in a table inside the process, so
// the Steam Input data DroidDeck's Linux Steam publishes in /dev/shm never reaches a game on DroidDeck-Bionic. Once
// that library is loaded, this preload makes it use the files the Linux client uses: DROIDDECK_BIONIC_SHM_DIR
// (DroidDeck's /dev/shm) under Steam's user id. DROIDDECK_BIONIC_STEAMSHM=0 turns it off.
// The library's thread semaphores come from sem_open with a per-object name ("%p"), which Android's libc does not
// implement (it always fails), so this preload also gives sem_open/sem_close a table of in-process semaphores.
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <link.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <semaphore.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#define EXPORT __attribute__((visibility("default")))

typedef int (*shm_open_fn)(const char *name, size_t size, bool *created);
typedef void (*shm_unlink_fn)(const char *name);

static shm_open_fn orig_open;
static shm_unlink_fn orig_unlink;
static char shm_dir[PATH_MAX];
static char steam_uid[16];
static int notes;

static void say(const char *fmt, ...)
{
    char b[600];
    va_list a;
    va_start(a, fmt);
    int n = vsnprintf(b, sizeof b, fmt, a);
    va_end(a);
    if (n > 0) write(2, b, n < (int)sizeof b ? (size_t)n : sizeof b - 1);
}

// "/u<digits>-Shm_<hex>" or "/u<digits>-ValveIPCSharedObj-Steam": the part after the uid, else NULL.
static const char *shared_part(const char *name)
{
    if (!name || name[0] != '/' || name[1] != 'u') return NULL;
    const char *p = name + 2;
    while (*p >= '0' && *p <= '9') p++;
    if (p == name + 2 || *p != '-') return NULL;
    if (!strcmp(p + 1, "ValveIPCSharedObj-Steam")) return p + 1;
    const char *h = p + 5;
    if (strncmp(p + 1, "Shm_", 4)) return NULL;
    while ((*h >= '0' && *h <= '9') || (*h >= 'a' && *h <= 'f')) h++;
    return h > p + 5 && h <= p + 13 && !*h ? p + 1 : NULL;
}

// The uid Steam put in its IPC object's name (DroidDeck's Linux side reports 0).
static const char *uid_of_steam(void)
{
    if (steam_uid[0]) return steam_uid;
    DIR *d = opendir(shm_dir);
    struct dirent *e;
    while (d && (e = readdir(d))) {
        const char *n = e->d_name, *p = n + 1;
        if (n[0] != 'u') continue;
        while (*p >= '0' && *p <= '9') p++;
        if (p > n + 1 && p - n - 1 < (long)sizeof steam_uid && !strcmp(p, "-ValveIPCSharedObj-Steam")) {
            memcpy(steam_uid, n + 1, (size_t)(p - n - 1));
            steam_uid[p - n - 1] = 0;
            break;
        }
    }
    if (d) closedir(d);
    if (!steam_uid[0]) return "0";
    return steam_uid;
}

static bool real_path(const char *part, char *out, size_t n)
{
    int w = snprintf(out, n, "%s/u%s-%s", shm_dir, uid_of_steam(), part);
    return w > 0 && (size_t)w < n;
}

static int hook_open(const char *name, size_t size, bool *created)
{
    const char *part = shared_part(name);
    char path[PATH_MAX];
    if (!part || !real_path(part, path, sizeof path)) return orig_open(name, size, created);
    for (int i = 0; i < 4; i++) {
        int fd = open(path, O_RDWR | O_CLOEXEC);
        if (fd >= 0) {
            if (notes++ < 64) say("steamshm: %s opened (%zu bytes)\n", path, size);
            return fd;
        }
        if (errno != ENOENT) break;
        // made and sized under a private name, then put in place in one step: no other process can open it while it
        // is still empty, and an object another process published first is opened instead
        char tmp[PATH_MAX];
        int w = snprintf(tmp, sizeof tmp, "%s.%d.%d", path, (int)getpid(), i);
        if (w <= 0 || (size_t)w >= sizeof tmp) break;
        fd = open(tmp, O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
        if (fd < 0) break;
        if (ftruncate(fd, (off_t)size) != 0) {
            close(fd);
            unlink(tmp);
            break;
        }
        int placed = (int)syscall(SYS_renameat2, AT_FDCWD, tmp, AT_FDCWD, path, 1 /* RENAME_NOREPLACE */);
        if (placed != 0 && (errno == ENOSYS || errno == EINVAL)) {  // no renameat2: a hard link fails on EEXIST too
            placed = link(tmp, path);
            unlink(tmp);
        }
        if (placed == 0) {
            if (created) *created = true;
            if (notes++ < 64) say("steamshm: %s created (%zu bytes)\n", path, size);
            return fd;
        }
        int err = errno;
        close(fd);
        unlink(tmp);
        errno = err;
        if (err != EEXIST) break;
    }
    say("steamshm: %s failed (errno %d)\n", path, errno);
    return -1;
}

static void hook_unlink(const char *name)
{
    const char *part = shared_part(name);
    char path[PATH_MAX];
    // Steam's IPC object belongs to Steam: never removed from a game
    if (!part || !strncmp(part, "Valve", 5) || !real_path(part, path, sizeof path)) {
        orig_unlink(name);
        return;
    }
    if (unlink(path) == 0 && notes++ < 64) say("steamshm: %s removed\n", path);
}

struct object {
    uintptr_t base;
    uintptr_t lo[8], hi[8];
    bool exec[8];
    int n;
};

static int find_object(struct dl_phdr_info *info, size_t size, void *data)
{
    (void)size;
    struct object *o = data;
    const char *n = info->dlpi_name;
    size_t l = n ? strlen(n) : 0;
    if (l < 18 || strcmp(n + l - 18, "/libsteamclient.so")) return 0;
    o->base = info->dlpi_addr;
    o->n = 0;
    for (int i = 0; i < info->dlpi_phnum && o->n < 8; i++) {
        const ElfW(Phdr) *p = &info->dlpi_phdr[i];
        if (p->p_type != PT_LOAD) continue;
        o->lo[o->n] = info->dlpi_addr + p->p_vaddr;
        o->hi[o->n] = o->lo[o->n] + p->p_memsz;
        o->exec[o->n] = (p->p_flags & PF_X) != 0;
        o->n++;
    }
    return 1;
}

static bool readable(const struct object *o, uintptr_t a, size_t n)
{
    for (int i = 0; i < o->n; i++)
        if (a >= o->lo[i] && a + n <= o->hi[i]) return true;
    return false;
}

static int64_t sext(uint64_t v, int bits)
{
    return (int64_t)(v << (64 - bits)) >> (64 - bits);
}

// adrp xd at w[0] and add xd, xd, #imm at w[1]: the address they form, or 0.
static uintptr_t adrp_add(const uint32_t *w, int reg)
{
    if ((w[0] & 0x9f000000) != 0x90000000 || (int)(w[0] & 31) != reg) return 0;
    if ((w[1] & 0xffc00000) != 0x91000000 || (int)(w[1] & 31) != reg || (int)((w[1] >> 5) & 31) != reg) return 0;
    uint64_t imm = ((uint64_t)((w[0] >> 5) & 0x7ffff) << 2) | ((w[0] >> 29) & 3);
    uintptr_t page = ((uintptr_t)w & ~(uintptr_t)0xfff) + (uintptr_t)(sext(imm, 21) << 12);
    return page + ((w[1] >> 10) & 0xfff);
}

static uintptr_t bl_target(const uint32_t *w)
{
    if ((w[0] & 0xfc000000) != 0x94000000) return 0;
    return (uintptr_t)w + (uintptr_t)(sext(w[0] & 0x3ffffff, 26) << 2);
}

// The first four instructions get copied into a trampoline: none may depend on the pc.
static bool relocatable(const uint32_t *w)
{
    for (int i = 0; i < 4; i++) {
        uint32_t x = w[i];
        if ((x & 0x1f000000) == 0x10000000 || (x & 0x7c000000) == 0x14000000 || (x & 0xff000010) == 0x54000000 ||
            (x & 0x7e000000) == 0x34000000 || (x & 0x7e000000) == 0x36000000 || (x & 0x3b000000) == 0x18000000)
            return false;
    }
    return true;
}

// Back from w to the frame setup: stp x29, x30, [sp, #-N]!, or sub sp, sp, #N then stp x29, x30, [sp, #M].
static uint32_t *function_start(const struct object *o, uint32_t *w)
{
    for (int i = 0; i < 256; i++, w--) {
        if (!readable(o, (uintptr_t)w - 4, 8)) return NULL;
        if ((*w & 0xffc07fff) == 0xa9807bfd) return w;
        if ((*w & 0xffc07fff) == 0xa9007bfd) return (w[-1] & 0xff8003ff) == 0xd10003ff ? w - 1 : NULL;
    }
    return NULL;
}

// open(".", O_TMPFILE | O_RDWR, 0777) in the library's shared-memory open: its only nameless file.
static uint32_t *find_shm_open(const struct object *o)
{
    uint32_t *found = NULL;
    for (int s = 0; s < o->n; s++) {
        if (!o->exec[s]) continue;
        for (uint32_t *w = (uint32_t *)o->lo[s]; (uintptr_t)(w + 6) <= o->hi[s]; w++) {
            if (w[0] != 0x52880041 || w[3] != 0x72a00801 || w[4] != 0x52803fe2 || !bl_target(w + 5)) continue;
            uintptr_t str = adrp_add(w + 1, 0);
            if (!str || !readable(o, str, 2) || memcmp((const char *)str, ".", 2)) continue;
            if (found) return NULL;
            found = w;
        }
    }
    return found ? function_start(o, found) : NULL;
}

static const char *find_string(const struct object *o, const char *s)
{
    size_t n = strlen(s) + 1;
    for (int i = 0; i < o->n; i++) {
        const char *p = memmem((const void *)o->lo[i], o->hi[i] - o->lo[i], s, n);
        if (p) return p;
    }
    return NULL;
}

// CSharedMemory::Open: snprintf of "/u%d-Shm_%x", a call to the unlink with that name, then the open.
static uint32_t *find_shm_unlink(const struct object *o, uintptr_t open_fn)
{
    const char *fmt = find_string(o, "/u%d-Shm_%x");
    uint32_t *found = NULL;
    if (!fmt) return NULL;
    for (int s = 0; s < o->n; s++) {
        if (!o->exec[s]) continue;
        for (uint32_t *w = (uint32_t *)o->lo[s]; (uintptr_t)(w + 64) <= o->hi[s]; w++) {
            if ((w[0] & 0x9f000000) != 0x90000000 || adrp_add(w, (int)(w[0] & 31)) != (uintptr_t)fmt) continue;
            uint32_t *p = w + 2, *end = w + 12;
            while (p < end && !bl_target(p)) p++;   // snprintf
            if (p == end) continue;
            uint32_t *u = p + 1;
            end = p + 5;
            while (u < end && !bl_target(u)) u++;
            if (u == end) continue;
            bool opens = false;
            for (uint32_t *q = u + 1; q < u + 48; q++)
                if (bl_target(q) == open_fn) opens = true;
            if (!opens) continue;
            uint32_t *fn = (uint32_t *)bl_target(u);
            if (found && found != fn) return NULL;
            found = fn;
        }
    }
    return found;
}

static void flush(uintptr_t a, size_t n)
{
    uint64_t ctr;
    __asm__ volatile("mrs %0, ctr_el0" : "=r"(ctr));
    uintptr_t d = 4u << ((ctr >> 16) & 15), i = 4u << (ctr & 15), x;
    for (x = a & ~(d - 1); x < a + n; x += d) __asm__ volatile("dc cvau, %0" ::"r"(x) : "memory");
    __asm__ volatile("dsb ish" ::: "memory");
    for (x = a & ~(i - 1); x < a + n; x += i) __asm__ volatile("ic ivau, %0" ::"r"(x) : "memory");
    __asm__ volatile("dsb ish\n\tisb" ::: "memory");
}

static void jump_code(uint32_t *out, uintptr_t to)
{
    out[0] = 0x58000050;   // ldr x16, #8
    out[1] = 0xd61f0200;   // br x16
    memcpy(out + 2, &to, 8);
}

static void *trampoline(uint32_t *fn)
{
    size_t page = getauxval(AT_PAGESZ);
    uint32_t *t = mmap(NULL, page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (t == MAP_FAILED) return NULL;
    memcpy(t, fn, 16);
    jump_code(t + 4, (uintptr_t)(fn + 4));
    if (mprotect(t, page, PROT_READ | PROT_EXEC)) {
        munmap(t, page);
        return NULL;
    }
    flush((uintptr_t)t, 32);
    return t;
}

static void drop_trampoline(void *t)
{
    if (t) munmap(t, getauxval(AT_PAGESZ));
}

// The library's code pages are file mappings: patched anonymous copies are prepared first and then moved over the
// originals in one step each. Both hooks go in or neither: the pages of both functions (one range when they share a
// page) are copied, patched and made executable before anything is replaced, and a copy of the original pages is
// kept, so a failed second replacement puts the first range back.
struct range {
    uintptr_t lo;
    size_t len;
    uint8_t *patched, *saved;
};

static bool prepare(struct range *r, uintptr_t lo, uintptr_t hi)
{
    r->lo = lo;
    r->len = hi - lo;
    r->patched = mmap(NULL, r->len, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    r->saved = mmap(NULL, r->len, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (r->patched == MAP_FAILED || r->saved == MAP_FAILED) {
        if (r->patched != MAP_FAILED) munmap(r->patched, r->len);
        if (r->saved != MAP_FAILED) munmap(r->saved, r->len);
        r->patched = r->saved = NULL;
        return false;
    }
    memcpy(r->patched, (void *)lo, r->len);
    memcpy(r->saved, (void *)lo, r->len);
    return true;
}

static void patch(struct range *r, uint32_t *fn, uintptr_t to)
{
    if ((uintptr_t)fn >= r->lo && (uintptr_t)fn + 16 <= r->lo + r->len)
        jump_code((uint32_t *)(r->patched + ((uintptr_t)fn - r->lo)), to);
}

static bool seal(struct range *r)
{
    return !mprotect(r->patched, r->len, PROT_READ | PROT_EXEC) && !mprotect(r->saved, r->len, PROT_READ | PROT_EXEC);
}

static bool commit(struct range *r)
{
    if (mremap(r->patched, r->len, r->len, MREMAP_MAYMOVE | MREMAP_FIXED, (void *)r->lo) == MAP_FAILED) return false;
    r->patched = NULL;  // now the mapping at lo
    flush(r->lo, r->len);
    return true;
}

static void restore(struct range *r)
{
    if (r->saved && mremap(r->saved, r->len, r->len, MREMAP_MAYMOVE | MREMAP_FIXED, (void *)r->lo) != MAP_FAILED) {
        r->saved = NULL;
        flush(r->lo, r->len);
    }
}

static void release(struct range *r)
{
    if (r->patched) munmap(r->patched, r->len);
    if (r->saved) munmap(r->saved, r->len);
    r->patched = r->saved = NULL;
}

static bool redirect_both(uint32_t *fa, uintptr_t ta, uint32_t *fb, uintptr_t tb)
{
    size_t page = getauxval(AT_PAGESZ);
    uintptr_t alo = (uintptr_t)fa & ~(page - 1), ahi = ((uintptr_t)fa + 16 + page - 1) & ~(page - 1);
    uintptr_t blo = (uintptr_t)fb & ~(page - 1), bhi = ((uintptr_t)fb + 16 + page - 1) & ~(page - 1);
    struct range r[2] = {{0}};
    int n = 1;
    if (alo < bhi && blo < ahi) {  // overlapping pages: one range holds both patches
        if (!prepare(&r[0], alo < blo ? alo : blo, ahi > bhi ? ahi : bhi)) return false;
    } else if (!prepare(&r[0], alo, ahi) || !prepare(&r[1], blo, bhi)) {
        release(&r[0]);
        release(&r[1]);
        return false;
    } else n = 2;
    for (int i = 0; i < n; i++) {
        patch(&r[i], fa, ta);
        patch(&r[i], fb, tb);
    }
    bool ok = true;
    for (int i = 0; i < n && ok; i++) ok = seal(&r[i]);
    if (ok && !commit(&r[0])) ok = false;
    else if (ok && n == 2 && !commit(&r[1])) {
        restore(&r[0]);
        ok = false;
    }
    for (int i = 0; i < n; i++) release(&r[i]);
    return ok;
}

static void install(void)
{
    const char *mode = getenv("DROIDDECK_BIONIC_STEAMSHM");
    const char *dir = getenv("DROIDDECK_BIONIC_SHM_DIR");
    struct object o = {0};
    uint32_t *open_fn, *unlink_fn = NULL;
    if (mode && !strcmp(mode, "0")) return;
    if (!dir || !*dir || strlen(dir) >= sizeof shm_dir) {
        say("steamshm: no DROIDDECK_BIONIC_SHM_DIR; Steam Input data stays in the library\n");
        return;
    }
    strcpy(shm_dir, dir);
    if (!dl_iterate_phdr(find_object, &o)) {
        say("steamshm: libsteamclient.so not found among the loaded libraries\n");
        return;
    }
    open_fn = find_shm_open(&o);
    unlink_fn = open_fn ? find_shm_unlink(&o, (uintptr_t)open_fn) : NULL;
    if (!open_fn || !unlink_fn || !relocatable(open_fn) || !relocatable(unlink_fn)) {
        say("steamshm: this libsteamclient.so does not match (open %p, unlink %p); left as it is\n",
            (void *)open_fn, (void *)unlink_fn);
        return;
    }
    orig_open = (shm_open_fn)trampoline(open_fn);
    orig_unlink = (shm_unlink_fn)trampoline(unlink_fn);
    if (!orig_open || !orig_unlink ||
        !redirect_both(unlink_fn, (uintptr_t)hook_unlink, open_fn, (uintptr_t)hook_open)) {
        drop_trampoline((void *)orig_open);
        drop_trampoline((void *)orig_unlink);
        orig_open = NULL;
        orig_unlink = NULL;
        say("steamshm: could not redirect the library's shared memory; left as it is\n");
        return;
    }
    say("steamshm: libsteamclient.so +%#lx open / +%#lx unlink use %s (Steam uid %s)\n",
        (unsigned long)((uintptr_t)open_fn - o.base), (unsigned long)((uintptr_t)unlink_fn - o.base), shm_dir,
        uid_of_steam());
}

struct named_sem {
    struct named_sem *next;
    sem_t sem;
    int refs;
    char name[];
};

static struct named_sem *sems;
static pthread_mutex_t sems_lock = PTHREAD_MUTEX_INITIALIZER;

// Named semaphores of this process only: the last sem_close frees one (there is no sem_unlink to wait for).
EXPORT sem_t *sem_open(const char *name, int oflag, ...)
{
    unsigned value = 0;
    if (oflag & O_CREAT) {
        va_list a;
        va_start(a, oflag);
        (void)va_arg(a, unsigned);   // mode
        value = va_arg(a, unsigned);
        va_end(a);
    }
    if (!*name) {
        errno = EINVAL;
        return SEM_FAILED;
    }
    sem_t *r = SEM_FAILED;
    pthread_mutex_lock(&sems_lock);
    struct named_sem *s = sems;
    while (s && strcmp(s->name, name)) s = s->next;
    if (s && (oflag & O_CREAT) && (oflag & O_EXCL)) {
        errno = EEXIST;
    } else if (s) {
        s->refs++;
        r = &s->sem;
    } else if (!(oflag & O_CREAT)) {
        errno = ENOENT;
    } else if ((s = calloc(1, sizeof *s + strlen(name) + 1))) {
        strcpy(s->name, name);
        if (sem_init(&s->sem, 0, value) == 0) {
            s->refs = 1;
            s->next = sems;
            sems = s;
            r = &s->sem;
        } else {
            free(s);
        }
    } else {
        errno = ENOMEM;
    }
    pthread_mutex_unlock(&sems_lock);
    return r;
}

EXPORT int sem_close(sem_t *sem)
{
    int r = -1;
    pthread_mutex_lock(&sems_lock);
    struct named_sem **p = &sems;
    while (*p && &(*p)->sem != sem) p = &(*p)->next;
    if (*p) {
        struct named_sem *s = *p;
        r = 0;
        if (--s->refs == 0) {
            *p = s->next;
            sem_destroy(&s->sem);
            free(s);
        }
    } else {
        errno = EINVAL;
    }
    pthread_mutex_unlock(&sems_lock);
    return r;
}

static long raw_getuid(void)
{
    register long x8 __asm__("x8") = 174;   // __NR_getuid
    register long x0 __asm__("x0");
    __asm__ volatile("svc #0" : "=r"(x0) : "r"(x8) : "memory");
    return x0;
}

// libsteamclient asks for the uid just before it names each shared-memory object, so its first call installs the
// redirect before the first open. Calls from anywhere else only return the uid.
EXPORT uid_t getuid(void)
{
    static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
    static int done;
    if (!__atomic_load_n(&done, __ATOMIC_ACQUIRE)) {
        Dl_info i;
        size_t l;
        if (dladdr(__builtin_return_address(0), &i) && i.dli_fname && (l = strlen(i.dli_fname)) >= 18 &&
            !strcmp(i.dli_fname + l - 18, "/libsteamclient.so")) {
            pthread_mutex_lock(&lock);
            if (!done) {
                install();
                __atomic_store_n(&done, 1, __ATOMIC_RELEASE);
            }
            pthread_mutex_unlock(&lock);
        }
    }
    return (uid_t)raw_getuid();
}
