// Turns off Android's heap pointer tagging (TBI) in the bionic Wine processes it is preloaded into.
// Wine/FEX paths can hand free() a pointer whose top byte was dropped; with tagging on, bionic aborts
// ("Pointer tag for 0x... was truncated"). Going from TBI to none at run time is allowed by bionic.
// mallopt is looked up at run time, so the file builds with any NDK API level.
#include <dlfcn.h>
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL_OPT (-204)
#define M_HEAP_TAGGING_LEVEL_NONE_VAL 0
__attribute__((constructor(101))) static void ddbionic_notag(void) {
    int (*mallopt_fn)(int, int) = (int (*)(int, int))dlsym(RTLD_DEFAULT, "mallopt");
    if (mallopt_fn) mallopt_fn(M_BIONIC_SET_HEAP_TAGGING_LEVEL_OPT, M_HEAP_TAGGING_LEVEL_NONE_VAL);
}
