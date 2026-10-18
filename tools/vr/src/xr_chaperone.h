#pragma once
#include "xr_tracking_file.h"
#include <cmath>
#include <iomanip>
#include <locale>
#include <sstream>
#include <string>

struct XrSeatedOrigin {
    double yaw, translation[3];
};

class XrChaperoneOrigin {
public:
    void Reset() { published_ = false; }
    template <typename Publish> void Update(const DroidDeckXrTracking &tracking, Publish publish) {
        if (!published_ && XrTrackingFile::Fresh(tracking) && XrTrackingFile::Tracked(tracking.head))
            published_ = publish(tracking.head);
    }
private:
    bool published_ = false;
};

inline XrSeatedOrigin xr_seated_origin(const DroidDeckXrPose &head) {
    const auto *q = head.orientation;
    double yaw = std::atan2(2 * (q[0] * q[2] + q[3] * q[1]), 1 - 2 * (q[0] * q[0] + q[1] * q[1]));
    double c = std::cos(yaw), s = std::sin(yaw);
    return {-yaw, {-c * head.position[0] + s * head.position[2], -head.position[1],
                   -s * head.position[0] - c * head.position[2]}};
}

inline std::string xr_chaperone_json(const DroidDeckXrPose &head, const char *timestamp) {
    auto seated = xr_seated_origin(head);
    std::ostringstream json; json.imbue(std::locale::classic()); json << std::setprecision(9);
    json << "{\"jsonid\":\"chaperone_info\",\"version\":5,\"time\":\"" << timestamp
         << "\",\"universes\":[{\"universeID\":\"2\",\"standing\":{\"translation\":[0,0,0],\"yaw\":0},"
         << "\"seated\":{\"translation\":[" << seated.translation[0] << ',' << seated.translation[1] << ',' << seated.translation[2]
         << "],\"yaw\":" << seated.yaw << "},\"play_area\":[0,0],\"collision_bounds\":[]}]}";
    return json.str();
}
