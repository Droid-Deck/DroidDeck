/**
 * The DroidDeck guest session, in its smoke-test form.
 *
 * This is the program the HarmonyOS port runs inside the Linux guest to
 * prove that the guest can start at all. It is deliberately tiny and
 * statically linked, so it runs on any Linux kernel regardless of the
 * guest distribution's libc.
 *
 * It writes a heartbeat to stdout, which the supervisor captures into the
 * session log, and touches a marker file inside the guest, so the user can
 * confirm that the guest really ran rather than the launcher silently
 * failing.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define MARKER "/run/droiddeck-session.pid"

static void write_marker(void) {
  FILE *file = fopen(MARKER, "w");
  if (file == NULL) {
    return;
  }
  fprintf(file, "%d\n", (int)getpid());
  fclose(file);
}

int main(int argc, char **argv) {
  (void)argc;
  (void)argv;

  write_marker();

  printf("droiddeck guest session started (pid %d)\n", (int)getpid());
  fflush(stdout);

  time_t started = time(NULL);
  for (;;) {
    sleep(1);
    time_t now = time(NULL);
    printf("droiddeck guest heartbeat %ld\n", (long)(now - started));
    fflush(stdout);
  }

  return 0;
}
