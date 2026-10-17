#include "spatial_tracking.h"
#include <cassert>
#include <cstddef>
#include <cstdio>
#include <limits>

static float PoseAngle(const float *a, const float *b) {
    double dot=0,normA=0,normB=0;
    for (int i=0; i<4; ++i) {
        dot+=double(a[i])*b[i];normA+=double(a[i])*a[i];normB+=double(b[i])*b[i];
    }
    return float(2*std::acos(std::clamp(std::abs(dot)/std::sqrt(normA*normB),0.,1.)));
}

static void CheckPredictionDirection() {
    for (const int framesPerSample : {10,20}) {
        OpticalPose pose;
        dd_spatial_hand hand{};
        for (int frame=0; frame<2000; ++frame) {
            if (frame%framesPerSample==0) {
                const int sample=frame/framesPerSample;
                const float sign=sample%2 ? -1.f : 1.f;
                const float position=sample ? sign*.004f : 0;
                const float angle=sample ? sign*.05f : 0;
                hand.pose={30000000000LL+frame*4000000LL,{position,0,0},
                    {0,sign*std::sin(angle*.5f),0,sign*std::cos(angle*.5f)},DD_SPATIAL_VALID};
            }
            assert(pose.Update(hand,true,30060000000LL+frame*4000000LL,1,1));
            assert(!pose.predict);
            assert(std::abs(pose.rendered.position[0])<=.004f);
        }
    }
}

static void CheckSmallMotion() {
    for (const int framesPerSample : {10,20}) {
        OpticalPose pose;
        dd_spatial_hand hand{};
        const float identity[]={0,0,0,1};
        float previousPosition=0,previousAngle=0;
        for (int sample=0; sample<56; ++sample) {
            const int step=sample<16 ? 0 : sample<28 ? sample-15 : sample<44 ? 12 : 55-sample;
            const float position=step*.0002f,angle=step*.000872665f;
            hand.pose={70000000000LL+sample*framesPerSample*4000000LL,{position,0,0},
                {0,std::sin(angle*.5f),0,std::cos(angle*.5f)},DD_SPATIAL_VALID};
            for (int frame=0; frame<framesPerSample; ++frame) {
                assert(pose.Update(hand,true,hand.pose.time_ns+60000000LL+frame*4000000LL,1,1));
                if (frame==0 && (sample==16 || sample==44)) {
                    const float direction=sample==16 ? 1.f : -1.f;
                    assert(direction*(pose.rendered.position[0]-previousPosition)>.00002f);
                    assert(direction*(PoseAngle(identity,pose.rendered.rotation)-previousAngle)>.00008f);
                }
                assert(pose.rendered.time_ns==hand.pose.time_ns);
            }
            const float actualAngle=PoseAngle(identity,pose.rendered.rotation);
            assert(std::abs(pose.rendered.position[0]-position)<.0002f);
            assert(std::abs(actualAngle-angle)<.00012f);
            if ((sample>=16 && sample<28) || sample>=44) {
                const float direction=sample<28 ? 1.f : -1.f;
                assert(direction*(pose.rendered.position[0]-previousPosition)>.0001f);
                assert(direction*(actualAngle-previousAngle)>.0005f);
            }
            previousPosition=pose.rendered.position[0];previousAngle=actualAngle;
        }
    }
}

static void CheckPalmMotion() {
    for (const int framesPerSample : {10,20}) {
        OpticalPose pose;
        dd_spatial_hand hand{};
        const float identity[]={0,0,0,1};
        float maximumPositionError=0,maximumAngleError=0;
        for (int frame=0; frame<1000; ++frame) {
            if (frame%framesPerSample==0) {
                const float elapsed=frame*.004f;
                const float angle=elapsed*.1f;
                hand.pose={40000000000LL+frame*4000000LL,{elapsed*.01f,0,0},
                    {0,std::sin(angle*.5f),0,std::cos(angle*.5f)},DD_SPATIAL_VALID};
            }
            assert(pose.Update(hand,true,40060000000LL+frame*4000000LL,1,1));
            if (frame>250) {
                const float observedAngle=float(hand.pose.time_ns-40000000000LL)*1e-9f*.1f;
                maximumPositionError=std::max(maximumPositionError,std::abs(pose.rendered.position[0]-hand.pose.position[0]));
                maximumAngleError=std::max(maximumAngleError,std::abs(PoseAngle(identity,pose.rendered.rotation)-observedAngle));
            }
        }
        std::printf("Palm %.1fHz slow motion maximum error position=%.3fmm rotation=%.3fdeg\n",
            250./framesPerSample,maximumPositionError*1000,maximumAngleError*180/M_PI);
        assert(maximumPositionError<.001f && maximumAngleError<.013f);
        pose.Reset();
        hand.pose={50000000000LL,{0,0,0},{0,0,0,1},DD_SPATIAL_VALID};
        for (int frame=0; frame<framesPerSample; ++frame)
            assert(pose.Update(hand,true,50060000000LL+frame*4000000LL,1,1));
        hand.pose.time_ns+=framesPerSample*4000000LL;
        hand.pose.position[0]=.1f;
        hand.pose.rotation[1]=std::sin(float(M_PI/4));
        hand.pose.rotation[3]=std::cos(float(M_PI/4));
        int positionResponseMs=-1,rotationResponseMs=-1;
        for (int frame=0; frame<20; ++frame) {
            assert(pose.Update(hand,true,hand.pose.time_ns+60000000LL+frame*4000000LL,1,1));
            const auto &rendered=pose.rendered;
            assert(rendered.position[0]>=0 && rendered.position[0]<=.100001f && !pose.predict);
            if (positionResponseMs<0 && rendered.position[0]>=.095f) positionResponseMs=frame*4;
            if (rotationResponseMs<0 && PoseAngle(identity,rendered.rotation)>=float(M_PI*.475))
                rotationResponseMs=frame*4;
            assert(rendered.time_ns==hand.pose.time_ns);
        }
        assert(positionResponseMs>=36 && positionResponseMs<=52);
        assert(rotationResponseMs>=36 && rotationResponseMs<=52);
        std::printf("Palm %.1fHz 95%% step response position=%dms rotation=%dms after receipt\n",
            250./framesPerSample,positionResponseMs,rotationResponseMs);
        const auto observed=pose.last;
        hand.pose.position[0]=2;
        hand.pose.rotation[1]=0;hand.pose.rotation[3]=1;
        assert(pose.Update(hand,true,hand.pose.time_ns+140000000LL,1,1));
        assert(std::memcmp(&pose.last,&observed,sizeof(observed))==0);
    }
}

static void CheckVisualHold() {
    OpticalPose pose;
    OpticalInputs input;
    dd_spatial_hand hand{};
    for (int sample=0; sample<3; ++sample) {
        hand.pose={60000000000LL+sample*80000000LL,{sample*.02f,0,0},{0,0,0,1},
            DD_SPATIAL_VALID|DD_SPATIAL_TRIGGER_VALID|DD_SPATIAL_GRIP_VALID};
        hand.trigger=sample==2 ? 1.f : 0.f;hand.grip=sample==2 ? 1u : 0u;
        assert(input.Update(hand,true,hand.pose.time_ns+60000000LL,1,1));
        for (int frame=0; frame<20; ++frame)
            assert(pose.Update(hand,true,hand.pose.time_ns+60000000LL+frame*4000000LL,1,1));
    }
    assert(pose.predict && input.click && input.grip);
    const auto held=pose.rendered;
    const int64_t capture=hand.pose.time_ns;
    assert(pose.Update({},true,capture+140000000LL,1,1));
    assert(!input.Update({},true,capture+140000000LL,1,1));
    assert(!input.click && !input.grip && input.trigger==0);
    assert(!pose.predict && !pose.receivedAt && !pose.observations);
    auto repeated=hand;repeated.pose.position[0]=9;
    for (int ageMs : {150,200,201,300,350}) {
        const int64_t now=capture+ageMs*1000000LL;
        assert(pose.Update(repeated,true,now,1,1));
        input.Update(repeated,true,now,1,1);
        assert(!input.click && !input.grip && input.trigger==0);
        assert(pose.last.pose.time_ns==capture);
        assert(std::memcmp(&pose.rendered,&held,sizeof(held))==0);
        assert(!pose.predict && !pose.receivedAt && !pose.observations);
    }
    auto stale=hand;stale.pose.time_ns+=20000000LL;stale.pose.position[0]=5;
    assert(pose.Update(stale,true,capture+350000000LL,1,1));
    assert(pose.last.pose.time_ns==capture && std::memcmp(&pose.rendered,&held,sizeof(held))==0);
    assert(!pose.Update(stale,true,capture+350000001LL,1,1));
    assert(!pose.last.pose.time_ns && !pose.rendered.flags);
    assert(!pose.Update(repeated,true,capture+351000000LL,1,1));
    OpticalPose unseen;
    assert(!unseen.Update(stale,true,stale.pose.time_ns+200000001LL,1,1));
    auto fresh=hand;fresh.pose.time_ns=capture+350000000LL;fresh.pose.position[0]=.5f;
    fresh.pose.rotation[1]=std::sin(.3f);fresh.pose.rotation[3]=std::cos(.3f);
    assert(pose.Update(fresh,true,capture+400000000LL,1,1));
    assert(pose.rendered.position[0]==.5f && !pose.predict);
    assert(PoseAngle(pose.rendered.rotation,fresh.pose.rotation)<1e-6f);
    assert(pose.rendered.time_ns==fresh.pose.time_ns);
    assert(input.Update(fresh,true,capture+400000000LL,1,1));
    assert(!input.click && !input.grip && input.trigger==0);
    for (int reset=0; reset<3; ++reset) {
        OpticalPose resetPose;
        assert(resetPose.Update(hand,true,capture,1,1));
        assert(resetPose.Update({},true,capture+150000000LL,1,1));
        const uint64_t connection=reset==1 ? 2 : 1,origin=reset==2 ? 2 : 1;
        assert(!resetPose.Update({},reset!=0,capture+160000000LL,connection,origin));
        assert(!resetPose.last.pose.time_ns && !resetPose.rendered.flags);
        assert(!resetPose.Update({},true,capture+170000000LL,connection,origin));
    }
}

int main() {
    CheckPredictionDirection();
    CheckSmallMotion();
    CheckPalmMotion();
    CheckVisualHold();
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
    assert(pose.Update({},true,1560000000,7,2));
    assert(!pose.Update({},true,1560000001,7,2));

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
    assert(pose.Update({},true,hand.pose.time_ns+350000000,7,3));
    assert(!pose.Update({},true,hand.pose.time_ns+350000001,7,3));
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
    puts("PASS: spatial layout, files, freshness, independent timestamps and optical input recovery");
}
