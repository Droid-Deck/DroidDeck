#define _GNU_SOURCE
#define _LARGEFILE64_SOURCE
#include <assert.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

int main(int argc, char **argv)
{
    assert(argc == 2);
    void (*clear)(int) = dlsym(RTLD_DEFAULT, "chain_clear");
    unsigned (*calls)(unsigned) = dlsym(RTLD_DEFAULT, "chain_calls");
    int64_t (*offset)(unsigned) = dlsym(RTLD_DEFAULT, "chain_offset");
    assert(clear && calls && offset);
    if (strcmp(argv[1], "off")) assert(!setenv("DROIDDECK_LOW32_MODE", argv[1], 1));
    else assert(!unsetenv("DROIDDECK_LOW32_MODE"));
    int fd = open("/dev/zero", O_RDWR | O_CLOEXEC);
    assert(fd >= 0);
    struct stat st;
    assert(!fstat(fd, &st));
    char rdev[48];
    snprintf(rdev, sizeof(rdev), "%ju", (uintmax_t)st.st_rdev);
    assert(!setenv("DROIDDECK_LOW32_RDEV", rdev, 1));
    for (unsigned channel = 0; channel < 2; ++channel) {
        clear(fd);
        void *p = channel ? mmap64(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0)
                          : mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        assert(p != MAP_FAILED);
        assert(calls(channel) == 1 && calls(1 - channel) == 0 && offset(channel) == 0);
        if (!strcmp(argv[1], "low32")) assert((uintptr_t)p + 4096 <= UINT64_C(0x100000000));
        *(unsigned *)p = 0xcafef00d;
        assert(*(unsigned *)p == 0xcafef00d && !munmap(p, 4096));
        clear(fd);
        errno = 0;
        p = channel ? mmap64(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 1)
                    : mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 1);
        assert(p == MAP_FAILED && errno == EINVAL);
        assert(calls(channel) == 1 && calls(1 - channel) == 0 && offset(channel) == 1);
    }
    close(fd);
    /* Regular-file forwarding must preserve an mmap64 offset above 4 GiB. */
    fd = memfd_create("preload-chain-test", 0);
    const off64_t large_offset = INT64_C(0x200001000);
    assert(fd >= 0 && !ftruncate64(fd, large_offset + 4096));
    unsigned value = 0x1234abcd;
    assert(pwrite64(fd, &value, sizeof(value), large_offset) == (ssize_t)sizeof(value));
    clear(fd);
    unsigned *p = mmap64(NULL, 4096, PROT_READ, MAP_SHARED, fd, large_offset);
    assert(p != MAP_FAILED && *p == value);
    assert(calls(1) == 1 && calls(0) == 0 && offset(1) == large_offset);
    assert(!munmap(p, 4096));
    close(fd);
    printf("PASS separate mmap/mmap64 chains (%s), EINVAL and >4GiB offset readback\n", argv[1]);
    return 0;
}
