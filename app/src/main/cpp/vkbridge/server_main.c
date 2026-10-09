/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * vkbridge server entry point.
 *
 *   libvkbridge_server.so --socket <path> [--log <file>] [--vulkan <libvulkan>] [--selftest]
 *
 * Packaged as a "library" so Android extracts it into the native library directory, from where
 * the app executes it like proot. It loads the system Vulkan loader (on the device: the vendor's
 * Mali driver behind /system/lib64/libvulkan.so), runs a self-test that decides how mapped memory
 * is shared with the Linux side, then serves the session's Vulkan calls until it is killed or its
 * parent dies.
 */
#define _GNU_SOURCE
#include "vkb_server.h"

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <android/log.h>
#elif defined(__GLIBC__)
#include <execinfo.h>
#endif

PFN_vkGetInstanceProcAddr vkb_gipa;
vkb_dispatch vkb_global_dt;
int vkb_verbose;

static FILE *log_file;
static int log_level = VKB_LOG_INFO;
static pthread_mutex_t log_lock = PTHREAD_MUTEX_INITIALIZER;

int vkb_log_level(void) { return log_level; }

void vkb_log(int level, const char *fmt, ...)
{
    if (level > log_level) return;
    char msg[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    static const char *const tags[] = {"E", "W", "I", "D"};
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tm;
    localtime_r(&ts.tv_sec, &tm);
    pthread_mutex_lock(&log_lock);
    FILE *f = log_file ? log_file : stderr;
    fprintf(f, "%02d:%02d:%02d.%03ld vkbridge-server %s %s\n", tm.tm_hour, tm.tm_min, tm.tm_sec,
            ts.tv_nsec / 1000000, tags[level & 3], msg);
    fflush(f);
    pthread_mutex_unlock(&log_lock);
#ifdef __ANDROID__
    static const int prio[] = {ANDROID_LOG_ERROR, ANDROID_LOG_WARN, ANDROID_LOG_INFO, ANDROID_LOG_DEBUG};
    __android_log_write(prio[level & 3], "vkbridge", msg);
#endif
}

extern __thread const char *vkb_srv_current_cmd;

/* A crash is almost always the driver's: say in which call, then die as we would have. */
static void on_crash(int sig)
{
    char buf[256];
    const char *cmd = vkb_srv_current_cmd ? vkb_srv_current_cmd : "(no Vulkan call)";
    int n = snprintf(buf, sizeof(buf), "vkbridge-server E crashed with signal %d in %s\n", sig, cmd);
    if (log_file) {
        if (write(fileno(log_file), buf, (size_t)n) < 0) {}
    }
    if (write(2, buf, (size_t)n) < 0) {}
#ifdef __ANDROID__
    __android_log_write(ANDROID_LOG_FATAL, "vkbridge", buf);
#elif defined(__GLIBC__)
    void *frames[32];
    int nf = backtrace(frames, 32);
    backtrace_symbols_fd(frames, nf, 2);
    if (log_file) backtrace_symbols_fd(frames, nf, fileno(log_file));
#endif
    signal(sig, SIG_DFL);
    raise(sig);
}

static void *load_vulkan(const char *override)
{
    const char *names[] = {
        override,
#ifdef __ANDROID__
        "libvulkan.so",
#else
        "libvulkan.so.1",
        "libvulkan.so",
#endif
    };
    for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); i++) {
        if (!names[i]) continue;
        void *h = dlopen(names[i], RTLD_NOW | RTLD_LOCAL);
        if (h) {
            VKB_INFO("Vulkan loader: %s", names[i]);
            return h;
        }
        VKB_WARN("cannot open %s: %s", names[i], dlerror());
    }
    return NULL;
}

static int listen_on(const char *path)
{
    struct sockaddr_un a;
    if (strlen(path) >= sizeof(a.sun_path)) {
        VKB_ERR("socket path too long (%zu bytes): %s", strlen(path), path);
        return -1;
    }
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        VKB_ERR("socket: %s", strerror(errno));
        return -1;
    }
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    strcpy(a.sun_path, path);
    unlink(path);
    if (bind(fd, (struct sockaddr *)&a, sizeof(a)) < 0) {
        VKB_ERR("bind %s: %s", path, strerror(errno));
        close(fd);
        return -1;
    }
    chmod(path, 0600);
    if (listen(fd, 128) < 0) {
        VKB_ERR("listen: %s", strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

static void usage(void)
{
    fprintf(stderr, "usage: vkbridge-server --socket PATH [--log FILE] [--vulkan LIB] [--selftest] [--verbose]\n");
}

int main(int argc, char **argv)
{
    const char *sock_path = getenv("VKBRIDGE_SOCKET");
    const char *log_path = getenv("VKBRIDGE_SERVER_LOG");
    const char *vk_lib = getenv("VKBRIDGE_SERVER_VULKAN");
    int selftest_only = 0;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--socket") && i + 1 < argc) sock_path = argv[++i];
        else if (!strcmp(argv[i], "--log") && i + 1 < argc) log_path = argv[++i];
        else if (!strcmp(argv[i], "--vulkan") && i + 1 < argc) vk_lib = argv[++i];
        else if (!strcmp(argv[i], "--selftest")) selftest_only = 1;
        else if (!strcmp(argv[i], "--verbose")) vkb_verbose = 1;
        else {
            usage();
            return 2;
        }
    }
    if (getenv("VKBRIDGE_DEBUG")) vkb_verbose = 1;
    if (vkb_verbose) log_level = VKB_LOG_DEBUG;
    if (log_path) {
        log_file = fopen(log_path, "ae");
        if (!log_file) fprintf(stderr, "vkbridge-server: cannot open log %s: %s\n", log_path, strerror(errno));
    }
    signal(SIGPIPE, SIG_IGN);
    signal(SIGSEGV, on_crash);
    signal(SIGBUS, on_crash);
    signal(SIGABRT, on_crash);
    signal(SIGILL, on_crash);
    signal(SIGFPE, on_crash);
    /* The server is the app's child: when the app dies, so does the session's GPU. */
    prctl(PR_SET_PDEATHSIG, SIGTERM);

    VKB_INFO("starting (pid %d, protocol %016llx)", getpid(), (unsigned long long)VKB_PROTOCOL_HASH);
    void *lib = load_vulkan(vk_lib);
    if (!lib) {
        VKB_ERR("no Vulkan loader; nothing to serve");
        return 1;
    }
    vkb_gipa = (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");
    if (!vkb_gipa) {
        VKB_ERR("the Vulkan loader exports no vkGetInstanceProcAddr");
        return 1;
    }
    vkb_dispatch_load_instance(&vkb_global_dt, vkb_gipa, VK_NULL_HANDLE);

    vkb_selftest_all();
    if (selftest_only) return vkb_npds > 0 ? 0 : 1;

    if (!sock_path) {
        usage();
        return 2;
    }
    int lfd = listen_on(sock_path);
    if (lfd < 0) return 1;
    VKB_INFO("listening on %s", sock_path);
    vkb_srv_serve(lfd);
    return 0;
}
