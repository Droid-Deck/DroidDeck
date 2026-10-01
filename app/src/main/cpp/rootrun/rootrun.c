/*
 * rootrun: run the Linux runtime's session inside its rootfs with chroot, as root.
 *
 * proot exists because an unprivileged Android app cannot unshare a user namespace, so it rewrites
 * the guest's syscalls instead - and every stat, sendmsg and GPU ioctl of a session pays for it.
 * Where the device is rooted there is a real uid 0, and the same rootfs can be entered with
 * mount(2) and chroot(2), leaving the kernel to do what proot emulates.
 *
 * One process, one job, no shell: mount what the guest needs at the paths it expects, chroot, drop
 * the environment to the guest's own, exec. Nothing here parses a command line, so paths under
 * /data/user/0/<package>/... never need quoting.
 *
 * Every mount is attempted and reported, never required: a kernel that refuses one leaves the
 * guest without that path rather than leaving no guest at all.
 *
 * Exit: 0 only if the guest was exec'd (this process is then the guest). 2 for a usage error, so a
 * caller can tell "this binary is not the runner I think it is" from "the guest failed".
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sched.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <sys/xattr.h>
#include <unistd.h>

#define VERSION "1"

/* The label the guest's /dev/shm is given, and the only one that lets a client's buffer through.
 *
 * An fd crossing a unix socket is checked by the kernel's security_file_receive hook, which runs the
 * receiving domain against the file's own label. The compositor runs in the app's domain
 * (u:r:untrusted_app_27:s0:c..), which receives app_data_file fds and silently drops tmpfs ones -
 * the fd never reaches the receiver's ancillary data, and libwayland then reports the missing
 * argument as "invalid arguments for wl_shm#N.create_pool". Files made on a fresh tmpfs come out
 * u:object_r:tmpfs:s0, so a guest that shm_open()s there cannot hand its pool to the compositor.
 *
 * This is what the guest under proot never hit: that path binds the app's cache at /dev/shm, whose
 * files carry app_data_file already. The type alone is enough - the category set is not part of the
 * check - and app_data_file is the type the guest's own rootfs files already have, so this grants
 * nothing the guest could not already send. */
#define SHM_SELINUX_LABEL "u:object_r:app_data_file:s0"

static const char *prog = "rootrun";

/*
 * Built dynamically on purpose (tools/rootrun/build.sh). A static link emits a TLS segment at an
 * 8-byte alignment that Bionic refuses on arm64 ("executable's TLS segment is underaligned ...
 * needs to be at least 64"), and the linker keeps that alignment even when the program declares an
 * aligned thread-local of its own; a dynamic link has no TLS segment at all and starts anywhere.
 */

static void warn(const char *what, const char *path) {
    fprintf(stderr, "%s: %s %s: %s\n", prog, what, path ? path : "", strerror(errno));
}

/* Refuse memfd_create in the guest, so shared memory falls back to files under /dev/shm.
 *
 * A memfd is anonymous kernel shmem and always carries u:object_r:tmpfs:s0 - there is no inode to
 * label, and fscreate does not reach it (measured). The compositor's domain drops an fd with that
 * label, so a client that makes its buffer pool from a memfd cannot hand it over and the session
 * dies at "invalid arguments for wl_shm#N.create_pool". A file under /dev/shm is labeled
 * app_data_file by the mount main() makes there and is accepted.
 *
 * ENOSYS, not EPERM: memfd_create is a fallback-able interface and every implementation that uses
 * it already handles its absence, which is what the kernel reports on a system without it. The
 * filter is installed before the exec and inherited, and applies to the guest's whole process tree.
 * Best effort - a kernel without seccomp leaves the guest as it was. */
static void block_memfd_create(void) {
#ifdef __NR_memfd_create
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, (uint32_t)offsetof(struct seccomp_data, arch)),
        /* Not arm64: fall through to ALLOW, the last instruction (1 + 1 + 3). */
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, (uint32_t)offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_memfd_create, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | (ENOSYS & SECCOMP_RET_DATA)),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog = { .len = (unsigned short)(sizeof filter / sizeof filter[0]), .filter = filter };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) { warn("cannot set no_new_privs", NULL); return; }
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) != 0) warn("cannot filter memfd_create", NULL);
#endif
}


static void own_as_guest(const char *path);

/* Every component of path, path included, as directories.
 *
 * What this creates belongs to the guest, not to root. The bind targets it makes - the app's own
 * directories, placed under the rootfs so proot and the runner can bind them at their own paths -
 * are directories the guest then has to write into, and left root-owned they are exactly the
 * "files in the rootfs the app can no longer rewrite" the chown below exists for. Only a directory
 * this call created is chowned: one that was already there keeps its owner. */
static int mkdir_parents(const char *path, mode_t mode) {
    char buf[4096];
    size_t n = strlen(path);
    if (n >= sizeof(buf)) { errno = ENAMETOOLONG; return -1; }
    memcpy(buf, path, n + 1);
    for (char *p = buf + 1; *p; p++) {
        if (*p != '/') continue;
        *p = '\0';
        if (mkdir(buf, mode) == 0) own_as_guest(buf);
        else if (errno != EEXIST) return -1;
        *p = '/';
    }
    if (mkdir(buf, mode) == 0) own_as_guest(buf);
    else if (errno != EEXIST) return -1;
    return 0;
}

/* The directory a file lives in, and not the file itself. */
static int mkdir_parent_of(const char *path, mode_t mode) {
    const char *slash = strrchr(path, '/');
    if (slash == NULL || slash == path) return 0;
    char buf[4096];
    size_t n = (size_t)(slash - path);
    if (n >= sizeof(buf)) { errno = ENAMETOOLONG; return -1; }
    memcpy(buf, path, n);
    buf[n] = '\0';
    return mkdir_parents(buf, mode);
}

/* Create a file that belongs to the guest, not to root.
 *
 * The runner starts as root (a root manager started it) and writes a few files on the guest's
 * behalf: the pid file, and the --write targets, which include the guest's /etc/passwd and
 * /etc/group. Left root-owned they are files the app's own uid cannot replace, so the next session
 * - unrooted, or rooted with a different uid - writes them and silently fails. Measured on a
 * Galaxy S23: a rooted session left files in the rootfs that the app could no longer rewrite. */
static int guest_uid = -1;
static int guest_gid = -1;

static void own_as_guest(const char *path) {
    if (guest_uid < 0 && guest_gid < 0) return;
    if (chown(path, (uid_t)(guest_uid < 0 ? 0 : guest_uid), (gid_t)(guest_gid < 0 ? 0 : guest_gid)) != 0) {
        warn("cannot give the guest ownership of", path);
    }
}

/* One device node under the guest's /dev, for the tmpfs fallback above. */
static void make_dev(const char *dev, const char *name, int major, int minor, mode_t mode) {
    char path[4096];
    snprintf(path, sizeof(path), "%s/%s", dev, name);
    if (mknod(path, S_IFCHR | mode, makedev(major, minor)) != 0 && errno != EEXIST) warn("cannot mknod", path);
    chmod(path, mode);
}

/* A file has to exist before it can be a bind target. As above, one this call creates belongs to the
 * guest; one that was already there is left alone. O_EXCL is what tells the two apart. */
static int touch(const char *path) {
    if (mkdir_parent_of(path, 0755) != 0) return -1;
    int fd = open(path, O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC, 0644);
    if (fd < 0) return errno == EEXIST ? 0 : -1;
    close(fd);
    own_as_guest(path);
    return 0;
}

/* A bind target must exist before it can be mounted over: a directory where the source is one, an
 * empty file otherwise. An existing target is left alone - it may be a directory the runtime
 * already has there. */
static int ensure_target(const char *src, const char *dst) {
    struct stat st;
    if (stat(dst, &st) == 0) return 0;
    if (stat(src, &st) == 0 && S_ISDIR(st.st_mode)) return mkdir_parents(dst, 0755);
    return touch(dst);
}

static int bind_mount(const char *src, const char *dst) {
    if (ensure_target(src, dst) != 0) { warn("cannot create", dst); return -1; }
    if (mount(src, dst, NULL, MS_BIND | MS_REC, NULL) != 0) { warn("cannot bind", dst); return -1; }
    return 0;
}

static int mount_fs(const char *src, const char *dst, const char *fstype, unsigned long flags) {
    if (mkdir_parents(dst, 0755) != 0) { warn("cannot create", dst); return -1; }
    if (mount(src, dst, fstype, flags, NULL) != 0) { warn("cannot mount", dst); return -1; }
    return 0;
}

static int write_file(const char *dst, const char *content) {
    if (mkdir_parent_of(dst, 0755) != 0) { warn("cannot create", dst); return -1; }
    int out = open(dst, O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC, 0644);
    if (out < 0) { warn("cannot write", dst); return -1; }
    size_t len = strlen(content);
    ssize_t wrote = write(out, content, len);
    close(out);
    if (wrote < 0 || (size_t)wrote != len) { warn("cannot write", dst); return -1; }
    own_as_guest(dst);
    return 0;
}

/* One "src:dst" pair. A spec with no colon means "at its own path", the way proot's -b reads it:
 * the app's own directories (its files, its cache, the session's runtime dir) are bound where they
 * already are, and dropping those would leave the guest without the socket it presents through. */
static int split_pair(char *spec, char **left, char **right) {
    char *colon = strchr(spec, ':');
    if (colon == NULL) {
        if (*spec == '\0') return -1;
        *left = spec;
        *right = spec;
        return 0;
    }
    if (colon == spec) return -1;
    *colon = '\0';
    *left = spec;
    *right = colon + 1;
    return **right ? 0 : -1;
}

static void usage(void) {
    fprintf(stderr,
            "%s " VERSION "\n"
            "usage: rootrun --root DIR [options] -- PROGRAM [ARGS...]\n"
            "  --bind SRC:DST        bind-mount SRC at DST (recursive), inside the new root\n"
            "  --write DST:CONTENT   write CONTENT to the file DST\n"
            "  --env NAME=VALUE      add one variable to the guest's environment\n"
            "  --dir PATH            the guest's working directory (default /)\n"
            "  --hostname NAME       set the guest's hostname (best effort)\n"
            "  --uid N --gid N       drop to this uid and gid before exec\n"
            "  --pidfile PATH        write this process's pid here before the exec\n"
            "  --keep-env            keep this process's environment as well\n",
            prog);
}

int main(int argc, char **argv) {
    const char *root = NULL, *dir = "/", *hostname = NULL, *pidfile = NULL;
    char **binds = calloc((size_t)argc, sizeof(char *));
    char **writes = calloc((size_t)argc, sizeof(char *));
    char **envs = calloc((size_t)argc, sizeof(char *));
    int nbind = 0, nwrite = 0, nenv = 0;
    int uid = -1, gid = -1, keep_env = 0;
    int i = 1;

    if (binds == NULL || writes == NULL || envs == NULL) return 2;
    if (argc > 1 && (strcmp(argv[1], "--help") == 0 || strcmp(argv[1], "-h") == 0)) { usage(); return 2; }
    if (argc > 1 && strcmp(argv[1], "--version") == 0) { printf("%s\n", VERSION); return 0; }

    for (; i < argc; i++) {
        char *a = argv[i];
        if (strcmp(a, "--") == 0) { i++; break; }
        else if (strcmp(a, "--root") == 0 && i + 1 < argc) root = argv[++i];
        else if (strcmp(a, "--dir") == 0 && i + 1 < argc) dir = argv[++i];
        else if (strcmp(a, "--hostname") == 0 && i + 1 < argc) hostname = argv[++i];
        else if (strcmp(a, "--pidfile") == 0 && i + 1 < argc) pidfile = argv[++i];
        else if (strcmp(a, "--uid") == 0 && i + 1 < argc) uid = atoi(argv[++i]);
        else if (strcmp(a, "--gid") == 0 && i + 1 < argc) gid = atoi(argv[++i]);
        else if (strcmp(a, "--keep-env") == 0) keep_env = 1;
        else if (strcmp(a, "--bind") == 0 && i + 1 < argc) binds[nbind++] = argv[++i];
        else if (strcmp(a, "--write") == 0 && i + 1 < argc) writes[nwrite++] = argv[++i];
        else if (strcmp(a, "--env") == 0 && i + 1 < argc) envs[nenv++] = argv[++i];
        else { fprintf(stderr, "%s: unknown argument %s\n", prog, a); usage(); return 2; }
    }
    if (root == NULL || i >= argc) { usage(); return 2; }
    guest_uid = uid;
    guest_gid = gid;

    /* A namespace of our own, so every mount below belongs to this guest and is gone when it exits:
     * in the host's namespace they would outlive the session, stack on the next one's (the second
     * run of a session met "Device or resource busy" on its own rootfs), and leave the app's own
     * /proc and /sys views rearranged. Root can unshare this; an unprivileged app cannot, which is
     * the whole reason proot exists. Then nothing propagates back to the caller either. */
    if (unshare(CLONE_NEWNS) != 0) warn("cannot unshare the mount namespace", "");
    if (mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) != 0) warn("cannot make mounts private", "/");

    /* The runtime's own filesystems, then whatever the app asked for. */
    char proc[4096], sys[4096], dev[4096];
    int shm_ready = 0;
    snprintf(proc, sizeof(proc), "%s/proc", root);
    snprintf(sys, sizeof(sys), "%s/sys", root);
    snprintf(dev, sizeof(dev), "%s/dev", root);
    mount_fs("proc", proc, "proc", MS_NOSUID | MS_NODEV | MS_NOEXEC);
    mount_fs("sysfs", sys, "sysfs", MS_NOSUID | MS_NODEV | MS_NOEXEC);
    /* The guest's /dev. devtmpfs where the kernel offers it; else the host's /dev bound in, which
     * is what proot does and what the app-uid guest needs (/dev/null, /dev/zero, /dev/urandom,
     * /dev/pts and the nodes the GPU and pads are reached through are all there, with the modes
     * Android gives them); else a tmpfs with the standard nodes made by hand. An empty /dev is not
     * an option: the session script's first line writes to /dev/null, and without it the guest dies
     * before it starts (measured: "== gamescope:" then nothing). */
    if (mount_fs("devtmpfs", dev, "devtmpfs", MS_NOSUID | MS_STRICTATIME) != 0) {
        struct stat st;
        if (stat("/dev/null", &st) != 0) {
            if (mount_fs("tmpfs", dev, "tmpfs", MS_NOSUID | MS_STRICTATIME) == 0) {
                mkdir_parents(dev, 0755);
                chmod(dev, 0755);
                make_dev(dev, "null", 1, 3, 0666);
                make_dev(dev, "zero", 1, 5, 0666);
                make_dev(dev, "full", 1, 7, 0666);
                make_dev(dev, "random", 1, 8, 0666);
                make_dev(dev, "urandom", 1, 9, 0666);
                make_dev(dev, "tty", 5, 0, 0666);
                make_dev(dev, "ptmx", 5, 2, 0666);
                mount_fs("devpts", "pts", "devpts", MS_NOSUID | MS_NOEXEC);
                char pts[4096];
                snprintf(pts, sizeof(pts), "%s/pts", dev);
                mount_fs("devpts", pts, "devpts", MS_NOSUID | MS_NOEXEC);
            }
        } else {
            bind_mount("/dev", dev);
        }
    }
    /* The guest writes here from its first line. */
    chmod(dev, 0755);

    /* The guest's /dev/shm, and the app's own bind to that path is skipped below.
     *
     * A real tmpfs, not the app's cache: glibc's shm_open and everything above it want the semantics
     * of the real thing. What the guest writes here is labeled for the compositor's domain (see
     * SHM_SELINUX_LABEL) - without that, a session starts, connects, and then dies the moment its
     * first client creates a pool. Sized in pages; tmpfs is charged only as it is used. */
    {
        char shm[4096];
        snprintf(shm, sizeof(shm), "%s/dev/shm", root);
        mkdir_parents(shm, 0755);
        if (mount_fs("tmpfs", shm, "tmpfs", MS_NOSUID | MS_NODEV | MS_STRICTATIME) == 0) {
            chmod(shm, 0777);
            /* The label is inherited by everything created under it, so one call covers every pool
             * the session makes. Best effort: a kernel without SELinux, or a policy that refuses
             * the set, leaves the guest as it would have been anyway. */
            if (setxattr(shm, "security.selinux", SHM_SELINUX_LABEL,
                         sizeof(SHM_SELINUX_LABEL) - 1, 0) != 0)
                warn("cannot label the guest's /dev/shm", strerror(errno));
            shm_ready = 1;
        } else {
            warn("cannot mount tmpfs for", shm);
        }
    }

    for (int b = 0; b < nbind; b++) {
        char *left = NULL, *right = NULL;
        if (split_pair(binds[b], &left, &right) != 0) { fprintf(stderr, "%s: bad bind %s\n", prog, binds[b]); continue; }
        /* The tmpfs above is the shm the guest needs, and it carries the label the compositor
         * accepts; binding the app's cache over it would replace it with the cache's directory,
         * which is not the same filesystem and not ours to label. */
        if (shm_ready && strcmp(right, "/dev/shm") == 0) continue;
        char dst[4096];
        snprintf(dst, sizeof(dst), "%s%s", root, right);
        bind_mount(left, dst);
    }
    for (int w = 0; w < nwrite; w++) {
        char *left = NULL, *right = NULL;
        if (split_pair(writes[w], &left, &right) != 0) { fprintf(stderr, "%s: bad write %s\n", prog, writes[w]); continue; }
        char dst[4096];
        snprintf(dst, sizeof(dst), "%s%s", root, left);
        write_file(dst, right);
    }

    /* The caller may have started us through a root helper, so the pid it knows is not the guest's:
     * this file is how it finds the process to stop. Written before the drop, since the guest may
     * not be allowed to write here. */
    if (pidfile != NULL) {
        char pid[32];
        snprintf(pid, sizeof(pid), "%d\n", (int)getpid());
        write_file(pidfile, pid);
    }

    if (chdir(root) != 0) { warn("cannot enter", root); return 1; }
    if (chroot(root) != 0) { warn("cannot chroot to", root); return 1; }
    if (chdir(dir) != 0) { warn("cannot chdir to", dir); return 1; }

    if (hostname != NULL) {
        /* UTS_NAMESPACE: on a kernel that allows it the guest gets its own hostname; where it does
         * not, the host's name stays and the guest is no worse off than under proot. */
        if (unshare(CLONE_NEWUTS) == 0) sethostname(hostname, strlen(hostname));
    }

    /* The guest's environment, and nothing of the app's unless asked: proot's own variables mean
     * nothing here, and a session that inherited the app's LD_LIBRARY_PATH would load the app's
     * libs into the guest's programs. */
    if (!keep_env) clearenv();
    for (int e = 0; e < nenv; e++) {
        char *eq = strchr(envs[e], '=');
        if (eq == NULL) continue;
        *eq = '\0';
        setenv(envs[e], eq + 1, 1);
        *eq = '=';
    }

    if (gid >= 0 && setgid((gid_t)gid) != 0) warn("cannot setgid", NULL);
    if (uid >= 0 && setuid((uid_t)uid) != 0) warn("cannot setuid", NULL);

    /* Before the exec, so the guest and everything it starts inherit it. */
    block_memfd_create();

    execv(argv[i], &argv[i]);
    fprintf(stderr, "%s: cannot exec %s: %s\n", prog, argv[i], strerror(errno));
    return 1;
}
