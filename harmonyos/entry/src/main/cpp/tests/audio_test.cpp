// Host-side tests for the guest audio ring.
//
// The ring has to survive the guest's PCM arriving faster than it is played,
// and the read that fills it must never block the thread that drives the UI.
//
// These tests pin both:
//
//   * the read returns straight away when the guest has nothing to say, so
//     the UI thread is never parked in the socket;
//   * the frames come out in the order they went in, even after the ring has
//     wrapped several times.
//
// They are plain C++ and run on the build machine, so no device is needed:
//
//   tools/test_native_host.sh

#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "audio/audio.h"

namespace {

int g_failures = 0;

void Check(bool condition, const std::string &what) {
  if (!condition) {
    std::printf("FAIL: %s\n", what.c_str());
    g_failures++;
  }
}

/** How many frames the guest sends. The ring holds a quarter of that. */
const size_t kFrames = 20000;

/** How long the guest waits before it says anything. */
const int kSilenceSeconds = 2;

std::string SocketPath() {
  char pattern[] = "/tmp/ddk-audio-XXXXXX";
  const char *directory = mkdtemp(pattern);
  return std::string(directory == nullptr ? "/tmp" : directory) + "/audio.sock";
}

/** The guest side: accept, wait, then send the frames. */
[[noreturn]] void Guest(int listener) {
  const int connection = accept(listener, nullptr, nullptr);
  if (connection < 0) {
    _exit(1);
  }
  sleep(kSilenceSeconds);

  std::vector<float> frame(2);
  for (size_t index = 0; index < kFrames; ++index) {
    frame[0] = static_cast<float>(index);
    frame[1] = -static_cast<float>(index);
    const char *bytes = reinterpret_cast<const char *>(frame.data());
    // One interleaved stereo frame is two floats. The size of the vector
    // that holds the frame is not the size of the frame.
    const size_t frameBytes = frame.size() * sizeof(float);
    size_t written = 0;
    while (written < frameBytes) {
      const ssize_t got = write(connection, bytes + written, frameBytes - written);
      if (got <= 0) {
        close(connection);
        _exit(0);
      }
      written += static_cast<size_t>(got);
    }
  }
  close(connection);
  _exit(0);
}

}  // namespace

int main() {
  const std::string path = SocketPath();

  const int listener = socket(AF_UNIX, SOCK_STREAM, 0);
  if (listener < 0) {
    std::printf("FAIL: cannot create the socket\n");
    return 1;
  }
  struct sockaddr_un address;
  std::memset(&address, 0, sizeof(address));
  address.sun_family = AF_UNIX;
  std::strncpy(address.sun_path, path.c_str(), sizeof(address.sun_path) - 1);
  if (bind(listener, reinterpret_cast<struct sockaddr *>(&address), sizeof(address)) != 0 ||
      listen(listener, 4) != 0) {
    std::printf("FAIL: cannot listen on the socket\n");
    return 1;
  }

  const pid_t guest = fork();
  if (guest < 0) {
    std::printf("FAIL: cannot fork the guest\n");
    return 1;
  }
  if (guest == 0) {
    Guest(listener);
  }
  close(listener);

  ddk::AudioSink &sink = ddk::AudioSink::Instance();
  Check(sink.Open(path), "the sink connects to the guest's socket");

  std::vector<float> frames(2 * 1024);

  // The guest says nothing for a while. The read has to come back empty
  // rather than wait for it: this read runs on the thread that drives the
  // UI, and parking it there freezes the app.
  const auto before = std::chrono::steady_clock::now();
  const size_t idle = sink.Read(frames.data(), 1024);
  const auto after = std::chrono::steady_clock::now();
  const long waited = std::chrono::duration_cast<std::chrono::milliseconds>(after - before).count();
  Check(idle == 0, "a read with nothing to play returns nothing");
  Check(waited < 500, "the read does not wait for the guest");

  // Now the frames arrive. They have to come out in the order they went in,
  // even though the ring wraps several times on the way.
  size_t received = 0;
  bool ordered = true;
  size_t wrong = 0;
  while (received < kFrames) {
    const size_t got = sink.Read(frames.data(), 1024);
    if (got == 0) {
      if (!sink.IsOpen()) {
        break;  // the guest closed the stream
      }
      usleep(1000);
      continue;
    }
    for (size_t index = 0; index < got; ++index) {
      const float expected = static_cast<float>(received + index);
      if (frames[index * 2] != expected) {
        ordered = false;
        ++wrong;
      }
    }
    received += got;
  }

  Check(ordered, "the frames come out in the order they went in");
  Check(received == kFrames, "every frame is delivered");
  if (!ordered) {
    std::printf("     %zu frames came out wrong\n", wrong);
  }

  sink.Close();

  int status = 0;
  waitpid(guest, &status, 0);
  unlink(path.c_str());

  if (g_failures != 0) {
    std::printf("%d check(s) failed\n", g_failures);
    return 1;
  }
  std::printf("the audio ring behaves as it should\n");
  return 0;
}
