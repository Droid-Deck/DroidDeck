#if defined(__x86_64__) || defined(__i386__)
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stddef.h>

typedef struct pa_operation pa_operation;

#define PA_OPERATION_CANCELLED 2

static void *libpulse(const char *name) {
    void *fn = dlsym(RTLD_NEXT, name);
    if (!fn) {
        void *handle = dlopen("libpulse.so.0", RTLD_LAZY | RTLD_NOLOAD);
        if (handle) {
            fn = dlsym(handle, name);
            dlclose(handle);
        }
    }
    return fn;
}

int pa_operation_get_state(pa_operation *o) {
    static int (*next)(pa_operation *);
    if (!o)
        return PA_OPERATION_CANCELLED;
    if (!next)
        next = (int (*)(pa_operation *))libpulse("pa_operation_get_state");
    return next ? next(o) : PA_OPERATION_CANCELLED;
}

void pa_operation_unref(pa_operation *o) {
    static void (*next)(pa_operation *);
    if (!o)
        return;
    if (!next)
        next = (void (*)(pa_operation *))libpulse("pa_operation_unref");
    if (next)
        next(o);
}

void pa_operation_cancel(pa_operation *o) {
    static void (*next)(pa_operation *);
    if (!o)
        return;
    if (!next)
        next = (void (*)(pa_operation *))libpulse("pa_operation_cancel");
    if (next)
        next(o);
}
#else
typedef int pulseguard_arm_build_has_nothing_to_do;
#endif
