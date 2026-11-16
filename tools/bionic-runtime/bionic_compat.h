// glibc's program_invocation_name is __progname in Android's libc (exported, set from argv[0]).
#pragma once
#ifdef __cplusplus
extern "C" char *__progname;
#else
extern char *__progname;
#endif
#define program_invocation_name __progname
