#ifndef UNIX_LISTEN_H
#define UNIX_LISTEN_H
#include <stdbool.h>

bool droiddeck_unix_path_fits(const char *dir, const char *name);
int droiddeck_unix_listen(const char *dir, const char *name, int backlog);
#endif
