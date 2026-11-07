// Host-side test for the guest's input ring.
//
// The ring is the contract between this app and the library upstream
// preloads into the guest's Steam client. Nothing in the port checks that
// contract at runtime, and a single wrong offset makes the guest's pad
// silently dead. So the layout is pinned here, byte for byte, against
// upstream's own constants.
//
// The references are:
//   app/src/main/java/com/droiddeck/launcher/input/FakeInputWriter.java
//   app/src/main/cpp/fakeinput_steam.cpp
//
//   tools/test_native_host.sh

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "input/ring_writer.h"

namespace {

int g_failures = 0;

void Check(bool condition, const std::string &what) {
  if (!condition) {
    std::printf("FAIL: %s\n", what.c_str());
    g_failures++;
  }
}

template <typename T>
T Load(const uint8_t *base, size_t offset) {
  T value;
  std::memcpy(&value, base + offset, sizeof(T));
  return value;
}

/** Reads the ring file the writer produced, the way the guest would. */
struct Ring {
  std::vector<uint8_t> bytes;

  explicit Ring(const std::string &path) {
    bytes.resize(ddk::RingWriter::kFileSize);
    const int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) {
      return;
    }
    if (read(fd, bytes.data(), bytes.size()) != static_cast<ssize_t>(bytes.size())) {
      bytes.clear();
    }
    close(fd);
  }

  bool valid() const {
    return bytes.size() == ddk::RingWriter::kFileSize;
  }

  uint32_t magic() const { return Load<uint32_t>(bytes.data(), 0); }
  uint32_t version() const { return Load<uint32_t>(bytes.data(), 4); }
  uint32_t eventSize() const { return Load<uint32_t>(bytes.data(), 8); }
  uint32_t capacity() const { return Load<uint32_t>(bytes.data(), 12); }
  uint64_t writeSeq() const { return Load<uint64_t>(bytes.data(), 16); }
  uint64_t snapshotSeq() const { return Load<uint64_t>(bytes.data(), 32); }
  uint32_t snapshotButtons() const { return Load<uint32_t>(bytes.data(), 40); }
  int16_t axis(size_t index) const {
    return Load<int16_t>(bytes.data(), 44 + index * 2);
  }

  /** The event at a slot, as upstream's RingEvent. */
  struct Event {
    int64_t sec;
    int64_t usec;
    uint16_t type;
    uint16_t code;
    int32_t value;
  };

  Event event(uint64_t index) const {
    const uint8_t *slot = bytes.data() + 64 + index * 24;
    Event value;
    value.sec = Load<int64_t>(slot, 0);
    value.usec = Load<int64_t>(slot, 8);
    value.type = Load<uint16_t>(slot, 16);
    value.code = Load<uint16_t>(slot, 18);
    value.value = Load<int32_t>(slot, 20);
    return value;
  }
};

constexpr uint16_t kEvSyn = 0x00;
constexpr uint16_t kEvKey = 0x01;
constexpr uint16_t kEvAbs = 0x03;
constexpr uint16_t kEvMsc = 0x04;
constexpr uint16_t kMscScan = 0x04;
constexpr uint16_t kSynReport = 0x00;

constexpr uint16_t kBtnA = 0x130;
constexpr uint16_t kAbsX = 0x00;
constexpr uint16_t kAbsGas = 0x09;

constexpr size_t kImuOffset = 98368;
constexpr size_t kTritonOffset = 98368 + 64;

void TestHeaderMatchesUpstream() {
  const std::string path = "/tmp/droiddeck-ring-test/header.bin";
  ddk::RingWriter &writer = ddk::RingWriter::Instance();
  Check(writer.Open(path), "the ring opens");
  writer.Close();

  Ring ring(path);
  Check(ring.valid(), "the ring has the size the guest expects");
  Check(ring.magic() == 0x46494252, "the magic is upstream's");
  Check(ring.version() == 2, "the version is upstream's");
  Check(ring.eventSize() == 24, "the event size is upstream's");
  Check(ring.capacity() == 4096, "the capacity is upstream's");
  Check(ring.writeSeq() == 0, "the write sequence starts at zero");
  Check(Load<uint32_t>(ring.bytes.data(), kImuOffset) == 0x31554D49,
        "the imu block carries upstream's magic");
  Check(Load<uint32_t>(ring.bytes.data(), kTritonOffset) == 0x4e525454,
        "the triton block carries upstream's magic");
  // Upstream's pad type: the steam pad, marked as set.
  Check(Load<uint32_t>(ring.bytes.data(), kImuOffset + 44) == (0x80000000u | 1u),
        "the pad type names the steam pad");
}

void TestButtonWritesUpstreamsEvents() {
  const std::string path = "/tmp/droiddeck-ring-test/button.bin";
  ddk::RingWriter &writer = ddk::RingWriter::Instance();
  Check(writer.Open(path), "the ring opens");
  writer.Write("button", kBtnA, 1);
  writer.Close();

  Ring ring(path);
  Check(ring.valid(), "the ring has the size the guest expects");
  // Upstream sends the scan code, the key and the report.
  Check(ring.writeSeq() == 3, "a button is three events");

  const Ring::Event scan = ring.event(0);
  Check(scan.type == kEvMsc && scan.code == kMscScan,
        "the first event is the scan code");
  const Ring::Event key = ring.event(1);
  Check(key.type == kEvKey && key.code == kBtnA && key.value == 1,
        "the second event is the key");
  const Ring::Event report = ring.event(2);
  Check(report.type == kEvSyn && report.code == kSynReport,
        "the third event is the report");

  // The snapshot carries the button state.
  Check(ring.snapshotButtons() == 1, "the snapshot records the button");
  Check(ring.snapshotSeq() == 2, "the snapshot sequence advanced twice");
}

void TestAxisWritesUpstreamsEvents() {
  const std::string path = "/tmp/droiddeck-ring-test/axis.bin";
  ddk::RingWriter &writer = ddk::RingWriter::Instance();
  Check(writer.Open(path), "the ring opens");
  writer.Write("axis", kAbsX, 16000);
  writer.Write("axis", kAbsGas, 255);
  writer.Close();

  Ring ring(path);
  Check(ring.valid(), "the ring has the size the guest expects");
  // An axis is one event plus the report.
  Check(ring.writeSeq() == 4, "two axes are four events");

  const Ring::Event axis = ring.event(0);
  Check(axis.type == kEvAbs && axis.code == kAbsX && axis.value == 16000,
        "the first event is the axis");
  const Ring::Event trigger = ring.event(2);
  Check(trigger.type == kEvAbs && trigger.code == kAbsGas,
        "the third event is the trigger");

  // The snapshot keeps the axes in upstream's order.
  Check(ring.axis(0) == 16000, "the snapshot keeps the left stick");
  Check(ring.axis(4) == 255, "the snapshot keeps the right trigger");
}

void TestTheSequenceWrapsAroundTheRing() {
  const std::string path = "/tmp/droiddeck-ring-test/wrap.bin";
  ddk::RingWriter &writer = ddk::RingWriter::Instance();
  Check(writer.Open(path), "the ring opens");
  for (int count = 0; count < 4098; ++count) {
    writer.Write("button", kBtnA, count % 2);
  }
  writer.Close();

  Ring ring(path);
  Check(ring.valid(), "the ring has the size the guest expects");
  // The sequence keeps counting; the slot is the sequence modulo the
  // capacity.
  Check(ring.writeSeq() == 4098 * 3, "the sequence counts every event");
  const Ring::Event wrapped = ring.event(4098 % 4096);
  Check(wrapped.type == kEvMsc, "the ring wrapped to the start");
}

}  // namespace

int main() {
  // The ring's directory has to exist before the writer creates the file.
  if (mkdir("/tmp/droiddeck-ring-test", 0755) != 0) {
    std::printf("the ring test directory could not be created\n");
    return 2;
  }

  TestHeaderMatchesUpstream();
  TestButtonWritesUpstreamsEvents();
  TestAxisWritesUpstreamsEvents();
  TestTheSequenceWrapsAroundTheRing();

  if (g_failures == 0) {
    std::printf("ring writer tests: all checks passed\n");
    return 0;
  }
  std::printf("ring writer tests: %d check(s) failed\n", g_failures);
  return 1;
}
