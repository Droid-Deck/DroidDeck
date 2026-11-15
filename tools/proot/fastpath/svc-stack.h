#include <sys/resource.h>
#include <string.h>

static __thread uintptr_t svc_stack_low __attribute__((tls_model("initial-exec")));
static __thread uintptr_t svc_stack_high __attribute__((tls_model("initial-exec")));
static __thread uintptr_t svc_stack_gap __attribute__((tls_model("initial-exec")));
static __thread int svc_stack_main __attribute__((tls_model("initial-exec")));

static int svc_hex(const char **text, uintptr_t *value) {
  const char *p = *text;
  uintptr_t n = 0;
  int digits = 0;
  for (;; p++) {
    unsigned c = (unsigned char)*p;
    unsigned d = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : 16;
    if (d == 16) break;
    if (++digits > (int)(2 * sizeof n)) return 0;
    n = (n << 4) | d;
  }
  *text = p;
  *value = n;
  return digits > 0;
}

static int svc_stack_line(const char *line, uintptr_t sp, uintptr_t *low, uintptr_t *high) {
  uintptr_t a, b;
  if (!svc_hex(&line, &a) || *line++ != '-' || !svc_hex(&line, &b) ||
      *line++ != ' ' || line[0] != 'r' || line[1] != 'w' || a >= b || sp < a || sp >= b) return 0;
  *low = a;
  *high = b;
  return 1;
}

static __attribute__((noinline)) int svc_stack_find(uintptr_t sp) {
  char chunk[512], line[96];
  size_t at = 0;
  long fd = sc(SYS_openat, AT_FDCWD, (long)"/proc/self/maps", O_RDONLY | O_CLOEXEC, 0, 0);
  if (fd < 0) return 0;
  long n;
  uintptr_t low = 0, high = 0, previous = 0;
  int found = 0;
  while (!found && (n = sc(SYS_read, fd, (long)chunk, sizeof chunk, 0, 0)) > 0) {
    for (long i = 0; i < n; i++) {
      if (chunk[i] == '\n') {
        line[at] = 0;
        found = svc_stack_line(line, sp, &low, &high);
        if (!found) {
          const char *p = line;
          uintptr_t a, b;
          if (svc_hex(&p, &a) && *p++ == '-' && svc_hex(&p, &b) && b <= sp) previous = b;
        }
        at = 0;
        if (found) break;
      } else if (at < sizeof line - 1) line[at++] = chunk[i];
    }
  }
  sc(SYS_close, fd, 0, 0, 0, 0);
  if (found) {
    svc_stack_low = 0;
    svc_stack_high = high;
    svc_stack_main = strstr(line, "[stack]") != NULL;
    svc_stack_gap = previous < low && low - previous > 16 * 1024 * 1024 ? previous + 16 * 1024 * 1024 : low;
    svc_stack_low = low;
  }
  return found;
}

static int svc_stack_room(uintptr_t sp) {
  stack_t alt;
  if (sc(SYS_sigaltstack, 0, (long)&alt, 0, 0, 0) < 0 || (alt.ss_flags & SS_ONSTACK)) return 0;
  if (!svc_stack_low || sp < svc_stack_low || sp >= svc_stack_high)
    if (!svc_stack_find(sp)) return 0;
  uintptr_t low = svc_stack_low;
  if (svc_stack_main) {
    struct rlimit limit;
    if (sc(SYS_prlimit64, 0, RLIMIT_STACK, 0, (long)&limit, 0) < 0) return 0;
    uintptr_t grow = limit.rlim_cur >= svc_stack_high ? 0 : svc_stack_high - limit.rlim_cur;
    if (grow < svc_stack_gap) grow = svc_stack_gap;
    if (grow < low) low = grow;
  }
  /* Path translation nests several PATH_MAX-sized frames. */
  return sp - low >= 96 * 1024;
}
