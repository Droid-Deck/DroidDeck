#include "runtime/runtime.h"

#include <dirent.h>
#include <fcntl.h>
#include <ftw.h>
#include <signal.h>
#include <sys/ptrace.h>
#include <sys/stat.h>

// PTRACE_TRACEME is 0 on both Linux and the Darwin hosts the unit tests
// run on, but macOS does not always export it.
#ifndef PTRACE_TRACEME
#define PTRACE_TRACEME 0
#endif
#include <sys/statvfs.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>

#include "ddk_log.h"
#include "runtime/install.h"
#include "runtime/unpack.h"

extern char **environ;

namespace ddk {
namespace {

constexpr const char *kGuestRuntimeDir = "/run/droiddeck";
constexpr const char *kHostDir = "/host";

/**
 * The session scripts the runtime may provide.
 *
 * Upstream renamed its session script from `bannerlator-session` to
 * `droiddeck-session`; both are accepted so an older runtime still works.
 */
const char *const kSessionScripts[] = {
    "/usr/local/bin/droiddeck-session",
    "/usr/local/bin/bannerlator-session",
};

/**
 * The guest path, matching upstream's LinuxRuntime.GUEST_PATH.
 */
constexpr const char *kGuestPath =
    "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games";

bool IsDirectory(const std::string &path) {
  struct stat info;
  return stat(path.c_str(), &info) == 0 && S_ISDIR(info.st_mode);
}

bool IsFile(const std::string &path) {
  struct stat info;
  return stat(path.c_str(), &info) == 0 && S_ISREG(info.st_mode);
}

/**
 * The interpreter a script asks for in its "#!" line.
 *
 * Direct mode hands the guest command to the guest's dynamic loader, and a
 * loader can only run an ELF file. The guest's session command is a shell
 * script, so it has to be run by the interpreter the script names, taken
 * out of the rootfs. Returns an empty string when the file is not a script
 * or cannot be read.
 */
std::string ScriptInterpreter(const std::string &path) {
  std::ifstream file(path, std::ios::binary);
  if (!file.good()) {
    return std::string();
  }
  std::string line;
  if (!std::getline(file, line) || line.rfind("#!", 0) != 0) {
    return std::string();
  }

  std::string rest = line.substr(2);
  const size_t start = rest.find_first_not_of(" \t");
  if (start == std::string::npos) {
    return std::string();
  }
  rest = rest.substr(start);
  const size_t end = rest.find_first_of(" \t");
  std::string interpreter = rest.substr(0, end);

  // "#!/usr/bin/env bash" asks for bash by name rather than by path.
  if (interpreter == "/usr/bin/env" || interpreter == "/bin/env") {
    const std::string arguments = end == std::string::npos ? std::string() : rest.substr(end + 1);
    const size_t argumentStart = arguments.find_first_not_of(" \t");
    if (argumentStart == std::string::npos) {
      return std::string();
    }
    const std::string name = arguments.substr(argumentStart).substr(
        0, arguments.substr(argumentStart).find_first_of(" \t"));
    if (name.rfind("/", 0) != 0) {
      return "/bin/" + name;
    }
    return name;
  }
  return interpreter;
}

long long DirectorySize(const std::string &path) {
  long long total = 0;
  DIR *dir = opendir(path.c_str());
  if (dir == nullptr) {
    return 0;
  }
  struct dirent *entry = nullptr;
  while ((entry = readdir(dir)) != nullptr) {
    if (std::strcmp(entry->d_name, ".") == 0 || std::strcmp(entry->d_name, "..") == 0) {
      continue;
    }
    std::string child = path + "/" + entry->d_name;
    struct stat info;
    if (lstat(child.c_str(), &info) != 0) {
      continue;
    }
    if (S_ISDIR(info.st_mode)) {
      total += DirectorySize(child);
    } else if (S_ISREG(info.st_mode)) {
      total += static_cast<long long>(info.st_size);
    }
  }
  closedir(dir);
  return total;
}

}  // namespace

const char *RuntimeModeName(RuntimeMode mode) {
  switch (mode) {
    case RuntimeMode::Proot:
      return "proot";
    case RuntimeMode::Direct:
      return "direct";
    default:
      return "unsupported";
  }
}

Runtime &Runtime::Instance() {
  static Runtime instance;
  return instance;
}

bool Runtime::PtraceAvailable() {
  // A child that can install a ptrace trace is a child proot can drive.
  pid_t child = fork();
  if (child < 0) {
    return false;
  }
  if (child == 0) {
    // The last argument is an int on Darwin and a void* on Linux; 0 works
    // for both.
    if (ptrace(PTRACE_TRACEME, 0, nullptr, 0) != 0) {
      _exit(1);
    }
    _exit(0);
  }
  int status = 0;
  waitpid(child, &status, 0);
  return WIFEXITED(status) && WEXITSTATUS(status) == 0;
}

RuntimeMode Runtime::DetectMode() const {
  if (PtraceAvailable()) {
    // proot needs the patched binary from the addon pack. Without it there
    // is no path rewriting, so the guest is exec'd directly.
    const std::string proot = options_.rootDir + kHostDir + "/proot";
    if (access(proot.c_str(), F_OK) == 0) {
      return RuntimeMode::Proot;
    }
    DDK_LOGW("proot is not installed in the runtime; falling back to a direct launch");
  }
  return RuntimeMode::Direct;
}

std::vector<std::string> Runtime::BuildBinds(const RuntimeOptions &options) const {
  // Mirrors upstream's LinuxRuntime.binds(), minus the Android-only paths.
  std::vector<std::string> binds;
  binds.push_back("/dev");
  binds.push_back("/proc");
  binds.push_back("/sys");
  binds.push_back("/proc/self/fd:/dev/fd");
  binds.push_back("/proc/self/fd/0:/dev/stdin");
  binds.push_back("/proc/self/fd/1:/dev/stdout");
  binds.push_back("/proc/self/fd/2:/dev/stderr");
  binds.push_back(options.rootDir + "/etc/droiddeck/empty:/sys/fs/selinux");
  // A kernel that refuses to list /sys/class/thermal makes mangoapp abort at
  // start, so an empty directory is bound over it instead.
  if (access("/sys/class/thermal", R_OK) != 0) {
    binds.push_back(options.rootDir + "/etc/droiddeck/empty:/sys/class/thermal");
  }
  binds.push_back(options.rootDir);

  // The app's own directories are bound at the same absolute path, the way
  // upstream binds them. That is what lets the guest write the session log
  // to the path the launcher reads it from.
  if (!options.hostFilesDir.empty()) {
    binds.push_back(options.hostFilesDir);
  }
  if (!options.hostCacheDir.empty()) {
    binds.push_back(options.hostCacheDir);
  }

  // The guest sees the compositor's socket directory at /run/droiddeck, the
  // path upstream's guest session expects.
  if (!options.hostRuntimeDir.empty()) {
    binds.push_back(options.hostRuntimeDir + ":" + kGuestRuntimeDir);
  }

  // The app sandbox hides most of /proc/sys from the guest, so the runtime
  // ships copies of the files the guest reads and those are bound over the
  // unreadable paths. This mirrors upstream's fake procfs handling.
  const char *const fakeProcFiles[] = {
      "pci_devices",  "stat",         "version",        "loadavg",
      "uptime",       "vmstat",       "cap_last_cap",   "overflowuid",
      "overflowgid",  "file-max",     "overcommit_memory",
  };
  const char *const fakeProcPaths[] = {
      "/proc/bus/pci/devices",   "/proc/stat",               "/proc/version",
      "/proc/loadavg",           "/proc/uptime",              "/proc/vmstat",
      "/proc/sys/kernel/cap_last_cap", "/proc/sys/kernel/overflowuid",
      "/proc/sys/kernel/overflowgid",  "/proc/sys/fs/file-max",
      "/proc/sys/vm/overcommit_memory",
  };
  const size_t fakeProcCount = sizeof(fakeProcFiles) / sizeof(fakeProcFiles[0]);
  for (size_t index = 0; index < fakeProcCount; ++index) {
    const std::string fake = options.rootDir + "/etc/droiddeck/proc/" + fakeProcFiles[index];
    if (!IsFile(fake)) {
      continue;
    }
    if (access(fakeProcPaths[index], R_OK) == 0) {
      // The host's own copy is readable, so the real one is used.
      continue;
    }
    binds.push_back(fake + ":" + fakeProcPaths[index]);
  }

  // The Vulkan ICD directory is inside the rootfs, which is the guest's /,
  // so the loader finds it there. Binding the host's copy of the same path
  // would only create a path that does not exist in the guest.
  return binds;
}

std::map<std::string, std::string> Runtime::BuildEnvironment(const RuntimeOptions &options) const {
  std::map<std::string, std::string> env;
  env["HOME"] = "/root";
  env["USER"] = "root";
  env["LOGNAME"] = "root";
  env["SHELL"] = "/bin/bash";
  env["PATH"] = kGuestPath;
  env["TERM"] = "xterm-256color";
  env["LANG"] = "C.UTF-8";
  env["XDG_DATA_HOME"] = "/root/.local/share";
  env["XDG_DATA_DIRS"] =
      "/root/.local/share/flatpak/exports/share:/usr/local/share:/usr/share";
  env["PROOT_TMP_DIR"] = "/tmp";
  env["DROIDDECK_HARMONY"] = "1";
  // The compositor's socket lives in the runtime directory, which the guest
  // sees as /run/droiddeck. Without these the guest would never find it.
  if (!options.hostRuntimeDir.empty()) {
    env["XDG_RUNTIME_DIR"] = kGuestRuntimeDir;
    env["WAYLAND_DISPLAY"] = "wayland-0";
  }
  if (!options.vulkanDriver.empty()) {
    env["VK_ICD_FILENAMES"] = options.vulkanIcd.empty() ? options.vulkanDriver : options.vulkanIcd;
    env["VK_DRIVER_FILES"] = env["VK_ICD_FILENAMES"];
  } else if (!options.vulkanIcd.empty()) {
    env["VK_ICD_FILENAMES"] = options.vulkanIcd;
    env["VK_DRIVER_FILES"] = options.vulkanIcd;
  }
  for (const auto &pair : options.env) {
    env[pair.first] = pair.second;
  }
  return env;
}

std::vector<std::string> Runtime::BuildArgv(const RuntimeOptions &options, RuntimeMode mode) const {
  std::vector<std::string> argv;

  if (mode == RuntimeMode::Proot) {
    const std::string root = options.rootDir;
    const std::string proot = root + kHostDir + "/proot";

    argv.push_back(proot);
    argv.push_back("--kill-on-exit");
    argv.push_back("--kernel-release=5.10.0-droiddeck-harmony");
    // Fake root: the guest believes it is uid 0, like upstream's
    // RootSpawner does with proot's -0 flag.
    argv.push_back("-0");
    argv.push_back("-r");
    argv.push_back(root);
    argv.push_back("-w");
    argv.push_back(options.cwd.empty() ? "/root" : options.cwd);

    for (const std::string &bind : BuildBinds(options)) {
      argv.push_back("-b");
      argv.push_back(bind);
    }
    // The guest environment is rebuilt from scratch, so the variables are
    // passed as arguments to `env -i` rather than inherited. Without
    // WAYLAND_DISPLAY the guest would never find the compositor's socket.
    argv.push_back("/usr/bin/env");
    argv.push_back("-i");
    for (const auto &pair : BuildEnvironment(options)) {
      argv.push_back(pair.first + "=" + pair.second);
    }
  }

  // Guest command.
  if (mode == RuntimeMode::Direct) {
    // Without proot there is no path remapping, so the guest's own loader is
    // invoked with the rootfs on its library path.
    const std::string root = options.rootDir;
    // The guest's command is an absolute path inside the rootfs.
    const std::string command = options.command.rfind("/", 0) == 0
                                    ? root + options.command
                                    : root + "/" + options.command;

    argv.push_back(root + "/lib/ld-linux-aarch64.so.1");
    argv.push_back("--library-path");
    argv.push_back(root + "/lib:" + root + "/usr/lib:" + root + "/usr/lib/aarch64-linux-gnu");

    // The loader runs ELF files, and the guest's command is a shell script.
    // A script is run by the interpreter its "#!" line names, and that
    // interpreter has to come out of the rootfs.
    const std::string interpreter = ScriptInterpreter(command);
    std::string shell;
    if (!interpreter.empty()) {
      shell = root + interpreter;
      if (!IsFile(shell)) {
        // The rootfs merges /usr into the top level, so the interpreter a
        // script names as /bin/bash can live at /usr/bin/bash.
        const std::string merged = root + "/usr" + interpreter;
        if (IsFile(merged)) {
          shell = merged;
        } else {
          shell.clear();
        }
      }
    }
    if (!shell.empty()) {
      argv.push_back(shell);
      argv.push_back(command);
      DDK_LOGI("direct mode runs %{public}s with %{public}s",
               options.command.c_str(), shell.c_str());
    } else {
      argv.push_back(command);
    }
  } else {
    argv.push_back(options.command);
  }

  for (const std::string &arg : options.args) {
    argv.push_back(arg);
  }
  return argv;
}

bool Runtime::Start(const RuntimeOptions &options) {
  if (pid_ > 0) {
    lastError_ = "a session is already running";
    return false;
  }
  if (options.rootDir.empty() || !IsDirectory(options.rootDir)) {
    lastError_ = "the Linux runtime is not installed";
    return false;
  }
  if (options.command.empty()) {
    lastError_ = "no guest command given";
    return false;
  }

  options_ = options;
  mode_ = DetectMode();
  if (mode_ == RuntimeMode::Unsupported) {
    lastError_ = "the sandbox does not allow running a Linux guest";
    return false;
  }

  std::vector<std::string> argv = BuildArgv(options_, mode_);
  std::vector<char *> raw;
  raw.reserve(argv.size() + 1);
  for (std::string &item : argv) {
    raw.push_back(item.data());
  }
  raw.push_back(nullptr);

  logPath_ = options_.rootDir + "/../guest-session.log";
  const std::string logPath = logPath_;

  pid_t child = fork();
  if (child < 0) {
    lastError_ = std::string("fork failed: ") + std::strerror(errno);
    return false;
  }

  if (child == 0) {
    // Child: become a session leader so the whole guest tree can be
    // signalled at once, then exec.
    setsid();

    int log = open(logPath.c_str(), O_CREAT | O_WRONLY | O_APPEND, 0640);
    if (log >= 0) {
      dup2(log, STDOUT_FILENO);
      dup2(log, STDERR_FILENO);
      if (log > STDERR_FILENO) {
        close(log);
      }
    }
    int devnull = open("/dev/null", O_RDONLY);
    if (devnull >= 0) {
      dup2(devnull, STDIN_FILENO);
      if (devnull > STDERR_FILENO) {
        close(devnull);
      }
    }

    std::map<std::string, std::string> childEnv = BuildEnvironment(options_);
    for (const auto &pair : childEnv) {
      setenv(pair.first.c_str(), pair.second.c_str(), 1);
    }
    unsetenv("LD_PRELOAD");
    unsetenv("PROOT_FASTPATH");

    execv(raw[0], raw.data());
    // execv only returns on failure.
    _exit(127);
  }

  pid_ = child;
  lastError_.clear();
  DDK_LOGI("session started pid=%{public}d mode=%{public}s", child, RuntimeModeName(mode_));
  return true;
}

bool Runtime::Stop() {
  if (pid_ <= 0) {
    return false;
  }
  // Signal the whole process group so proot and the guest tree go away.
  kill(-pid_, SIGTERM);
  kill(pid_, SIGTERM);

  for (int i = 0; i < 30; ++i) {
    int status = 0;
    pid_t done = waitpid(pid_, &status, WNOHANG);
    if (done == pid_ || (done < 0 && errno == ECHILD)) {
      pid_ = -1;
      DDK_LOGI("session stopped");
      return true;
    }
    usleep(100000);
  }

  kill(-pid_, SIGKILL);
  kill(pid_, SIGKILL);
  int status = 0;
  waitpid(pid_, &status, 0);
  pid_ = -1;
  DDK_LOGI("session killed");
  return true;
}

RuntimeStatus Runtime::Status() const {
  return StatusAt(options_.rootDir, false);
}

RuntimeStatus Runtime::StatusFor(const RuntimeOptions &options) const {
  return StatusAt(options.rootDir, true);
}

RuntimeStatus Runtime::StatusAt(const std::string &rootDir, bool measureSize) const {
  // A guest that exited on its own is reaped here. Without this the session
  // keeps reporting "running" after the guest is gone, and it can never be
  // started again.
  if (pid_ > 0) {
    int exitStatus = 0;
    if (waitpid(pid_, &exitStatus, WNOHANG) == pid_) {
      pid_ = -1;
    }
  }

  RuntimeStatus status;
  status.rootDir = rootDir;
  status.lastError = lastError_;
  status.pid = pid_ > 0 ? pid_ : 0;
  status.running = pid_ > 0;
  status.mode = mode_;

  const std::string root = rootDir;
  if (root.empty()) {
    return status;
  }

  // Upstream marks the runtime installed once gamescope and the session
  // script are present in the rootfs. The session script comes from the
  // overlay, so a runtime without the overlay applied is not usable.
  const bool hasGamescope = IsFile(root + "/usr/bin/gamescope") || IsFile(root + "/usr/games/gamescope");
  bool hasSession = false;
  for (const char *script : kSessionScripts) {
    if (IsFile(root + script)) {
      hasSession = true;
      break;
    }
  }
  status.installed = hasGamescope && hasSession;
  status.version = ReadVersionMarker(root);
  status.sizeBytes = (measureSize && status.installed) ? DirectorySize(root) : 0;
  return status;
}

std::vector<std::string> Runtime::LogTail(size_t maxLines) const {
  std::vector<std::string> lines;
  if (logPath_.empty()) {
    return lines;
  }
  std::ifstream file(logPath_);
  if (!file.good()) {
    return lines;
  }
  std::string line;
  while (std::getline(file, line)) {
    lines.push_back(line);
    if (lines.size() > maxLines * 4) {
      lines.erase(lines.begin(), lines.begin() + lines.size() - maxLines);
    }
  }
  while (lines.size() > maxLines) {
    lines.erase(lines.begin());
  }
  return lines;
}

}  // namespace ddk
