#ifndef DDK_PULSE_SERVER_H
#define DDK_PULSE_SERVER_H

#include <string>

namespace ddk {

/**
 * Runs the PulseAudio server the guest's audio client connects to.
 *
 * Upstream DroidDeck runs a PulseAudio *server* on the host: the guest's
 * Steam client is told where it is with PULSE_SERVER, and the server's
 * sink module writes the audio to the phone's audio HAL. The port keeps
 * that architecture and replaces only the last step — the sink module
 * writes to a socket the native audio sink reads.
 *
 * So there are two sockets:
 *
 *   <runtime dir>/pulse/native      the server's Pulse protocol socket,
 *                                    which the guest connects to
 *   <runtime dir>/droiddeck-audio.sock   the sink module's output, which
 *                                    the native audio sink connects to
 */
class PulseServer {
 public:
  static PulseServer &Instance();

  /**
   * Starts the server.
   *
   * @param runtimeDir the directory the sockets and the config live in
   * @param binaryDir the directory the HAP's libraries were unpacked into
   * @param logPath where the server's output is written
   * @return true when the server was started
   */
  bool Start(const std::string &runtimeDir, const std::string &binaryDir,
             const std::string &logPath);

  /** Stops the server. */
  void Stop();

  bool IsRunning() const;

  /** The socket the guest connects to, as PULSE_SERVER wants it. */
  std::string GuestServerAddress(const std::string &runtimeDir) const;

  /** The socket the sink module writes the audio to. */
  std::string AudioSocketPath(const std::string &runtimeDir) const;

  const std::string &LastError() const;

 private:
  PulseServer() = default;

  pid_t pid_ = -1;
  std::string lastError_;

  /** Writes the server's configuration file. */
  bool WriteConfig(const std::string &runtimeDir, std::string *error) const;
};

}  // namespace ddk

#endif  // DDK_PULSE_SERVER_H
