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
        s.version = 1; assert(pwrite(fd, &s, sizeof(s), 0) == sizeof(s)); assert(!reader.Read(&out));
        s.version = 2; assert(pwrite(fd, &s, sizeof(s), 0) == sizeof(s)); assert(!reader.Read(&out));
        s.input_epochs = 0x04030201;
        s.version = DROIDDECK_SPATIALPOSE_VERSION; s.head.time_ns += 90000000; assert(pwrite(fd, &s, sizeof(s), 0) == sizeof(s)); assert(reader.Read(&out));
        assert(SpatialFresh(out.head, 1190000000)); assert(!SpatialFresh(out.head,1190000001));
        assert(out.hands[0].pose.time_ns == 950000000);
        assert(out.input_epochs == 0x04030201);
        assert(SpatialHandFresh(out.hands[0],1150000000)); assert(!SpatialHandFresh(out.hands[0],1150000001));
    }
    close(fd); unlink(path);
    assert(!SpatialFresh(s.head,1));
    s.head.position[0] = std::numeric_limits<float>::quiet_NaN(); assert(!SpatialFresh(s.head,1100000000));
    auto hand=s.hands[0]; hand.pose.time_ns=1000000000; hand.trigger=1; hand.grip=1;
    hand.pose.flags |= DD_SPATIAL_TRIGGER_VALID | DD_SPATIAL_GRIP_VALID;
    OpticalInputs input;
    assert(input.Update(hand,true,1000000000,7,1)); assert(!input.click && !input.grip);
    auto updateInput = [&](float trigger, uint32_t grip, uint16_t epochs = 0) {
        hand.pose.time_ns += 10000000;
        hand.trigger=trigger; hand.grip=grip;
        return input.Update(hand,true,hand.pose.time_ns,7,1,epochs);
    };
    updateInput(0,0); assert(input.armed && input.gripArmed);
    updateInput(.8f,1); assert(input.click && input.grip);
    updateInput(.5f,1); assert(input.click);
    updateInput(.4f,1); assert(!input.click && input.grip);
    updateInput(1,1); assert(input.click && input.grip);
    assert(input.Update(hand,true,hand.pose.time_ns+200000000,7,1)); assert(input.click && input.grip);
    assert(!input.Update(hand,true,hand.pose.time_ns+200000001,7,1)); assert(!input.armed && !input.grip && input.trigger==0);
    hand.pose.time_ns += 210000000;
    updateInput(1,1); assert(!input.click && !input.grip);
    updateInput(0,0); updateInput(1,1); assert(input.click && input.grip);
    hand.pose.flags &= ~DD_SPATIAL_TRIGGER_VALID;
    updateInput(1,1); assert(!input.armed && !input.click && input.trigger==0 && input.grip);
    hand.pose.flags |= DD_SPATIAL_TRIGGER_VALID;
    updateInput(1,1); assert(!input.click && input.grip);
    updateInput(0,1); updateInput(1,1); assert(input.click);
    hand.pose.flags &= ~DD_SPATIAL_GRIP_VALID;
    updateInput(1,1); assert(input.click && !input.grip && !input.gripArmed);
    hand.pose.flags |= DD_SPATIAL_GRIP_VALID;
    updateInput(1,1); assert(input.click && !input.grip);
    updateInput(1,0); updateInput(1,1); assert(input.grip);

    updateInput(1,1,0x0100); assert(input.click && !input.grip && !input.gripArmed);
    updateInput(1,1,0x0100); assert(input.click && !input.grip);
    updateInput(1,0,0x0100); updateInput(1,1,0x0100); assert(input.grip);
    updateInput(1,1,0x0101); assert(!input.click && !input.armed && input.grip);
    updateInput(0,1,0x0101); updateInput(1,1,0x0101); assert(input.click && input.grip);
    updateInput(1,1,0x0202); assert(!input.click && !input.grip);
    updateInput(0,0,0x0202); updateInput(1,1,0x0202); assert(input.click && input.grip);

    input.Update({},false,hand.pose.time_ns+1,7,1,0x0202);
    hand.trigger=0; hand.grip=0;
    input.Update(hand,true,hand.pose.time_ns+2,7,1,0x0202); assert(!input.armed && !input.gripArmed);
    updateInput(1,1,0x0202); assert(!input.click && !input.grip);
    updateInput(0,0,0x0202); updateInput(1,1,0x0202); assert(input.click && input.grip);
    hand.pose.time_ns+=210000000;
    updateInput(1,1,0x0202); assert(!input.click && !input.grip);
    updateInput(0,0,0x0202); updateInput(1,1,0x0202);
    auto backwards=hand; backwards.pose.time_ns-=1000000; backwards.trigger=0; backwards.grip=0;
    assert(!input.Update(backwards,true,hand.pose.time_ns,7,1,0x0202)); assert(!input.armed && !input.gripArmed);
    updateInput(0,0,0x0202); updateInput(1,1,0x0202);
    input.Update(hand,true,hand.pose.time_ns,7,2,0x0202); assert(!input.click && !input.grip);
    hand.pose.time_ns=1210000000;

    OpticalPose pose;
    assert(pose.Update(hand,true,1210000000,7,2));
    auto corrupt = hand; corrupt.pose.position[0] = std::numeric_limits<float>::quiet_NaN();
    assert(pose.Update(corrupt,true,1220000000,7,2));
    assert(pose.last.pose.position[0] == hand.pose.position[0]);
    auto future = hand; future.pose.time_ns = 1500000000;
    assert(pose.Update(future,true,1230000000,7,2));
    assert(pose.last.pose.time_ns == 1210000000);
    auto old = hand; old.pose.time_ns -= 10000000; old.pose.position[0] = 99;
    assert(pose.Update(old,true,1240000000,7,2));
    assert(pose.last.pose.time_ns == 1210000000);
    assert(pose.Update({},true,1410000000,7,2));
    assert(!pose.Update({},true,1410000001,7,2));

    for (const int64_t delay : {60000000LL, 80000000LL, 120000000LL}) {
        pose.Reset();
        hand.pose = {2000000000LL, {0,0,0}, {0,0,0,1.002f}, DD_SPATIAL_VALID};
        float previous = 0;
        for (int sample=0; sample<8; ++sample) {
            hand.pose.time_ns = 2000000000LL+sample*80000000LL;
            hand.pose.position[0] = sample*.02f;
            const float sign = sample%2 ? -1.f : 1.f;
            hand.pose.rotation[1] = sign*1.002f*std::sin(sample*.05f);
            hand.pose.rotation[3] = sign*1.002f*std::cos(sample*.05f);
            float midway = 0;
            for (int frame=0; frame<20; ++frame) {
                const int64_t now = hand.pose.time_ns+delay+frame*4000000LL;
                assert(pose.Update(hand,true,now,7,3));
                assert(pose.receivedAt==hand.pose.time_ns+delay);
                assert(pose.rendered.time_ns==hand.pose.time_ns);
                assert(pose.rendered.position[0]<=hand.pose.position[0]+.020001f);
                float norm=0; for(float v:pose.rendered.rotation) norm+=v*v;
                assert(std::abs(norm-1)<1e-5);
                if (sample>=4) {
                    assert(pose.predict);
                    assert(pose.rendered.position[0]>previous+.0005f);
                    assert(pose.rendered.position[0]<previous+.006f);
                    const float movingTarget = hand.pose.position[0]+frame*.001f;
                    assert(std::abs(pose.rendered.position[0]-movingTarget)<.006f);
                }
                if (frame==10) midway = pose.rendered.position[0];
                if (sample>=4 && frame==19) assert(pose.rendered.position[0]-midway>.005f);
                previous = pose.rendered.position[0];
            }
        }
        const int64_t stoppedAt = hand.pose.time_ns+80000000;
        const float beforeStop = pose.rendered.position[0];
        hand.pose.time_ns = stoppedAt;
        for (int frame=0; frame<20; ++frame) {
            assert(pose.Update(hand,true,stoppedAt+delay+frame*4000000LL,7,3));
            assert(pose.rendered.position[0]<=beforeStop);
            assert(pose.rendered.position[0]>=hand.pose.position[0]);
        }
        assert(std::abs(pose.rendered.position[0]-hand.pose.position[0])<.0001f);

        hand.pose.time_ns+=80000000; hand.pose.position[0]-=.04f;
        previous = pose.rendered.position[0];
        for (int frame=0; frame<20; ++frame) {
            assert(pose.Update(hand,true,hand.pose.time_ns+delay+frame*4000000LL,7,3));
            assert(!pose.predict);
            assert(pose.rendered.position[0]<=previous);
            assert(pose.rendered.position[0]>=hand.pose.position[0]);
            previous=pose.rendered.position[0];
        }
        hand.pose.time_ns+=80000000; hand.pose.position[0]-=.04f;
        pose.Update(hand,true,hand.pose.time_ns+delay,7,3);
        assert(pose.predict);
        for (int frame=1; frame<20; ++frame) {
            pose.Update(hand,true,hand.pose.time_ns+delay+frame*4000000LL,7,3);
            assert(pose.rendered.position[0]>=hand.pose.position[0]-.020001f);
        }
        hand.pose.time_ns+=80000000; hand.pose.position[0]+=1.f;
        pose.Update(hand,true,hand.pose.time_ns+delay,7,3);
        assert(!pose.predict);
        for (int frame=1; frame<20; ++frame) {
            pose.Update(hand,true,hand.pose.time_ns+delay+frame*4000000LL,7,3);
            assert(pose.rendered.position[0]<=hand.pose.position[0]);
        }
    }

    const auto held = pose.rendered;
    pose.Update({},true,hand.pose.time_ns+196000000,7,3);
    assert(!pose.predict && !pose.receivedAt);
    assert(std::memcmp(&pose.rendered,&held,sizeof(held))==0);
    pose.Update(hand,true,hand.pose.time_ns+198000000,7,3);
    assert(std::memcmp(&pose.rendered,&held,sizeof(held))==0);
    assert(pose.Update({},true,hand.pose.time_ns+200000000,7,3));
    assert(!pose.Update({},true,hand.pose.time_ns+200000001,7,3));
    hand.pose.time_ns+=400000000; hand.pose.position[0]=.5f;
    pose.Update(hand,true,hand.pose.time_ns+60000000,7,3);
    assert(pose.rendered.position[0]==.5f && !pose.predict);
    hand.pose.time_ns+=80000000; hand.pose.position[0]=.6f;
    pose.Update(hand,true,hand.pose.time_ns+60000000,7,4);
    assert(pose.rendered.position[0]==.6f && !pose.predict);
    hand.pose.time_ns+=80000000; hand.pose.position[0]=.7f;
    pose.Update(hand,true,hand.pose.time_ns+60000000,8,4);
    assert(pose.rendered.position[0]==.7f && !pose.predict);
    pose.Update({},false,hand.pose.time_ns+64000000,8,4);
    assert(!pose.last.pose.time_ns && !pose.receivedAt && !pose.predict);

    pose.Reset();
    const int64_t delays[] = {60000000,120000000,80000000,60000000,120000000,80000000};
    int sample=0;
    hand.pose = {6000000000LL, {0,0,0}, {0,0,0,1}, DD_SPATIAL_VALID};
    float previous=0;
    for (int64_t now=6060000000LL; now<6556000000LL; now+=4000000) {
        if (sample<5 && now>=6000000000LL+(sample+1)*80000000LL+delays[sample+1]) {
            ++sample;
            hand.pose.time_ns=6000000000LL+sample*80000000LL;
            hand.pose.position[0]=sample*.02f;
        }
        assert(pose.Update(hand,true,now,8,4));
        assert(pose.receivedAt==hand.pose.time_ns+delays[sample]);
        assert(pose.rendered.position[0]>=previous);
        assert(pose.rendered.position[0]<=previous+.01f);
        assert(pose.rendered.position[0]<=hand.pose.position[0]+.020001f);
        assert(pose.rendered.time_ns==hand.pose.time_ns);
        previous=pose.rendered.position[0];
    }
    pose.Reset();
    hand.pose = {8000000000LL, {0,0,0}, {0,0,0,1}, DD_SPATIAL_VALID};
    for (int sample=0; sample<40; ++sample) {
        hand.pose.time_ns=8000000000LL+sample*80000000LL;
        const float sign=sample%2 ? -1.f : 1.f;
        hand.pose.position[0]=sample ? sign*.0014f : 0;
        hand.pose.position[1]=sample ? -sign*.0008f : 0;
        hand.pose.position[2]=sample ? sign*.0004f : 0;
        const float angle=sample ? sign*.010471976f : 0;
        hand.pose.rotation[1]=sign*std::sin(angle*.5f);
        hand.pose.rotation[3]=sign*std::cos(angle*.5f);
        for (int frame=0; frame<20; ++frame) {
            assert(pose.Update(hand,true,hand.pose.time_ns+60000000+frame*4000000LL,8,4));
            for (float v:pose.rendered.position) assert(v==0);
            assert(pose.rendered.rotation[1]==0 && pose.rendered.rotation[3]==1);
            assert(pose.positionAnchored && pose.rotationAnchored && !pose.predict);
            assert(pose.rendered.time_ns==hand.pose.time_ns);
        }
    }

    pose.Reset();
    hand.pose = {12000000000LL, {0,0,0}, {0,0,0,1}, DD_SPATIAL_VALID};
    previous=0;
    for (int sample=0; sample<40; ++sample) {
        hand.pose.time_ns=12000000000LL+sample*80000000LL;
        hand.pose.position[0]=sample*.0002f;
        for (int frame=0; frame<20; ++frame) {
            assert(pose.Update(hand,true,hand.pose.time_ns+60000000+frame*4000000LL,8,4));
            if (sample<15) assert(pose.rendered.position[0]==0);
            if (sample>=20) {
                assert(!pose.positionAnchored && pose.rotationAnchored);
                assert(pose.rendered.position[0]>previous+.0000001f);
                assert(std::abs(pose.rendered.position[0]-hand.pose.position[0])<.001f);
            }
            previous=pose.rendered.position[0];
        }
    }

    pose.Reset();
    hand.pose = {16000000000LL, {0,0,0}, {0,0,0,1}, DD_SPATIAL_VALID};
    const float identity[]={0,0,0,1};
    previous=0;
    for (int sample=0; sample<48; ++sample) {
        hand.pose.time_ns=16000000000LL+sample*80000000LL;
        const float angle=sample*.000872665f;
        hand.pose.rotation[1]=std::sin(angle*.5f);
        hand.pose.rotation[3]=std::cos(angle*.5f);
        for (int frame=0; frame<20; ++frame)
            assert(pose.Update(hand,true,hand.pose.time_ns+60000000+frame*4000000LL,8,4));
        if (sample<20) assert(pose.rendered.rotation[1]==0);
        if (sample>=26) {
            assert(pose.positionAnchored && !pose.rotationAnchored);
            const float actual=OpticalPose::Angle(identity,pose.rendered.rotation);
            assert(actual>previous+.0005f && std::abs(actual-angle)<.001f);
            previous=actual;
        }
    }

    pose.Reset();
    hand.pose = {20000000000LL, {0,0,0}, {0,0,0,1}, DD_SPATIAL_VALID};
    for (int frame=0; frame<20; ++frame)
        assert(pose.Update(hand,true,hand.pose.time_ns+60000000+frame*4000000LL,8,4));
    hand.pose.time_ns+=80000000;
    hand.pose.position[0]=.025f;
    hand.pose.rotation[1]=std::sin(.261799388f*.5f);
    hand.pose.rotation[3]=std::cos(.261799388f*.5f);
    assert(pose.Update(hand,true,hand.pose.time_ns+60000000,8,4));
    assert(pose.rendered.position[0]>.005f && !pose.positionAnchored && !pose.rotationAnchored);
    assert(OpticalPose::Angle(identity,pose.rendered.rotation)>.04f);
    for (int frame=1; frame<20; ++frame)
        assert(pose.Update(hand,true,hand.pose.time_ns+60000000+frame*4000000LL,8,4));
    dd_spatial_pose settled{};
    for (int sample=1; sample<=10; ++sample) {
        hand.pose.time_ns+=80000000;
        const float sign=sample%2 ? -1.f : 1.f;
        hand.pose.position[0]=.025f+sign*.0006f;
        const float angle=.261799388f+sign*.002f;
        hand.pose.rotation[1]=std::sin(angle*.5f);
        hand.pose.rotation[3]=std::cos(angle*.5f);
        for (int frame=0; frame<20; ++frame) {
            assert(pose.Update(hand,true,hand.pose.time_ns+60000000+frame*4000000LL,8,4));
            if (sample>=8) {
                assert(pose.positionAnchored && pose.rotationAnchored && !pose.predict);
                assert(OpticalPose::Distance(pose.rendered.position,settled.position)<.0000001f);
                assert(OpticalPose::Angle(pose.rendered.rotation,settled.rotation)<.000001f);
            }
            assert(pose.rendered.time_ns==hand.pose.time_ns);
        }
        if (sample==7) settled=pose.rendered;
    }
    puts("PASS: spatial layout, files, freshness, independent timestamps and optical input recovery");
}
