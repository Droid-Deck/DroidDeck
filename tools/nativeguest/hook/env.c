// Part of libddnatv-hook.so, included by ddnatv-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// The environment of started programs: Android programs get it without the hook, rootfs
// programs with it.

#ifndef DDNATV_HOST_TEST

static const char *ddnatv_hook_library(char *buf, size_t bufsz);

static char **clean_env_for_system(char *const envp[]) {
    int count = 0;
    while (envp && envp[count]) count++;
    char **new_env = calloc(count + 3, sizeof(char *));
    int dst = 0;
    int has_path = 0;
    for (int i = 0; i < count; i++) {
        char first = envp[i][0];
        if (!((first == 'L' && (strncmp(envp[i], "LD_PRELOAD=", 11) == 0 ||
                                strncmp(envp[i], "LD_LIBRARY_PATH=", 16) == 0)) ||
              (first == 'G' && strncmp(envp[i], "GLIBC_TUNABLES=", 15) == 0))) {
            if (first == 'P' && strncmp(envp[i], "PATH=", 5) == 0) {
                has_path = 1;
                size_t plen = strlen(envp[i]);
                char *new_path = malloc(plen + 64);
                if (new_path) {
                    snprintf(new_path, plen + 64, "PATH=/system/bin:/system/xbin:/sbin:/vendor/bin:%s", envp[i] + 5);
                    new_env[dst++] = new_path;
                } else {
                    new_env[dst++] = envp[i];
                }
            } else {
                new_env[dst++] = envp[i];
            }
        }
    }
    if (!has_path) {
        new_env[dst++] = strdup("PATH=/system/bin:/system/xbin:/sbin:/vendor/bin");
    }
    new_env[dst] = NULL;
    return new_env;
}

// LD_PRELOAD with every absolute path that is not in the rootfs yet mapped into it. Entries are
// separated by ':' or ' '. free() the result.
static char *map_preload_paths(const char *value) {
    if (!value) return NULL;
    size_t cap = strlen(value) + (g_ddnatv_root_len + 1) * 16 + 16;
    char *out = malloc(cap);
    if (!out) return NULL;
    size_t used = 0;
    out[0] = '\0';
    const char *p = value;
    while (*p) {
        size_t len = strcspn(p, ": ");
        if (len > 0 && len < PATH_MAX) {
            char entry[PATH_MAX];
            memcpy(entry, p, len);
            entry[len] = '\0';
            char buf[PATH_MAX];
            const char *mapped = entry[0] == '/' ? rewrite_path_in_root(entry, buf, sizeof(buf)) : entry;
            size_t mlen = strlen(mapped);
            if (used + mlen + 2 >= cap) break;
            if (used) out[used++] = ':';
            memcpy(out + used, mapped, mlen);
            used += mlen;
            out[used] = '\0';
        }
        p += len;
        if (*p) p++;
    }
    return out;
}

static char **prepare_ddnatv_env(char *const envp[], const char *real_exe) {
    init_ddnatv_hook();
    int count = 0;
    int has_preload = 0;
    int has_ld_library_path = 0;
    int has_root = 0;
    int has_tunables = 0;
    int has_path = 0;
    int has_tmp = 0;
    int has_threads_max = 0;
    int has_xz_opt = 0;
    int has_xz_defaults = 0;
    int has_frontend = 0;
    int has_debconf_frontend = 0;
    int has_debconf_seen = 0;

    const char *chosen_real_exe = (real_exe && real_exe[0] != '\0') ? real_exe : (g_real_exe[0] != '\0' ? g_real_exe : NULL);

    // Every variable is looked at on every exec, so only the names that start with its first letter
    // are compared (this was 13 strncmp() calls for each of the 30 or so variables)
    int has_nt = 0;
    int has_binds = 0;
    int has_hostname = 0;
    int has_preload_list = 0;
    const char *preload_value = NULL;
    while (envp && envp[count]) {
        const char *e = envp[count];
        switch (e[0]) {
        case 'L':
            if (strncmp(e, "LD_PRELOAD=", 11) == 0) { has_preload = 1; preload_value = e + 11; }
            else if (strncmp(e, "LD_LIBRARY_PATH=", 16) == 0) has_ld_library_path = 1;
            break;
        case 'G':
            if (strncmp(e, "GLIBC_TUNABLES=", 15) == 0) has_tunables = 1;
            break;
        case 'P':
            if (strncmp(e, "PATH=", 5) == 0) has_path = 1;
            break;
        case 'T':
            if (strncmp(e, "TMPDIR=", 7) == 0) has_tmp = 1;
            break;
        case 'X':
            if (strncmp(e, "XZ_OPT=", 7) == 0) has_xz_opt = 1;
            else if (strncmp(e, "XZ_DEFAULTS=", 12) == 0) has_xz_defaults = 1;
            break;
        case 'D':
            if (strncmp(e, "DDNATV_ROOT=", 12) == 0) has_root = 1;
            else if (strncmp(e, "DDNATV_NT=", 10) == 0) has_nt = 1;
            else if (strncmp(e, "DDNATV_BINDS=", 13) == 0) has_binds = 1;
            else if (strncmp(e, "DDNATV_PRELOAD=", 15) == 0) has_preload_list = 1;
            else if (strncmp(e, "DDNATV_HOSTNAME=", 16) == 0) has_hostname = 1;
            else if (strncmp(e, "DPKG_DEB_THREADS_MAX=", 21) == 0) has_threads_max = 1;
            else if (strncmp(e, "DEBIAN_FRONTEND=", 16) == 0) has_frontend = 1;
            else if (strncmp(e, "DEBCONF_FRONTEND=", 17) == 0) has_debconf_frontend = 1;
            else if (strncmp(e, "DEBCONF_NONINTERACTIVE_SEEN=", 28) == 0) has_debconf_seen = 1;
            break;
        default:
            break;
        }
        count++;
    }

    char **new_env = calloc(count + 23, sizeof(char *));
    int dst = 0;
    for (int i = 0; i < count; i++) {
        if (has_preload && envp[i][0] == 'L' && strncmp(envp[i], "LD_PRELOAD=", 11) == 0) {
            // ld.so opens these itself: the guest's paths are mapped to the rootfs, and the libraries every
            // program loads (DDNATV_PRELOAD) come first
            char *mapped = map_preload_paths(preload_value);
            if (mapped) {
                size_t chain = g_preload_host ? strlen(g_preload_host) : 0;
                int has_chain = chain > 0 && strstr(mapped, g_preload_host) != NULL;
                size_t len = 11 + chain + 1 + strlen(mapped) + 1;
                char *str = malloc(len);
                if (str) {
                    if (chain > 0 && !has_chain) snprintf(str, len, "LD_PRELOAD=%s:%s", g_preload_host, mapped);
                    else snprintf(str, len, "LD_PRELOAD=%s", mapped);
                    new_env[dst++] = str;
                    free(mapped);
                    continue;
                }
                free(mapped);
            }
        }
        // If we have an explicit real executable, drop the inherited entry to replace with the fresh target
        if (chosen_real_exe && envp[i][0] == 'D' && strncmp(envp[i], "DDNATV_REAL_EXE=", 16) == 0) {
            continue;
        }
        new_env[dst++] = envp[i];
    }

    // Exact-size strings: this runs before every exec, often in a freshly forked child where each
    // newly touched page is a copy-on-write fault.
    if (chosen_real_exe) {
        size_t len = strlen(chosen_real_exe);
        char *str = malloc(16 + len + 1);
        if (str) {
            memcpy(str, "DDNATV_REAL_EXE=", 16);
            memcpy(str + 16, chosen_real_exe, len + 1);
            new_env[dst++] = str;
        }
    }

    char hook_buf[PATH_MAX];
    const char *hook = (!has_preload && g_ddnatv_root[0] != '\0') ? ddnatv_hook_library(hook_buf, sizeof(hook_buf)) : NULL;
    if (hook) {
        size_t hook_len = strlen(hook);
        size_t host_len = g_preload_host ? strlen(g_preload_host) + 1 : 0;
        size_t size = 11 + host_len + hook_len + 1;
        char *str = malloc(size);
        if (str) {
            size_t n = ddnatv_append(str, size, 0, "LD_PRELOAD=", 11);
            if (host_len) {
                n = ddnatv_append(str, size, n, g_preload_host, host_len - 1);
                n = ddnatv_append(str, size, n, ":", 1);
            }
            ddnatv_append(str, size, n, hook, hook_len);
            new_env[dst++] = str;
        }
    }
    if (!has_preload_list && g_preload_guest) {
        size_t len = strlen(g_preload_guest);
        char *str = malloc(15 + len + 1);
        if (str) {
            memcpy(str, "DDNATV_PRELOAD=", 15);
            memcpy(str + 15, g_preload_guest, len + 1);
            new_env[dst++] = str;
        }
    }
    if (!has_ld_library_path && g_ddnatv_root[0] != '\0') {
        size_t size = 16 + 1;
        for (size_t i = 0; i < sizeof(k_rootfs_lib_dirs) / sizeof(k_rootfs_lib_dirs[0]); i++) {
            size += 1 + g_ddnatv_root_len + strlen(k_rootfs_lib_dirs[i]);
        }
        char *str = malloc(size);
        if (str) {
            size_t n = ddnatv_append(str, size, 0, "LD_LIBRARY_PATH=", 16);
            append_rootfs_lib_dirs(str, size, n, 0);
            new_env[dst++] = str;
        }
    }
    if (!has_root && g_ddnatv_root[0] != '\0') {
        char *str = malloc(PATH_MAX + 16);
        if (str) {
            snprintf(str, PATH_MAX + 16, "DDNATV_ROOT=%s", g_ddnatv_root);
            new_env[dst++] = str;
        }
    }
    if (!has_hostname && g_hostname[0]) {
        char *str = malloc(16 + sizeof(g_hostname));
        if (str) {
            snprintf(str, 16 + sizeof(g_hostname), "DDNATV_HOSTNAME=%s", g_hostname);
            new_env[dst++] = str;
        }
    }
    if (!has_binds && g_binds_raw) {
        size_t len = strlen(g_binds_raw);
        char *str = malloc(13 + len + 1);
        if (str) {
            memcpy(str, "DDNATV_BINDS=", 13);
            memcpy(str + 13, g_binds_raw, len + 1);
            new_env[dst++] = str;
        }
    }
    // This process looked for the ARM translator's files and found none: the programs it starts
    // need not look again (it takes a look at /proc/self/fd in each of them, see privileged.c)
    if (!has_nt && g_no_translator) {
        new_env[dst++] = strdup("DDNATV_NT=0");
    }
    if (!has_tunables) {
        new_env[dst++] = strdup("GLIBC_TUNABLES=glibc.pthread.rseq=0");
    }
    if (!has_path) {
        new_env[dst++] = strdup("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
    }
    if (!has_tmp && g_ddnatv_root[0] != '\0') {
        char *str = malloc(PATH_MAX + 16);
        if (str) {
            snprintf(str, PATH_MAX + 16, "TMPDIR=%s/tmp", g_ddnatv_root);
            new_env[dst++] = str;
        }
    }
    if (!has_threads_max) {
        new_env[dst++] = strdup("DPKG_DEB_THREADS_MAX=1");
    }
    if (!has_xz_opt) {
        new_env[dst++] = strdup("XZ_OPT=-T1");
    }
    if (!has_xz_defaults) {
        new_env[dst++] = strdup("XZ_DEFAULTS=-T1");
    }
    if (!has_frontend) {
        new_env[dst++] = strdup("DEBIAN_FRONTEND=noninteractive");
    }
    if (!has_debconf_frontend) {
        new_env[dst++] = strdup("DEBCONF_FRONTEND=noninteractive");
    }
    if (!has_debconf_seen) {
        new_env[dst++] = strdup("DEBCONF_NONINTERACTIVE_SEEN=true");
    }
    new_env[dst] = NULL;
    return new_env;
}

static void free_modified_env(char **modified_env, char *const orig_env[]) {
    if (!modified_env) return;
    for (int i = 0; modified_env[i] != NULL; i++) {
        int is_orig = 0;
        if (orig_env) {
            for (int j = 0; orig_env[j] != NULL; j++) {
                if (modified_env[i] == orig_env[j]) {
                    is_orig = 1;
                    break;
                }
            }
        }
        if (!is_orig) {
            free(modified_env[i]);
        }
    }
    free(modified_env);
}

#endif /* !DDNATV_HOST_TEST */
