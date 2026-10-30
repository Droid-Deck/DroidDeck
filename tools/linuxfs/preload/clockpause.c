#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/futex.h>
#include <pthread.h>
#include <semaphore.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/timerfd.h>
#include <time.h>
#include <unistd.h>

#if defined(__aarch64__) || defined(BL_CLOCK_ANY_ARCH)

#ifndef CP_CTL
#define CP_CTL "/tmp/.bl-clock"
#endif
#ifndef FUTEX_LOCK_PI2
#define FUTEX_LOCK_PI2 13
#endif
#define CP_FDS 65536

static const int64_t *cp_shift;
static int cp_state;
static size_t cp_cond_byte;
static unsigned char cp_cond_mask;
static unsigned char cp_fds[CP_FDS / 8];

static int (*cp_gettime)(clockid_t, struct timespec *);
static int (*cp_nanosleep)(clockid_t, int, const struct timespec *, struct timespec *);
static int (*cp_cond_timedwait)(pthread_cond_t *, pthread_mutex_t *, const struct timespec *);
static int (*cp_cond_clockwait)(pthread_cond_t *, pthread_mutex_t *, clockid_t, const struct timespec *);
static int (*cp_sem_clockwait)(sem_t *, clockid_t, const struct timespec *);
static int (*cp_mutex_clocklock)(pthread_mutex_t *, clockid_t, const struct timespec *);
static int (*cp_rdlock)(pthread_rwlock_t *, clockid_t, const struct timespec *);
static int (*cp_wrlock)(pthread_rwlock_t *, clockid_t, const struct timespec *);
static int (*cp_join)(pthread_t, void **, clockid_t, const struct timespec *);
static int (*cp_tfd_create)(int, int);
static int (*cp_tfd_settime)(int, int, const struct itimerspec *, struct itimerspec *);

static void *cp_real(const char *name) {
  return dlsym(RTLD_NEXT, name);
}

#define CP_REAL(var, name) (var ? var : (var = cp_real(name)))

static int cp_find_cond_bit(void) {
  pthread_condattr_t attr;
  pthread_cond_t mono, real;
  const unsigned char *a = (const unsigned char *)&mono, *b = (const unsigned char *)&real;
  int found = 0;
  memset(&mono, 0, sizeof(mono));
  memset(&real, 0, sizeof(real));
  if (pthread_condattr_init(&attr) != 0) return 0;
  if (pthread_condattr_setclock(&attr, CLOCK_MONOTONIC) == 0 && pthread_cond_init(&mono, &attr) == 0) {
    if (pthread_cond_init(&real, NULL) == 0) {
      for (size_t i = 0; i < sizeof(pthread_cond_t); i++) {
        unsigned char diff = a[i] ^ b[i];
        if (!diff) continue;
        if (found || (diff & (diff - 1))) {
          found = -1;
          break;
        }
        cp_cond_byte = i;
        cp_cond_mask = diff;
        found = 1;
      }
      pthread_cond_destroy(&real);
    }
    pthread_cond_destroy(&mono);
  }
  pthread_condattr_destroy(&attr);
  return found == 1;
}

static void cp_init(void) {
  struct stat st;
  void *map;
  int fd;
  if (strcmp(program_invocation_short_name, "plasmashell") != 0) return;
  if (!cp_find_cond_bit()) return;
  fd = open(CP_CTL, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return;
  if (fstat(fd, &st) != 0 || st.st_size < (off_t)sizeof(int64_t)) {
    close(fd);
    return;
  }
  map = mmap(NULL, sizeof(int64_t), PROT_READ, MAP_SHARED, fd, 0);
  close(fd);
  if (map != MAP_FAILED) __atomic_store_n(&cp_shift, (const int64_t *)map, __ATOMIC_RELEASE);
}

static int64_t cp_offset(void) {
  const int64_t *shift;
  int state = __atomic_load_n(&cp_state, __ATOMIC_ACQUIRE);
  if (state == 0) {
    int expected = 0;
    if (__atomic_compare_exchange_n(&cp_state, &expected, 1, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
      cp_init();
      __atomic_store_n(&cp_state, 2, __ATOMIC_RELEASE);
    }
    return 0;
  }
  if (state != 2) return 0;
  shift = __atomic_load_n(&cp_shift, __ATOMIC_ACQUIRE);
  if (!shift) return 0;
  int64_t value = __atomic_load_n(shift, __ATOMIC_RELAXED);
  return value > 0 ? value : 0;
}

__attribute__((constructor)) static void cp_start(void) {
  cp_offset();
}

static int cp_mono(clockid_t clock) {
  return clock == CLOCK_MONOTONIC || clock == CLOCK_MONOTONIC_RAW || clock == CLOCK_MONOTONIC_COARSE;
}

static void cp_move(struct timespec *ts, int64_t ns) {
  int64_t total = (int64_t)ts->tv_nsec + ns % 1000000000LL;
  ts->tv_sec += (time_t)(ns / 1000000000LL);
  while (total < 0) {
    total += 1000000000LL;
    ts->tv_sec--;
  }
  while (total >= 1000000000LL) {
    total -= 1000000000LL;
    ts->tv_sec++;
  }
  ts->tv_nsec = (long)total;
}

static const struct timespec *cp_later(const struct timespec *ts, struct timespec *out, int64_t shift) {
  if (!ts || !shift) return ts;
  *out = *ts;
  cp_move(out, shift);
  return out;
}

static void cp_mark(int fd, int mono) {
  if (fd < 0 || fd >= CP_FDS) return;
  if (mono) __atomic_or_fetch(&cp_fds[fd / 8], (unsigned char)(1u << (fd % 8)), __ATOMIC_RELAXED);
  else __atomic_and_fetch(&cp_fds[fd / 8], (unsigned char)~(1u << (fd % 8)), __ATOMIC_RELAXED);
}

static int cp_marked(int fd) {
  return fd >= 0 && fd < CP_FDS && (__atomic_load_n(&cp_fds[fd / 8], __ATOMIC_RELAXED) & (1u << (fd % 8)));
}

static int cp_cond_mono(const pthread_cond_t *cond) {
  return (((const volatile unsigned char *)cond)[cp_cond_byte] & cp_cond_mask) != 0;
}

int clock_gettime(clockid_t clock, struct timespec *ts) {
  if (!CP_REAL(cp_gettime, "clock_gettime")) {
    errno = ENOSYS;
    return -1;
  }
  int rc = cp_gettime(clock, ts);
  if (rc == 0 && cp_mono(clock)) {
    int64_t shift = cp_offset();
    if (shift) cp_move(ts, -shift);
  }
  return rc;
}

int clock_nanosleep(clockid_t clock, int flags, const struct timespec *req, struct timespec *rem) {
  struct timespec moved;
  if (!CP_REAL(cp_nanosleep, "clock_nanosleep")) return ENOSYS;
  if ((flags & TIMER_ABSTIME) && cp_mono(clock)) req = cp_later(req, &moved, cp_offset());
  return cp_nanosleep(clock, flags, req, rem);
}

int pthread_cond_timedwait(pthread_cond_t *cond, pthread_mutex_t *mutex, const struct timespec *abstime) {
  struct timespec moved;
  if (!CP_REAL(cp_cond_timedwait, "pthread_cond_timedwait")) return ENOSYS;
  int64_t shift = cp_offset();
  if (shift && cp_cond_mono(cond)) abstime = cp_later(abstime, &moved, shift);
  return cp_cond_timedwait(cond, mutex, abstime);
}

int pthread_cond_clockwait(pthread_cond_t *cond, pthread_mutex_t *mutex, clockid_t clock, const struct timespec *abstime) {
  struct timespec moved;
  if (!CP_REAL(cp_cond_clockwait, "pthread_cond_clockwait")) return ENOSYS;
  if (cp_mono(clock)) abstime = cp_later(abstime, &moved, cp_offset());
  return cp_cond_clockwait(cond, mutex, clock, abstime);
}

int sem_clockwait(sem_t *sem, clockid_t clock, const struct timespec *abstime) {
  struct timespec moved;
  if (!CP_REAL(cp_sem_clockwait, "sem_clockwait")) {
    errno = ENOSYS;
    return -1;
  }
  if (cp_mono(clock)) abstime = cp_later(abstime, &moved, cp_offset());
  return cp_sem_clockwait(sem, clock, abstime);
}

int pthread_mutex_clocklock(pthread_mutex_t *mutex, clockid_t clock, const struct timespec *abstime) {
  struct timespec moved;
  if (!CP_REAL(cp_mutex_clocklock, "pthread_mutex_clocklock")) return ENOSYS;
  if (cp_mono(clock)) abstime = cp_later(abstime, &moved, cp_offset());
  return cp_mutex_clocklock(mutex, clock, abstime);
}

int pthread_rwlock_clockrdlock(pthread_rwlock_t *lock, clockid_t clock, const struct timespec *abstime) {
  struct timespec moved;
  if (!CP_REAL(cp_rdlock, "pthread_rwlock_clockrdlock")) return ENOSYS;
  if (cp_mono(clock)) abstime = cp_later(abstime, &moved, cp_offset());
  return cp_rdlock(lock, clock, abstime);
}

int pthread_rwlock_clockwrlock(pthread_rwlock_t *lock, clockid_t clock, const struct timespec *abstime) {
  struct timespec moved;
  if (!CP_REAL(cp_wrlock, "pthread_rwlock_clockwrlock")) return ENOSYS;
  if (cp_mono(clock)) abstime = cp_later(abstime, &moved, cp_offset());
  return cp_wrlock(lock, clock, abstime);
}

int pthread_clockjoin_np(pthread_t thread, void **result, clockid_t clock, const struct timespec *abstime) {
  struct timespec moved;
  if (!CP_REAL(cp_join, "pthread_clockjoin_np")) return ENOSYS;
  if (cp_mono(clock)) abstime = cp_later(abstime, &moved, cp_offset());
  return cp_join(thread, result, clock, abstime);
}

int timerfd_create(int clock, int flags) {
  if (!CP_REAL(cp_tfd_create, "timerfd_create")) {
    errno = ENOSYS;
    return -1;
  }
  int fd = cp_tfd_create(clock, flags);
  cp_mark(fd, cp_mono(clock));
  return fd;
}

static const struct itimerspec *cp_timer(int fd, int flags, const struct itimerspec *value, struct itimerspec *out) {
  int64_t shift;
  if (!value || !(flags & TFD_TIMER_ABSTIME) || !cp_marked(fd)) return value;
  if ((value->it_value.tv_sec == 0 && value->it_value.tv_nsec == 0) || !(shift = cp_offset())) return value;
  *out = *value;
  cp_move(&out->it_value, shift);
  return out;
}

int timerfd_settime(int fd, int flags, const struct itimerspec *value, struct itimerspec *old) {
  struct itimerspec moved;
  if (!CP_REAL(cp_tfd_settime, "timerfd_settime")) {
    errno = ENOSYS;
    return -1;
  }
  return cp_tfd_settime(fd, flags, cp_timer(fd, flags, value, &moved), old);
}

__attribute__((visibility("hidden"))) int bl_clock_syscall(long number, const long *args, long (*real)(long, ...), long *ret) {
  struct timespec moved;
  struct itimerspec timer;
  int64_t shift;
  if (number == SYS_clock_gettime) {
    *ret = real(number, args[0], args[1]);
    if (*ret == 0 && args[1] && cp_mono((clockid_t)args[0]) && (shift = cp_offset()))
      cp_move((struct timespec *)args[1], -shift);
    return 1;
  }
  if (number == SYS_timerfd_create) {
    *ret = real(number, args[0], args[1]);
    cp_mark((int)*ret, cp_mono((clockid_t)args[0]));
    return 1;
  }
  if (number == SYS_timerfd_settime) {
    const struct itimerspec *value = cp_timer((int)args[0], (int)args[1], (const struct itimerspec *)args[2], &timer);
    if (value == (const struct itimerspec *)args[2]) return 0;
    *ret = real(number, args[0], args[1], (long)value, args[3]);
    return 1;
  }
  if (number == SYS_clock_nanosleep) {
    if (!((int)args[1] & TIMER_ABSTIME) || !cp_mono((clockid_t)args[0]) || !args[2] || !(shift = cp_offset())) return 0;
    *ret = real(number, args[0], args[1], (long)cp_later((const struct timespec *)args[2], &moved, shift), args[3]);
    return 1;
  }
  if (number == SYS_futex) {
    int op = (int)args[1];
    int cmd = op & FUTEX_CMD_MASK;
    if (op & FUTEX_CLOCK_REALTIME) return 0;
    if (cmd != FUTEX_WAIT_BITSET && cmd != FUTEX_WAIT_REQUEUE_PI && cmd != FUTEX_LOCK_PI2) return 0;
    if (!args[3] || !(shift = cp_offset())) return 0;
    *ret = real(number, args[0], args[1], args[2], (long)cp_later((const struct timespec *)args[3], &moved, shift), args[4], args[5]);
    return 1;
  }
#ifdef SYS_futex_waitv
  if (number == SYS_futex_waitv) {
    if (!args[3] || (clockid_t)args[4] != CLOCK_MONOTONIC || !(shift = cp_offset())) return 0;
    *ret = real(number, args[0], args[1], args[2], (long)cp_later((const struct timespec *)args[3], &moved, shift), args[4]);
    return 1;
  }
#endif
  return 0;
}

#else

__attribute__((visibility("hidden"))) int bl_clock_syscall(long number, const long *args, long (*real)(long, ...), long *ret) {
  (void)number;
  (void)args;
  (void)real;
  (void)ret;
  return 0;
}

#endif
