#ifndef DDK_COMPOSITOR_OHOS_H
#define DDK_COMPOSITOR_OHOS_H

#include <napi/native_api.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Registers the compositor's ArkTS facing functions on the Node-API module.
 *
 * The implementation lives in compositor_ohos.c, which replaces upstream's
 * JNI entry point (waylandcomp_jni.c).
 */
napi_value CompositorRegister(napi_env env, napi_value exports);

#ifdef __cplusplus
}
#endif

#endif  // DDK_COMPOSITOR_OHOS_H
