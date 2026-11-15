/* tools/linuxfs/preload/subreaper.c
 *
 * Mark every process that loads libblsession.so as a child subreaper.
 *
 * wlroots starts Xwayland with a double fork: gamescope -> middle process
 * (exits immediately) -> Xwayland.  With no subreaper in the tree, Xwayland
 * is reparented to pid 1 and becomes an orphan.  On some vendor kernels
 * (observed on Meizu / Flyme 12) an orphan reaper SIGKILLs it in that window,
 * which makes gamescope wait forever in wlserver_init() and the UI hangs on
 * "Starting display and audio".
 *
 * With PR_SET_CHILD_SUBREAPER the reparent target becomes the nearest marked
 * ancestor instead of init, so the orphan event never happens.
 *
 * Harmless for processes that do not fork, and cheap (one syscall at load).
 */
#define _GNU_SOURCE
#include <sys/prctl.h>

__attribute__((constructor))
static void bl_session_set_subreaper(void)
{
    (void)prctl(PR_SET_CHILD_SUBREAPER, 1);
}
