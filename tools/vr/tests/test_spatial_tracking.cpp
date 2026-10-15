#include "spatial_tracking.h"
#include <cassert>
#include <cstddef>
#include <cstdio>
#include <limits>

int main() {
    static_assert(sizeof(droiddeck_spatialpose) == 176);
    static_assert(offsetof(droiddeck_spatialpose, head) == 40);
    static_assert(offsetof(droiddeck_spatialpose, hands) == 80);
    char path[] = "/tmp/droiddeck-spatial-XXXXXX";
    int fd = mkstemp(path); assert(fd >= 0);
    droiddeck_spatialpose out{}, s{};
    s.magic = DROIDDECK_SPATIALPOSE_MAGIC; s.version = DROIDDECK_SPATIALPOSE_VERSION; s.size = sizeof(s);
    s.head = {1000000000LL, {1,2,3}, {0,0,0,1}, DD_SPATIAL_VALID};
    s.hands[0].pose = s.head; s.hands[0].pose.time_ns -= 50000000;
    {
        SpatialFile empty(path); assert(!empty.Read(&out));
        assert(write(fd, &s, sizeof(s)-1) == sizeof(s)-1); assert(!empty.Read(&out));
    }
    assert(pwrite(fd, &s, sizeof(s), 0) == sizeof(s));
    {
        SpatialFile reader(path); assert(reader.Read(&out));
        assert(out.hands[0].pose.time_ns == 950000000);
        s.seq = 1; assert(pwrite(fd, &s, sizeof(s), 0) == sizeof(s)); assert(!reader.Read(&out));
        s.seq = 2; s.version = 99; assert(pwrite(fd, &s, sizeof(s), 0) == sizeof(s)); assert(!reader.Read(&out));
        s.version = 1; s.head.time_ns += 90000000; assert(pwrite(fd, &s, sizeof(s), 0) == sizeof(s)); assert(reader.Read(&out));
        assert(SpatialFresh(out.head, 1100000000)); assert(!SpatialFresh(out.hands[0].pose,1100000000));
    }
    close(fd); unlink(path);
    assert(!SpatialFresh(s.head,1));
    s.head.position[0] = std::numeric_limits<float>::quiet_NaN(); assert(!SpatialFresh(s.head,1100000000));
    auto hand=s.hands[0]; hand.pose.time_ns=1000000000; hand.trigger=1; hand.grip=1;
    OpticalInputs input;
    assert(input.Update(hand,true,1000000000,7,1)); assert(!input.click && !input.grip);
    hand.trigger=0; hand.grip=0; input.Update(hand,true,1000000000,7,1); assert(input.armed);
    hand.trigger=.8f; hand.grip=1; input.Update(hand,true,1000000000,7,1); assert(input.click && input.grip);
    hand.trigger=.5f; input.Update(hand,true,1000000000,7,1); assert(input.click);
    hand.trigger=.4f; input.Update(hand,true,1000000000,7,1); assert(!input.click);
    assert(!input.Update(hand,true,1100000001,7,1)); assert(!input.armed && !input.grip && input.trigger==0);
    hand.trigger=1; assert(input.Update(hand,true,1000000000,7,1)); assert(!input.click);
    hand.trigger=0; hand.grip=0; input.Update(hand,true,1000000000,7,1); assert(input.armed);
    hand.trigger=1; input.Update(hand,true,1000000000,7,2); assert(!input.click);
    assert(!input.Update(hand,false,1000000000,7,2));
    puts("PASS: spatial layout, files, freshness, independent timestamps and optical input recovery");
}
