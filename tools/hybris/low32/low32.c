/* Experimental per-process PowerVR device-backed low-address mmap adapter.
 * No copied/shadow memory, changed device offset or MAP_FIXED replacement.
 * Default is off. Enable only for the identified render-device rdev and the
 * target x86/WoW64 game. Native 64-bit Linux/glibc preload, not an Android ABI DLL.
 */
#define _GNU_SOURCE
#define _LARGEFILE64_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <inttypes.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif

static _Atomic uint64_t cursor;
static _Atomic unsigned logged;
typedef void *(*map_function)(void *,size_t,int,int,int,off_t);
typedef void *(*map64_function)(void *,size_t,int,int,int,off64_t);
static _Atomic(map_function) next_map;
static _Atomic(map64_function) next_map64;
_Static_assert(sizeof(off_t) == sizeof(off64_t) && sizeof(off_t) == 8,
               "64-bit host required");
static __thread int resolving __attribute__((tls_model("initial-exec")));
static __thread int active __attribute__((tls_model("initial-exec")));
static void *native_map(void *addr, size_t length, int prot, int flags, int fd, off_t offset)
{
    map_function next=atomic_load(&next_map);
    if (!next && !resolving) {
        resolving=1;
        next=(map_function)dlsym(RTLD_NEXT,"mmap");
        atomic_store(&next_map,next);
        resolving=0;
    }
    /* Bootstrap only while resolving; otherwise preserve existing preload hooks. */
    if (next) return next(addr,length,prot,flags,fd,offset);
    return (void *)syscall(SYS_mmap, addr, length, prot, flags, fd, offset);
}

static void *native_map64(void *addr, size_t length, int prot, int flags, int fd, off_t offset)
{
    map64_function next=atomic_load(&next_map64);
    if (!next && !resolving) {
        resolving=1;
        next=(map64_function)dlsym(RTLD_NEXT,"mmap64");
        atomic_store(&next_map64,next);
        resolving=0;
    }
    if (next) return next(addr,length,prot,flags,fd,(off64_t)offset);
    return (void *)syscall(SYS_mmap, addr, length, prot, flags, fd, offset);
}

static void trace_map(const char *kind, size_t n, int prot, int flags, int fd,
                      off_t off, const struct stat *st, void *p, int error)
{
    int saved = errno;
    unsigned count = atomic_fetch_add(&logged, 1);
    if (count < 256 || error != 0) {
        char text[320];
        int nbytes = snprintf(text, sizeof(text),
            "DD_LOW32 %s pid=%ld len=%zu prot=%x flags=%x fd=%d off=%jd mode=%o rdev=%ju ptr=%p errno=%d\n",
            kind, (long)getpid(), n, prot, flags, fd, (intmax_t)off, st ? st->st_mode : 0,
            st ? (uintmax_t)st->st_rdev : 0, p, error);
        if (nbytes > 0) {
            ssize_t written = write(STDERR_FILENO, text,
                (size_t)nbytes < sizeof(text) ? (size_t)nbytes : sizeof(text)-1);
            (void)written;
        }
    }
    errno = saved;
}

static int target_device(int fd, struct stat *st)
{
    const char *value = getenv("DROIDDECK_LOW32_RDEV");
    char *end = NULL;
    if (!value || !*value || fd < 0) return 0;
    int saved = errno;
    errno = 0;
    uintmax_t expected = strtoumax(value, &end, 10);
    int valid = !errno && end && !*end && expected > 0 &&
                fstat(fd, st) == 0 && S_ISCHR(st->st_mode) &&
                (uintmax_t)st->st_rdev == expected;
    errno = saved;
    return valid;
}

static void *adapted_map(map_function forward, void *addr, size_t length, int prot,
                         int flags, int fd, off_t offset)
{
    const char *mode = getenv("DROIDDECK_LOW32_MODE");
    struct stat st = {0};
    if (mode && !strcmp(mode, "trace")) {
        void *p = forward(addr, length, prot, flags, fd, offset);
        if (fd >= 0 && (prot & PROT_WRITE) && !(prot & PROT_EXEC) &&
            (flags & MAP_TYPE) == MAP_SHARED) {
            int saved=errno;
            (void)fstat(fd,&st);
            errno=saved;
            trace_map("trace", length, prot, flags, fd, offset, &st, p,
                      p == MAP_FAILED ? errno : 0);
        }
        return p;
    }
    if (!mode || strcmp(mode, "low32") || addr || !length ||
        (flags & (MAP_ANONYMOUS | MAP_FIXED | MAP_FIXED_NOREPLACE)) ||
        (prot & PROT_EXEC) || (flags & MAP_TYPE) != MAP_SHARED ||
        !target_device(fd, &st))
        return forward(addr, length, prot, flags, fd, offset);

    const uintptr_t lower = 0x10000000u, upper = 0xffff0000u;
    const uintptr_t step = 0x00400000u;
    if (length > upper-lower) {
        errno = ENOMEM;
        trace_map("too_large", length, prot, flags, fd, offset, &st, MAP_FAILED, errno);
        return MAP_FAILED;
    }
    long page_value=sysconf(_SC_PAGESIZE);
    uintptr_t page=page_value>0?(uintptr_t)page_value:4096;
    uintptr_t rounded=(length+page-1)&~(page-1);
    uintptr_t span=upper-lower;
    uintptr_t compact=(uintptr_t)(atomic_fetch_add(&cursor,rounded)%span);
    if (rounded>span-compact) compact=0;
    unsigned slots = (unsigned)((span-length)/step)+1;
    unsigned start = (unsigned)(compact/step)%slots;
    int original_errno = errno;
    for (unsigned i = 0; i < 1; i++) {
        /* Compact first: hundreds of small driver mappings must not waste a
         * 4MiB address slot each. Coarse search is only for existing Wine holes. */
        uintptr_t hint = lower + (i==0?compact:(uintptr_t)((start+i-1)%slots)*step);
        void *p = forward((void *)hint, length, prot,
                             flags|MAP_FIXED_NOREPLACE, fd, offset);
        if (p != MAP_FAILED) {
            uintptr_t first = (uintptr_t)p;
            if (first >= lower && first < upper && length <= upper-first) {
                errno = original_errno;
                trace_map("mapped", length, prot, flags, fd, offset, &st, p, 0);
                return p;
            }
            /* Older kernels can ignore NOREPLACE; never return a high mapping. */
            int saved = errno;
            (void)syscall(SYS_munmap, p, length);
            errno = saved;
        } else if (errno != EEXIST && errno != ENOMEM && errno != EAGAIN) {
            trace_map("rejected", length, prot, flags, fd, offset, &st, p, errno);
            return p;
        }
    }
    /* Pack small allocations into address holes before coarse probing can
     * fragment large holes. Walk
     * this process's current mappings, then use NOREPLACE to tolerate races.
     * No pointer paths or contents are read; only address ranges are used. */
    for (unsigned pass = 0; pass < 2; ++pass) {
        FILE *maps = fopen("/proc/self/maps", "r");
        if (!maps) break;
        char line[512];
        uintptr_t gap_start = lower;
        unsigned lines = 0;
        while (fgets(line, sizeof(line), maps) && ++lines < 65536) {
            uintptr_t first, last;
            if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR, &first, &last) != 2) continue;
            if (last <= gap_start) continue;
            uintptr_t gap_end = first < upper ? first : upper;
            if (gap_end > gap_start && rounded <= gap_end - gap_start) {
                void *p = forward((void *)gap_start, length, prot,
                    flags | MAP_FIXED_NOREPLACE, fd, offset);
                if (p != MAP_FAILED) {
                    uintptr_t address = (uintptr_t)p;
                    if (address >= lower && address < upper && length <= upper - address) {
                        fclose(maps);
                        errno = original_errno;
                        trace_map("mapped_gap", length, prot, flags, fd, offset, &st, p, 0);
                        return p;
                    }
                    (void)syscall(SYS_munmap, p, length);
                } else if (errno != EEXIST && errno != ENOMEM && errno != EAGAIN) {
                    int saved = errno;
                    fclose(maps);
                    errno = saved;
                    trace_map("rejected", length, prot, flags, fd, offset, &st, MAP_FAILED, errno);
                    return MAP_FAILED;
                }
            }
            if (last > gap_start) gap_start = last;
            if (gap_start >= upper) break;
        }
        if (gap_start < upper && rounded <= upper - gap_start) {
            void *p = forward((void *)gap_start, length, prot,
                flags | MAP_FIXED_NOREPLACE, fd, offset);
            if (p != MAP_FAILED) {
                uintptr_t address = (uintptr_t)p;
                if (address >= lower && address < upper && length <= upper - address) {
                    fclose(maps);
                    errno = original_errno;
                    trace_map("mapped_gap", length, prot, flags, fd, offset, &st, p, 0);
                    return p;
                }
                (void)syscall(SYS_munmap, p, length);
            }
        }
        fclose(maps);
    }
    for (unsigned i = 1; i <= slots; i++) {
        /* Last resort if address walking was unavailable or raced. */
        uintptr_t hint = lower + (i==0?compact:(uintptr_t)((start+i-1)%slots)*step);
        void *p = forward((void *)hint, length, prot,
                             flags|MAP_FIXED_NOREPLACE, fd, offset);
        if (p != MAP_FAILED) {
            uintptr_t first = (uintptr_t)p;
            if (first >= lower && first < upper && length <= upper-first) {
                errno = original_errno;
                trace_map("mapped", length, prot, flags, fd, offset, &st, p, 0);
                return p;
            }
            /* Older kernels can ignore NOREPLACE; never return a high mapping. */
            int saved = errno;
            (void)syscall(SYS_munmap, p, length);
            errno = saved;
        } else if (errno != EEXIST && errno != ENOMEM && errno != EAGAIN) {
            trace_map("rejected", length, prot, flags, fd, offset, &st, p, errno);
            return p;
        }
    }
    errno = ENOMEM;
    trace_map("no_low_hole", length, prot, flags, fd, offset, &st, MAP_FAILED, errno);
    return MAP_FAILED;
}

static void *intercept_map(map_function forward, void *addr, size_t length,
                           int prot, int flags, int fd, off_t offset)
{
    if (active) return forward(addr,length,prot,flags,fd,offset);
    active=1;
    void *p=adapted_map(forward,addr,length,prot,flags,fd,offset);
    active=0;
    return p;
}

void *mmap(void *addr, size_t length, int prot, int flags, int fd, off_t offset)
{
    return intercept_map(native_map,addr,length,prot,flags,fd,offset);
}

void *mmap64(void *addr, size_t length, int prot, int flags, int fd, off64_t offset)
{
    return intercept_map(native_map64,addr,length,prot,flags,fd,(off_t)offset);
}
