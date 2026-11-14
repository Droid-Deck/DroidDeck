#ifndef DDK_RING_WRITER_H
#define DDK_RING_WRITER_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace ddk {

/**
 * Writes the guest's controller events into the ring buffer the guest reads.
 *
 * Upstream DroidDeck does not use /dev/uinput. HarmonyOS does not give an
 * app that device node, and upstream's own design already works around it:
 * the host writes evdev events into a shared ring, and a library preloaded
 * into the guest's Steam client reads the ring and hands the events to
 * Steam's input API.
 *
 * This class is the host's half of that. The format is upstream's, byte for
 * byte, because the guest's library is upstream's:
 *
 *   header        64 bytes, at the start of the file
 *   events        4096 slots of 24 bytes, after the header
 *   imu block     64 bytes, after the events
 *   triton block 256 bytes, after the imu block
 *
 * The file is shared between the two sides through the runtime directory,
 * which is bound into the guest as /run/droiddeck.
 */
class RingWriter {
 public:
  static RingWriter &Instance();

  /**
   * Creates the ring file and prepares its header.
   *
   * @param path where the ring lives, in the host's runtime directory
   */
  bool Open(const std::string &path);

  void Close();

  bool IsOpen() const;

  /**
   * Stages one controller event and publishes it.
   *
   * @param kind 'button', 'axis' or 'hat'
   * @param code the evdev code
   * @param value 0 or 1 for a button, -32768..32767 for an axis
   */
  void Write(const std::string &kind, int code, int value);

  /** The path the guest is told to read. */
  const std::string &Path() const;

  const std::string &LastError() const;

  // --- the format, exposed so the tests can check it ---

  /** The size of the ring file. */
  static constexpr size_t kFileSize = 98688;
  /** The size of the header. */
  static constexpr size_t kHeaderSize = 64;
  /** The size of one event. */
  static constexpr size_t kEventSize = 24;
  /** How many events the ring holds. */
  static constexpr uint32_t kCapacity = 4096;
  /** The magic the guest checks. */
  static constexpr uint32_t kMagic = 0x46494252;
  /** The format version the guest checks. */
  static constexpr uint32_t kVersion = 2;

 private:
  RingWriter() = default;

  /** Copies the staged events into the ring and advances the sequence. */
  void Publish();

  int fd_ = -1;
  uint8_t *data_ = nullptr;
  std::string path_;
  std::string lastError_;

  /** The events waiting to be published. */
  struct Event {
    int64_t sec;
    int64_t usec;
    uint16_t type;
    uint16_t code;
    int32_t value;
  };
  std::vector<Event> staged_;

  /** The button and axis states the snapshot carries. */
  bool buttons_[11] = {false};
  int32_t axes_[8] = {0, 0, 0, 0, 0, 0, 0, 0};
};

}  // namespace ddk

#endif  // DDK_RING_WRITER_H
