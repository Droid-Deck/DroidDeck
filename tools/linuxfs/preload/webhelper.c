#define _GNU_SOURCE
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#ifndef WEBHELPER_DEFAULT
#define WEBHELPER_DEFAULT "--no-zygote --in-process-gpu -AudioServiceOutOfProcess"
#endif

static int is_webhelper_browser(const char *path, char *const argv[]) {
  if (!path || !argv) return 0;
  const char *base = strrchr(path, '/');
  base = base ? base + 1 : path;
  if (strcmp(base, "steamwebhelper")) return 0;
  for (int i = 1; argv[i]; i++)
    if (!strncmp(argv[i], "--type=", 7)) return 0;
  return 1;
}

static char *join_features(const char *have, const char *add) {
  size_t a = have ? strlen(have) : 0, b = strlen(add);
  char *out = malloc(a + b + 2);
  if (!out) return NULL;
  out[0] = 0;
  if (a) strcat(strcat(out, have), ",");
  return strcat(out, add);
}

static const char *env_value(char *const envp[], const char *variable) {
  size_t n = strlen(variable);
  for (int i = 0; envp && envp[i]; i++)
    if (!strncmp(envp[i], variable, n) && envp[i][n] == '=') return envp[i] + n + 1;
  return NULL;
}

static int env_set(char *const envp[], const char *name) {
  const char *value = env_value(envp, name);
  return value && *value;
}

static int mali_wayland_desktop(char *const envp[]) {
  const char *standin = env_value(envp, "DD_MALI_RENDER_STANDIN");
  const char *session = env_value(envp, "XDG_SESSION_TYPE");
  return standin && !strcmp(standin, "1") && session && !strcmp(session, "wayland") &&
         env_set(envp, "WAYLAND_DISPLAY") && !env_set(envp, "DD_INSIDE") &&
         !env_set(envp, "GAMESCOPE_WAYLAND_DISPLAY") && !env_set(envp, "DD_DESKTOP_GPU_CHILD");
}

static int electron_node_mode(char *const envp[]) {
  const char *mode = env_value(envp, "ELECTRON_RUN_AS_NODE");
  return mode && *mode;
}

static int beside_chrome_sandbox(const char *path) {
  char *real = realpath(path, NULL);
  if (!real) return 0;
  int found = 0;
  for (int up = 0; up < 2 && !found; up++) {
    char *slash = strrchr(real, '/');
    if (!slash) break;
    *slash = 0;
    size_t dir = strlen(real);
    char *sandbox = malloc(dir + sizeof "/chrome-sandbox");
    if (!sandbox) break;
    memcpy(sandbox, real, dir);
    strcpy(sandbox + dir, "/chrome-sandbox");
    found = access(sandbox, F_OK) == 0;
    free(sandbox);
  }
  free(real);
  return found;
}

static char *folder_of(const char *path) {
  char *real = realpath(path, NULL);
  char *slash = real ? strrchr(real, '/') : NULL;
  if (slash) *slash = 0;
  return real;
}

static int started_by_its_own_app(const char *path) {
  char self[4096];
  ssize_t n = readlink("/proc/self/exe", self, sizeof self - 1);
  if (n <= 0) return 0;
  self[n] = 0;
  char *mine = folder_of(self), *theirs = folder_of(path);
  int same = mine && theirs && !strcmp(mine, theirs) && beside_chrome_sandbox(self);
  free(mine);
  free(theirs);
  return same;
}

static char *const *chromium_argv(const char *path, char *const argv[], char *const envp[]) {
  if (!path || !argv || !argv[0]) return argv;
  int argc = 0, sandbox = 1, switches = 1, render_node = 0, headless = 0;
  const char *platform = NULL;
  for (; argv[argc]; argc++) {
    if (!argc) continue;
    const char *arg = argv[argc];
    if (!strcmp(arg, "--no-sandbox")) sandbox = 0;
    if (!strcmp(arg, "--")) switches = 0;
    if (!switches) continue;
    if (!strncmp(arg, "--type=", 7)) return argv;
    if (!strncmp(arg, "--ozone-platform=", 17) || !strcmp(arg, "--ozone-platform")) platform = arg;
    if (!strncmp(arg, "--render-node-override=", 23) || !strcmp(arg, "--render-node-override")) render_node = 1;
    if (!strcmp(arg, "--headless") || !strncmp(arg, "--headless=", 11)) headless = 1;
  }
  if (!beside_chrome_sandbox(path) || started_by_its_own_app(path)) return argv;
  int desktop = mali_wayland_desktop(envp);
  const char *ozone = NULL;
  if (!platform) {
    if (desktop && !headless) ozone = "--ozone-platform=wayland";
    if (!desktop && env_set(envp, "DISPLAY")) ozone = "--ozone-platform=x11";
  }
  int skip_render_probe = desktop && !headless && !render_node &&
                          (!platform || !strcmp(platform, "--ozone-platform=wayland"));
  if (!sandbox && !ozone && !skip_render_probe) return argv;
  char **out = calloc((size_t)argc + 4, sizeof *out);
  if (!out) return argv;
  int n = 0;
  out[n++] = argv[0];
  if (sandbox) out[n++] = (char *)"--no-sandbox";
  if (ozone) out[n++] = (char *)ozone;
  if (skip_render_probe) out[n++] = (char *)"--render-node-override=";
  for (int i = 1; i < argc; i++) out[n++] = argv[i];
  return out;
}

__attribute__((visibility("hidden")))
char *const *dd_webhelper_argv(const char *path, char *const argv[], char *const envp[]) {
  if (electron_node_mode(envp)) return argv;
  if (!is_webhelper_browser(path, argv)) return chromium_argv(path, argv, envp);
  const char *spec = getenv("DD_WEBHELPER_ARGS");
  if (!spec || !*spec) spec = WEBHELPER_DEFAULT;
  if (!strcmp(spec, "stock")) return argv;

  int argc = 0;
  while (argv[argc]) argc++;
  char *tokens = strdup(spec);
  if (!tokens) return argv;
  int extra = 0;
  for (char *c = tokens; *c; c++)
    if (*c == ' ') extra++;
  char **out = calloc(argc + extra + 4, sizeof *out);
  if (!out) return argv;

  const char *enable = NULL, *disable = NULL;
  int n = 0;
  for (int i = 0; i < argc; i++) {
    if (i && !strncmp(argv[i], "--enable-features=", 18)) enable = argv[i] + 18;
    else if (i && !strncmp(argv[i], "--disable-features=", 19)) disable = argv[i] + 19;
    else out[n++] = argv[i];
  }
  char *add_enable = NULL, *add_disable = NULL, *save = NULL;
  for (char *t = strtok_r(tokens, " ", &save); t; t = strtok_r(NULL, " ", &save)) {
    if (t[0] == '+' && t[1]) {
      char *joined = join_features(add_enable, t + 1);
      free(add_enable);
      add_enable = joined;
    } else if (t[0] == '-' && t[1] && t[1] != '-') {
      char *joined = join_features(add_disable, t + 1);
      free(add_disable);
      add_disable = joined;
    } else if (!strncmp(t, "--", 2)) {
      out[n++] = t;
    }
  }
  char *merged;
  if (add_enable && (merged = join_features(enable, add_enable))) enable = merged;
  if (add_disable && (merged = join_features(disable, add_disable))) disable = merged;
  char *flag;
  if (enable && (flag = malloc(strlen(enable) + 19))) out[n++] = strcat(strcpy(flag, "--enable-features="), enable);
  if (disable && (flag = malloc(strlen(disable) + 20))) out[n++] = strcat(strcpy(flag, "--disable-features="), disable);
  out[n] = NULL;
  return out;
}
