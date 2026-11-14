// Writes the seccomp filter Android's zygote gives an app (from the device's libandroid_runtime.so)
// to a file, as the raw sock_filter array, for seccomp-sim -f on devices whose zygote skips it
// (a permissive SELinux). A bionic program, built with the NDK and run from adb shell:
//   aarch64-linux-android33-clang -O2 -rdynamic -o app-seccomp-dump app-seccomp-dump.c -ldl
//   app-seccomp-dump OUT
#include <dlfcn.h>
#include <errno.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

static const char *g_out;
static int g_dumped;

// the runtime's own prctl() call lands here first
__attribute__((visibility("default"))) int prctl(int option, ...) {
    va_list ap;
    va_start(ap, option);
    unsigned long a2 = va_arg(ap, unsigned long), a3 = va_arg(ap, unsigned long);
    unsigned long a4 = va_arg(ap, unsigned long), a5 = va_arg(ap, unsigned long);
    va_end(ap);
    if (option == PR_SET_SECCOMP && a2 == SECCOMP_MODE_FILTER && g_out) {
        const struct sock_fprog *prog = (const struct sock_fprog *)a3;
        FILE *f = fopen(g_out, "wb");
        if (f) {
            fwrite(prog->filter, sizeof(prog->filter[0]), prog->len, f);
            fclose(f);
            fprintf(stderr, "app-seccomp-dump: %u instructions to %s\n", prog->len, g_out);
            g_dumped = 1;
        }
        return 0; // not installed in this process
    }
    return (int)syscall(SYS_prctl, option, a2, a3, a4, a5);
}

int main(int argc, char **argv) {
    if (argc != 2) { fprintf(stderr, "usage: %s OUT\n", argv[0]); return 2; }
    g_out = argv[1];
    void *rt = dlopen("libandroid_runtime.so", RTLD_NOW);
    if (!rt) { fprintf(stderr, "%s\n", dlerror()); return 2; }
    int (*set_app_filter)(void) = (int (*)(void))dlsym(rt, "_Z22set_app_seccomp_filterv");
    if (!set_app_filter) { fprintf(stderr, "no set_app_seccomp_filter\n"); return 2; }
    set_app_filter();
    return g_dumped ? 0 : 1;
}
