#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <linux/capability.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>

extern char *program_invocation_short_name;

static int cannot_schedule_realtime(void) {
    struct rlimit priority;
    struct __user_cap_header_struct header = {_LINUX_CAPABILITY_VERSION_3, 0};
    struct __user_cap_data_struct caps[2] = {{0}};
    if (getrlimit(RLIMIT_RTPRIO, &priority) || priority.rlim_cur != 0 ||
        syscall(SYS_capget, &header, caps)) return 0;
    return !(caps[CAP_SYS_NICE / 32].effective & (UINT32_C(1) << (CAP_SYS_NICE % 32)));
}

/* Without real-time scheduling, vrcompositor's frame threads ran at the same nice level as Steam's web helpers on
 * the guest's three cores and missed refreshes. Its threads at normal priority take the highest nice level instead;
 * some are started where they do not inherit it, so they are raised again once a second. */
static int compositor_without_realtime(void) {
    return !strcmp(program_invocation_short_name, "vrcompositor") && cannot_schedule_realtime();
}

static void raise_threads(void) {
    DIR *tasks = opendir("/proc/self/task");
    if (!tasks) return;
    for (struct dirent *task; (task = readdir(tasks));) {
        const int tid = atoi(task->d_name);
        if (tid <= 0) continue;
        errno = 0;
        const int nice = getpriority(PRIO_PROCESS, (id_t)tid);
        if (!errno && nice <= 0 && nice > -20) setpriority(PRIO_PROCESS, (id_t)tid, -20);
    }
    closedir(tasks);
}

static void *raise_loop(void *unused) {
    for (;;) { raise_threads(); sleep(1); }
    return unused;
}

__attribute__((constructor)) static void raise_compositor(void) {
    if (!compositor_without_realtime()) return;
    setpriority(PRIO_PROCESS, 0, -20);
    pthread_t thread;
    if (!pthread_create(&thread, NULL, raise_loop, NULL)) pthread_detach(thread);
}

int setrlimit(__rlimit_resource_t resource, const struct rlimit *limit) {
    int (*next)(__rlimit_resource_t, const struct rlimit *) = dlsym(RTLD_NEXT, "setrlimit");
    if (!next) { errno = ENOSYS; return -1; }
    const int saved = errno;
    if (resource == RLIMIT_RTTIME &&
        !strcmp(program_invocation_short_name, "vrcompositor") && cannot_schedule_realtime()) {
        struct rlimit current;
        if (!getrlimit(resource, &current) &&
            (limit->rlim_cur < current.rlim_cur || limit->rlim_max < current.rlim_max)) {
            errno = EPERM;
            return -1;
        }
    }
    errno = saved;
    return next(resource, limit);
}
