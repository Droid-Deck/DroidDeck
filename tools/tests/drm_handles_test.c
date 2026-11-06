#define _GNU_SOURCE
#include <assert.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

/* Only the device/driver boundary is simulated. Descriptors and their lifetimes are real.
 * F_DUPFD_QUERY is modelled so this test also runs on older CI kernels; the device probe
 * separately exercises the actual query on Android. */
#define TEST_FD_LIMIT 65536
static _Atomic unsigned identities[TEST_FD_LIMIT];
static struct f_owner_ex owners[TEST_FD_LIMIT];
static unsigned identity_serial = 1;
static int identity_denied, owner_denied, owner_probe_delay, kgsl_present;
static int kernel_error = EINVAL;
static int native_imports, native_exports, native_closes;
static const char *driver_name = "pvr";
static const char *pvr_node = "/dev/dri/renderD129";
static int fixture_stat(const char *path, struct stat *st);
static long fixture_syscall(long number, ...);
static int fixture_fcntl(int fd, int op, ...);
static void *fixture_dlsym(void *object, const char *name);
static int fixture_close(int fd);

#define stat(path, st) fixture_stat(path, st)
#define syscall fixture_syscall
#define fcntl fixture_fcntl
#define dlsym fixture_dlsym
#define close fixture_close
#include "../linuxfs/preload/drm.c"
#undef stat
#undef syscall
#undef fcntl
#undef dlsym
#undef close

static int test_open(const char *path) {
  int fd = open(path, O_RDWR | O_CLOEXEC);
  assert(fd >= 0 && fd < TEST_FD_LIMIT);
  identities[fd] = identity_serial++;
  return fd;
}

static int fixture_close(int fd) {
  if (fd >= 0 && fd < TEST_FD_LIMIT) identities[fd] = 0;
  return close(fd);
}

static int fixture_stat(const char *path, struct stat *st) {
  if (!strcmp(path, "/dev/kgsl-3d0")) {
    if (kgsl_present) return stat("/dev/zero", st);
    errno = ENOENT;
    return -1;
  }
  if (!strcmp(path, pvr_node)) return stat("/dev/null", st);
  if (!strcmp(path, "/dev/dri/renderD128")) return stat("/dev/zero", st);
  if (!strncmp(path, "/dev/dri/", 9)) { errno = ENOENT; return -1; }
  return stat(path, st);
}

static int fixture_fcntl(int fd, int op, ...) {
  va_list ap;
  va_start(ap, op);
  if (op == F_GETOWN_EX || op == F_SETOWN_EX) {
    struct f_owner_ex *owner = va_arg(ap, struct f_owner_ex *);
    va_end(ap);
    if (owner_denied) { errno = EINVAL; return -1; }
    if (fd < 0 || fd >= TEST_FD_LIMIT || !identities[fd] || !owner) {
      errno = EBADF;
      return -1;
    }
    if (op == F_GETOWN_EX) {
      *owner = owners[identities[fd]];
    } else {
      owners[identities[fd]] = *owner;
      if (owner_probe_delay && owner->pid) usleep(1000);
    }
    return 0;
  }
  int arg = va_arg(ap, int);
  va_end(ap);
  if (op == F_DUPFD_QUERY) {
    if (identity_denied) { errno = EINVAL; return -1; }
    if (fd < 0 || arg < 0 || fd >= TEST_FD_LIMIT || arg >= TEST_FD_LIMIT ||
        !identities[fd] || !identities[arg]) { errno = EBADF; return -1; }
    return identities[fd] == identities[arg];
  }
  int ret = fcntl(fd, op, arg);
  if (ret >= 0 && (op == F_DUPFD || op == F_DUPFD_CLOEXEC)) {
    assert(ret < TEST_FD_LIMIT);
    identities[ret] = identities[fd];
  }
  return ret;
}

static long fixture_syscall(long number, ...) {
  va_list ap;
  va_start(ap, number);
  if (number == SYS_ioctl) {
    (void)va_arg(ap, int);
    unsigned long request = va_arg(ap, unsigned long);
    struct bl_drm_version *v = va_arg(ap, void *);
    assert(request == BL_DRM_VERSION);
    snprintf(v->name, v->name_len, "%s", driver_name);
    v->name_len = strlen(driver_name);
    va_end(ap);
    return 0;
  }
  va_end(ap);
  assert(number == SYS_kcmp);
  errno = EPERM;
  return -1;
}

static int native_import(int fd, int dma, uint32_t *handle) {
  (void)fd;
  native_imports++;
  if (fcntl(dma, F_GETFD) < 0) return -1;
  if (!handle) { errno = EFAULT; return -1; }
  if (kernel_error) { errno = kernel_error; return -1; }
  *handle = 42;
  return 0;
}
static int native_export(int fd, uint32_t handle, uint32_t flags, int *out) {
  (void)flags;
  native_exports++;
  if (handle != 42 || !out) { errno = EINVAL; return -1; }
  *out = fixture_fcntl(fd, F_DUPFD_CLOEXEC, 0);
  return *out < 0 ? -1 : 0;
}
static int native_close(int fd, uint32_t handle) {
  (void)fd;
  native_closes++;
  if (handle == 42) return 0;
  errno = EINVAL;
  return -1;
}
static void *fixture_dlsym(void *object, const char *name) {
  assert(object == RTLD_NEXT);
  if (!strcmp(name, "drmPrimeFDToHandle")) return native_import;
  if (!strcmp(name, "drmPrimeHandleToFD")) return native_export;
  if (!strcmp(name, "drmCloseBufferHandle")) return native_close;
  abort();
}

static int count_fds(void) {
  int count = 0;
  for (int fd = 0; fd < TEST_FD_LIMIT; ++fd) count += identities[fd] != 0;
  return count;
}
static void empty_table(void) {
  for (int i = 0; i < MAX_HANDLES; ++i) assert(!handle_entries[i].handle);
}

struct fallback_stress {
  int drm;
  int dma;
  int failures;
};

static void *stress_fallback_imports(void *opaque) {
  struct fallback_stress *stress = opaque;
  for (int i = 0; i < 20; i++) {
    uint32_t handle = 0;
    if (drmPrimeFDToHandle(stress->drm, stress->dma, &handle) != 0 ||
        drmCloseBufferHandle(stress->drm, handle) != 0) {
      stress->failures++;
    }
  }
  return NULL;
}

int main(void) {
  int drm = test_open("/dev/null"), dma = test_open("/dev/zero");
  uint32_t handle = 0;
  unsetenv("BL_POWERVR_DRM_HANDLES");
  setenv("BL_POWERVR_RENDER_NODE", pvr_node, 1);
  assert(drmPrimeFDToHandle(drm, dma, &handle) != 0);
  setenv("BL_POWERVR_DRM_HANDLES", "yes", 1);
  assert(drmPrimeFDToHandle(drm, dma, &handle) != 0);
  setenv("BL_POWERVR_DRM_HANDLES", "1", 1);
  unsetenv("BL_POWERVR_RENDER_NODE");
  assert(drmPrimeFDToHandle(drm, dma, &handle) != 0); /* no selected node */
  setenv("BL_POWERVR_RENDER_NODE", "", 1);
  assert(drmPrimeFDToHandle(drm, dma, &handle) != 0);
  setenv("BL_POWERVR_RENDER_NODE", "/dev/dri/renderD128", 1);
  assert(drmPrimeFDToHandle(drm, dma, &handle) != 0); /* another GPU owns 128 */
  setenv("BL_POWERVR_RENDER_NODE", "/dev/dri/renderD130", 1);
  assert(drmPrimeFDToHandle(drm, dma, &handle) != 0); /* selected node is absent */
  setenv("BL_POWERVR_RENDER_NODE", __FILE__, 1);
  assert(drmPrimeFDToHandle(drm, dma, &handle) != 0); /* regular files are not DRM nodes */
  setenv("BL_POWERVR_RENDER_NODE", pvr_node, 1);
  driver_name = "other";
  assert(drmPrimeFDToHandle(drm, dma, &handle) != 0);
  driver_name = "pvr";
  assert(drmPrimeFDToHandle(dma, dma, &handle) != 0); /* wrong character device */
  kernel_error = EACCES;
  assert(drmPrimeFDToHandle(drm, dma, &handle) != 0 && errno == EACCES);
  kernel_error = EINVAL;
  assert(drmPrimeFDToHandle(drm, -1, &handle) != 0 && errno == EBADF);
  empty_table();

  int baseline = count_fds();
  identity_denied = 1;
  assert(drmPrimeFDToHandle(drm, dma, &handle) == 0 && (handle & PVR_HANDLE_BIT));
  int denied_alias = fixture_fcntl(drm, F_DUPFD_CLOEXEC, 0), denied_out = -1;
  int denied_separate = test_open("/dev/null");
  assert(drmPrimeHandleToFD(denied_separate, handle, O_CLOEXEC, &denied_out) != 0);
  assert(drmPrimeHandleToFD(denied_alias, handle, O_CLOEXEC, &denied_out) == 0);
  fixture_close(denied_out);
  assert(drmCloseBufferHandle(denied_alias, handle) == 0);
  fixture_close(denied_alias);
  fixture_close(denied_separate);
  assert(count_fds() == baseline);
  owner_denied = 1;
  assert(drmPrimeFDToHandle(drm, dma, &handle) == -EOPNOTSUPP);
  assert(count_fds() == baseline);
  owner_denied = 0;

  /* Marker changes and restoration must be atomic across concurrent imports of one DRM OFD. */
  owner_probe_delay = 1;
  struct fallback_stress stress[8];
  pthread_t workers[8];
  for (int i = 0; i < 8; i++) {
    stress[i] = (struct fallback_stress){.drm = drm, .dma = dma};
    assert(pthread_create(&workers[i], NULL, stress_fallback_imports, &stress[i]) == 0);
  }
  for (int i = 0; i < 8; i++) {
    assert(pthread_join(workers[i], NULL) == 0);
    assert(stress[i].failures == 0);
  }
  owner_probe_delay = 0;
  assert(owners[identities[drm]].pid == 0);
  assert(count_fds() == baseline);
  empty_table();
  identity_denied = 0;

  assert(drmPrimeFDToHandle(drm, dma, &handle) == 0 && (handle & PVR_HANDLE_BIT));
  assert(count_fds() == baseline + 2);
  int alias = fixture_fcntl(drm, F_DUPFD_CLOEXEC, 0), out = -1;
  int separate = test_open("/dev/null");
  assert(drmPrimeHandleToFD(separate, handle, O_CLOEXEC | O_RDWR, &out) != 0);
  struct gem_close close_request = {.handle = handle};
  int rc = 123;
  assert(bl_drm_gem_close(separate, GEM_CLOSE, &close_request, &rc) == 0 && rc == 123);
  assert(drmPrimeHandleToFD(alias, handle, O_CLOEXEC | O_RDWR, &out) == 0);
  assert(fcntl(out, F_GETFD) & FD_CLOEXEC);
  struct stat src, dst;
  assert(fstat(dma, &src) == 0 && fstat(out, &dst) == 0);
  assert(src.st_dev == dst.st_dev && src.st_ino == dst.st_ino);
  fixture_close(out);
  assert(drmPrimeHandleToFD(alias, handle, 0, &out) == 0);
  assert(!(fcntl(out, F_GETFD) & FD_CLOEXEC));
  fixture_close(out);
  assert(drmPrimeHandleToFD(alias, handle, 0x40, &out) == -EINVAL);

  /* A closed-and-reused descriptor must not inherit the old open file's handles. */
  int old_number = drm;
  fixture_close(drm);
  drm = test_open("/dev/null");
  assert(drm == old_number);
  assert(drmPrimeHandleToFD(drm, handle, O_CLOEXEC, &out) != 0);
  assert(drmCloseBufferHandle(drm, handle) != 0);
  assert(drmPrimeHandleToFD(alias, handle, O_CLOEXEC, &out) == 0);
  fixture_close(out);
  assert(bl_drm_gem_close(alias, GEM_CLOSE, &close_request, &rc) == 1 && rc == 0);
  assert(drmPrimeHandleToFD(alias, handle, O_CLOEXEC, &out) != 0);
  fixture_close(alias);
  fixture_close(separate);
  assert(count_fds() == baseline);
  empty_table();

  /* Real kernel handles and their direct GEM_CLOSE ioctls are never swallowed. */
  kernel_error = 0;
  assert(drmPrimeFDToHandle(drm, dma, &handle) == 0 && handle == 42);
  empty_table();
  assert(drmPrimeHandleToFD(drm, handle, O_CLOEXEC, &out) == 0);
  fixture_close(out);
  close_request.handle = handle;
  assert(bl_drm_gem_close(drm, GEM_CLOSE, &close_request, &rc) == 0);
  assert(drmCloseBufferHandle(drm, handle) == 0);
  assert(native_imports && native_exports && native_closes);
  kernel_error = EINVAL;

  /* Slot reuse must not reuse public tokens or leak either owned descriptor. */
  uint32_t previous = 0;
  for (int n = 0; n < 1000; ++n) {
    assert(drmPrimeFDToHandle(drm, dma, &handle) == 0 && handle != previous);
    assert(drmCloseBufferHandle(drm, handle) == 0);
    if (previous) assert(drmPrimeHandleToFD(drm, previous, O_CLOEXEC, &out) != 0);
    previous = handle;
  }
  assert(count_fds() == baseline);
  empty_table();

  assert(drmPrimeFDToHandle(drm, dma, &handle) == 0);
  pid_t child = fork();
  assert(child >= 0);
  if (child == 0) {
    assert(drmPrimeHandleToFD(drm, handle, O_CLOEXEC, &out) == 0);
    fixture_close(out);
    assert(drmCloseBufferHandle(drm, handle) == 0);
    _exit(0);
  }
  int status;
  assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
  assert(drmPrimeHandleToFD(drm, handle, O_CLOEXEC, &out) == 0);
  fixture_close(out);
  assert(drmCloseBufferHandle(drm, handle) == 0);

  /* Preserve the original device layout when PowerVR owns renderD128. */
  pvr_node = "/dev/dri/renderD128";
  setenv("BL_POWERVR_RENDER_NODE", pvr_node, 1);
  assert(drmPrimeFDToHandle(drm, dma, &handle) == 0 && (handle & PVR_HANDLE_BIT));
  assert(drmCloseBufferHandle(drm, handle) == 0);

  /* Retain the old KGSL path with the PowerVR opt-in unset. */
  unsetenv("BL_POWERVR_DRM_HANDLES");
  kgsl_present = 1;
  kgsl_state = 0;
  assert(drmPrimeFDToHandle(dma, drm, &handle) == 0 && !(handle & PVR_HANDLE_BIT));
  assert(drmPrimeHandleToFD(dma, handle, O_CLOEXEC | O_RDWR, &out) == 0);
  fixture_close(out);
  close_request.handle = handle;
  assert(bl_drm_gem_close(dma, GEM_CLOSE, &close_request, &rc) == 1 && rc == 0);
  assert(drmCloseBufferHandle(dma, handle) == -EINVAL);
  empty_table();
  assert(count_fds() == baseline);
  fixture_close(drm);
  fixture_close(dma);
  puts("DRM adapter scoping, ownership, reuse, fork and kernel-passthrough tests passed");
}
