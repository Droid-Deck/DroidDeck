/* Controlled second preload: distinct mmap/mmap64 counters and 64-bit offsets. */
#define _GNU_SOURCE
#define _LARGEFILE64_SOURCE
#include <dlfcn.h>
#include <stdint.h>
#include <sys/mman.h>
#include <sys/types.h>

static int selected_fd = -2;
static unsigned calls[2];
static int64_t offsets[2];

void chain_clear(int fd)
{
    selected_fd = fd;
    calls[0] = calls[1] = 0;
    offsets[0] = offsets[1] = -1;
}

unsigned chain_calls(unsigned channel) { return calls[channel]; }
int64_t chain_offset(unsigned channel) { return offsets[channel]; }

void *mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off)
{
    static void *(*next)(void *, size_t, int, int, int, off_t);
    if (!next) next = dlsym(RTLD_NEXT, "mmap");
    if (fd == selected_fd) { ++calls[0]; offsets[0] = off; }
    return next(addr, len, prot, flags, fd, off);
}

void *mmap64(void *addr, size_t len, int prot, int flags, int fd, off64_t off)
{
    static void *(*next)(void *, size_t, int, int, int, off64_t);
    if (!next) next = dlsym(RTLD_NEXT, "mmap64");
    if (fd == selected_fd) { ++calls[1]; offsets[1] = off; }
    return next(addr, len, prot, flags, fd, off);
}
