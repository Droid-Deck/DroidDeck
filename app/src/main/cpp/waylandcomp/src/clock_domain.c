#define _GNU_SOURCE
#include "clock_domain.h"

#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static const int64_t *g_shift;
static char g_path[PATH_MAX];

void droiddeck_clock_domain_set_control(const char *path) {
    int fd;
    struct stat st;
    if (!path || !*path) return;
    if (g_shift) munmap((void *)g_shift, sizeof(*g_shift));
    g_shift = NULL;
    if (snprintf(g_path, sizeof(g_path), "%s", path) >= (int)sizeof(g_path)) {
        g_path[0] = 0;
        return;
    }
    fd = open(g_path, O_RDONLY | O_CLOEXEC);
    if (fd < 0 || fstat(fd, &st) != 0 || st.st_size < (off_t)sizeof(int64_t)) {
        if (fd >= 0) close(fd);
        return;
    }
    g_shift = mmap(NULL, sizeof(*g_shift), PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    if (g_shift == MAP_FAILED) g_shift = NULL;
}

static int64_t shift_ns(void) {
    int64_t shift = g_shift ? __atomic_load_n(g_shift, __ATOMIC_RELAXED) : 0;
    return shift > 0 ? shift : 0;
}

int64_t droiddeck_clock_domain_adjust_ns(int64_t value) {
    int64_t shift = shift_ns();
    return value > shift ? value - shift : 0;
}

int64_t droiddeck_clock_domain_now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return droiddeck_clock_domain_adjust_ns((int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec);
}

uint32_t droiddeck_clock_domain_now_ms(void) {
    return (uint32_t)(droiddeck_clock_domain_now_ns() / 1000000LL);
}
