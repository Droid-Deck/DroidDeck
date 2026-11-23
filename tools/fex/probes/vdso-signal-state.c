#define _GNU_SOURCE
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <time.h>

static volatile sig_atomic_t alarms;

static void on_alarm(int sig) {
  (void)sig;
  alarms++;
}

int main(int argc, char **argv) {
  int seconds = argc > 1 ? atoi(argv[1]) : 3;
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_handler = on_alarm;
  sa.sa_flags = SA_RESTART;
  sigaction(SIGALRM, &sa, NULL);
  struct itimerval it = {{0, 1000}, {0, 1000}};
  setitimer(ITIMER_REAL, &it, NULL);

  struct timespec start, now, last;
  clock_gettime(CLOCK_MONOTONIC, &start);
  last = start;
  unsigned long calls = 0, bad = 0;
  unsigned long a = 1, b = 3, c = 1 ^ 0x5a5a5a5a5a5a5a5aUL;
  do {
    clock_gettime(CLOCK_MONOTONIC, &now);
    calls++;
    if (b != a * 3 || c != (a ^ 0x5a5a5a5a5a5a5a5aUL))
      bad++;
    if (now.tv_sec < last.tv_sec || (now.tv_sec == last.tv_sec && now.tv_nsec < last.tv_nsec))
      bad++;
    last = now;
    a = a * 6364136223846793005UL + 1442695040888963407UL;
    b = a * 3;
    c = a ^ 0x5a5a5a5a5a5a5a5aUL;
  } while (now.tv_sec - start.tv_sec < seconds);

  struct itimerval off = {{0, 0}, {0, 0}};
  setitimer(ITIMER_REAL, &off, NULL);
  printf("calls=%lu alarms=%d bad=%lu\n", calls, (int)alarms, bad);
  return bad || alarms == 0;
}
