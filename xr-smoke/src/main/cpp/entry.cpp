#include <android_native_app_glue.h>
#include "OpenXrAndroid.h"
void android_main(android_app *app) { droiddeck::xr::runAndroidXr(app); }
