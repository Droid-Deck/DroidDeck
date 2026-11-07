/*
 * The logging the native module uses.
 *
 * This is the staging copy; the port's own ddk_log.h replaces it when the
 * tree is synced back.
 */
#ifndef DDK_LOG_H
#define DDK_LOG_H

#define DDK_LOGI(...) ((void) 0)
#define DDK_LOGW(...) ((void) 0)
#define DDK_LOGE(...) ((void) 0)

#endif  // DDK_LOG_H
