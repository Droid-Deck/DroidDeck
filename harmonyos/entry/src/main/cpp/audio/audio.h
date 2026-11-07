#ifndef DDK_AUDIO_H
#define DDK_AUDIO_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace ddk {

/**
 * Pumps the guest's PCM stream out of the Linux session.
 *
 * Upstream Android runs a PulseAudio sink (DirectAudio) inside the app and
 * writes to an Android AudioTrack. HarmonyOS has no PulseAudio, and the
 * native audio API in API 26 is builder-based rather than the old
 * OH_AudioRenderer calls, so the port splits the job:
 *
 *   * the guest streams interleaved stereo float frames over a Unix socket;
 *   * this class reads those frames into a ring buffer;
 *   * the ArkTS side feeds them to an `audio.AudioRenderer`, which is the
 *     platform's supported playback path.
 *
 * Keeping the ring buffer in native means the guest's socket never blocks
 * the UI thread.
 */
class AudioSink {
 public:
  static AudioSink &Instance();

  /** Connects to the guest's audio socket. */
  bool Open(const std::string &socketPath);

  void Close();

  bool IsOpen() const;

  /**
   * Copies up to maxFrames interleaved stereo frames into the buffer.
   *
   * @return the number of frames copied, 0 when the stream is idle
   */
  size_t Read(float *frames, size_t maxFrames);

  /** Number of frames currently buffered. */
  size_t Available() const;

  const std::string &LastError() const;

 private:
  AudioSink() = default;

  int fd_ = -1;
  std::string lastError_;

  /** Ring buffer of interleaved stereo frames. */
  static constexpr size_t kCapacityFrames = 8192;
  std::vector<float> buffer_;
  size_t readIndex_ = 0;
  size_t writeIndex_ = 0;
  size_t count_ = 0;
};

}  // namespace ddk

#endif  // DDK_AUDIO_H
