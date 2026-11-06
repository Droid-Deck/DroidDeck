/*
 * Zink keeps kopper when the session draws through Venus.
 *
 * On Mali the session reaches the GPU through Venus over vtest, and Xwayland has no DRM node to
 * build glamor on, so it offers no DRI3. Without DRI3, Zink can only present through kopper (its
 * Vulkan WSI path). Steam's steamwebhelper.sh exports LIBGL_KOPPER_DISABLE=true for the client
 * interface unconditionally, so on Mali every GL context the interface asked for failed and the
 * webhelper restarted until Steam gave up on GPU acceleration. Steam restores that script when
 * it verifies its install, so the variable is dropped here instead, before Mesa reads it.
 */
#define _GNU_SOURCE 1
#include <stdlib.h>
#include <string.h>

__attribute__((constructor)) static void bl_venus_keep_kopper(void) {
    const char *debug = getenv("VN_DEBUG");
    if (debug && strstr(debug, "vtest")) unsetenv("LIBGL_KOPPER_DISABLE");
}
