#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <linux/capability.h>
#include <stdint.h>
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

int setrlimit(__rlimit_resource_t resource, const struct rlimit *limit) {
    int (*next)(__rlimit_resource_t, const struct rlimit *) = dlsym(RTLD_NEXT, "setrlimit");
    if (!next) { errno = ENOSYS; return -1; }
    const int saved = errno;
    if (resource == RLIMIT_RTTIME &&
        !strcmp(program_invocation_short_name, "vrcompositor") && cannot_schedule_realtime()) {
        struct rlimit current;
        if (!getrlimit(resource, &current) &&
            (limit->rlim_cur < current.rlim_cur || limit->rlim_max < current.rlim_max)) {
            // SDL's failed RTKit fallback leaves a fatal RT budget on Android GPU submissions.
            errno = EPERM;
            return -1;
        }
    }
    errno = saved;
    return next(resource, limit);
}
