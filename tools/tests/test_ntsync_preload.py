import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

NTDLL = r"""
#define _GNU_SOURCE
#include <signal.h>
#include <string.h>

int install_handler(int sig, void (*fn)(int, siginfo_t *, void *), void **seen) {
  struct sigaction sa, old;
  memset(&sa, 0, sizeof(sa));
  sa.sa_sigaction = fn;
  sa.sa_flags = SA_SIGINFO | SA_RESTART;
  sigemptyset(&sa.sa_mask);
  if (sigaction(sig, &sa, NULL) != 0) return -1;
  if (sigaction(sig, NULL, &old) != 0) return -1;
  *seen = (void *)old.sa_sigaction;
  return 0;
}

int install_oneshot(int sig, void (*fn)(int, siginfo_t *, void *)) {
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_sigaction = fn;
  sa.sa_flags = SA_SIGINFO | SA_RESTART | SA_RESETHAND;
  sigemptyset(&sa.sa_mask);
  return sigaction(sig, &sa, NULL);
}
"""

PROGRAM = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/ioctl.h>
#include <pthread.h>
#include <semaphore.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

struct sem_args { uint32_t count, max; };
struct mutex_args { uint32_t owner, count; };
struct event_args { uint32_t manual, signaled; };
struct wait_args { uint64_t timeout, objs; uint32_t count, index, flags, owner, alert, pad; };
#define NT_CREATE_SEM _IOW('N', 0x80, struct sem_args)
#define NT_SEM_RELEASE _IOWR('N', 0x81, uint32_t)
#define NT_WAIT_ANY _IOWR('N', 0x82, struct wait_args)
#define NT_WAIT_ALL _IOWR('N', 0x83, struct wait_args)
#define NT_CREATE_MUTEX _IOW('N', 0x84, struct mutex_args)
#define NT_MUTEX_UNLOCK _IOWR('N', 0x85, struct mutex_args)
#define NT_MUTEX_KILL _IOW('N', 0x86, uint32_t)
#define NT_CREATE_EVENT _IOW('N', 0x87, struct event_args)
#define NT_EVENT_SET _IOR('N', 0x88, uint32_t)
#define NT_EVENT_RESET _IOR('N', 0x89, uint32_t)
#define NT_EVENT_PULSE _IOR('N', 0x8a, uint32_t)
#define NT_SEM_READ _IOR('N', 0x8b, struct sem_args)
#define NT_MUTEX_READ _IOR('N', 0x8c, struct mutex_args)
#define NT_EVENT_READ _IOR('N', 0x8d, struct event_args)

int install_handler(int sig, void (*fn)(int, siginfo_t *, void *), void **seen);
int install_oneshot(int sig, void (*fn)(int, siginfo_t *, void *));

static int dev, fails;
#define CHECK(name, cond) do { if (!(cond)) { printf("FAIL %s line %d errno %d\n", name, __LINE__, errno); fails++; } } while (0)

static uint64_t mono_ns(int64_t delta_ms) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec + (uint64_t)(delta_ms * 1000000);
}

static int wait_on(unsigned long req, const int *objs, uint32_t count, uint32_t owner, int alert, uint64_t timeout, uint32_t *index) {
  struct wait_args w = {timeout, (uintptr_t)objs, count, 0xdead, 0, owner, (uint32_t)alert, 0};
  int r = ioctl(dev, req, &w);
  if (index) *index = w.index;
  return r < 0 ? errno : 0;
}

static int event(int manual, int signaled) {
  struct event_args a = {(uint32_t)manual, (uint32_t)signaled};
  return ioctl(dev, NT_CREATE_EVENT, &a);
}

static int sem(uint32_t count, uint32_t max) {
  struct sem_args a = {count, max};
  return ioctl(dev, NT_CREATE_SEM, &a);
}

static int mutex(uint32_t owner, uint32_t count) {
  struct mutex_args a = {owner, count};
  return ioctl(dev, NT_CREATE_MUTEX, &a);
}

static uint32_t prev_of(int fd, unsigned long req, uint32_t in) {
  uint32_t v = in;
  if (ioctl(fd, req, &v) < 0) return 0xffffffff;
  return v;
}

static void semaphores(void) {
  struct sem_args bad = {3, 2}, r;
  CHECK("sem_create_invalid", ioctl(dev, NT_CREATE_SEM, &bad) < 0 && errno == EINVAL);
  int s = sem(2, 2);
  CHECK("sem_create", s >= 0);
  uint32_t idx;
  CHECK("sem_wait1", wait_on(NT_WAIT_ANY, &s, 1, 1, 0, 0, &idx) == 0 && idx == 0);
  CHECK("sem_wait2", wait_on(NT_WAIT_ANY, &s, 1, 1, 0, 0, &idx) == 0);
  CHECK("sem_empty", wait_on(NT_WAIT_ANY, &s, 1, 1, 0, 0, NULL) == ETIMEDOUT);
  CHECK("sem_release_prev", prev_of(s, NT_SEM_RELEASE, 1) == 0);
  CHECK("sem_overflow", prev_of(s, NT_SEM_RELEASE, 2) == 0xffffffff && errno == EOVERFLOW);
  CHECK("sem_read", ioctl(s, NT_SEM_READ, &r) == 0 && r.count == 1 && r.max == 2);
  close(s);
}

static void mutexes(void) {
  struct mutex_args bad = {0, 1}, r, u;
  CHECK("mutex_create_invalid", ioctl(dev, NT_CREATE_MUTEX, &bad) < 0 && errno == EINVAL);
  int m = mutex(0, 0);
  uint32_t idx;
  CHECK("mutex_owner_zero", wait_on(NT_WAIT_ANY, &m, 1, 0, 0, 0, NULL) == EINVAL);
  CHECK("mutex_take", wait_on(NT_WAIT_ANY, &m, 1, 7, 0, 0, &idx) == 0 && idx == 0);
  CHECK("mutex_recurse", wait_on(NT_WAIT_ANY, &m, 1, 7, 0, 0, NULL) == 0);
  CHECK("mutex_other", wait_on(NT_WAIT_ANY, &m, 1, 8, 0, 0, NULL) == ETIMEDOUT);
  CHECK("mutex_read", ioctl(m, NT_MUTEX_READ, &r) == 0 && r.owner == 7 && r.count == 2);
  u = (struct mutex_args){8, 0};
  CHECK("mutex_unlock_eperm", ioctl(m, NT_MUTEX_UNLOCK, &u) < 0 && errno == EPERM);
  u = (struct mutex_args){0, 0};
  CHECK("mutex_unlock_einval", ioctl(m, NT_MUTEX_UNLOCK, &u) < 0 && errno == EINVAL);
  u = (struct mutex_args){7, 0};
  CHECK("mutex_unlock_prev", ioctl(m, NT_MUTEX_UNLOCK, &u) == 0 && u.count == 2);
  uint32_t who = 8;
  CHECK("mutex_kill_eperm", ioctl(m, NT_MUTEX_KILL, &who) < 0 && errno == EPERM);
  who = 7;
  CHECK("mutex_kill", ioctl(m, NT_MUTEX_KILL, &who) == 0);
  CHECK("mutex_read_dead", ioctl(m, NT_MUTEX_READ, &r) < 0 && errno == EOWNERDEAD && r.owner == 0 && r.count == 0);
  CHECK("mutex_abandoned", wait_on(NT_WAIT_ANY, &m, 1, 9, 0, 0, &idx) == EOWNERDEAD && idx == 0);
  CHECK("mutex_after_abandon", ioctl(m, NT_MUTEX_READ, &r) == 0 && r.owner == 9 && r.count == 1);
  close(m);
}

static void events(void) {
  int a = event(0, 1), m = event(1, 0);
  struct event_args r;
  CHECK("auto_take", wait_on(NT_WAIT_ANY, &a, 1, 1, 0, 0, NULL) == 0);
  CHECK("auto_consumed", wait_on(NT_WAIT_ANY, &a, 1, 1, 0, 0, NULL) == ETIMEDOUT);
  CHECK("set_prev0", prev_of(m, NT_EVENT_SET, 0) == 0);
  CHECK("set_prev1", prev_of(m, NT_EVENT_SET, 0) == 1);
  CHECK("manual_kept", wait_on(NT_WAIT_ANY, &m, 1, 1, 0, 0, NULL) == 0 && wait_on(NT_WAIT_ANY, &m, 1, 1, 0, 0, NULL) == 0);
  CHECK("reset_prev", prev_of(m, NT_EVENT_RESET, 0) == 1);
  CHECK("pulse_prev", prev_of(m, NT_EVENT_PULSE, 0) == 0);
  CHECK("pulse_leaves_unsignaled", ioctl(m, NT_EVENT_READ, &r) == 0 && r.manual == 1 && r.signaled == 0);
  close(a);
  close(m);
}

static void any_order(void) {
  int o[4] = {event(1, 0), sem(1, 5), event(1, 1), sem(0, 5)};
  int alert = event(0, 1);
  uint32_t idx;
  CHECK("any_lowest", wait_on(NT_WAIT_ANY, o, 4, 1, alert, 0, &idx) == 0 && idx == 1);
  CHECK("any_next", wait_on(NT_WAIT_ANY, o, 4, 1, alert, 0, &idx) == 0 && idx == 2);
  prev_of(o[2], NT_EVENT_RESET, 0);
  CHECK("any_alert", wait_on(NT_WAIT_ANY, o, 4, 1, alert, 0, &idx) == 0 && idx == 4);
  CHECK("any_alert_consumed", wait_on(NT_WAIT_ANY, o, 4, 1, alert, 0, NULL) == ETIMEDOUT);
  int dup[3] = {o[3], o[0], o[3]};
  prev_of(o[3], NT_SEM_RELEASE, 1);
  CHECK("any_duplicate_lowest", wait_on(NT_WAIT_ANY, dup, 3, 1, 0, 0, &idx) == 0 && idx == 0);
  int many[65];
  for (int i = 0; i < 65; i++) many[i] = o[0];
  CHECK("any_too_many", wait_on(NT_WAIT_ANY, many, 65, 1, 0, 0, NULL) == EINVAL);
  int badfd = 0;
  CHECK("any_invalid_fd", wait_on(NT_WAIT_ANY, &badfd, 1, 1, 0, 0, NULL) == EINVAL);
  CHECK("any_timeout", wait_on(NT_WAIT_ANY, o, 1, 1, 0, mono_ns(30), NULL) == ETIMEDOUT);
  for (int i = 0; i < 4; i++) close(o[i]);
  close(alert);
}

static void all_semantics(void) {
  int s = sem(1, 5), e = event(0, 0), m = mutex(0, 0);
  int o[3] = {s, e, m};
  struct sem_args sr;
  uint32_t idx;
  CHECK("all_not_ready", wait_on(NT_WAIT_ALL, o, 3, 4, 0, 0, NULL) == ETIMEDOUT);
  CHECK("all_untouched", ioctl(s, NT_SEM_READ, &sr) == 0 && sr.count == 1);
  prev_of(e, NT_EVENT_SET, 0);
  CHECK("all_ready", wait_on(NT_WAIT_ALL, o, 3, 4, 0, 0, &idx) == 0 && idx == 0);
  CHECK("all_took_sem", ioctl(s, NT_SEM_READ, &sr) == 0 && sr.count == 0);
  int dup[2] = {s, s};
  CHECK("all_duplicate", wait_on(NT_WAIT_ALL, dup, 2, 4, 0, 0, NULL) == EINVAL);
  int alert = event(1, 1);
  CHECK("all_alert", wait_on(NT_WAIT_ALL, o, 3, 4, alert, 0, &idx) == 0 && idx == 3);
  close(s);
  close(e);
  close(m);
  close(alert);
}

static int wake_obj;
static uint32_t wake_index;
static int wake_ret;
static void *waiter(void *p) {
  int all = (int)(intptr_t)p;
  int objs[2] = {wake_obj, wake_obj + 0};
  wake_ret = wait_on(all ? NT_WAIT_ALL : NT_WAIT_ANY, objs, 1, 3, 0, UINT64_MAX, &wake_index);
  return NULL;
}

static void cross_thread(void) {
  wake_obj = event(0, 0);
  pthread_t t;
  pthread_create(&t, NULL, waiter, NULL);
  usleep(30000);
  prev_of(wake_obj, NT_EVENT_SET, 0);
  pthread_join(t, NULL);
  CHECK("thread_wake", wake_ret == 0 && wake_index == 0);
  struct event_args r;
  CHECK("thread_auto_reset", ioctl(wake_obj, NT_EVENT_READ, &r) == 0 && r.signaled == 0);
  close(wake_obj);
  int s = sem(0, 10);
  wake_obj = s;
  pthread_t ts[3];
  for (int i = 0; i < 3; i++) pthread_create(&ts[i], NULL, waiter, NULL);
  usleep(30000);
  CHECK("sem_release_three", prev_of(s, NT_SEM_RELEASE, 3) == 0);
  for (int i = 0; i < 3; i++) pthread_join(ts[i], NULL);
  struct sem_args sr;
  CHECK("sem_all_taken", ioctl(s, NT_SEM_READ, &sr) == 0 && sr.count == 0);
  close(s);
}

static void cross_process(void) {
  int sv[2];
  CHECK("socketpair", socketpair(AF_UNIX, SOCK_STREAM, 0, sv) == 0);
  int inherited = event(0, 0);
  pid_t pid = fork();
  if (pid == 0) {
    uint32_t idx;
    int r1 = wait_on(NT_WAIT_ANY, &inherited, 1, 5, 0, mono_ns(5000), &idx);
    char c;
    struct iovec io = {&c, 1};
    char cbuf[CMSG_SPACE(sizeof(int))];
    struct msghdr msg = {0};
    msg.msg_iov = &io;
    msg.msg_iovlen = 1;
    msg.msg_control = cbuf;
    msg.msg_controllen = sizeof(cbuf);
    int got = -1;
    if (recvmsg(sv[1], &msg, 0) == 1) memcpy(&got, CMSG_DATA(CMSG_FIRSTHDR(&msg)), sizeof(int));
    int r2 = wait_on(NT_WAIT_ANY, &got, 1, 5, 0, mono_ns(5000), NULL);
    _exit(r1 == 0 && r2 == 0 ? 0 : 1);
  }
  usleep(30000);
  prev_of(inherited, NT_EVENT_SET, 0);
  int sent = sem(0, 1);
  char c = 'x';
  struct iovec io = {&c, 1};
  char cbuf[CMSG_SPACE(sizeof(int))];
  struct msghdr msg = {0};
  msg.msg_iov = &io;
  msg.msg_iovlen = 1;
  msg.msg_control = cbuf;
  msg.msg_controllen = sizeof(cbuf);
  struct cmsghdr *cm = CMSG_FIRSTHDR(&msg);
  cm->cmsg_level = SOL_SOCKET;
  cm->cmsg_type = SCM_RIGHTS;
  cm->cmsg_len = CMSG_LEN(sizeof(int));
  memcpy(CMSG_DATA(cm), &sent, sizeof(int));
  sendmsg(sv[0], &msg, 0);
  usleep(30000);
  prev_of(sent, NT_SEM_RELEASE, 1);
  int status = 0;
  waitpid(pid, &status, 0);
  CHECK("process_wake", WIFEXITED(status) && WEXITSTATUS(status) == 0);
  close(inherited);
  close(sent);
  close(sv[0]);
  close(sv[1]);
}

static int ping_obj, pong_obj, stress_sem;
static void *stress_ponger(void *p) {
  long n = (long)p;
  uint32_t v;
  for (long i = 0; i < n; i++) {
    if (wait_on(NT_WAIT_ANY, &ping_obj, 1, 21, 0, UINT64_MAX, NULL) != 0) return (void *)1;
    ioctl(pong_obj, NT_EVENT_SET, &v);
  }
  return NULL;
}
static void *stress_hammer(void *p) {
  long n = (long)p;
  for (long i = 0; i < n; i++) {
    if (wait_on(NT_WAIT_ANY, &stress_sem, 1, 22, 0, UINT64_MAX, NULL) != 0) return (void *)1;
    uint32_t c = 1;
    ioctl(stress_sem, NT_SEM_RELEASE, &c);
  }
  return NULL;
}

static void stress(void) {
  long rounds = 20000;
  ping_obj = event(0, 0);
  pong_obj = event(0, 0);
  pthread_t t;
  void *bad = NULL;
  pthread_create(&t, NULL, stress_ponger, (void *)rounds);
  uint32_t v;
  int ok = 1;
  for (long i = 0; i < rounds && ok; i++) {
    ioctl(ping_obj, NT_EVENT_SET, &v);
    ok = wait_on(NT_WAIT_ANY, &pong_obj, 1, 20, 0, UINT64_MAX, NULL) == 0;
  }
  pthread_join(t, &bad);
  CHECK("stress_pingpong", ok && bad == NULL);
  stress_sem = sem(1, 1);
  pthread_t th[4];
  for (int i = 0; i < 4; i++) pthread_create(&th[i], NULL, stress_hammer, (void *)5000L);
  int hammer_ok = 1;
  for (int i = 0; i < 4; i++) {
    pthread_join(th[i], &bad);
    hammer_ok &= bad == NULL;
  }
  struct sem_args sr;
  CHECK("stress_contended", hammer_ok && ioctl(stress_sem, NT_SEM_READ, &sr) == 0 && sr.count == 1);
  close(ping_obj);
  close(pong_obj);
  close(stress_sem);
}

static int busy_obj;
static volatile int handled, stop_worker;
static sem_t parked, unpark;
static void reentrant(int sig, siginfo_t *si, void *uc) {
  (void)sig; (void)si; (void)uc;
  uint32_t v = 0;
  ioctl(busy_obj, NT_EVENT_SET, &v);
  handled++;
}
static void suspending(int sig, siginfo_t *si, void *uc) {
  (void)sig; (void)si; (void)uc;
  sem_post(&parked);
  while (sem_wait(&unpark) != 0) {}
  handled++;
}
static void *worker(void *p) {
  (void)p;
  uint32_t v;
  while (!stop_worker) {
    ioctl(busy_obj, NT_EVENT_SET, &v);
    wait_on(NT_WAIT_ANY, &busy_obj, 1, 11, 0, 0, NULL);
  }
  return NULL;
}

static void signals(void) {
  void *seen = NULL;
  busy_obj = event(0, 0);
  CHECK("install_reentrant", install_handler(SIGUSR1, reentrant, &seen) == 0);
  CHECK("query_original", seen == (void *)reentrant);
  pthread_t w;
  stop_worker = 0;
  handled = 0;
  pthread_create(&w, NULL, worker, NULL);
  for (int i = 0; i < 2000; i++) {
    pthread_kill(w, SIGUSR1);
    if (i % 64 == 0) usleep(100);
  }
  usleep(20000);
  stop_worker = 1;
  pthread_join(w, NULL);
  CHECK("reentrant_ran", handled > 0);
  sem_init(&parked, 0, 0);
  sem_init(&unpark, 0, 0);
  CHECK("install_suspending", install_handler(SIGUSR1, suspending, &seen) == 0);
  stop_worker = 0;
  handled = 0;
  pthread_create(&w, NULL, worker, NULL);
  uint32_t v;
  for (int i = 0; i < 300; i++) {
    pthread_kill(w, SIGUSR1);
    while (sem_wait(&parked) != 0) {}
    ioctl(busy_obj, NT_EVENT_SET, &v);
    wait_on(NT_WAIT_ANY, &busy_obj, 1, 12, 0, 0, NULL);
    sem_post(&unpark);
  }
  stop_worker = 1;
  pthread_join(w, NULL);
  CHECK("suspend_ran", handled == 300);
  close(busy_obj);
}

static volatile int burst_count;
static void bursting(int sig, siginfo_t *si, void *uc) {
  (void)sig; (void)si; (void)uc;
  uint32_t v = 0;
  ioctl(busy_obj, NT_EVENT_SET, &v);
  __atomic_add_fetch(&burst_count, 1, __ATOMIC_RELAXED);
}

static void bursts(void) {
  void *seen = NULL;
  busy_obj = event(0, 0);
  for (int s = 0; s < 8; s++) CHECK("install_burst", install_handler(SIGRTMIN + s, bursting, &seen) == 0);
  pthread_t w;
  stop_worker = 0;
  burst_count = 0;
  pthread_create(&w, NULL, worker, NULL);
  union sigval val = {0};
  int sent = 0;
  for (int i = 0; i < 400; i++) {
    for (int s = 0; s < 8; s++)
      if (pthread_sigqueue(w, SIGRTMIN + s, val) == 0) sent++;
    usleep(200);
  }
  for (int i = 0; i < 2000 && __atomic_load_n(&burst_count, __ATOMIC_RELAXED) < sent; i++) usleep(1000);
  stop_worker = 1;
  pthread_join(w, NULL);
  CHECK("burst_all_handled", burst_count == sent && sent > 0);
  close(busy_obj);
}

static volatile int oneshot_count;
static void oneshot(int sig, siginfo_t *si, void *uc) {
  (void)sig; (void)si; (void)uc;
  __atomic_add_fetch(&oneshot_count, 1, __ATOMIC_RELAXED);
}

static void oneshots(void) {
  busy_obj = event(0, 0);
  pthread_t w;
  stop_worker = 0;
  oneshot_count = 0;
  pthread_create(&w, NULL, worker, NULL);
  int fired = 0;
  for (int i = 0; i < 300; i++) {
    if (install_oneshot(SIGWINCH, oneshot) != 0) break;
    int before = __atomic_load_n(&oneshot_count, __ATOMIC_RELAXED);
    pthread_kill(w, SIGWINCH);
    fired++;
    for (int j = 0; j < 2000 && __atomic_load_n(&oneshot_count, __ATOMIC_RELAXED) == before; j++) usleep(50);
  }
  stop_worker = 1;
  pthread_join(w, NULL);
  CHECK("oneshot_handlers_run", oneshot_count == fired && fired == 300);
  close(busy_obj);
}

static int outer_obj, inner_obj, other_dev, other_obj;
static volatile int nested_ok, nested_done, outer_done;
static void nesting(int sig, siginfo_t *si, void *uc) {
  (void)sig; (void)si; (void)uc;
  uint32_t idx = 99;
  int ok = wait_on(NT_WAIT_ANY, &inner_obj, 1, 21, 0, mono_ns(50), &idx) == 0 && idx == 0;
  struct wait_args w = {mono_ns(2), (uintptr_t)&other_obj, 1, 0xdead, 0, 22, 0, 0};
  ok = ok && ioctl(other_dev, NT_WAIT_ANY, &w) < 0 && errno == ETIMEDOUT;
  nested_ok += ok;
  __atomic_store_n(&nested_done, 1, __ATOMIC_RELEASE);
}
static void *outer_waiter(void *p) {
  struct wait_args w = {mono_ns(3000), (uintptr_t)&outer_obj, 1, 99, 0, 23, 0, 0};
  int r;
  do { r = ioctl(dev, NT_WAIT_ANY, &w); } while (r < 0 && errno == EINTR);
  *(int *)p = r == 0 && w.index == 0;
  __atomic_store_n(&outer_done, 1, __ATOMIC_RELEASE);
  return NULL;
}

static void nested_waits(void) {
  void *seen = NULL;
  other_dev = open("/dev/ntsync", O_RDWR | O_CLOEXEC);
  CHECK("second_device", other_dev >= 0);
  struct event_args ea = {0, 0};
  other_obj = ioctl(other_dev, NT_CREATE_EVENT, &ea);
  inner_obj = event(1, 1);
  CHECK("install_nesting", install_handler(SIGUSR2, nesting, &seen) == 0);
  int good = 0, early = 0;
  nested_ok = 0;
  for (int i = 0; i < 40; i++) {
    outer_obj = event(0, 0);
    int result = 0;
    pthread_t t;
    nested_done = outer_done = 0;
    pthread_create(&t, NULL, outer_waiter, &result);
    usleep(5000);
    pthread_kill(t, SIGUSR2);
    while (!__atomic_load_n(&nested_done, __ATOMIC_ACQUIRE)) usleep(200);
    usleep(5000);
    early += __atomic_load_n(&outer_done, __ATOMIC_ACQUIRE);
    uint32_t v = 0;
    ioctl(outer_obj, NT_EVENT_SET, &v);
    pthread_join(t, NULL);
    good += result;
    close(outer_obj);
  }
  CHECK("outer_wait_not_woken_by_nested_wait", early == 0);
  CHECK("outer_wait_survives_nested_waits", good == 40);
  CHECK("nested_waits_complete", nested_ok == 40);
  close(inner_obj);
  close(other_obj);
  close(other_dev);
}

int main(void) {
  alarm(60);
  dev = open("/dev/ntsync", O_RDWR | O_CLOEXEC);
  if (dev < 0) {
    printf("no device %d\n", errno);
    return 1;
  }
  semaphores();
  mutexes();
  events();
  any_order();
  all_semantics();
  cross_thread();
  cross_process();
  stress();
  signals();
  bursts();
  oneshots();
  nested_waits();
  printf("done fails=%d\n", fails);
  return fails != 0;
}
"""


class NtsyncPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which("cc"):
            raise unittest.SkipTest("no compiler")
        cls.tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls.tmp.name)
        (tmp / "ntdll.c").write_text(NTDLL)
        (tmp / "prog.c").write_text(PROGRAM)
        sources = sorted(str(p) for p in PRELOAD.glob("*.c"))
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-pthread", "-o", str(tmp / "libblsession.so"), *sources, "-ldl"],
                       check=True)
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-o", str(tmp / "ntdll.so"), str(tmp / "ntdll.c")], check=True)
        subprocess.run(["cc", "-O1", "-pthread", "-o", str(tmp / "prog"), str(tmp / "prog.c"), str(tmp / "ntdll.so"),
                        "-Wl,-rpath," + str(tmp)], check=True)
        cls.program = tmp / "prog"
        cls.preload = tmp / "libblsession.so"

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_program(self):
        env = dict(os.environ, LD_PRELOAD=str(self.preload), BL_SYNC="1")
        return subprocess.run([str(self.program)], env=env, capture_output=True, text=True, timeout=90)

    def test_emulation_matches_the_ntsync_driver(self):
        out = self.run_program()
        self.assertEqual(out.returncode, 0, out.stdout + out.stderr)
        self.assertIn("done fails=0", out.stdout)

    def test_repeated_runs_stay_consistent(self):
        for _ in range(3):
            out = self.run_program()
            self.assertEqual(out.returncode, 0, out.stdout + out.stderr)


if __name__ == "__main__":
    unittest.main()
