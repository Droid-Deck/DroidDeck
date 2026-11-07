#include <napi/native_api.h>

#include <string>
#include <vector>

#include "audio/audio.h"
#include "audio/pulse_server.h"
#include "ddk_log.h"
#include "device/device.h"
#include "input/gamepad.h"
#include "runtime/runtime.h"
#include "runtime/install.h"
#include "runtime/unpack.h"
#include "surface/surface.h"

// The compositor's Node-API entry points, registered alongside the rest.
#include "compositor/compositor_ohos.h"

namespace {

/** Sets a string property on an object. */
bool SetString(napi_env env, napi_value object, const char *key, const std::string &value) {
  napi_value text = nullptr;
  if (napi_create_string_utf8(env, value.c_str(), value.size(), &text) != napi_ok) {
    return false;
  }
  napi_set_named_property(env, object, key, text);
  return true;
}

bool SetBool(napi_env env, napi_value object, const char *key, bool value) {
  napi_value flag = nullptr;
  napi_get_boolean(env, value, &flag);
  napi_set_named_property(env, object, key, flag);
  return true;
}

bool SetNumber(napi_env env, napi_value object, const char *key, double value) {
  napi_value number = nullptr;
  napi_create_double(env, value, &number);
  napi_set_named_property(env, object, key, number);
  return true;
}

/** Reads a string property, returning an empty string when absent. */
std::string GetString(napi_env env, napi_value object, const char *key) {
  napi_value value = nullptr;
  if (napi_get_named_property(env, object, key, &value) != napi_ok) {
    return std::string();
  }
  napi_valuetype type = napi_undefined;
  napi_typeof(env, value, &type);
  if (type != napi_string) {
    return std::string();
  }
  size_t length = 0;
  napi_get_value_string_utf8(env, value, nullptr, 0, &length);
  std::string text(length, '\0');
  napi_get_value_string_utf8(env, value, text.data(), length + 1, &length);
  text.resize(length);
  return text;
}

/**
 * Reads a string argument that may be passed either as a plain string or
 * as an object with the given property.
 */
std::string GetStringArg(napi_env env, napi_value value, const char *key) {
  napi_valuetype type = napi_undefined;
  napi_typeof(env, value, &type);
  if (type == napi_string) {
    size_t length = 0;
    napi_get_value_string_utf8(env, value, nullptr, 0, &length);
    std::string text(length, '\0');
    napi_get_value_string_utf8(env, value, text.data(), length + 1, &length);
    text.resize(length);
    return text;
  }
  if (type == napi_object) {
    return GetString(env, value, key);
  }
  return std::string();
}

/** Reads a string array property. */
std::vector<std::string> GetStringArray(napi_env env, napi_value object, const char *key) {
  std::vector<std::string> values;
  napi_value array = nullptr;
  if (napi_get_named_property(env, object, key, &array) != napi_ok) {
    return values;
  }
  bool isArray = false;
  napi_is_array(env, array, &isArray);
  if (!isArray) {
    return values;
  }
  uint32_t length = 0;
  napi_get_array_length(env, array, &length);
  for (uint32_t index = 0; index < length; ++index) {
    napi_value item = nullptr;
    napi_get_element(env, array, index, &item);
    napi_valuetype type = napi_undefined;
    napi_typeof(env, item, &type);
    if (type != napi_string) {
      continue;
    }
    size_t size = 0;
    napi_get_value_string_utf8(env, item, nullptr, 0, &size);
    std::string text(size, '\0');
    napi_get_value_string_utf8(env, item, text.data(), size + 1, &size);
    text.resize(size);
    values.push_back(text);
  }
  return values;
}

// --- Exported functions -------------------------------------------------

napi_value GetDeviceInfo(napi_env env, napi_callback_info info) {
  ddk::GpuIdentity gpu = ddk::DetectGpu();

  napi_value result = nullptr;
  napi_create_object(env, &result);

  SetString(env, result, "gpuName", gpu.name);
  SetString(env, result, "gpuVendor", gpu.vendor);
  SetNumber(env, result, "gpuModel", gpu.model);
  SetString(env, result, "abi", ddk::DetectAbi());
  SetNumber(env, result, "cpuCores", ddk::DetectCpuCores());
  SetNumber(env, result, "ramMiB", ddk::DetectRamMiB());
  SetBool(env, result, "vulkanBridge", ddk::DetectVulkanBridge());
  // Filled in by ArkTS from deviceInfo and the SDK version.
  SetString(env, result, "deviceBrand", "");
  SetString(env, result, "deviceModel", "");
  SetString(env, result, "osRelease", "");
  SetNumber(env, result, "apiLevel", 0);

  return result;
}

napi_value RuntimeStatus(napi_env env, napi_callback_info info) {
  ddk::RuntimeStatus status = ddk::Runtime::Instance().Status();

  napi_value result = nullptr;
  napi_create_object(env, &result);
  SetBool(env, result, "installed", status.installed);
  SetString(env, result, "version", status.version);
  SetString(env, result, "rootDir", status.rootDir);
  SetNumber(env, result, "sizeBytes", static_cast<double>(status.sizeBytes));
  SetBool(env, result, "running", status.running);
  SetNumber(env, result, "pid", status.pid);
  SetString(env, result, "mode", ddk::RuntimeModeName(status.mode));
  SetString(env, result, "lastError", status.lastError);
  return result;
}

napi_value RuntimeStart(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1] = {nullptr};
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc < 1) {
    napi_value result = nullptr;
    napi_get_boolean(env, false, &result);
    return result;
  }

  ddk::RuntimeOptions options;
  options.rootDir = GetString(env, argv[0], "rootDir");
  options.hostRuntimeDir = GetString(env, argv[0], "hostRuntimeDir");
  options.hostFilesDir = GetString(env, argv[0], "hostFilesDir");
  options.hostCacheDir = GetString(env, argv[0], "hostCacheDir");
  options.command = GetString(env, argv[0], "command");
  options.args = GetStringArray(env, argv[0], "args");
  options.cwd = GetString(env, argv[0], "cwd");
  options.vulkanDriver = GetString(env, argv[0], "vulkanDriver");
  options.vulkanIcd = GetString(env, argv[0], "vulkanIcd");

  napi_value envObject = nullptr;
  if (napi_get_named_property(env, argv[0], "env", &envObject) == napi_ok) {
    napi_valuetype type = napi_undefined;
    napi_typeof(env, envObject, &type);
    if (type == napi_object) {
      napi_value names = nullptr;
      napi_get_property_names(env, envObject, &names);
      uint32_t length = 0;
      napi_get_array_length(env, names, &length);
      for (uint32_t index = 0; index < length; ++index) {
        napi_value key = nullptr;
        napi_get_element(env, names, index, &key);
        napi_value value = nullptr;
        napi_get_property(env, envObject, key, &value);
        napi_valuetype valueType = napi_undefined;
        napi_typeof(env, value, &valueType);
        if (valueType != napi_string) {
          continue;
        }
        size_t size = 0;
        napi_get_value_string_utf8(env, key, nullptr, 0, &size);
        std::string name(size, '\0');
        napi_get_value_string_utf8(env, key, name.data(), size + 1, &size);
        name.resize(size);
        options.env[name] = GetString(env, envObject, name.c_str());
      }
    }
  }

  napi_value desktop = nullptr;
  if (napi_get_named_property(env, argv[0], "desktop", &desktop) == napi_ok) {
    bool value = false;
    napi_get_value_bool(env, desktop, &value);
    options.desktop = value;
  }

  const bool started = ddk::Runtime::Instance().Start(options);
  napi_value result = nullptr;
  napi_get_boolean(env, started, &result);
  return result;
}

napi_value RuntimeStop(napi_env env, napi_callback_info info) {
  const bool stopped = ddk::Runtime::Instance().Stop();
  napi_value result = nullptr;
  napi_get_boolean(env, stopped, &result);
  return result;
}

napi_value RuntimeUnpack(napi_env env, napi_callback_info info) {
  size_t argc = 2;
  napi_value argv[2] = {nullptr};
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc < 2) {
    napi_value result = nullptr;
    napi_get_boolean(env, false, &result);
    return result;
  }

  const std::string archive = GetStringArg(env, argv[0], "path");
  const std::string destination = GetStringArg(env, argv[1], "destination");
  const ddk::UnpackResult unpacked = ddk::UnpackArchive(archive, destination);

  napi_value result = nullptr;
  napi_create_object(env, &result);
  SetBool(env, result, "ok", unpacked.ok);
  SetString(env, result, "error", unpacked.error);
  SetNumber(env, result, "files", static_cast<double>(unpacked.files));
  SetNumber(env, result, "bytes", static_cast<double>(unpacked.bytes));
  return result;
}

napi_value RuntimeVerifySha256(napi_env env, napi_callback_info info) {
  size_t argc = 2;
  napi_value argv[2] = {nullptr};
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc < 2) {
    napi_value result = nullptr;
    napi_get_boolean(env, false, &result);
    return result;
  }
  const std::string path = GetStringArg(env, argv[0], "path");
  const std::string expected = GetStringArg(env, argv[1], "expected");
  const bool verified = ddk::VerifySha256(path, expected);
  napi_value result = nullptr;
  napi_get_boolean(env, verified, &result);
  return result;
}

/**
 * Installs a runtime: unpack, prepare, apply the overlay and write the
 * version marker.
 *
 * The whole sequence lives in the native layer because it moves gigabytes
 * around, and because the overlay has to land on top of the unpacked rootfs
 * before the session script exists.
 */
napi_value RuntimeInstall(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1] = {nullptr};
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc < 1) {
    napi_value result = nullptr;
    napi_create_object(env, &result);
    SetBool(env, result, "ok", false);
    SetString(env, result, "error", "no install options given");
    SetNumber(env, result, "files", 0);
    SetNumber(env, result, "bytes", 0);
    return result;
  }

  const std::string archive = GetString(env, argv[0], "archive");
  const std::string destination = GetString(env, argv[0], "destination");
  const std::string overlay = GetString(env, argv[0], "overlay");
  const std::string version = GetString(env, argv[0], "version");
  const std::string sha256 = GetString(env, argv[0], "sha256");

  napi_value result = nullptr;
  napi_create_object(env, &result);
  SetNumber(env, result, "files", 0);
  SetNumber(env, result, "bytes", 0);

  if (archive.empty() || destination.empty()) {
    SetBool(env, result, "ok", false);
    SetString(env, result, "error", "the archive or the destination is missing");
    return result;
  }

  // The download is checked before anything is written, so a corrupted
  // archive never reaches the rootfs.
  if (!sha256.empty() && !ddk::VerifySha256(archive, sha256)) {
    SetBool(env, result, "ok", false);
    SetString(env, result, "error", "the downloaded runtime does not match its checksum");
    return result;
  }

  ddk::UnpackResult unpacked = ddk::UnpackArchive(archive, destination);
  if (!unpacked.ok) {
    SetBool(env, result, "ok", false);
    SetString(env, result, "error", unpacked.error);
    return result;
  }

  if (!ddk::PrepareRootfs(destination)) {
    SetBool(env, result, "ok", false);
    SetString(env, result, "error", "the runtime could not be prepared");
    return result;
  }

  long long files = unpacked.files;
  if (!overlay.empty()) {
    const ddk::UnpackResult applied = ddk::ApplyOverlay(overlay, destination);
    if (!applied.ok) {
      SetBool(env, result, "ok", false);
      SetString(env, result, "error", applied.error);
      return result;
    }
    files += applied.files;
  }

  if (!version.empty() && !ddk::WriteVersionMarker(destination, version)) {
    SetBool(env, result, "ok", false);
    SetString(env, result, "error", "the version marker could not be written");
    return result;
  }

  SetBool(env, result, "ok", true);
  SetString(env, result, "error", "");
  SetNumber(env, result, "files", static_cast<double>(files));
  SetNumber(env, result, "bytes", static_cast<double>(unpacked.bytes));
  return result;
}

napi_value SurfaceAttach(napi_env env, napi_callback_info info) {
  // The framework already handed the window to the module through the
  // OnSurfaceCreated entry point; this call only reports whether the EGL
  // context is up.
  const bool attached = ddk::SurfaceHost::Instance().Attach();
  napi_value result = nullptr;
  napi_get_boolean(env, attached, &result);
  return result;
}

napi_value SurfaceDetach(napi_env env, napi_callback_info info) {
  ddk::SurfaceHost::Instance().Detach();
  napi_value result = nullptr;
  napi_get_undefined(env, &result);
  return result;
}

napi_value InputOpen(napi_env env, napi_callback_info info) {
  const bool opened = ddk::Gamepad::Instance().Open();
  napi_value result = nullptr;
  napi_get_boolean(env, opened, &result);
  return result;
}

napi_value InputClose(napi_env env, napi_callback_info info) {
  ddk::Gamepad::Instance().Close();
  napi_value result = nullptr;
  napi_get_undefined(env, &result);
  return result;
}

napi_value InputEvent(napi_env env, napi_callback_info info) {
  size_t argc = 3;
  napi_value argv[3] = {nullptr};
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc < 3) {
    napi_value result = nullptr;
    napi_get_undefined(env, &result);
    return result;
  }

  std::string kind = GetStringArg(env, argv[0], "kind");

  int32_t code = 0;
  napi_get_value_int32(env, argv[1], &code);
  double value = 0.0;
  napi_get_value_double(env, argv[2], &value);

  ddk::Gamepad::Instance().Event(kind, code, static_cast<int>(value));

  napi_value result = nullptr;
  napi_get_undefined(env, &result);
  return result;
}

napi_value AudioServerStop(napi_env env, napi_callback_info info) {
  (void) env;
  (void) info;
  ddk::PulseServer::Instance().Stop();
  napi_value result = nullptr;
  napi_get_undefined(env, &result);
  return result;
}

/**
 * Starts the PulseAudio server the guest's audio client connects to.
 *
 * The arguments are the runtime directory, the directory the HAP's
 * libraries were unpacked into, and where the server's log goes.
 */
napi_value AudioServerStart(napi_env env, napi_callback_info info) {
  size_t argc = 3;
  napi_value argv[3] = {nullptr, nullptr, nullptr};
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc < 2) {
    napi_value result = nullptr;
    napi_get_boolean(env, false, &result);
    return result;
  }

  const std::string runtimeDir = GetStringArg(env, argv[0], "path");
  const std::string binaryDir = GetStringArg(env, argv[1], "path");
  const std::string logPath = argc >= 3 ? GetStringArg(env, argv[2], "path") : std::string();

  const bool started = ddk::PulseServer::Instance().Start(runtimeDir, binaryDir, logPath);

  napi_value result = nullptr;
  napi_create_object(env, &result);
  SetBool(env, result, "ok", started);
  SetString(env, result, "error", ddk::PulseServer::Instance().LastError());
  SetString(env, result, "address", ddk::PulseServer::Instance().GuestServerAddress(runtimeDir));
  SetString(env, result, "socket", ddk::PulseServer::Instance().AudioSocketPath(runtimeDir));
  return result;
}

napi_value AudioOpen(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1] = {nullptr};
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  std::string socketPath = "/runtime/droiddeck-audio.sock";
  if (argc >= 1) {
    const std::string given = GetStringArg(env, argv[0], "path");
    if (!given.empty()) {
      socketPath = given;
    }
  }
  const bool opened = ddk::AudioSink::Instance().Open(socketPath);
  napi_value result = nullptr;
  napi_get_boolean(env, opened, &result);
  return result;
}

napi_value AudioRead(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1] = {nullptr};
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  int32_t maxFrames = 1024;
  if (argc >= 1) {
    napi_get_value_int32(env, argv[0], &maxFrames);
  }
  if (maxFrames <= 0) {
    maxFrames = 1024;
  }

  std::vector<float> frames(static_cast<size_t>(maxFrames) * 2, 0.0f);
  const size_t read = ddk::AudioSink::Instance().Read(frames.data(), static_cast<size_t>(maxFrames));

  void *data = nullptr;
  napi_value buffer = nullptr;
  napi_create_arraybuffer(env, read * 2 * sizeof(float), &data, &buffer);
  if (data != nullptr && read > 0) {
    std::memcpy(data, frames.data(), read * 2 * sizeof(float));
  }
  return buffer;
}

napi_value AudioClose(napi_env env, napi_callback_info info) {
  ddk::AudioSink::Instance().Close();
  napi_value result = nullptr;
  napi_get_undefined(env, &result);
  return result;
}

napi_value LogTail(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1] = {nullptr};
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  int32_t maxLines = 200;
  if (argc >= 1) {
    napi_get_value_int32(env, argv[0], &maxLines);
  }

  const std::vector<std::string> lines = ddk::Runtime::Instance().LogTail(static_cast<size_t>(maxLines));

  napi_value result = nullptr;
  napi_create_array_with_length(env, lines.size(), &result);
  for (size_t index = 0; index < lines.size(); ++index) {
    napi_value text = nullptr;
    napi_create_string_utf8(env, lines[index].c_str(), lines[index].size(), &text);
    napi_set_element(env, result, static_cast<uint32_t>(index), text);
  }
  return result;
}

napi_value Init(napi_env env, napi_value exports) {
  const napi_property_descriptor descriptors[] = {
      {"getDeviceInfo", nullptr, GetDeviceInfo, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"runtimeStatus", nullptr, RuntimeStatus, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"runtimeStart", nullptr, RuntimeStart, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"runtimeStop", nullptr, RuntimeStop, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"runtimeUnpack", nullptr, RuntimeUnpack, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"runtimeInstall", nullptr, RuntimeInstall, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"runtimeVerifySha256", nullptr, RuntimeVerifySha256, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"surfaceAttach", nullptr, SurfaceAttach, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"surfaceDetach", nullptr, SurfaceDetach, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"inputOpen", nullptr, InputOpen, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"inputClose", nullptr, InputClose, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"inputEvent", nullptr, InputEvent, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"audioOpen", nullptr, AudioOpen, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"audioRead", nullptr, AudioRead, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"audioClose", nullptr, AudioClose, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"audioServerStart", nullptr, AudioServerStart, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"audioServerStop", nullptr, AudioServerStop, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"logTail", nullptr, LogTail, nullptr, nullptr, nullptr, napi_default, nullptr},
  };

  napi_define_properties(env, exports, sizeof(descriptors) / sizeof(descriptors[0]), descriptors);
  CompositorRegister(env, exports);
  return exports;
}

napi_module g_droiddeckModule = {
    1,                          /* nm_version */
    0,                          /* nm_flags */
    nullptr,                    /* nm_filename */
    Init,                       /* nm_register_func */
    "droiddeck_native",         /* nm_modname */
    nullptr,                    /* nm_priv */
    {nullptr},                  /* reserved */
};

}  // namespace

extern "C" __attribute__((constructor)) void RegisterDroidDeckModule(void) {
  napi_module_register(&g_droiddeckModule);
}
