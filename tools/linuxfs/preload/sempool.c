#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <unistd.h>

/* Some Unity games create and close an unused unnamed semaphore thousands of times a second, each
 * a wineserver round trip. A semaphore no request named between its create and its close stays
 * open and answers the next identical create. BL_SEMPOOL holds "<create_semaphore>,<close_handle>".
 *
 * A pooled handle is open on the server but closed for the program, so a request naming one may be
 * a use after close: the handle then waits in sp_zombie until a thread whose request and reply fds
 * are known closes it for real, before its own request reaches the server. Replies are only forged
 * on those fds, learnt from a request whose well-formed reply came back on another fd; both must be
 * pipes or sockets. A thread's first request goes out before its fds are known, so a use after close
 * in it still reaches the server with the handle open. */

int bl_from_ntdll(const void *caller) __attribute__((visibility("hidden")));
int bl_nl_write(int fd, const void *buf, size_t count, ssize_t *rc) __attribute__((visibility("hidden")));
int bl_nl_writev(int fd, const struct iovec *iov, int iovcnt, ssize_t *rc) __attribute__((visibility("hidden")));

#define SP_MSG 64
#define SP_HDR 12
#define SP_OBJATTR 16
#define SP_FREE 16
#define SP_LIVE 64
#define SP_ZOMBIE 64
#define SP_OBJ_INHERIT 0x2u
#define SP_INVALID_HANDLE 0xC0000008u

struct sp_key {
  uint32_t access, initial, max, attributes;
};

struct sp_entry {
  uint32_t handle;
  int dirty;
  struct sp_key key;
};

static int sp_state = -1;
static int sp_create_req, sp_close_req;
static pthread_mutex_t sp_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t sp_fork_once = PTHREAD_ONCE_INIT;
static struct sp_entry sp_live[SP_LIVE];
static int sp_nlive;
static struct sp_entry sp_free[SP_FREE];
static int sp_nfree;
static uint32_t sp_zombie[SP_ZOMBIE];
static int sp_nzombie;
static int sp_tracked;

enum { SP_IDLE, SP_CAPTURE, SP_FAKE };
static __thread int sp_pending;
static __thread unsigned char sp_reply[SP_MSG];
static __thread struct sp_key sp_capture;
static __thread int sp_capture_fd = -1;
static __thread int sp_wfd = -1;
static __thread int sp_rfd = -1;
static __thread int sp_learn_fd = -1;
static __thread uint32_t sp_learn_max;

static ssize_t (*sp_real_read)(int, void *, size_t);
static ssize_t (*sp_real_write)(int, const void *, size_t);
static ssize_t (*sp_real_writev)(int, const struct iovec *, int);

static void sp_after_fork(void) {
  pthread_mutex_init(&sp_lock, NULL);
  sp_nlive = sp_nfree = 0;
  __atomic_store_n(&sp_nzombie, 0, __ATOMIC_RELEASE);
  __atomic_store_n(&sp_tracked, 0, __ATOMIC_RELEASE);
  __atomic_store_n(&sp_state, 0, __ATOMIC_RELEASE);
  sp_pending = SP_IDLE;
}

static void sp_watch_fork(void) {
  pthread_atfork(NULL, NULL, sp_after_fork);
}

static int sp_on(void) {
  int s = __atomic_load_n(&sp_state, __ATOMIC_ACQUIRE);
  if (s >= 0) return s;
  const char *e = getenv("BL_SEMPOOL");
  char *end = NULL;
  long create = e ? strtol(e, &end, 10) : -1, close = -1;
  if (end == e) create = -1;
  if (create >= 0 && *end == ',') {
    const char *second = end + 1;
    close = strtol(second, &end, 10);
    if (end == second) close = -1;
  }
  s = create >= 0 && create < 4096 && close >= 0 && close < 4096 && create != close && *end == 0;
  if (s) {
    __atomic_store_n(&sp_create_req, (int)create, __ATOMIC_RELAXED);
    __atomic_store_n(&sp_close_req, (int)close, __ATOMIC_RELAXED);
    pthread_once(&sp_fork_once, sp_watch_fork);
  }
  __atomic_store_n(&sp_state, s, __ATOMIC_RELEASE);
  return s;
}

static uint32_t sp_u32(const void *p) {
  uint32_t v;
  memcpy(&v, p, sizeof v);
  return v;
}

static void sp_fake(uint32_t error, uint32_t handle) {
  memset(sp_reply, 0, sizeof sp_reply);
  memcpy(sp_reply, &error, sizeof error);
  memcpy(sp_reply + 8, &handle, sizeof handle);
  sp_pending = SP_FAKE;
}

static void sp_count(void) {
  __atomic_store_n(&sp_tracked, sp_nlive + sp_nfree + sp_nzombie, __ATOMIC_RELEASE);
}

static void sp_drop_free(int i) {
  sp_free[i] = sp_free[--sp_nfree];
}

static void sp_drop_live(int i) {
  sp_live[i] = sp_live[--sp_nlive];
}

static int sp_find_free(uint32_t handle) {
  for (int i = 0; i < sp_nfree; i++)
    if (sp_free[i].handle == handle) return i;
  return -1;
}

static int sp_find_zombie(uint32_t handle) {
  for (int i = 0; i < sp_nzombie; i++)
    if (sp_zombie[i] == handle) return i;
  return -1;
}

static void sp_mark(const unsigned char *p, size_t n, uint32_t except) {
  for (size_t off = 0; off + 4 <= n; off += 4) {
    uint32_t v = sp_u32(p + off);
    if (!v || v == except) continue;
    for (int i = 0; i < sp_nlive; i++)
      if (sp_live[i].handle == v) sp_live[i].dirty = 1;
    int i = sp_find_free(v);
    if (i >= 0) {
      sp_zombie[sp_nzombie] = v;
      __atomic_store_n(&sp_nzombie, sp_nzombie + 1, __ATOMIC_RELEASE);
      sp_drop_free(i);
    }
  }
}

static void sp_scan(const struct iovec *iov, int n, uint32_t except) {
  if (!__atomic_load_n(&sp_tracked, __ATOMIC_ACQUIRE)) return;
  pthread_mutex_lock(&sp_lock);
  sp_mark((const unsigned char *)iov[0].iov_base + SP_HDR, SP_MSG - SP_HDR, except);
  for (int i = 1; i < n; i++) sp_mark(iov[i].iov_base, iov[i].iov_len, except);
  sp_count();
  pthread_mutex_unlock(&sp_lock);
}

static int sp_io(ssize_t done) {
  if (done == SP_MSG) return 1;
  if (done >= 0) errno = EPIPE;
  return 0;
}

/* Runs inside ntdll's server call before this thread's request is written, so the extra round
 * trips cannot interleave with another request on these fds. The lock stays held until every
 * zombie is closed: a close or create racing on another thread cannot reuse a number first. */
static int sp_bury(int wfd, int rfd) {
  if (!__atomic_load_n(&sp_nzombie, __ATOMIC_ACQUIRE)) return 1;
  int ok = 1;
  pthread_mutex_lock(&sp_lock);
  while (ok && sp_nzombie) {
    uint32_t msg[SP_MSG / 4] = { (uint32_t)sp_close_req, 0, 0, sp_zombie[sp_nzombie - 1] };
    unsigned char reply[SP_MSG];
    ssize_t done;
    do done = sp_real_write(wfd, msg, sizeof msg); while (done < 0 && errno == EINTR);
    ok = sp_io(done);
    if (ok) {
      do done = sp_real_read(rfd, reply, sizeof reply); while (done < 0 && errno == EINTR);
      ok = sp_io(done);
    }
    if (ok) __atomic_store_n(&sp_nzombie, sp_nzombie - 1, __ATOMIC_RELEASE);
  }
  sp_count();
  pthread_mutex_unlock(&sp_lock);
  return ok;
}

static void sp_forget(uint32_t handle) {
  for (int i = sp_nlive - 1; i >= 0; i--)
    if (sp_live[i].handle == handle) sp_drop_live(i);
  int i = sp_find_free(handle);
  if (i >= 0) sp_drop_free(i);
  i = sp_find_zombie(handle);
  if (i >= 0) {
    sp_zombie[i] = sp_zombie[sp_nzombie - 1];
    __atomic_store_n(&sp_nzombie, sp_nzombie - 1, __ATOMIC_RELEASE);
  }
}

/* The server just handed out this number, so anything still recorded under it is already gone. */
static void sp_track(uint32_t handle, const struct sp_key *key) {
  pthread_mutex_lock(&sp_lock);
  sp_forget(handle);
  if (sp_nlive == SP_LIVE) {
    int victim = 0;
    for (int i = 0; i < sp_nlive; i++)
      if (sp_live[i].dirty) { victim = i; break; }
    sp_drop_live(victim);
  }
  sp_live[sp_nlive++] = (struct sp_entry){ handle, 0, *key };
  sp_count();
  pthread_mutex_unlock(&sp_lock);
}

static int sp_reuse(const struct sp_key *key, uint32_t *handle) {
  int found = 0;
  pthread_mutex_lock(&sp_lock);
  for (int i = 0; i < sp_nfree && sp_nlive < SP_LIVE; i++) {
    if (memcmp(&sp_free[i].key, key, sizeof *key)) continue;
    *handle = sp_free[i].handle;
    sp_live[sp_nlive++] = (struct sp_entry){ *handle, 0, *key };
    sp_drop_free(i);
    found = 1;
    break;
  }
  sp_count();
  pthread_mutex_unlock(&sp_lock);
  return found;
}

enum { SP_PASS, SP_KEPT, SP_GONE };

static int sp_close(uint32_t handle, int forge) {
  int result = SP_PASS;
  pthread_mutex_lock(&sp_lock);
  for (int i = 0; i < sp_nlive; i++) {
    if (sp_live[i].handle != handle) continue;
    if (forge && !sp_live[i].dirty && sp_nfree < SP_FREE && sp_nzombie + sp_nfree < SP_ZOMBIE) {
      sp_free[sp_nfree++] = sp_live[i];
      result = SP_KEPT;
    }
    sp_drop_live(i);
    break;
  }
  if (result == SP_PASS && (sp_find_free(handle) >= 0 || sp_find_zombie(handle) >= 0)) {
    if (forge) result = SP_GONE;
    else sp_forget(handle);
  }
  sp_count();
  pthread_mutex_unlock(&sp_lock);
  return result;
}

static int sp_create_key(const struct iovec *iov, int n, struct sp_key *key) {
  const unsigned char *req = iov[0].iov_base;
  if (n != 2 || iov[1].iov_len != SP_OBJATTR || sp_u32(req + 4) != SP_OBJATTR || sp_u32(req + 8)) return 0;
  const unsigned char *attr = iov[1].iov_base;
  if (sp_u32(attr) || sp_u32(attr + 8) || sp_u32(attr + 12) || (sp_u32(attr + 4) & SP_OBJ_INHERIT)) return 0;
  *key = (struct sp_key){ sp_u32(req + 12), sp_u32(req + 16), sp_u32(req + 20), sp_u32(attr + 4) };
  return 1;
}

static int sp_request_shape(const struct iovec *iov, int n) {
  const unsigned char *req = iov[0].iov_base;
  size_t data = 0;
  for (int i = 1; i < n; i++) data += iov[i].iov_len;
  return sp_u32(req) < 4096 && (sp_u32(req) || data) && sp_u32(req + 4) == data && sp_u32(req + 8) <= (1u << 26);
}

static int sp_channel(int fd) {
  struct stat st;
  return fstat(fd, &st) == 0 && (S_ISFIFO(st.st_mode) || S_ISSOCK(st.st_mode));
}

static ssize_t sp_send(int fd, const struct iovec *iov, int n) {
  const unsigned char *req = iov[0].iov_base;
  int code = (int)sp_u32(req);
  if (sp_rfd >= 0 ? fd != sp_wfd : !sp_channel(fd)) {
    sp_scan(iov, n, 0);
    return -2;
  }
  if (sp_rfd < 0 && sp_request_shape(iov, n)) {
    sp_learn_fd = fd;
    sp_learn_max = sp_u32(req + 8);
  }
  int known = fd == sp_wfd && sp_rfd >= 0;
  if (code == sp_close_req && n == 1 && !sp_u32(req + 4) && !sp_u32(req + 8)) {
    uint32_t handle = sp_u32(req + SP_HDR);
    int closed = sp_close(handle, known);
    if (closed == SP_KEPT || closed == SP_GONE) {
      sp_fake(closed == SP_KEPT ? 0 : SP_INVALID_HANDLE, 0);
      return SP_MSG;
    }
    sp_scan(iov, n, handle);
    if (known && !sp_bury(fd, sp_rfd)) return -1;
    return -2;
  }
  sp_scan(iov, n, 0);
  if (known && !sp_bury(fd, sp_rfd)) return -1;
  struct sp_key key;
  if (code == sp_create_req && sp_create_key(iov, n, &key)) {
    uint32_t handle;
    if (known && sp_reuse(&key, &handle)) {
      sp_fake(0, handle);
      return SP_MSG + SP_OBJATTR;
    }
    sp_capture = key;
    sp_capture_fd = fd;
    sp_pending = SP_CAPTURE;
  }
  return -2;
}

/* ntdll blocks signals around its server calls but not around other writes, and a handler's own
 * server call would wait on sp_lock held by the interrupted thread. */
static ssize_t sp_send_masked(int fd, const struct iovec *iov, int n) {
  sigset_t all, old;
  sigfillset(&all);
  pthread_sigmask(SIG_BLOCK, &all, &old);
  ssize_t r = sp_send(fd, iov, n);
  int saved = errno;
  pthread_sigmask(SIG_SETMASK, &old, NULL);
  errno = saved;
  return r;
}

static void sp_resolve(void) {
  if (!__atomic_load_n(&sp_real_read, __ATOMIC_ACQUIRE))
    __atomic_store_n(&sp_real_read, (ssize_t (*)(int, void *, size_t))dlsym(RTLD_NEXT, "read"), __ATOMIC_RELEASE);
  if (!__atomic_load_n(&sp_real_write, __ATOMIC_ACQUIRE))
    __atomic_store_n(&sp_real_write, (ssize_t (*)(int, const void *, size_t))dlsym(RTLD_NEXT, "write"), __ATOMIC_RELEASE);
  if (!__atomic_load_n(&sp_real_writev, __ATOMIC_ACQUIRE))
    __atomic_store_n(&sp_real_writev, (ssize_t (*)(int, const struct iovec *, int))dlsym(RTLD_NEXT, "writev"),
                     __ATOMIC_RELEASE);
}

ssize_t write(int fd, const void *buf, size_t count) {
  ssize_t nl;
  if (bl_nl_write(fd, buf, count, &nl)) return nl;
  if (__builtin_expect(!__atomic_load_n(&sp_real_write, __ATOMIC_ACQUIRE), 0)) sp_resolve();
  if (count == SP_MSG && sp_on() && bl_from_ntdll(__builtin_return_address(0))) {
    struct iovec one = { (void *)buf, count };
    ssize_t r = sp_send_masked(fd, &one, 1);
    if (r != -2) return r;
    r = sp_real_write(fd, buf, count);
    if (r != (ssize_t)count) {
      sp_pending = SP_IDLE;
      sp_learn_fd = -1;
    }
    return r;
  }
  return sp_real_write(fd, buf, count);
}

ssize_t writev(int fd, const struct iovec *iov, int iovcnt) {
  ssize_t nl;
  if (bl_nl_writev(fd, iov, iovcnt, &nl)) return nl;
  if (__builtin_expect(!__atomic_load_n(&sp_real_writev, __ATOMIC_ACQUIRE), 0)) sp_resolve();
  if (iovcnt > 0 && iov[0].iov_len == SP_MSG && sp_on() && bl_from_ntdll(__builtin_return_address(0))) {
    ssize_t r = sp_send_masked(fd, iov, iovcnt);
    if (r != -2) return r;
    size_t total = 0;
    for (int i = 0; i < iovcnt; i++) total += iov[i].iov_len;
    r = sp_real_writev(fd, iov, iovcnt);
    if (r != (ssize_t)total) {
      sp_pending = SP_IDLE;
      sp_learn_fd = -1;
    }
    return r;
  }
  return sp_real_writev(fd, iov, iovcnt);
}

ssize_t read(int fd, void *buf, size_t count) {
  if (__builtin_expect(!__atomic_load_n(&sp_real_read, __ATOMIC_ACQUIRE), 0)) sp_resolve();
  if (__builtin_expect(sp_pending != SP_IDLE, 0) && count == SP_MSG && bl_from_ntdll(__builtin_return_address(0))) {
    if (sp_pending == SP_FAKE) {
      if (fd != sp_rfd) return sp_real_read(fd, buf, count);
      sp_pending = SP_IDLE;
      memcpy(buf, sp_reply, SP_MSG);
      return SP_MSG;
    }
    if (fd == sp_capture_fd || (sp_rfd >= 0 && fd != sp_rfd)) return sp_real_read(fd, buf, count);
    sp_pending = SP_IDLE;
    sp_learn_fd = -1;
    struct sp_key key = sp_capture;
    ssize_t r = sp_real_read(fd, buf, count);
    if (r == SP_MSG && !sp_u32(buf) && !sp_u32((unsigned char *)buf + 4) && sp_u32((unsigned char *)buf + 8) &&
        sp_channel(fd)) {
      sp_wfd = sp_capture_fd;
      sp_rfd = fd;
      sigset_t all, old;
      sigfillset(&all);
      pthread_sigmask(SIG_BLOCK, &all, &old);
      sp_track(sp_u32((unsigned char *)buf + 8), &key);
      pthread_sigmask(SIG_SETMASK, &old, NULL);
    }
    return r;
  }
  if (__builtin_expect(sp_learn_fd >= 0, 0) && sp_rfd < 0 && count == SP_MSG && fd != sp_learn_fd &&
      bl_from_ntdll(__builtin_return_address(0))) {
    int wfd = sp_learn_fd;
    sp_learn_fd = -1;
    ssize_t r = sp_real_read(fd, buf, count);
    if (r == SP_MSG && sp_u32((unsigned char *)buf + 4) <= sp_learn_max && sp_channel(fd)) {
      sp_wfd = wfd;
      sp_rfd = fd;
    }
    return r;
  }
  return sp_real_read(fd, buf, count);
}
