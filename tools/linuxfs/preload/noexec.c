#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/statvfs.h>
#include <unistd.h>

#if defined(__aarch64__) || defined(NOEXEC_TEST)

/* Processes whose x86 code FEX translates: FEX's own loaders, and Wine's ARM64EC processes, which
 * carry FEX inside them (libwow64fex.dll; Proton Experimental (ARM64), DroidDeck-Proton). Both read
 * the guest's code bytes and run their own JIT output, so a guest page mapped from a noexec mount
 * without PROT_EXEC still runs; what they check is the protection Wine believes it set. Without
 * the Wine names here, a DLL laid out page-aligned on the SD card (Ys Origin's ogg/vorbis libraries,
 * 2026-10-26) was left non-executable after "map_image_into_view failed to set 60000020
 * protection ... noexec filesystem?", and the ARM64EC FEX ended the game at its first call into it
 * ("NoExec instruction in entry block", EXCEPTION_ACCESS_VIOLATION info[0]=8). */
static int in_fex(void) {
  static int known = -1;
  if (known < 0) {
    char exe[512];
    ssize_t n = readlink("/proc/self/exe", exe, sizeof exe - 1);
    exe[n > 0 ? n : 0] = 0;
    const char *name = strrchr(exe, '/');
    name = name ? name + 1 : exe;
    known = !strcmp(name, "FEX") || !strcmp(name, "FEXInterpreter") || !strcmp(name, "FEXLoader") ||
            !strncmp(name, "wine", 4);
  }
  return known;
}

__attribute__((visibility("hidden"))) int bl_noexec_retry(int prot, int flags, int fd) {
  int error = errno;
  struct statvfs vfs;
  int retry = (error == EACCES || error == EPERM) && (prot & PROT_EXEC) && fd >= 0 && !(flags & MAP_ANONYMOUS) &&
              in_fex() && fstatvfs(fd, &vfs) == 0 && (vfs.f_flag & ST_NOEXEC);
  errno = error;
  return retry;
}

static int (*real_mprotect)(void *, size_t, int);

__attribute__((constructor)) static void noexec_init(void) {
  real_mprotect = (int (*)(void *, size_t, int))dlsym(RTLD_NEXT, "mprotect");
}

int mprotect(void *addr, size_t len, int prot) {
  if (!real_mprotect) noexec_init();
  int result = real_mprotect(addr, len, prot);
  if (result && errno == EACCES && (prot & PROT_EXEC) && in_fex()) {
    result = real_mprotect(addr, len, prot & ~PROT_EXEC);
    if (result) errno = EACCES;
  }
  return result;
}

#else

__attribute__((visibility("hidden"))) int bl_noexec_retry(int prot, int flags, int fd) {
  (void)prot, (void)flags, (void)fd;
  return 0;
}

#endif
