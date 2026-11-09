// Part of libddnatv-hook.so, included by ddnatv-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// execve() and posix_spawn(): rootfs programs through ld.so with the hook, scripts through their
// interpreter, Android programs with Android's environment.

#ifndef DDNATV_HOST_TEST

// Runs before every exec, mostly in a child a shell has just forked: the copies write only the
// string (ddnatv_copy_cstr), not the whole PATH_MAX buffer, since each page written there is a
// copy-on-write fault.
static void resolve_symlinks(const char *path, char *out, size_t out_size, int depth) {
    if (!path || !out || out_size == 0) return;
    ddnatv_copy_cstr(out, out_size, path);
    if (depth > 8) return;

    char link_target[PATH_MAX];
    ssize_t len = readlink(path, link_target, sizeof(link_target) - 1);
    if (len < 0) return;
    link_target[len] = '\0';

    char next_path[PATH_MAX];
    if (link_target[0] == '/') {
        char rw_buf[PATH_MAX];
        const char *rewritten = rewrite_path(link_target, rw_buf, sizeof(rw_buf));
        ddnatv_copy_cstr(next_path, sizeof(next_path), rewritten);
    } else {
        const char *slash = strrchr(path, '/');
        if (slash) {
            size_t dir_len = (size_t)(slash - path);
            if (dir_len > sizeof(next_path) - 1) dir_len = sizeof(next_path) - 1;
            memcpy(next_path, path, dir_len);
            next_path[dir_len] = '\0';
            size_t n = ddnatv_append(next_path, sizeof(next_path), dir_len, "/", 1);
            ddnatv_append(next_path, sizeof(next_path), n, link_target, (size_t)len);
        } else {
            ddnatv_copy_cstr(next_path, sizeof(next_path), link_target);
        }
    }
    // proot's link2symlink leaves hard links as ".l2s.*" files that programs must not see by name
    const char *next_base = strrchr(next_path, '/');
    next_base = next_base ? next_base + 1 : next_path;
    if (strncmp(next_base, ".l2s.", 5) == 0) return;
    resolve_symlinks(next_path, out, out_size, depth + 1);
}

static char **ensure_hook_in_envp(char *const envp[], const char *real_exe) {
    return prepare_ddnatv_env(envp, real_exe);
}

// The file whose header decides how to start `target`: the symlink-resolved ELF (so ld.so and
// /proc/self/exe see the real program, as with the kernel), else `target` itself (scripts,
// links to ld.so). `probe` describes the returned file.
static const char *select_exec_target(const char *target, char *resolved, size_t resolved_size, struct exec_probe *probe) {
    resolve_symlinks(target, resolved, resolved_size, 0);
    probe_exec_file(resolved, probe);
    if (probe->is_elf && !is_ld_linux(resolved)) return resolved;
    if (strcmp(resolved, target) != 0) probe_exec_file(target, probe);
    return target;
}

// The library children get with --preload: the one this process runs from, so that a program
// always starts with the same build as its parent, else the first of usr/lib and lib that exists;
// NULL if neither does.
// The path dladdr() finds is kept (it does not change): set once, read by every later exec, and
// set before a fork by prepare_exec_in_parent() so that the children only read it.
static char g_hook_library[PATH_MAX];
static int g_hook_library_known = 0;

static const char *ddnatv_hook_library(char *buf, size_t bufsz) {
    static const char usr_lib[] = "/usr/lib/libddnatv-hook.so";
    static const char lib[] = "/lib/libddnatv-hook.so";
    if (__atomic_load_n(&g_hook_library_known, __ATOMIC_ACQUIRE)) return g_hook_library;
    Dl_info info;
    if (dladdr((void *)ddnatv_hook_library, &info) && info.dli_fname && info.dli_fname[0] == '/' &&
        strlen(info.dli_fname) < sizeof(g_hook_library)) {
        // loaded from there, so it exists
        ddnatv_copy_cstr(g_hook_library, sizeof(g_hook_library), info.dli_fname);
        __atomic_store_n(&g_hook_library_known, 1, __ATOMIC_RELEASE);
        return g_hook_library;
    }
    size_t n = ddnatv_append(buf, bufsz, 0, g_ddnatv_root, g_ddnatv_root_len);
    ddnatv_append(buf, bufsz, n, usr_lib, sizeof(usr_lib) - 1);
    if (access(buf, F_OK) == 0) return buf;
    n = ddnatv_append(buf, bufsz, 0, g_ddnatv_root, g_ddnatv_root_len);
    ddnatv_append(buf, bufsz, n, lib, sizeof(lib) - 1);
    return access(buf, F_OK) == 0 ? buf : NULL;
}

// ld.so does not need the exec bit on the program it loads, but other tools look at it: set
// it as the old access(X_OK)/chmod() pair did, using the mode the probe already has.
static void ensure_exec_bit(const char *path, mode_t mode) {
    if (S_ISDIR(mode) || (mode & 0111) != 0) return;
    if (strstr(path, "/bin/") || strstr(path, "/sbin/")) return;
    chmod(path, 0755);
}

// Rootfs path of a "#!" interpreter; a missing /bin/sh falls back to Android's shell.
static const char *shebang_interpreter(const char *interp, char *buf, size_t bufsz) {
    const char *rewritten = rewrite_path(interp, buf, bufsz);
    if (strcmp(interp, "/bin/sh") == 0 && access(rewritten, F_OK) != 0 && access("/system/bin/sh", X_OK) == 0) {
        return "/system/bin/sh";
    }
    return rewritten;
}

static const char *exec_prog_name(const char *target) {
    const char *slash = strrchr(target, '/');
    return slash ? slash + 1 : target;
}

// Hook execve
typedef int (*orig_execve_f_type)(const char *filename, char *const argv[], char *const envp[]);

// glibc's execve(), looked up once (also by prepare_exec_in_parent() before a fork)
static orig_execve_f_type g_orig_execve = NULL;

static orig_execve_f_type real_execve(void) {
    orig_execve_f_type f = __atomic_load_n(&g_orig_execve, __ATOMIC_RELAXED);
    if (!f) {
        f = (orig_execve_f_type)dlsym(RTLD_NEXT, "execve");
        __atomic_store_n(&g_orig_execve, f, __ATOMIC_RELAXED);
    }
    return f;
}

// prepare_exec_in_parent() rehearses an exec in the parent: everything up to the execve() itself,
// which this then skips. Only in the thread that rehearses: another thread's exec at that moment
// is a real one.
static int g_exec_rehearsal = 0;
static pthread_t g_exec_rehearsal_thread;

static int in_exec_rehearsal(void) {
    return __atomic_load_n(&g_exec_rehearsal, __ATOMIC_ACQUIRE) &&
           pthread_equal(pthread_self(), g_exec_rehearsal_thread);
}

static int run_execve(orig_execve_f_type f, const char *path, char *const argv[], char *const envp[]) {
    if (in_exec_rehearsal()) {
        errno = 0;
        return 0;
    }
    return f(path, argv, envp);
}

// ldconfig does nothing in The native userland (its cache is for paths that ld.so opens without the hook), but the
// exec code reads /etc/ld.so.conf(.d) again when /etc/ld.so.cache changes: "sudo ldconfig" after an
// edit of a .conf file marks it changed, as the real ldconfig would by writing it.
static void ldconfig_mark_cache(void) {
    if (g_ddnatv_root[0] == '\0') return;
    char path[PATH_MAX];
    size_t n = ddnatv_append(path, sizeof(path), 0, g_ddnatv_root, g_ddnatv_root_len);
    ddnatv_append(path, sizeof(path), n, "/etc/ld.so.cache", 16);
    int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_WRONLY | O_CREAT | O_CLOEXEC, 0644);
    if (fd < 0) return;
    syscall(SYS_utimensat, fd, NULL, NULL, 0);
    syscall(SYS_close, fd);
}

static int is_ldconfig_name(const char *name) {
    return strcmp(name, "ldconfig") == 0 || strcmp(name, "ldconfig.real") == 0;
}

static int ddnatv_execve(const char *filename, char *const argv[], char *const envp[], int depth) {
    orig_execve_f_type orig_execve = real_execve();
    if (!orig_execve) {
        errno = ENOSYS;
        return -1;
    }

    // Scripts get the path exactly as the caller wrote it ("./x.sh" stays "./x.sh" in $0),
    // like the kernel would pass it; routing below uses the absolute form.
    const char *caller_path = filename;
    char abs_buf[PATH_MAX];
    filename = absolutize_exec_path(filename, abs_buf, sizeof(abs_buf));

    char buf[PATH_MAX];
    const char *target = rewrite_path(filename, buf, sizeof(buf));

    init_ddnatv_hook();

    const char *f_base = (filename != NULL) ? strrchr(filename, '/') : NULL;
    f_base = (f_base != NULL) ? f_base + 1 : (filename != NULL ? filename : "");

    const char *prog_name = strrchr(target, '/');
    prog_name = (prog_name != NULL) ? prog_name + 1 : target;

    // Wine's preloader is a static program that opens the program and the dynamic linker it loads with
    // system calls of its own, which no hook can map, to reserve address ranges before the linker runs.
    // The program is started without it: its libc and ntdll reserve what Wine needs themselves.
    if ((strcmp(prog_name, "wine-preloader") == 0 || strcmp(prog_name, "wine64-preloader") == 0) &&
        argv && argv[0] && argv[1] && depth < DDNATV_MAX_SHEBANG_DEPTH) {
        return ddnatv_execve(argv[1], argv + 1, envp, depth + 1);
    }

    // Intercept utilities that fail or cause issues in unprivileged Android environment
    if (strcmp(f_base, "ldconfig") == 0 ||
        strcmp(f_base, "ldconfig.real") == 0 ||
        strcmp(f_base, "systemd-machine-id-setup") == 0 ||
        strcmp(f_base, "systemd-sysusers") == 0 ||
        strcmp(f_base, "systemd-tmpfiles") == 0 ||
        strcmp(f_base, "start-stop-daemon") == 0 ||
        strcmp(f_base, "mandb") == 0 ||
        strcmp(f_base, "mandb.real") == 0 ||
        strcmp(f_base, "update-mime-database") == 0 ||
        strcmp(f_base, "update-desktop-database") == 0 ||
        strcmp(f_base, "install-info") == 0 ||
        strcmp(f_base, "install-sgmlcatalog") == 0 ||
        strcmp(prog_name, "ldconfig") == 0 ||
        strcmp(prog_name, "ldconfig.real") == 0 ||
        strcmp(prog_name, "systemd-machine-id-setup") == 0 ||
        strcmp(prog_name, "systemd-sysusers") == 0 ||
        strcmp(prog_name, "systemd-tmpfiles") == 0 ||
        strcmp(prog_name, "start-stop-daemon") == 0 ||
        strcmp(prog_name, "mandb") == 0 ||
        strcmp(prog_name, "mandb.real") == 0 ||
        strcmp(prog_name, "update-mime-database") == 0 ||
        strcmp(prog_name, "update-desktop-database") == 0 ||
        strcmp(prog_name, "install-info") == 0 ||
        strcmp(prog_name, "install-sgmlcatalog") == 0) {
        if (is_ldconfig_name(f_base) || is_ldconfig_name(prog_name)) ldconfig_mark_cache();
        _exit(0);
    }
    if (strcmp(f_base, "mountpoint") == 0 || strcmp(prog_name, "mountpoint") == 0) {
        int is_virtual_fs = 0;
        for (int i = 1; argv && argv[i]; i++) {
            if (strcmp(argv[i], "/proc") == 0 || strcmp(argv[i], "/proc/") == 0 ||
                strcmp(argv[i], "/sys") == 0 || strcmp(argv[i], "/sys/") == 0 ||
                strcmp(argv[i], "/dev") == 0 || strcmp(argv[i], "/dev/") == 0 ||
                strcmp(argv[i], "/dev/pts") == 0) {
                is_virtual_fs = 1;
                break;
            }
        }
        if (is_virtual_fs) {
            _exit(0);
        }
    }
    if (strcmp(f_base, "policy-rc.d") == 0 || strcmp(prog_name, "policy-rc.d") == 0) {
        _exit(101);
    }

    // A static program inside the rootfs, to be made safe to start (prepare_static_program)
    const char *static_elf = NULL;
    char resolved_target[PATH_MAX];

    // Only intercept binaries/scripts within DDNATV_ROOT
    if ((g_ddnatv_root[0] != '\0' && strncmp(target, g_ddnatv_root, g_ddnatv_root_len) == 0) || is_mapped_guest_path(filename)) {
        struct exec_probe probe;
        const char *elf_target = select_exec_target(target, resolved_target, sizeof(resolved_target), &probe);
        if (probe.is_elf && !probe.is_dynamic) static_elf = elf_target;
        // A Go program built for Android asks for Android's linker, so it is started like the dynamic
        // ones, but it makes its own system calls: it gets the code of the static Go programs (#42)
        if (probe.is_dynamic && probe.android_interp) prepare_static_program_ex(elf_target, 1);

        // 1. Transparently route dynamically linked glibc ELF binaries through ld.so
        // Note: Statically linked binaries (PT_INTERP absent, e.g. Meta Muse Code) must execute directly!
        if (probe.is_dynamic) {
            char ld_so[PATH_MAX];
            ld_so[0] = '\0';
            if (find_dynamic_linker_for_machine(g_ddnatv_root, probe.e_machine, ld_so, sizeof(ld_so)) && strcmp(elf_target, ld_so) != 0) {
                ensure_exec_bit(elf_target, probe.mode);

                if (!in_exec_rehearsal()) ddnatv_copy_cstr(g_real_exe, sizeof(g_real_exe), elf_target);

                char hook_buf[PATH_MAX];
                const char *hook_so = ddnatv_hook_library(hook_buf, sizeof(hook_buf));
                char ld_lib_path[PATH_MAX * 6];
                build_ld_library_path(envp, probe.runpath, probe.runpath_is_rpath, elf_target, ld_lib_path, sizeof(ld_lib_path));
                char **new_argv = build_ldso_argv(ld_so, hook_so, ld_lib_path, elf_target, exec_prog_name(target), argv);
                if (!new_argv) {
                    errno = ENOMEM;
                    return -1;
                }
                char **new_env = ensure_hook_in_envp(envp, elf_target);
                int ret = run_execve(orig_execve, ld_so, new_argv, new_env);
                if (errno == EACCES && chmod(ld_so, 0755) == 0) {
                    // ld.so without its exec bit (an unusual restore): fix it once and retry
                    ret = run_execve(orig_execve, ld_so, new_argv, new_env);
                }
                int saved_errno = errno;
                free(new_argv);
                free_modified_env(new_env, envp);
                errno = saved_errno;
                return ret;
            }
        }

        // ld.so started directly (ldd, "ld.so --list PROGRAM", "ld.so PROGRAM"): the program it loads
        // gets the --library-path a start of its own gets, or ldd reports its libraries as not found,
        // and its path mapped into the rootfs, since ld.so opens it without the hook
        if (probe.is_elf && !probe.is_dynamic && is_ld_linux(elf_target)) {
            int caller_lib_path = 0;
            int pi = ldso_program_index(argv, &caller_lib_path);
            if (pi > 0 && caller_lib_path) {
                // the caller's own --library-path stays as it is; only the program is mapped (when it is
                // in the rootfs: a caller can also name a file by its real path)
                char prog_buf[PATH_MAX];
                const char *prog = argv[pi];
                if (prog[0] == '/') {
                    const char *mapped = rewrite_path(prog, prog_buf, sizeof(prog_buf));
                    int fd = mapped != prog ? (int)syscall(SYS_openat, AT_FDCWD, mapped, O_PATH | O_CLOEXEC) : -1;
                    if (fd >= 0) {
                        syscall(SYS_close, fd);
                        prog = mapped;
                    }
                }
                int argc = 0;
                while (argv[argc]) argc++;
                char **new_argv = (char **)malloc(sizeof(char *) * (size_t)(argc + 1));
                if (!new_argv) {
                    errno = ENOMEM;
                    return -1;
                }
                for (int i = 0; i <= argc; i++) new_argv[i] = argv[i];
                new_argv[pi] = (char *)prog;
                prepare_static_program(static_elf); // ld.so is a static program itself
                char **ddnatv_env = prepare_ddnatv_env(envp, target);
                int ret = run_execve(orig_execve, target, new_argv, ddnatv_env);
                int saved_errno = errno;
                free_modified_env(ddnatv_env, envp);
                free(new_argv);
                errno = saved_errno;
                return ret;
            }
            if (pi > 0) {
                char prog_buf[PATH_MAX];
                const char *prog = argv[pi][0] == '/' ? rewrite_path(argv[pi], prog_buf, sizeof(prog_buf)) : argv[pi];
                struct exec_probe prog_probe;
                probe_exec_file(prog, &prog_probe);
                char ld_lib_path[PATH_MAX * 6];
                build_ld_library_path(envp, prog_probe.runpath, prog_probe.runpath_is_rpath,
                                      prog[0] == '/' ? prog : NULL, ld_lib_path, sizeof(ld_lib_path));
                int argc = 0;
                while (argv[argc]) argc++;
                char **new_argv = (char **)malloc(sizeof(char *) * (size_t)(argc + 3));
                if (!new_argv) {
                    errno = ENOMEM;
                    return -1;
                }
                new_argv[0] = argv[0];
                new_argv[1] = (char *)"--library-path";
                new_argv[2] = ld_lib_path;
                for (int i = 1; i <= argc; i++) new_argv[i + 2] = argv[i];
                new_argv[pi + 2] = (char *)prog; // ld.so opens it itself: "ldd /usr/bin/adb" as well
                prepare_static_program(static_elf); // ld.so is a static program itself
                char **ddnatv_env = prepare_ddnatv_env(envp, target);
                int ret = run_execve(orig_execve, target, new_argv, ddnatv_env);
                int saved_errno = errno;
                free_modified_env(ddnatv_env, envp);
                free(new_argv);
                errno = saved_errno;
                return ret;
            }
        }

        // 2. Handle scripts with shebang lines (e.g., #!/bin/sh, #!/usr/bin/perl)
        if (!probe.is_elf && probe.n >= 2 && probe.hdr[0] == '#' && probe.hdr[1] == '!') {
            char *interp;
            char *interp_arg;
            parse_shebang(probe.hdr, &interp, &interp_arg);
            char interp_buf[PATH_MAX];
            const char *rewritten_interp = shebang_interpreter(interp, interp_buf, sizeof(interp_buf));
            const char *script = (caller_path && caller_path[0] != '/') ? caller_path : target;
            char **new_argv = build_script_argv(rewritten_interp, interp_arg, script, argv);
            if (!new_argv) {
                errno = ENOMEM;
                return -1;
            }
            int ret;
            if (is_host_interpreter(rewritten_interp)) {
                char **sys_env = clean_env_for_system(envp);
                ret = run_execve(orig_execve, rewritten_interp, new_argv, sys_env);
                int saved_errno = errno;
                free_modified_env(sys_env, envp);
                errno = saved_errno;
            } else if (depth >= DDNATV_MAX_SHEBANG_DEPTH) {
                errno = ELOOP;
                ret = -1;
            } else {
                char **ddnatv_env = prepare_ddnatv_env(envp, NULL);
                ret = ddnatv_execve(rewritten_interp, new_argv, ddnatv_env, depth + 1);
                int saved_errno = errno;
                free_modified_env(ddnatv_env, envp);
                errno = saved_errno;
            }
            free(new_argv);
            return ret;
        }
    } else {
        // Any binary outside DDNATV_ROOT is an Android host binary (e.g. /system/bin/su, /sbin/su, /data/adb/...)
        char **sys_env = clean_env_for_system(envp);
        int ret = run_execve(orig_execve, target, argv, sys_env);
        int saved_errno = errno;
        free_modified_env(sys_env, envp);
        errno = saved_errno;
        return ret;
    }

    if (static_elf) prepare_static_program(static_elf);
    char **ddnatv_env = prepare_ddnatv_env(envp, target);
    int ret = run_execve(orig_execve, target, argv, ddnatv_env);
    int saved_errno = errno;
    free_modified_env(ddnatv_env, envp);
    errno = saved_errno;
    return ret;
}

int execve(const char *filename, char *const argv[], char *const envp[]) {
    return ddnatv_execve(filename, argv, envp, 0);
}

// ---- fork() ---------------------------------------------------------------------------------
// A shell (and make, dpkg, perl, ...) starts a program with fork() and then execve() in the child.
// What the exec code above needs for the first time in a process (glibc's execve, fstat and readlink
// behind the hooks, the rootfs' ld.so, the path of this library) it found in the child, where every
// page it writes is a copy of the parent's: some 40 copy-on-write faults before each program even
// started, where a shell's child without the hook has fewer than ten. Now the parent finds them at
// its first fork(), and the children inherit them and only read them. At its eighth fork() the
// parent also rehearses an exec (everything but the execve() itself), which binds the calls the
// exec code makes and touches the code it runs. That costs about as much as it saves in four
// children, so only a process that keeps forking (a shell running a loop or a long script, make,
// dpkg) does it; one that forks a few times (sh -c with a pipe) does not pay for it.
#define DDNATV_FORKS_BEFORE_REHEARSAL 8
static int g_exec_prepared = 0;   // 1 after the first fork(), 2 after the rehearsal
static pthread_mutex_t g_exec_prepare_lock = PTHREAD_MUTEX_INITIALIZER;

static void rehearse_exec(void) {
    static const char rehearsal_target[] = "/usr/bin/true";
    char target_buf[PATH_MAX];
    const char *target = rewrite_path(rehearsal_target, target_buf, sizeof(target_buf));
    struct exec_probe probe;
    // A dynamic program only: it takes the one way through ddnatv_execve() that ends in run_execve()
    probe_exec_file(target, &probe);
    if (!probe.is_elf || !probe.is_dynamic) return;
    extern char **environ;
    char *rehearsal_argv[] = { (char *)"true", NULL };
    g_exec_rehearsal_thread = pthread_self();
    __atomic_store_n(&g_exec_rehearsal, 1, __ATOMIC_RELEASE);
    ddnatv_execve(rehearsal_target, rehearsal_argv, environ, 0);
    __atomic_store_n(&g_exec_rehearsal, 0, __ATOMIC_RELEASE);
}

static void prepare_exec_in_parent(int level) {
    // One thread prepares (two rehearsing at once would take each other for the other's thread).
    // Not waiting for the lock: a signal handler that forks while its thread is preparing would wait
    // for itself; a busy lock leaves the preparation to a later fork().
    if (pthread_mutex_trylock(&g_exec_prepare_lock) != 0) return;
    int done = __atomic_load_n(&g_exec_prepared, __ATOMIC_ACQUIRE);
    if (done >= level) {
        pthread_mutex_unlock(&g_exec_prepare_lock);
        return;
    }
    int saved_errno = errno;
    init_ddnatv_hook();
    real_execve();
    if (g_ddnatv_root[0] != '\0') {
        if (done < 1) {
            char ld_so[PATH_MAX];
#if defined(__aarch64__)
            uint16_t machine = 183;
#elif defined(__arm__)
            uint16_t machine = 40;
#elif defined(__x86_64__)
            uint16_t machine = 62;
#else
            uint16_t machine = 0;
#endif
            if (machine && find_dynamic_linker_for_machine(g_ddnatv_root, machine, ld_so, sizeof(ld_so))) {
                // readlink(), open(), read() and close() behind the hooks, as the probe calls them
                char resolved[PATH_MAX];
                struct exec_probe probe;
                resolve_symlinks(ld_so, resolved, sizeof(resolved), 0);
                probe_exec_file(ld_so, &probe);
            }
            char hook_buf[PATH_MAX];
            ddnatv_hook_library(hook_buf, sizeof(hook_buf));
            struct stat st;
            probe_fstat(-1, &st); // looks fstat() up; -1 only fails with EBADF
            char conf[PATH_MAX * 2];
            ldconf_dirs(conf, sizeof(conf)); // ld.so.conf read here, so the children only compare times
        }
        if (level >= 2) rehearse_exec();
    }
    __atomic_store_n(&g_exec_prepared, level, __ATOMIC_RELEASE);
    pthread_mutex_unlock(&g_exec_prepare_lock);
    errno = saved_errno;
}

pid_t fork(void) {
    static pid_t (*orig_fork)(void) = NULL;
    if (!orig_fork) orig_fork = (pid_t (*)(void))dlsym(RTLD_NEXT, "fork");
    if (!orig_fork) {
        errno = ENOSYS;
        return -1;
    }
    int done = __atomic_load_n(&g_exec_prepared, __ATOMIC_ACQUIRE);
    if (done < 2) {
        static int forks = 0;
        int n = __atomic_add_fetch(&forks, 1, __ATOMIC_RELAXED);
        if (n == 1 || n >= DDNATV_FORKS_BEFORE_REHEARSAL) prepare_exec_in_parent(n >= DDNATV_FORKS_BEFORE_REHEARSAL ? 2 : 1);
    }
    pid_t pid = orig_fork();
    // A child forked from a signal handler that interrupted a rehearsal is not rehearsing: its
    // execve() is a real one (only read in the usual case, so no page is copied for it)
    if (pid == 0 && __atomic_load_n(&g_exec_rehearsal, __ATOMIC_RELAXED)) __atomic_store_n(&g_exec_rehearsal, 0, __ATOMIC_RELAXED);
    return pid;
}

extern char **environ;

int execv(const char *path, char *const argv[]) {
    return execve(path, argv, environ);
}

int execl(const char *path, const char *arg0, ...) {
    va_list args;
    va_start(args, arg0);
    int count = (arg0 != NULL) ? 1 : 0;
    if (arg0 != NULL) {
        while (va_arg(args, const char *) != NULL) {
            count++;
        }
    }
    va_end(args);

    char **argv = (char **)calloc(count + 1, sizeof(char *));
    if (!argv) {
        errno = ENOMEM;
        return -1;
    }
    if (count > 0) {
        argv[0] = (char *)arg0;
        va_start(args, arg0);
        for (int i = 1; i < count; i++) {
            argv[i] = va_arg(args, char *);
        }
        va_end(args);
    }
    argv[count] = NULL;
    int ret = execv(path, argv);
    free(argv);
    return ret;
}

int execlp(const char *file, const char *arg0, ...) {
    va_list args;
    va_start(args, arg0);
    int count = (arg0 != NULL) ? 1 : 0;
    if (arg0 != NULL) {
        while (va_arg(args, const char *) != NULL) {
            count++;
        }
    }
    va_end(args);

    char **argv = (char **)calloc(count + 1, sizeof(char *));
    if (!argv) {
        errno = ENOMEM;
        return -1;
    }
    if (count > 0) {
        argv[0] = (char *)arg0;
        va_start(args, arg0);
        for (int i = 1; i < count; i++) {
            argv[i] = va_arg(args, char *);
        }
        va_end(args);
    }
    argv[count] = NULL;
    int ret = execvp(file, argv);
    free(argv);
    return ret;
}

int execle(const char *path, const char *arg0, ...) {
    va_list args;
    va_start(args, arg0);
    int count = (arg0 != NULL) ? 1 : 0;
    if (arg0 != NULL) {
        while (va_arg(args, const char *) != NULL) {
            count++;
        }
    }
    char *const *envp = va_arg(args, char *const *);
    va_end(args);

    char **argv = (char **)calloc(count + 1, sizeof(char *));
    if (!argv) {
        errno = ENOMEM;
        return -1;
    }
    if (count > 0) {
        argv[0] = (char *)arg0;
        va_start(args, arg0);
        for (int i = 1; i < count; i++) {
            argv[i] = va_arg(args, char *);
        }
        va_end(args);
    }
    argv[count] = NULL;
    int ret = execve(path, argv, (char *const *)envp);
    free(argv);
    return ret;
}

int execvp(const char *file, char *const argv[]) {
    if (!file || !*file) {
        errno = ENOENT;
        return -1;
    }
    if (strchr(file, '/')) {
        return execve(file, argv, environ);
    }
    const char *path_env = getenv("PATH");
    if (!path_env) path_env = "/usr/bin:/bin";
    char path_copy[4096];
    strncpy(path_copy, path_env, sizeof(path_copy) - 1);
    path_copy[sizeof(path_copy) - 1] = '\0';
    char *saveptr = NULL;
    char *token = strtok_r(path_copy, ":", &saveptr);
    while (token) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/%s", token, file);
        if (access(candidate, F_OK) == 0) {
            return execve(candidate, argv, environ);
        }
        token = strtok_r(NULL, ":", &saveptr);
    }
    const char *standard_paths[] = {"/usr/bin", "/bin", "/usr/sbin", "/sbin", "/usr/local/bin", NULL};
    for (int i = 0; standard_paths[i] != NULL; i++) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/%s", standard_paths[i], file);
        if (access(candidate, F_OK) == 0) {
            return execve(candidate, argv, environ);
        }
    }
    return execve(file, argv, environ);
}

int execvpe(const char *file, char *const argv[], char *const envp[]) {
    if (!file || !*file) {
        errno = ENOENT;
        return -1;
    }
    if (strchr(file, '/')) {
        return execve(file, argv, envp);
    }
    const char *path_env = NULL;
    if (envp) {
        for (char *const *ep = envp; *ep; ep++) {
            if (strncmp(*ep, "PATH=", 5) == 0) {
                path_env = *ep + 5;
                break;
            }
        }
    }
    if (!path_env) path_env = getenv("PATH");
    if (!path_env) path_env = "/usr/bin:/bin";
    char path_copy[4096];
    strncpy(path_copy, path_env, sizeof(path_copy) - 1);
    path_copy[sizeof(path_copy) - 1] = '\0';
    char *saveptr = NULL;
    char *token = strtok_r(path_copy, ":", &saveptr);
    while (token) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/%s", token, file);
        if (access(candidate, F_OK) == 0) {
            return execve(candidate, argv, envp);
        }
        token = strtok_r(NULL, ":", &saveptr);
    }
    const char *standard_paths[] = {"/usr/bin", "/bin", "/usr/sbin", "/sbin", "/usr/local/bin", NULL};
    for (int i = 0; standard_paths[i] != NULL; i++) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/%s", standard_paths[i], file);
        if (access(candidate, F_OK) == 0) {
            return execve(candidate, argv, envp);
        }
    }
    return execve(file, argv, envp);
}

typedef int (*orig_posix_spawn_f_type)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                                      const posix_spawnattr_t *, char *const [], char *const []);

static orig_posix_spawn_f_type real_posix_spawn(void) {
    static orig_posix_spawn_f_type orig_posix_spawn = NULL;
    if (!orig_posix_spawn) orig_posix_spawn = (orig_posix_spawn_f_type)dlsym(RTLD_NEXT, "posix_spawn");
    return orig_posix_spawn;
}

static int ddnatv_posix_spawn(pid_t *pid, const char *path,
                              const posix_spawn_file_actions_t *file_actions,
                              const posix_spawnattr_t *attrp,
                              char *const argv[], char *const envp[], int depth);

// A rootfs script: start its interpreter with the same file actions and attributes. Handing the
// script to the kernel resolves "#!/bin/sh" to Android's own shell, which crashed on the glibc
// hook in LD_PRELOAD (and "#!/usr/bin/env" etc. used to fall back to fork() + execve(), which
// dropped the file actions).
static int spawn_script(pid_t *pid, const char *script, struct exec_probe *probe,
                        const posix_spawn_file_actions_t *file_actions, const posix_spawnattr_t *attrp,
                        char *const argv[], char *const envp[], int depth) {
    char *interp;
    char *interp_arg;
    parse_shebang(probe->hdr, &interp, &interp_arg);
    char interp_buf[PATH_MAX];
    const char *rewritten_interp = shebang_interpreter(interp, interp_buf, sizeof(interp_buf));
    char **new_argv = build_script_argv(rewritten_interp, interp_arg, script, argv);
    if (!new_argv) return ENOMEM;
    int ret;
    if (is_host_interpreter(rewritten_interp)) {
        orig_posix_spawn_f_type orig_posix_spawn = real_posix_spawn();
        char **sys_env = clean_env_for_system(envp);
        ret = orig_posix_spawn ? orig_posix_spawn(pid, rewritten_interp, file_actions, attrp, new_argv, sys_env) : ENOSYS;
        free_modified_env(sys_env, envp);
    } else {
        ret = ddnatv_posix_spawn(pid, rewritten_interp, file_actions, attrp, new_argv, envp, depth + 1);
    }
    free(new_argv);
    return ret;
}

static int ddnatv_posix_spawn(pid_t *pid, const char *path,
                              const posix_spawn_file_actions_t *file_actions,
                              const posix_spawnattr_t *attrp,
                              char *const argv[], char *const envp[], int depth) {
    orig_posix_spawn_f_type orig_posix_spawn = real_posix_spawn();

    const char *caller_path = path;
    char abs_buf[PATH_MAX];
    path = absolutize_exec_path(path, abs_buf, sizeof(abs_buf));

    char buf[PATH_MAX];
    const char *target = rewrite_path(path, buf, sizeof(buf));

    init_ddnatv_hook();

    const char *f_base = (path != NULL) ? strrchr(path, '/') : NULL;
    f_base = (f_base != NULL) ? f_base + 1 : (path != NULL ? path : "");

    const char *prog_name = exec_prog_name(target);

    if (strcmp(f_base, "ldconfig") == 0 ||
        strcmp(f_base, "ldconfig.real") == 0 ||
        strcmp(f_base, "systemd-machine-id-setup") == 0 ||
        strcmp(f_base, "systemd-sysusers") == 0 ||
        strcmp(f_base, "systemd-tmpfiles") == 0 ||
        strcmp(f_base, "start-stop-daemon") == 0 ||
        strcmp(prog_name, "ldconfig") == 0 ||
        strcmp(prog_name, "ldconfig.real") == 0 ||
        strcmp(prog_name, "systemd-machine-id-setup") == 0 ||
        strcmp(prog_name, "systemd-sysusers") == 0 ||
        strcmp(prog_name, "systemd-tmpfiles") == 0 ||
        strcmp(prog_name, "start-stop-daemon") == 0) {
        pid_t child = fork();
        if (child == 0) {
            if (is_ldconfig_name(f_base) || is_ldconfig_name(prog_name)) ldconfig_mark_cache();
            _exit(0);
        } else if (child > 0) {
            if (pid) *pid = child;
            return 0;
        }
        return errno;
    }
    if (strcmp(f_base, "mountpoint") == 0 || strcmp(prog_name, "mountpoint") == 0) {
        int is_virtual_fs = 0;
        for (int i = 1; argv && argv[i]; i++) {
            if (strcmp(argv[i], "/proc") == 0 || strcmp(argv[i], "/proc/") == 0 ||
                strcmp(argv[i], "/sys") == 0 || strcmp(argv[i], "/sys/") == 0 ||
                strcmp(argv[i], "/dev") == 0 || strcmp(argv[i], "/dev/") == 0 ||
                strcmp(argv[i], "/dev/pts") == 0) {
                is_virtual_fs = 1;
                break;
            }
        }
        if (is_virtual_fs) {
            pid_t child = fork();
            if (child == 0) {
                _exit(0);
            } else if (child > 0) {
                if (pid) *pid = child;
                return 0;
            }
            return errno;
        }
    }
    if (strcmp(f_base, "policy-rc.d") == 0 || strcmp(prog_name, "policy-rc.d") == 0) {
        pid_t child = fork();
        if (child == 0) {
            _exit(101);
        } else if (child > 0) {
            if (pid) *pid = child;
            return 0;
        }
        return errno;
    }

    char *const *orig_ep = envp ? envp : environ;
    int in_root = (g_ddnatv_root[0] != '\0' && strncmp(target, g_ddnatv_root, g_ddnatv_root_len) == 0) || is_mapped_guest_path(path);

    struct exec_probe probe;
    char resolved_target[PATH_MAX];
    const char *elf_target = target;
    if (in_root) {
        elf_target = select_exec_target(target, resolved_target, sizeof(resolved_target), &probe);
        if (!probe.is_elf && probe.n >= 2 && probe.hdr[0] == '#' && probe.hdr[1] == '!') {
            // Like the kernel, give up after a few nested "#!" interpreters.
            if (depth >= DDNATV_MAX_SHEBANG_DEPTH) return ELOOP;
            const char *script = (caller_path && caller_path[0] != '/') ? caller_path : target;
            return spawn_script(pid, script, &probe, file_actions, attrp, argv, orig_ep, depth);
        }
    } else {
        probe.hdr[0] = '\0';
        probe.n = -1;
        probe.is_elf = 0;
        probe.is_dynamic = 0;
        probe.android_interp = 0;
        probe.e_machine = 0;
        probe.mode = 0;
        probe.runpath[0] = '\0';
        probe.runpath_is_rpath = 0;
    }

    char ld_so[PATH_MAX];
    ld_so[0] = '\0';
    int has_ld_so = probe.is_dynamic ? find_dynamic_linker_for_machine(g_ddnatv_root, probe.e_machine, ld_so, sizeof(ld_so)) : 0;

    // Android programs get Android's environment, as execve() gives them: the glibc hook in
    // LD_PRELOAD crashes them.
    char **new_envp = in_root ? ensure_hook_in_envp(orig_ep, elf_target) : clean_env_for_system(orig_ep);

    int ret = -1;
    if (has_ld_so && strcmp(elf_target, ld_so) != 0) {
        ensure_exec_bit(elf_target, probe.mode);
        if (probe.android_interp) prepare_static_program_ex(elf_target, 1); // a Go program built for Android (#42)

        char hook_buf[PATH_MAX];
        const char *hook_so = ddnatv_hook_library(hook_buf, sizeof(hook_buf));
        char ld_lib_path[PATH_MAX * 6];
        build_ld_library_path(orig_ep, probe.runpath, probe.runpath_is_rpath, elf_target, ld_lib_path, sizeof(ld_lib_path));
        char **new_argv = build_ldso_argv(ld_so, hook_so, ld_lib_path, elf_target, prog_name, argv);
        if (!new_argv) {
            ret = ENOMEM;
        } else {
            if (orig_posix_spawn) {
                ret = orig_posix_spawn(pid, ld_so, file_actions, attrp, new_argv, new_envp);
                if (ret == EACCES && chmod(ld_so, 0755) == 0) {
                    // ld.so without its exec bit (an unusual restore): fix it once and retry
                    ret = orig_posix_spawn(pid, ld_so, file_actions, attrp, new_argv, new_envp);
                }
            }
            free(new_argv);
        }
    } else {
        if (in_root && probe.is_elf && !probe.is_dynamic) prepare_static_program(elf_target);
        if (orig_posix_spawn) {
            ret = orig_posix_spawn(pid, target, file_actions, attrp, argv, new_envp);
        }
    }

    if (!orig_posix_spawn) {
        pid_t child = fork();
        if (child < 0) {
            free_modified_env(new_envp, orig_ep);
            return errno;
        } else if (child == 0) {
            execve(target, argv, new_envp);
            _exit(127);
        } else {
            if (pid) *pid = child;
            free_modified_env(new_envp, orig_ep);
            return 0;
        }
    }
    free_modified_env(new_envp, orig_ep);
    return ret;
}

int posix_spawn(pid_t *pid, const char *path,
                const posix_spawn_file_actions_t *file_actions,
                const posix_spawnattr_t *attrp,
                char *const argv[], char *const envp[]) {
    posix_spawnattr_t sanitized;
    return ddnatv_posix_spawn(pid, path, file_actions, spawn_attr_for_android(attrp, &sanitized), argv, envp, 0);
}

int posix_spawnp(pid_t *pid, const char *file,
                 const posix_spawn_file_actions_t *file_actions,
                 const posix_spawnattr_t *attrp,
                 char *const argv[], char *const envp[]) {
    if (!file || !*file) {
        return ENOENT;
    }
    if (strchr(file, '/')) {
        return posix_spawn(pid, file, file_actions, attrp, argv, envp);
    }
    const char *path_env = NULL;
    if (envp) {
        for (char *const *ep = envp; *ep; ep++) {
            if (strncmp(*ep, "PATH=", 5) == 0) {
                path_env = *ep + 5;
                break;
            }
        }
    }
    if (!path_env) path_env = getenv("PATH");
    if (!path_env) path_env = "/usr/bin:/bin";
    char path_copy[4096];
    strncpy(path_copy, path_env, sizeof(path_copy) - 1);
    path_copy[sizeof(path_copy) - 1] = '\0';
    char *saveptr = NULL;
    char *token = strtok_r(path_copy, ":", &saveptr);
    while (token) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/%s", token, file);
        if (access(candidate, F_OK) == 0) {
            return posix_spawn(pid, candidate, file_actions, attrp, argv, envp);
        }
        token = strtok_r(NULL, ":", &saveptr);
    }
    const char *standard_paths[] = {"/usr/bin", "/bin", "/usr/sbin", "/sbin", "/usr/local/bin", NULL};
    for (int i = 0; standard_paths[i] != NULL; i++) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/%s", standard_paths[i], file);
        if (access(candidate, F_OK) == 0) {
            return posix_spawn(pid, candidate, file_actions, attrp, argv, envp);
        }
    }
    // If not found in PATH or standard paths, return ENOENT without spawning zombie
    return ENOENT;
}

// ---- system(), popen() and pclose() ---------------------------------------------------------
// glibc starts the shell of system() and popen() from inside the C library (its own posix_spawn and
// execve), where the hook's execve() is not called: the kernel ran the machine's /bin/sh, which on
// Android is the system's own shell, and that one failed to link against Ubuntu's libraries
// ("CANNOT LINK EXECUTABLE sh: ...libc.so has bad ELF magic", #49). They start Ubuntu's /bin/sh here.

extern char **environ;

static pid_t start_shell(const char *command, int child_fd, int target_fd, const sigset_t *mask,
                         const struct sigaction *intr, const struct sigaction *quit, int close_popen_streams);

// While system() waits, SIGINT and SIGQUIT are ignored; with several threads in system() at once, the
// first one in saves the dispositions and the last one out puts them back (as glibc does)
static pthread_mutex_t g_system_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_system_waiting;
static struct sigaction g_system_intr, g_system_quit;

// system(3) as POSIX describes it: SIGINT and SIGQUIT ignored and SIGCHLD blocked while it waits
int system(const char *command) {
    if (!command) return access("/bin/sh", X_OK) == 0; // whether there is a shell (Ubuntu's)
    pthread_mutex_lock(&g_system_lock);
    if (g_system_waiting++ == 0) {
        struct sigaction ignore;
        memset(&ignore, 0, sizeof(ignore));
        ignore.sa_handler = SIG_IGN;
        sigemptyset(&ignore.sa_mask);
        sigaction(SIGINT, &ignore, &g_system_intr);
        sigaction(SIGQUIT, &ignore, &g_system_quit);
    }
    struct sigaction intr = g_system_intr, quit = g_system_quit;
    pthread_mutex_unlock(&g_system_lock);
    sigset_t block, old;
    sigemptyset(&block);
    sigaddset(&block, SIGCHLD);
    sigprocmask(SIG_BLOCK, &block, &old);

    int status = -1;
    pid_t pid = start_shell(command, -1, -1, &old, &intr, &quit, 0);
    if (pid > 0) {
        while (waitpid(pid, &status, 0) < 0) {
            if (errno != EINTR) {
                status = -1;
                break;
            }
        }
    }
    int saved = errno;
    pthread_mutex_lock(&g_system_lock);
    if (--g_system_waiting == 0) {
        sigaction(SIGINT, &g_system_intr, NULL);
        sigaction(SIGQUIT, &g_system_quit, NULL);
    }
    pthread_mutex_unlock(&g_system_lock);
    sigprocmask(SIG_SETMASK, &old, NULL);
    errno = saved;
    return status;
}

// The streams popen() made, with their shells: pclose() waits for the one of its stream
struct ddnatv_popen {
    FILE *fp;
    pid_t pid;
    struct ddnatv_popen *next;
};
static struct ddnatv_popen *g_popen_streams;
static pthread_mutex_t g_popen_lock = PTHREAD_MUTEX_INITIALIZER;

static pid_t start_shell(const char *command, int child_fd, int target_fd, const sigset_t *mask,
                         const struct sigaction *intr, const struct sigaction *quit, int close_popen_streams) {
    pid_t pid = fork();
    if (pid != 0) return pid;
    if (intr) sigaction(SIGINT, intr, NULL);
    if (quit) sigaction(SIGQUIT, quit, NULL);
    if (mask) sigprocmask(SIG_SETMASK, mask, NULL);
    if (child_fd >= 0) {
        if (child_fd == target_fd) {
            fcntl(target_fd, F_SETFD, 0);
        } else if (dup2(child_fd, target_fd) < 0) {
            _exit(127);
        }
    }
    // POSIX: the streams of earlier popen() calls are not open in the new shell
    if (close_popen_streams) {
        for (struct ddnatv_popen *p = g_popen_streams; p; p = p->next) {
            int fd = fileno(p->fp);
            if (fd >= 0 && fd != target_fd) close(fd);
        }
    }
    char *const argv[] = { (char *)"sh", (char *)"-c", (char *)command, NULL };
    ddnatv_execve("/bin/sh", argv, environ, 0);
    _exit(127);
}

FILE *popen(const char *command, const char *type) {
    if (!command || !type || (type[0] != 'r' && type[0] != 'w')) {
        errno = EINVAL;
        return NULL;
    }
    int reading = type[0] == 'r';
    int keep_on_exec = strchr(type, 'e') == NULL;
    int fds[2];
    if (pipe2(fds, O_CLOEXEC) != 0) return NULL;
    int parent_end = reading ? fds[0] : fds[1];
    int child_end = reading ? fds[1] : fds[0];
    struct ddnatv_popen *entry = (struct ddnatv_popen *)malloc(sizeof(*entry));
    if (!entry) {
        close(fds[0]);
        close(fds[1]);
        errno = ENOMEM;
        return NULL;
    }
    pthread_mutex_lock(&g_popen_lock);
    pid_t pid = start_shell(command, child_end, reading ? STDOUT_FILENO : STDIN_FILENO, NULL, NULL, NULL, 1);
    int saved = errno;
    close(child_end);
    FILE *fp = NULL;
    if (pid > 0) {
        if (keep_on_exec) fcntl(parent_end, F_SETFD, 0);
        fp = fdopen(parent_end, reading ? "r" : "w");
        saved = errno;
    }
    if (!fp) {
        close(parent_end);
        pthread_mutex_unlock(&g_popen_lock);
        free(entry);
        if (pid > 0) {
            // A shell nobody will read or write: it is not left to run (or waited for while it does)
            kill(pid, SIGKILL);
            while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {}
        }
        errno = saved;
        return NULL;
    }
    entry->fp = fp;
    entry->pid = pid;
    entry->next = g_popen_streams;
    g_popen_streams = entry;
    pthread_mutex_unlock(&g_popen_lock);
    return fp;
}

int pclose(FILE *stream) {
    struct ddnatv_popen *found = NULL;
    pthread_mutex_lock(&g_popen_lock);
    for (struct ddnatv_popen **p = &g_popen_streams; *p; p = &(*p)->next) {
        if ((*p)->fp == stream) {
            found = *p;
            *p = found->next;
            break;
        }
    }
    // Closed before the lock is let go: a popen() in between would fork a shell that keeps this pipe open,
    // and the shell of this stream would wait for its end of it
    if (found) fclose(stream);
    pthread_mutex_unlock(&g_popen_lock);
    if (!found) {
        // Not one of ours: glibc's own
        static int (*orig_pclose)(FILE *) = NULL;
        if (!orig_pclose) orig_pclose = (int (*)(FILE *))dlsym(RTLD_NEXT, "pclose");
        if (!orig_pclose) {
            errno = ECHILD;
            return -1;
        }
        return orig_pclose(stream);
    }
    pid_t pid = found->pid;
    free(found);
    int status = -1;
    pid_t r;
    do {
        r = waitpid(pid, &status, 0);
    } while (r < 0 && errno == EINTR);
    return r < 0 ? -1 : status;
}

#endif /* !DDNATV_HOST_TEST */
