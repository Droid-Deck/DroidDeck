#include "audio/pulse_server.h"

#include <fcntl.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <vector>

#include "ddk_log.h"

namespace ddk {
namespace {

/** The name the HAP's libraries are unpacked under. */
constexpr const char *kLibraryDir = "libs/arm64-v8a";

/** The daemon, which upstream runs from its shared library form. */
constexpr const char *kDaemon = "libpulseaudio.so";

/** The sink module the port ships. */
constexpr const char *kSinkModule = "module-droiddeck-sink.so";

}  // namespace

PulseServer &PulseServer::Instance() {
  static PulseServer instance;
  return instance;
}

std::string PulseServer::AudioSocketPath(const std::string &runtimeDir) const {
  return runtimeDir + "/droiddeck-audio.sock";
}

std::string PulseServer::GuestServerAddress(const std::string &runtimeDir) const {
  // This is what PULSE_SERVER wants: a "unix:" address, not a path.
  return "unix:" + runtimeDir + "/pulse/native";
}

bool PulseServer::WriteConfig(const std::string &runtimeDir, std::string *error) const {
  // The server listens for the guest's Pulse clients here...
  const std::string protocolSocket = runtimeDir + "/pulse/native";
  // ...and the sink module writes the audio out here.
  const std::string audioSocket = AudioSocketPath(runtimeDir);

  std::string config;
  config += "load-module module-native-protocol-unix auth-anonymous=1 "
            "auth-cookie-enabled=0 socket=\"" + protocolSocket + "\"\n";
  config += ".nofail\n";
  config += "load-module " + std::string(kSinkModule) +
            " sink_name=DroidDeckSink socket=\"" + audioSocket + "\"\n";
  config += "set-default-sink DroidDeckSink\n";
  config += ".fail\n";

  // The protocol socket lives in a directory of its own, the way PulseAudio
  // expects.
  const std::string pulseDir = runtimeDir + "/pulse";
  if (mkdir(pulseDir.c_str(), 0755) != 0 && errno != EEXIST) {
    *error = std::string("cannot create ") + pulseDir + ": " + std::strerror(errno);
    return false;
  }

  const std::string configPath = runtimeDir + "/default.pa";
  std::ofstream file(configPath, std::ios::binary | std::ios::trunc);
  if (!file.good()) {
    *error = "cannot write " + configPath;
    return false;
  }
  file << config;
  file.close();
  return true;
}

bool PulseServer::Start(const std::string &runtimeDir, const std::string &binaryDir,
                        const std::string &logPath) {
  if (pid_ > 0) {
    return true;
  }
  if (runtimeDir.empty()) {
    lastError_ = "no runtime directory given";
    return false;
  }

  std::string error;
  if (!WriteConfig(runtimeDir, &error)) {
    lastError_ = error;
    DDK_LOGE("%{public}s", lastError_.c_str());
    return false;
  }

  // The daemon and the modules are in the HAP's library directory, and
  // the loader has to find the libraries the daemon needs.
  const std::string daemon = binaryDir + "/" + kDaemon;
  if (access(daemon.c_str(), X_OK) != 0) {
    lastError_ = "the PulseAudio daemon is missing: " + daemon;
    DDK_LOGE("%{public}s", lastError_.c_str());
    return false;
  }

  const std::string libraryPath = binaryDir + ":" + runtimeDir;
  const std::string configPath = runtimeDir + "/default.pa";

  std::vector<std::string> args = {
      daemon,
      "--system=false",
      "--disable-shm=true",
      "--fail=false",
      "-n",
      "--file=" + configPath,
      "--daemonize=false",
      "--use-pid-file=false",
      "--exit-idle-time=-1",
      "--log-level=info",
      // The modules are loaded by name, and the daemon has to be told
      // where they are: the build's own path does not exist on a device.
      "--dl-search-path=" + binaryDir,
  };

  std::vector<char *> raw;
  raw.reserve(args.size() + 1);
  for (std::string &item : args) {
    raw.push_back(item.data());
  }
  raw.push_back(nullptr);

  pid_t child = fork();
  if (child < 0) {
    lastError_ = std::string("cannot fork: ") + std::strerror(errno);
    DDK_LOGE("%{public}s", lastError_.c_str());
    return false;
  }

  if (child == 0) {
    setsid();

    if (!logPath.empty()) {
      const int log = open(logPath.c_str(), O_CREAT | O_WRONLY | O_APPEND, 0640);
      if (log >= 0) {
        dup2(log, STDOUT_FILENO);
        dup2(log, STDERR_FILENO);
        if (log > STDERR_FILENO) {
          close(log);
        }
      }
    }
    const int devnull = open("/dev/null", O_RDONLY);
    if (devnull >= 0) {
      dup2(devnull, STDIN_FILENO);
      if (devnull > STDERR_FILENO) {
        close(devnull);
      }
    }

    setenv("HOME", runtimeDir.c_str(), 1);
    setenv("TMPDIR", runtimeDir.c_str(), 1);
    setenv("LD_LIBRARY_PATH", libraryPath.c_str(), 1);
    // The sink module is loaded by name, so the daemon has to find it.
    setenv("PULSE_LATENCY_MSEC", "60", 1);

    execv(raw[0], raw.data());
    _exit(127);
  }

  pid_ = child;
  lastError_.clear();
  DDK_LOGI("the PulseAudio server started, pid=%{public}d", static_cast<int>(child));
  return true;
}

void PulseServer::Stop() {
  if (pid_ <= 0) {
    return;
  }
  kill(-pid_, SIGTERM);
  kill(pid_, SIGTERM);

  for (int attempt = 0; attempt < 30; ++attempt) {
    int status = 0;
    const pid_t done = waitpid(pid_, &status, WNOHANG);
    if (done == pid_ || (done < 0 && errno == ECHILD)) {
      pid_ = -1;
      DDK_LOGI("the PulseAudio server stopped");
      return;
    }
    usleep(100000);
  }

  kill(-pid_, SIGKILL);
  kill(pid_, SIGKILL);
  int status = 0;
  waitpid(pid_, &status, 0);
  pid_ = -1;
  DDK_LOGI("the PulseAudio server killed");
}

bool PulseServer::IsRunning() const {
  return pid_ > 0;
}

const std::string &PulseServer::LastError() const {
  return lastError_;
}

}  // namespace ddk
