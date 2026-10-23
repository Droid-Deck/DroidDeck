// Android's SELinux lets the guest stat /sys/class/thermal and /sys/class/drm but not list them.
// MangoHud iterates both without a try/catch, so the EACCES aborts mangoapp. A denied listing
// under /sys reads as an empty directory instead.
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <string.h>
#include <sys/stat.h>

#ifndef SYS_ROOT
#define SYS_ROOT "/sys"
#endif
#define EMPTY_DIR "/tmp/.mangoapp-empty-dir"

static int under_sys(const char *name)
{
    size_t n = strlen(SYS_ROOT);
    return name && !strncmp(name, SYS_ROOT, n) && (name[n] == '/' || name[n] == '\0');
}

__attribute__((visibility("default"))) DIR *opendir(const char *name)
{
    static DIR *(*real_opendir)(const char *);
    if (!real_opendir) real_opendir = (DIR *(*)(const char *))dlsym(RTLD_NEXT, "opendir");
    if (!real_opendir) { errno = ENOSYS; return NULL; }
    DIR *dir = real_opendir(name);
    if (dir || errno != EACCES || !under_sys(name)) return dir;
    mkdir(EMPTY_DIR, 0755);
    dir = real_opendir(EMPTY_DIR);
    if (!dir) errno = EACCES;
    return dir;
}
