// Native half of phantomlab: forks sleepers, execs in place, and probes what a process may do.
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <netinet/in.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "PLAB"

JNIEXPORT jint JNICALL Java_com_droiddeck_phantomlab_Native_spawn(JNIEnv *env, jclass cls, jint n) {
    int made = 0;
    for (int i = 0; i < n; i++) {
        pid_t pid = fork();
        if (pid == 0) {
            for (;;) pause();
        }
        if (pid > 0) made++;
    }
    return made;
}

static void keep_binder_fds(void) {
    DIR *d = opendir("/proc/self/fd");
    if (!d) return;
    struct dirent *e;
    char path[64], target[256];
    while ((e = readdir(d)) != NULL) {
        int fd = atoi(e->d_name);
        if (fd <= 2) continue;
        snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
        ssize_t len = readlink(path, target, sizeof(target) - 1);
        if (len <= 0) continue;
        target[len] = 0;
        if (strstr(target, "binder")) {
            fcntl(fd, F_SETFD, 0);
            __android_log_print(ANDROID_LOG_INFO, TAG, "keeping fd %d (%s) across exec", fd, target);
        }
    }
    closedir(d);
}

JNIEXPORT void JNICALL Java_com_droiddeck_phantomlab_Native_execInPlace(JNIEnv *env, jclass cls, jobjectArray jargv, jboolean keep) {
    int n = (*env)->GetArrayLength(env, jargv);
    char **argv = calloc(n + 1, sizeof(char *));
    for (int i = 0; i < n; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, jargv, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i] = strdup(c);
    }
    if (keep) keep_binder_fds();
    execv(argv[0], argv);
    __android_log_print(ANDROID_LOG_ERROR, TAG, "execv %s failed: %s", argv[0], strerror(errno));
}

static char *report;
static size_t report_len, report_cap;

static void out(const char *fmt, ...) {
    char line[1024];
    va_list ap;
    va_start(ap, fmt);
    int len = vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
    if (len < 0) return;
    if (len >= (int)sizeof(line)) len = sizeof(line) - 1;
    if (report_len + len + 2 > report_cap) {
        report_cap = (report_len + len + 2) * 2;
        report = realloc(report, report_cap);
    }
    memcpy(report + report_len, line, len);
    report_len += len;
    report[report_len++] = '\n';
    report[report_len] = 0;
}

static void read_small(const char *path, char *buf, size_t size) {
    buf[0] = 0;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        snprintf(buf, size, "<%s>", strerror(errno));
        return;
    }
    ssize_t n = read(fd, buf, size - 1);
    close(fd);
    if (n < 0) n = 0;
    buf[n] = 0;
    for (ssize_t i = 0; i < n; i++) if (buf[i] == '\n' || buf[i] == 0) buf[i] = ' ';
}

#if defined(__aarch64__)
static const unsigned char ret42[] = {0x40, 0x05, 0x80, 0x52, 0xc0, 0x03, 0x5f, 0xd6};
#else
static const unsigned char ret42[] = {0xb8, 0x2a, 0x00, 0x00, 0x00, 0xc3};
#endif

static int call_code(void *code) {
    __builtin___clear_cache((char *)code, (char *)code + sizeof(ret42));
    return ((int (*)(void))code)();
}

static void probe_memfd_exec(void) {
    int fd = syscall(SYS_memfd_create, "lab", 0);
    if (fd < 0) {
        out("memfd_exec: memfd_create %s", strerror(errno));
        return;
    }
    write(fd, ret42, sizeof(ret42));
    void *p = mmap(NULL, 4096, PROT_READ | PROT_EXEC, MAP_SHARED, fd, 0);
    if (p == MAP_FAILED) out("memfd_exec: mmap exec %s", strerror(errno));
    else out("memfd_exec: ran, returned %d", call_code(p));
    close(fd);
}

static void probe_anon_exec(void) {
    void *p = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    memcpy(p, ret42, sizeof(ret42));
    if (mprotect(p, 4096, PROT_READ | PROT_EXEC) != 0) out("anon_exec: mprotect %s", strerror(errno));
    else out("anon_exec: ran, returned %d", call_code(p));
}

static int run_child(char *const argv[], int execfd) {
    pid_t pid = fork();
    if (pid == 0) {
        if (execfd >= 0) syscall(SYS_execveat, execfd, "", argv, environ, AT_EMPTY_PATH);
        else execv(argv[0], argv);
        _exit(100 + errno);
    }
    int status = 0;
    waitpid(pid, &status, 0);
    return WIFEXITED(status) ? WEXITSTATUS(status) : 1000 + WTERMSIG(status);
}

static void probe_exec(void) {
    char *argv[] = {"/system/bin/true", NULL};
    int st = run_child(argv, -1);
    out("execve /system/bin/true: %s", st == 0 ? "ok" : (st >= 100 && st < 1000 ? strerror(st - 100) : "status"));
    int src = open("/system/bin/toybox", O_RDONLY);
    int mfd = syscall(SYS_memfd_create, "toybox", 0);
    char buf[65536];
    ssize_t n;
    while (src >= 0 && (n = read(src, buf, sizeof(buf))) > 0) write(mfd, buf, n);
    if (src >= 0) close(src);
    char *targv[] = {"true", NULL};
    st = run_child(targv, mfd);
    out("execveat memfd(toybox true): %s", st == 0 ? "ok" : (st >= 100 && st < 1000 ? strerror(st - 100) : "status"));
    close(mfd);
}

static void probe_fd(int i, int fd) {
    struct stat st;
    if (fstat(fd, &st) != 0) {
        out("fd%d: fstat %s", i, strerror(errno));
        return;
    }
    char target[256] = "";
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    ssize_t len = readlink(path, target, sizeof(target) - 1);
    if (len > 0) target[len] = 0;
    if (S_ISSOCK(st.st_mode)) {
        int type = 0, family = 0;
        socklen_t sl = sizeof(type);
        getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &sl);
        struct sockaddr_storage ss;
        socklen_t al = sizeof(ss);
        getsockname(fd, (struct sockaddr *)&ss, &al);
        family = ss.ss_family;
        if (family == AF_INET && type == SOCK_STREAM) {
            struct sockaddr_in a = {.sin_family = AF_INET, .sin_port = htons(80)};
            inet_pton(AF_INET, "1.1.1.1", &a.sin_addr);
            if (connect(fd, (struct sockaddr *)&a, sizeof(a)) != 0) {
                out("fd%d tcp: connect %s", i, strerror(errno));
                return;
            }
            const char *req = "HEAD / HTTP/1.0\r\nHost: 1.1.1.1\r\n\r\n";
            write(fd, req, strlen(req));
            char resp[64] = "";
            ssize_t r = read(fd, resp, sizeof(resp) - 1);
            if (r > 0) resp[r] = 0;
            char *nl = strchr(resp, '\r');
            if (nl) *nl = 0;
            out("fd%d tcp: connected, got \"%s\"", i, r > 0 ? resp : strerror(errno));
        } else if (family == AF_INET && type == SOCK_DGRAM) {
            struct sockaddr_in a = {.sin_family = AF_INET, .sin_port = htons(53)};
            inet_pton(AF_INET, "1.1.1.1", &a.sin_addr);
            int r = connect(fd, (struct sockaddr *)&a, sizeof(a));
            out("fd%d udp: connect %s", i, r == 0 ? "ok" : strerror(errno));
        } else {
            char buf[64] = "";
            ssize_t r = read(fd, buf, sizeof(buf) - 1);
            if (r > 0) buf[r - 1] = 0;
            out("fd%d unix(family %d): read %s", i, family, r > 0 ? buf : strerror(errno));
        }
        return;
    }
    if (S_ISDIR(st.st_mode)) {
        int f = openat(fd, "probe.txt", O_RDONLY);
        out("fd%d dir %s: openat child %s", i, target, f >= 0 ? "ok" : strerror(errno));
        if (f >= 0) close(f);
        return;
    }
    char buf[64] = "";
    ssize_t r = pread(fd, buf, sizeof(buf) - 1, 0);
    if (r > 0) buf[r - 1] = 0;
    out("fd%d file %s: read %s", i, target, r >= 0 ? buf : strerror(errno));
    void *p = mmap(NULL, 4096, PROT_READ, MAP_PRIVATE, fd, 0);
    out("fd%d mmap read: %s", i, p == MAP_FAILED ? strerror(errno) : "ok");
    if (p != MAP_FAILED) munmap(p, 4096);
    p = mmap(NULL, 4096, PROT_READ | PROT_EXEC, MAP_PRIVATE, fd, 0);
    out("fd%d mmap exec: %s", i, p == MAP_FAILED ? strerror(errno) : "ok");
    if (p != MAP_FAILED) munmap(p, 4096);
    char *argv[] = {"x", NULL};
    int st2 = run_child(argv, fd);
    out("fd%d execveat: %s", i, st2 >= 100 && st2 < 1000 ? strerror(st2 - 100) : "ran");
}

static void probe_self(void) {
    char buf[512];
    out("uid=%d gid=%d pid=%d ppid=%d", getuid(), getgid(), getpid(), getppid());
    read_small("/proc/self/attr/current", buf, sizeof(buf));
    out("selinux: %s", buf);
    read_small("/proc/self/cgroup", buf, sizeof(buf));
    out("cgroup: %s", buf);
    int s = socket(AF_INET, SOCK_STREAM, 0);
    out("socket(AF_INET): %s", s >= 0 ? "ok" : strerror(errno));
    if (s >= 0) close(s);
    s = socket(AF_UNIX, SOCK_STREAM, 0);
    out("socket(AF_UNIX): %s", s >= 0 ? "ok" : strerror(errno));
    if (s >= 0) close(s);
    int f = open("/data/data/com.droiddeck.phantomlab/files/probe.txt", O_RDONLY);
    out("open app data by path: %s", f >= 0 ? "ok" : strerror(errno));
    if (f >= 0) close(f);
    const char *gpus[] = {"/dev/kgsl-3d0", "/dev/mali0", "/dev/dri/renderD128", NULL};
    for (int i = 0; gpus[i]; i++) {
        if (access(gpus[i], F_OK) != 0) continue;
        f = open(gpus[i], O_RDWR);
        out("open %s: %s", gpus[i], f >= 0 ? "ok" : strerror(errno));
        if (f >= 0) close(f);
    }
}

static void probe_pid(const char *spec) {
    int pid = atoi(spec);
    char path[64], buf[256];
    snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    read_small(path, buf, sizeof(buf));
    out("read /proc/%d/cmdline: %s", pid, buf);
    out("kill(%d, 0): %s", pid, kill(pid, 0) == 0 ? "ok" : strerror(errno));
    out("kill(%d, SIGCONT): %s", pid, kill(pid, SIGCONT) == 0 ? "ok" : strerror(errno));
}

static void try_write(const char *path, const char *value) {
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        out("write %s: open %s", path, strerror(errno));
        return;
    }
    ssize_t n = write(fd, value, strlen(value));
    out("write %s: %s", path, n >= 0 ? "ok" : strerror(errno));
    close(fd);
}

// Every way a process could leave the cgroup Android counts it in.
static void probe_escape(void) {
    char own[256], pid[32], path[300];
    read_small("/proc/self/cgroup", own, sizeof(own));
    char *v2 = strstr(own, "0::");
    char dir[200] = "";
    if (v2) sscanf(v2 + 3, "%199s", dir);
    snprintf(pid, sizeof(pid), "%d", getpid());
    try_write("/sys/fs/cgroup/cgroup.procs", pid);
    char parent[200];
    snprintf(parent, sizeof(parent), "%s", dir);
    char *slash = strrchr(parent, '/');
    if (slash) *slash = 0;
    snprintf(path, sizeof(path), "/sys/fs/cgroup%s/cgroup.procs", parent);
    try_write(path, pid);
    snprintf(path, sizeof(path), "/sys/fs/cgroup%s/lab", dir);
    out("mkdir %s: %s", path, mkdir(path, 0755) == 0 ? "ok" : strerror(errno));
    int r = syscall(SYS_unshare, 0x10000000 /* CLONE_NEWUSER */);
    out("unshare(CLONE_NEWUSER): %s", r == 0 ? "ok" : strerror(errno));
    r = syscall(SYS_unshare, 0x02000000 /* CLONE_NEWCGROUP */);
    out("unshare(CLONE_NEWCGROUP): %s", r == 0 ? "ok" : strerror(errno));
    int cg = open("/sys/fs/cgroup", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (cg < 0) {
        out("open /sys/fs/cgroup: %s", strerror(errno));
    } else {
        struct {
            unsigned long long flags, pidfd, child_tid, parent_tid, exit_signal, stack, stack_size, tls, set_tid,
                set_tid_size, cgroup;
        } args = {.flags = 0x200000000ULL /* CLONE_INTO_CGROUP */, .exit_signal = SIGCHLD, .cgroup = (unsigned)cg};
        long child = syscall(435 /* clone3 */, &args, sizeof(args));
        if (child == 0) _exit(0);
        if (child > 0) waitpid(child, NULL, 0);
        out("clone3(CLONE_INTO_CGROUP, root): %s", child > 0 ? "ok" : strerror(errno));
        close(cg);
    }
}

// Runs path with fd as its fd 3 and its stderr captured.
static void probe_libexec(const char *path, int fd) {
    int pipefd[2];
    pipe(pipefd);
    pid_t pid = fork();
    if (pid == 0) {
        dup2(pipefd[1], 2);
        if (fd >= 0) dup2(fd, 3);
        char *argv[] = {(char *)path, NULL};
        execv(path, argv);
        dprintf(2, "execv: %s", strerror(errno));
        _exit(127);
    }
    close(pipefd[1]);
    char buf[512] = "";
    ssize_t n = read(pipefd[0], buf, sizeof(buf) - 1);
    if (n > 0) buf[n] = 0;
    char *nl = strchr(buf, '\n');
    if (nl) *nl = 0;
    close(pipefd[0]);
    int status = 0;
    waitpid(pid, &status, 0);
    out("exec %s: status %d, said \"%s\"", path, WIFEXITED(status) ? WEXITSTATUS(status) : -WTERMSIG(status), buf);
}

JNIEXPORT jstring JNICALL Java_com_droiddeck_phantomlab_Native_probe(JNIEnv *env, jclass cls, jstring jwhat, jintArray jfds) {
    report_len = 0;
    if (report) report[0] = 0;
    const char *what = jwhat ? (*env)->GetStringUTFChars(env, jwhat, NULL) : "self";
    int n = (*env)->GetArrayLength(env, jfds);
    jint *fds = (*env)->GetIntArrayElements(env, jfds, NULL);
    char spec[1024];
    snprintf(spec, sizeof(spec), "%s", what);
    char *save = NULL;
    for (char *tok = strtok_r(spec, ",", &save); tok; tok = strtok_r(NULL, ",", &save)) {
        if (!strcmp(tok, "self")) probe_self();
        else if (!strcmp(tok, "memfd_exec")) probe_memfd_exec();
        else if (!strcmp(tok, "anon_exec")) probe_anon_exec();
        else if (!strcmp(tok, "exec")) probe_exec();
        else if (!strcmp(tok, "fds")) for (int i = 0; i < n; i++) probe_fd(i, fds[i]);
        else if (!strncmp(tok, "pid=", 4)) probe_pid(tok + 4);
        else if (!strcmp(tok, "escape")) probe_escape();
        else if (!strncmp(tok, "libexec=", 8)) probe_libexec(tok + 8, n > 0 ? fds[0] : -1);
    }
    (*env)->ReleaseIntArrayElements(env, jfds, fds, 0);
    return (*env)->NewStringUTF(env, report ? report : "");
}
