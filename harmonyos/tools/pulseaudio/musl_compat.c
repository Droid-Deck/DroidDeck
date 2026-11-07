/*
 * The functions musl does not have.
 *
 * See musl_compat.h for why this exists.
 */

#include <sys/utsname.h>
#include <unistd.h>

#include "musl_compat.h"

/*
 * The host id.
 *
 * glibc's version returns the host's IPv4 address, and falls back to the
 * machine id. The value only has to be stable for the life of the session:
 * PulseAudio uses it to make a session identifier when the system has no
 * hostname, so a value derived from the machine's name is good enough.
 */
long gethostid(void) {
    struct utsname name;

    if (uname(&name) < 0)
        return 0;

    /* A cheap hash of the node name: the same machine gives the same id,
     * and different machines almost never give the same one. */
    unsigned long hash = 5381;
    for (const char *c = name.nodename; *c; ++c)
        hash = hash * 33 + (unsigned char) *c;

    return (long) (hash & 0x7fffffff);
}
