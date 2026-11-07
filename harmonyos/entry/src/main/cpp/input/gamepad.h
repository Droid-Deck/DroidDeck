#ifndef DDK_GAMEPAD_H
#define DDK_GAMEPAD_H

#include <string>

namespace ddk {

/**
 * Virtual gamepad exposed to the Linux guest.
 *
 * Upstream Android writes the controller state into a uinput device that
 * the guest reads. HarmonyOS exposes no /dev/uinput to app sandboxes, so
 * this class reports whether the device could be created; when it cannot,
 * the guest falls back to reading controller events over the runtime's
 * control socket instead.
 */
class Gamepad {
 public:
  static Gamepad &Instance();

  /** Creates the uinput device. Returns false when the sandbox denies it. */
  bool Open();

  void Close();

  bool IsOpen() const;

  /**
   * Forwards one event to the guest.
   *
   * @param kind 'button' | 'axis' | 'hat'
   * @param code Linux input code (BTN_*, ABS_*)
   * @param value 0/1 for buttons, -32768..32767 for axes
   */
  void Event(const std::string &kind, int code, int value);

  /** Human readable reason the device could not be opened. */
  const std::string &LastError() const;

 private:
  Gamepad() = default;

  int fd_ = -1;
  std::string lastError_;
};

}  // namespace ddk

#endif  // DDK_GAMEPAD_H
