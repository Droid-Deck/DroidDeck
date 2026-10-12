/* SPDX-License-Identifier: GPL-3.0-or-later
 * Loaded as the AdrenoTools driver; DT_NEEDED points to the original Turnip.
 * Vulkan/HAL symbols are resolved from that dependency. Its ioctl calls resolve here.
 */
#define _GNU_SOURCE
#include "kgsl_compat.h"
#include <dlfcn.h>
#include <pthread.h>
#include <stdarg.h>
#include <sys/syscall.h>
#include <unistd.h>

static int (*next_ioctl)(int, int, ...);
static pthread_once_t once = PTHREAD_ONCE_INIT;
static void resolve_ioctl(void) { next_ioctl = dlsym(RTLD_NEXT, "ioctl"); }
static int forward(int fd, unsigned long request, void *arg) {
    pthread_once(&once, resolve_ioctl);
    return next_ioctl ? next_ioctl(fd, (int)request, arg) : (int)syscall(SYS_ioctl, fd, request, arg);
}
__attribute__((visibility("default"))) int ioctl(int fd, int request, ...) {
    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    return quest_kgsl_ioctl(forward, fd, (unsigned int)request, arg);
}
