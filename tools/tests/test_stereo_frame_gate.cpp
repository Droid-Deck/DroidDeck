#include "../../app/src/main/cpp/viture/stereo_frame_gate.h"
#include <cassert>
#include <cstdio>
#include <initializer_list>
#include <limits>

int main() {
    StereoFrameGate gate;
    gate.SetHalfRate(true);
    int copies = 0;
    for (int i = 0; i < 250; ++i) {
        const bool accepted = gate.Accept(1 + i*.04);
        assert(accepted == (i%2 == 0));
        copies += accepted;
    }
    assert(copies == 125 && gate.received == 250 && gate.skipped == 125);
    gate.SetHalfRate(false);
    for (int i = 0; i < 25; ++i) assert(gate.Accept(20 + i*.04));
    assert(gate.received == 275 && gate.skipped == 125);
    gate.SetHalfRate(true);
    assert(gate.Accept(30));
    gate.SetHalfRate(true);
    assert(!gate.Accept(30.04));
    assert(gate.Accept(30.08));
    assert(gate.Accept(1));
    assert(!gate.Accept(1.04));
    const auto count = gate.received;
    assert(!gate.Accept(std::numeric_limits<double>::quiet_NaN()));
    assert(!gate.Accept(0));
    assert(gate.received == count);
    gate = {};
    gate.SetHalfRate(true);
    assert(gate.Accept(1) && gate.received == 1 && gate.skipped == 0);
    for (int rate : {500, 600, 800, 1000}) {
        gate = {};
        gate.SetRatePermille(rate);
        copies = 0;
        int previousCopy = 0;
        for (int i = 0; i < 250; ++i) {
            gate.SetRatePermille(rate);
            const bool accepted = gate.Accept(1 + i*.04);
            if (i == 0) assert(accepted);
            if (accepted) {
                assert(i-previousCopy <= 2);
                previousCopy = i;
                ++copies;
            }
        }
        assert(copies == 250*rate/1000);
        assert(gate.received == 250 && gate.skipped == uint64_t(250-copies));
        assert(gate.Accept(.5));
    }
    gate = {};
    for (int rate : {500, 600, 800, 1000, 800, 600, 500}) {
        gate.SetRatePermille(rate);
        assert(gate.Accept(1 + gate.received*.04));
    }
    for (int invalid : {-1, 0, 499, 700, 1001}) {
        gate = {};
        gate.SetRatePermille(invalid);
        assert(gate.Accept(1));
        assert(!gate.Accept(1.04));
        assert(gate.Accept(1.08));
    }
    puts("PASS: fractional stereo copies, stable phase, full-rate setup, source resets, invalid settings and reconnect reset");
}
