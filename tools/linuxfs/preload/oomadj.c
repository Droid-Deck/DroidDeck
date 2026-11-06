/*
 * Based on jdmg28's DroidDeck-Mali fork (commit 6be00e00, GPL-3.0): steam-launch-wrapper runs every
 * game with --oom-score-adjust 900. Android's lmkd reads 900 as CACHED_APP_MIN_ADJ and kills the
 * foreground game first when memory runs short; on Linux 6.1 the raise also becomes the process's
 * permanent minimum. An app's processes cannot go below the score they inherit, so the only writes
 * that succeed from in here are raises: every write-open of an oom_score_adj file goes to /dev/null.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <string.h>
#include <unistd.h>

static int is_oom_score_adj(const char *path) {
  if (path == NULL || strncmp(path, "/proc/", 6) != 0) return 0;
  const char *p = path + 6;
  if (strncmp(p, "self/", 5) == 0) {
    p += 5;
  } else if (strncmp(p, "thread-self/", 12) == 0) {
    p += 12;
  } else {
    const char *digits = p;
    while (*p >= '0' && *p <= '9') p++;
    if (p == digits || *p != '/') return 0;
    p++;
    if (strncmp(p, "task/", 5) == 0) {
      p += 5;
      digits = p;
      while (*p >= '0' && *p <= '9') p++;
      if (p == digits || *p != '/') return 0;
      p++;
    }
  }
  return strcmp(p, "oom_score_adj") == 0 || strcmp(p, "oom_adj") == 0;
}

__attribute__((visibility("hidden"))) const char *bl_oom_adj_redirect(const char *path, int writing) {
  static int logged;
  if (!writing || !is_oom_score_adj(path)) return path;
  if (!__atomic_exchange_n(&logged, 1, __ATOMIC_RELAXED)) {
    static const char msg[] = "droiddeck: an oom_score_adj raise was dropped (Android would treat this program as a cached app)\n";
    int saved = errno;
    ssize_t ignored = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    (void)ignored;
    errno = saved;
  }
  return "/dev/null";
}
