#include "../../main/cpp/viture/sdk_bridge.cpp"
#include <cassert>

namespace {
int device;
int display_result;
int mode_result;
int dof_result;
int set_dof_result;
int display_calls;
int mode_calls;
int dof_calls;
int set_dof_calls;
int applied_dof;

int set_display_mode(XRDeviceProviderHandle handle, int value) {
    assert(handle == &device);
    assert(value == 0x3d);
    ++display_calls;
    return display_result;
}

int get_native_mode(XRDeviceProviderHandle handle) {
    assert(handle == &device);
    ++mode_calls;
    return mode_result;
}

int get_native_dof(XRDeviceProviderHandle handle) {
    assert(handle == &device);
    ++dof_calls;
    return dof_result;
}

int set_native_dof(XRDeviceProviderHandle handle, int value) {
    assert(handle == &device);
    ++set_dof_calls;
    applied_dof = value;
    return set_dof_result;
}

void reset() {
    session = std::make_unique<Session>();
    session->device = &device;
    session->token = 1;
    session->native_dof = true;
    session->tracking = true;
    sdk.api.xr_device_provider_native_set_display_mode = set_display_mode;
    sdk.api.xr_device_provider_native_get_mode = get_native_mode;
    sdk.api.xr_device_provider_native_get_dof = get_native_dof;
    sdk.api.xr_device_provider_native_set_dof = set_native_dof;
    display_result = set_dof_result = 0;
    mode_result = 1;
    dof_result = 2;
    display_calls = mode_calls = dof_calls = set_dof_calls = 0;
    applied_dof = -1;
}

int change_display() {
    return JNI_METHOD(nativeDeviceControl)(nullptr, nullptr, 1, 6, 0x3d, 0);
}
}

int main() {
    reset();
    assert(change_display() == 0);
    assert(display_calls == 1 && mode_calls == 1 && dof_calls == 1 && set_dof_calls == 1);
    assert(applied_dof == VITURE_NATIVE_DOF_0);
    assert(session->saved_dof == 2);
    dof_result = 0;
    assert(change_display() == 0);
    assert(dof_calls == 1 && set_dof_calls == 2 && session->saved_dof == 2);

    reset();
    session->tracking = false;
    assert(change_display() == 0);
    assert(display_calls == 1 && mode_calls == 0 && set_dof_calls == 0);
    assert(session->saved_dof == -1);

    reset();
    session->native_dof = false;
    assert(change_display() == -4);
    assert(display_calls == 0 && mode_calls == 0 && set_dof_calls == 0);

    reset();
    display_result = -6;
    assert(change_display() == -6);
    assert(display_calls == 1 && mode_calls == 0 && set_dof_calls == 0);

    reset();
    mode_result = -7;
    assert(change_display() == -7);
    assert(dof_calls == 0 && set_dof_calls == 0);

    reset();
    mode_result = 0;
    assert(change_display() == 0);
    assert(dof_calls == 0 && set_dof_calls == 0);

    reset();
    dof_result = -8;
    assert(change_display() == -8);
    assert(set_dof_calls == 0 && session->saved_dof == -1);

    reset();
    set_dof_result = -9;
    assert(change_display() == -9);
    assert(set_dof_calls == 1 && applied_dof == VITURE_NATIVE_DOF_0);

    sdk.api.GetVersionString = [] { return "2.4.2"; };
    assert(JNI_METHOD(nativeAvailable)(nullptr, nullptr) == JNI_TRUE);
    std::puts("VITURE bridge: native display tracking ownership, failure propagation, and loaded availability passed");
}
