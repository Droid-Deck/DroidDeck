#ifndef DDK_RUNTIME_H
#define DDK_RUNTIME_H

#include <sys/types.h>

#include <map>
#include <string>
#include <vector>

namespace ddk {

/**
 * How the Linux guest is executed.
 *
 * Upstream Android always uses proot, a ptrace-based path rewriter. On
 * HarmonyOS the app sandbox may or may not grant ptrace, so the port probes
 * the capability at runtime and picks the best available mechanism instead
 * of assuming one.
 */
enum class RuntimeMode {
  /** Neither proot nor a direct guest launch can work here. */
  Unsupported,
  /** ptrace is permitted: proot gives full upstream parity. */
  Proot,
  /** ptrace is denied: the guest is exec'd directly, without path remapping. */
  Direct
};

const char *RuntimeModeName(RuntimeMode mode);

struct RuntimeOptions {
  /** Absolute path of the guest rootfs inside the app sandbox. */
  std::string rootDir;
  /**
   * Absolute path of the host directory that holds the compositor's Wayland
   * socket. It is bound into the guest as /run/droiddeck, and the guest is
   * told to look for the socket there.
   */
  std::string hostRuntimeDir;
  /**
   * The app's own files directory.
   *
   * It is bound into the guest at the same absolute path, the way upstream
   * binds it, so the paths the guest is handed — the session log, the
   * debug directory — resolve inside the guest.
   */
  std::string hostFilesDir;
  /**
   * The app's cache directory, bound into the guest at the same path.
   */
  std::string hostCacheDir;
  /** Command to run inside the guest. */
  std::string command;
  /** Arguments for the guest command. */
  std::vector<std::string> args;
  /** Working directory inside the guest. */
  std::string cwd;
  /** Extra environment variables for the guest. */
  std::map<std::string, std::string> env;
  /**
   * The guest's Vulkan ICD directory, as the guest sees it.
   *
   * The loader looks for its manifests here, so this is a path inside the
   * guest, not on the host.
   */
  std::string vulkanDriver;
  /**
   * The Vulkan ICD manifest the guest is pinned to, as the guest sees it.
   *
   * `VK_ICD_FILENAMES` names manifest *files*, not the directory that holds
   * them, so a directory would be ignored and the loader would fall back to
   * its own search path. Pinning a manifest is also what makes the guest
   * pick a driver that works: on HarmonyOS the phone's GPU is not reachable
   * from the guest, so the software driver is the one that runs.
   */
  std::string vulkanIcd;
  /** Whether the guest desktop surface should be published. */
  bool desktop = false;
};

struct RuntimeStatus {
  bool installed = false;
  std::string version;
  std::string rootDir;
  long long sizeBytes = 0;
  bool running = false;
  int pid = 0;
  RuntimeMode mode = RuntimeMode::Unsupported;
  /** Last error, empty when the last operation succeeded. */
  std::string lastError;
};

/**
 * Supervisor for the Linux guest session.
 *
 * Owns the child process, the guest log file and the bind list, and keeps
 * the session observable from ArkTS.
 */
class Runtime {
 public:
  static Runtime &Instance();

  /** Probes whether ptrace is available to this process. */
  static bool PtraceAvailable();

  /** Detects which execution mode the sandbox allows. */
  RuntimeMode DetectMode() const;

  /** Starts the guest. Returns false and fills status().lastError on failure. */
  bool Start(const RuntimeOptions &options);

  /** Stops the guest if it is running. */
  bool Stop();

  /** Current state of the runtime and the running session. */
  RuntimeStatus Status() const;

  /**
   * Current state of a runtime rooted at the given directory.
   *
   * The install check only depends on the root directory, so the app can ask
   * whether a runtime is installed before it has ever started one — which is
   * the only way the check can ever say yes.
   *
   * @param rootDir  the guest rootfs to look at, empty for the running one
   * @param measureSize  measure how much space the rootfs takes. Measuring it
   *        walks the whole tree, so it is only done when it is asked for and
   *        never on the thread that drives the UI.
   */
  RuntimeStatus StatusAt(const std::string &rootDir, bool measureSize) const;

  /**
   * Current state of a runtime rooted at the given options.
   *
   * The install check only depends on the root directory, so it can be
   * asked about a rootfs that was just unpacked.
   */
  RuntimeStatus StatusFor(const RuntimeOptions &options) const;

  /** Most recent guest log lines, oldest first. */
  std::vector<std::string> LogTail(size_t maxLines) const;

  /** Builds the argv the guest is launched with, for logging and tests. */
  std::vector<std::string> BuildArgv(const RuntimeOptions &options, RuntimeMode mode) const;

 private:
  Runtime() = default;

  mutable pid_t pid_ = -1;
  mutable std::string lastError_;
  mutable std::string logPath_;
  RuntimeOptions options_;
  RuntimeMode mode_ = RuntimeMode::Unsupported;

  /** Host paths that are exposed to the guest, as proot bind specs. */
  std::vector<std::string> BuildBinds(const RuntimeOptions &options) const;

  /** Writes the guest environment for the given options. */
  std::map<std::string, std::string> BuildEnvironment(const RuntimeOptions &options) const;
};

}  // namespace ddk

#endif  // DDK_RUNTIME_H
