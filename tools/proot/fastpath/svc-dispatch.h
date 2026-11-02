static int svc_android_api;

static int svc_host_android_api(void) {
  const char *value = getenv("PROOT_FP_ANDROID_API");
  if (!value || !*value) return 0;
  char *end;
  long api = strtol(value, &end, 10);
  return !*end && api > 0 && api < 1000 ? (int)api : 0;
}

static long svc_memory(void *local, long remote, size_t size, int write) {
  if (!size) return 0;
  struct iovec here = {local, size}, there = {(void *)remote, size};
  return sc(write ? SYS_process_vm_writev : SYS_process_vm_readv, getpid(),
            (long)&here, 1, (long)&there, 1);
}

static int svc_path(long pointer, char *path) {
  long n = svc_memory(path, pointer, PATH_MAX, 0);
  return n > 0 && memchr(path, 0, (size_t)n) != NULL;
}

static long svc_output(long result, void *value, long out, size_t size) {
  if (result < 0) return result;
  return svc_memory(value, out, size, 1) == (long)size ? result : FP_SLOW;
}

static int svc_supported(int nr) {
  switch (nr) {
  case SYS_openat: case SYS_newfstatat: case SYS_statx: case SYS_readlinkat:
  case SYS_faccessat: case SYS_mkdirat: case SYS_unlinkat: case SYS_renameat:
  case SYS_renameat2: case SYS_symlinkat: case SYS_fchmodat: case SYS_utimensat:
  case SYS_truncate: case SYS_statfs: case SYS_getxattr: case SYS_lgetxattr:
  case SYS_listxattr: case SYS_llistxattr: case SYS_inotify_add_watch: case SYS_getcwd:
    return 1;
  case SYS_faccessat2:
    return svc_android_api > 0;
  case SYS_clone3:
    return svc_android_api > 0 && svc_android_api < 35;
  default: return 0;
  }
}

static long svc_blocked(const long *r) {
  if (!svc_android_api) return FP_SLOW;
  if (r[8] == SYS_faccessat2 || (r[8] == SYS_clone3 && svc_android_api < 35)) return -ENOSYS;
  return FP_SLOW;
}

static long svc_path_call(const long *r) {
#ifdef FP_SVC_TEST_HOOK
  FP_SVC_TEST_HOOK(r);
#endif
  char path[PATH_MAX], other[PATH_MAX];
  struct timespec times[2];
  const struct timespec *when = (const struct timespec *)r[2];
  int first = 1, second = -1;
  switch (r[8]) {
  case SYS_getcwd: {
    if (own_cwd(path) == FP_SLOW) return FP_SLOW;
    size_t n = strlen(path) + 1;
    if ((unsigned long)r[1] < n) return -ERANGE;
    return svc_output(n, path, r[0], n);
  }
  case SYS_truncate: case SYS_statfs: case SYS_getxattr: case SYS_lgetxattr:
  case SYS_listxattr: case SYS_llistxattr: first = 0; break;
  case SYS_renameat: case SYS_renameat2: second = 3; break;
  case SYS_symlinkat: first = 0; second = 2; break;
  case SYS_utimensat:
    if (when) {
      if (svc_memory(times, r[2], sizeof times, 0) != sizeof times) return FP_SLOW;
      for (int i = 0; i < 2; i++)
        if (times[i].tv_nsec != UTIME_NOW && times[i].tv_nsec != UTIME_OMIT &&
            (times[i].tv_nsec < 0 || times[i].tv_nsec >= 1000000000L)) return FP_SLOW;
      when = times;
    }
    if (!r[1]) return fp_utimens((int)r[0], NULL, when, (int)r[3]);
    break;
  case SYS_inotify_add_watch: {
    char link[64], target[64];
    snprintf(link, sizeof link, "/proc/self/fd/%d", (int)r[0]);
    long n = sc(SYS_readlinkat, AT_FDCWD, (long)link, (long)target, sizeof target - 1, 0);
    if (n <= 0) return FP_SLOW;
    target[n] = 0;
    if (strcmp(target, "anon_inode:inotify")) return FP_SLOW;
    break;
  }
  default:
    if (!svc_supported((int)r[8])) return FP_SLOW;
  }
  if (!svc_path(r[first], path) || (second >= 0 && !svc_path(r[second], other))) return FP_SLOW;
  switch (r[8]) {
  case SYS_openat: return fp_open((int)r[0], path, (int)r[2], (mode_t)r[3]);
  case SYS_newfstatat: {
    struct stat st;
    return svc_output(fp_stat((int)r[0], path, (int)r[3], &st), &st, r[2], sizeof st);
  }
  case SYS_statx: {
    struct statx st;
    return svc_output(fp_statx((int)r[0], path, (int)r[2], (unsigned)r[3], &st), &st, r[4], sizeof st);
  }
  case SYS_readlinkat: {
    char text[PATH_MAX];
    size_t written = 0;
    long result = fp_readlink((int)r[0], path, text, (size_t)r[3], &written);
    return svc_output(result, text, r[2], written);
  }
  case SYS_faccessat: return fp_access((int)r[0], path, (int)r[2], 0);
  case SYS_mkdirat: return fp_mkdir((int)r[0], path, (mode_t)r[2]);
  case SYS_unlinkat: return fp_unlink((int)r[0], path, (int)r[2]);
  case SYS_renameat: case SYS_renameat2:
    return fp_rename((int)r[0], path, (int)r[2], other, r[8] == SYS_renameat2 ? (unsigned)r[4] : 0);
  case SYS_symlinkat: return fp_symlink(path, (int)r[1], other);
  case SYS_fchmodat: return fp_chmod((int)r[0], path, (mode_t)r[2], 0);
  case SYS_utimensat: return fp_utimens((int)r[0], path, when, (int)r[3]);
  case SYS_truncate: return fp_truncate(path, (off_t)r[1]);
  case SYS_statfs: {
    struct statfs st;
    return svc_output(fp_statfs(path, &st), &st, r[1], sizeof st);
  }
  case SYS_getxattr: case SYS_lgetxattr:
    return fp_getxattr(path, (const char *)r[1], (void *)r[2], (size_t)r[3], r[8] == SYS_getxattr);
  case SYS_listxattr: case SYS_llistxattr:
    return fp_listxattr(path, (char *)r[1], (size_t)r[2], r[8] == SYS_listxattr);
  case SYS_inotify_add_watch: return fp_inotify((int)r[0], path, (uint32_t)r[2]);
  default: return FP_SLOW;
  }
}
