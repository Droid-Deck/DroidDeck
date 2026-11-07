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
  /** Bytes in one interleaved stereo frame. */
  static constexpr size_t kFrameBytes = 2 * sizeof(float);
  std::vector<float> buffer_;
  size_t readIndex_ = 0;
  size_t writeIndex_ = 0;
  size_t count_ = 0;

  /**
   * Bytes read from the socket that are not yet a whole frame.
   *
   * The socket is a byte stream, so a read can end in the middle of a frame.
   * The leftover bytes are carried over into the next read, so the frames
   * stay aligned — without that, every frame after the first split one is
   * read half a frame late.
   */
  std::vector<char> staging_;
  size_t stagingStart_ = 0;
  size_t stagingEnd_ = 0;
  /** How many bytes the staging buffer holds. */
  static constexpr size_t kStagingBytes = 64 * 1024;
};

}  // namespace ddk

#endif  // DDK_AUDIO_H
