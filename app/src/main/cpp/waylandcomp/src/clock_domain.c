#define _GNU_SOURCE
#include "clock_domain.h"

#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static const int64_t *g_shift;
static pthread_mutex_t g_control_lock = PTHREAD_MUTEX_INITIALIZER;
static dev_t g_device;
static ino_t g_inode;

void droiddeck_clock_domain_set_control(const char *path) {
    int fd;
    struct stat st;
    if (!path || !*path) return;
    pthread_mutex_lock(&g_control_lock);
    fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0 || fstat(fd, &st) != 0 || st.st_size < (off_t)sizeof(int64_t)) {
        if (fd >= 0) close(fd);
        pthread_mutex_unlock(&g_control_lock);
        return;
    }
    if (__atomic_load_n(&g_shift, __ATOMIC_ACQUIRE) && st.st_dev == g_device && st.st_ino == g_inode) {
        close(fd);
        pthread_mutex_unlock(&g_control_lock);
        return;
    }
    const int64_t *shift = mmap(NULL, sizeof(*shift), PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    if (shift != MAP_FAILED) {
        g_device = st.st_dev;
        g_inode = st.st_ino;
        __atomic_store_n(&g_shift, shift, __ATOMIC_RELEASE);
    }
    pthread_mutex_unlock(&g_control_lock);
}

static int64_t shift_ns(void) {
    const int64_t *mapping = __atomic_load_n(&g_shift, __ATOMIC_ACQUIRE);
    int64_t shift = mapping ? __atomic_load_n(mapping, __ATOMIC_RELAXED) : 0;
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
