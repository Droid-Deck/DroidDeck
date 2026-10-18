#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "hand_pose.h"

namespace droiddeck {

struct HandMesh {
    std::vector<Vec3> points, normals;
    std::vector<double> tints, shades;
    std::vector<std::array<int, 3>> faces;

    int Add(const Vec3 &p, const Vec3 &n, double tint = 0.0, double shade = 1.0) {
        points.push_back(p);
        normals.push_back(n);
        tints.push_back(tint);
        shades.push_back(shade);
        return (int)points.size() - 1;
    }

    void Face(int a, int b, int c, const Vec3 &outward) {
        const Vec3 n = Cross(points[b] - points[a], points[c] - points[a]);
        if (Dot(n, outward) < 0.0) faces.push_back({a, c, b});
        else faces.push_back({a, b, c});
    }
};

inline FingerCurls RelaxedCurls() {
    FingerCurls c;
    c.thumb = 0.15;
    c.index = 0.1;
    c.middle = c.ring = c.pinky = 0.15;
    return c;
}

inline void HandShape(bool right, bool pinch, bool fist, Vec3 out[kHandJoints]) {
    using namespace hand;
    FingerCurls c = RelaxedCurls();
    if (pinch) {
        c.thumb = 0.7;
        c.index = 0.55;
    }
    if (fist) c.middle = c.ring = c.pinky = 1.0;
    HandJoints(right, c, out);
    if (pinch) {
        const Vec3 meet = out[kIndexTip] + (out[kThumbIp] - out[kIndexTip]) * 0.15;
        const Vec3 shift = meet - out[kThumbTip];
        out[kThumbTip] = meet;
        out[kThumbIp] = out[kThumbIp] + shift * 0.5;
        out[kThumbMcp] = out[kThumbMcp] + shift * 0.15;
    }
}

enum class HandPart { Hand, ThumbIndex };

namespace glove {

constexpr double kStep = 0.0026;
constexpr double kPalmHalf = 0.0105;
constexpr double kPalmRound = 0.0085;
constexpr double kWristSide = 0.02;
constexpr double kWristSpan = 0.017;
constexpr double kWristRadius = 0.0165;
constexpr double kCuffStart = 0.006;
constexpr double kCuffLength = 0.016;
constexpr double kCuffWidth[2] = {0.0295, 0.029};
constexpr double kCuffDepth[2] = {0.021, 0.0205};
constexpr double kCuffRound = 0.006;
constexpr double kJointBlend = 0.001;
constexpr double kFingerBlend = 0.010;
constexpr double kThumbBlend = 0.016;
constexpr double kWristBlend = 0.012;
constexpr double kCuffBlend = 0.005;
constexpr double kTintEdge = 0.0015;
constexpr double kTouchBlend = 0.008;
constexpr double kCutBlend = 0.004;
constexpr double kDome[3] = {0.030, 0.040, 0.0135};
constexpr double kDomeAt = 0.55;
constexpr double kDomeBlend = 0.022;
constexpr double kFistRadius = 0.0095;
constexpr double kFistBlend = 0.006;
constexpr double kOcclusionStep = 0.004;
constexpr double kOcclusion = 3.0;
constexpr double kDarkest = 0.4;
constexpr double kInset = 0.0003;
constexpr double kReach = 0.03;
constexpr double kRadii[5][4] = {{0.0135, 0.0122, 0.0110, 0.0096},
                                 {0.0106, 0.0098, 0.0092, 0.0087},
                                 {0.0110, 0.0102, 0.0096, 0.0090},
                                 {0.0105, 0.0097, 0.0091, 0.0086},
                                 {0.0094, 0.0087, 0.0082, 0.0077}};

inline Vec3 Unit(const Vec3 &v) {
    const double l = Length(v);
    return l > 1e-12 ? v * (1.0 / l) : Vec3{0.0, 1.0, 0.0};
}

inline double Smin(double a, double b, double k) {
    const double h = std::max(k - std::fabs(a - b), 0.0) / k;
    return std::min(a, b) - h * h * k * 0.25;
}

inline double Cone(const Vec3 &p, const Vec3 &a, const Vec3 &b, double ra, double rb) {
    const Vec3 ab = b - a;
    const double t = std::min(1.0, std::max(0.0, Dot(p - a, ab) / std::max(Dot(ab, ab), 1e-12)));
    return Length(p - (a + ab * t)) - (ra + (rb - ra) * t);
}

inline double Polygon(const std::vector<std::array<double, 2>> &v, double x, double y) {
    double d = (x - v[0][0]) * (x - v[0][0]) + (y - v[0][1]) * (y - v[0][1]);
    double sign = 1.0;
    for (size_t i = 0, j = v.size() - 1; i < v.size(); j = i++) {
        const double ex = v[j][0] - v[i][0], ey = v[j][1] - v[i][1];
        const double wx = x - v[i][0], wy = y - v[i][1];
        const double t = std::min(1.0, std::max(0.0, (wx * ex + wy * ey) / std::max(ex * ex + ey * ey, 1e-18)));
        const double bx = wx - ex * t, by = wy - ey * t;
        d = std::min(d, bx * bx + by * by);
        const bool a = y >= v[i][1], b = y < v[j][1], c = ex * wy > ey * wx;
        if ((a && b && c) || (!a && !b && !c)) sign = -sign;
    }
    return sign * std::sqrt(d);
}

inline double Rounded(double a, double b, double round) {
    return std::min(std::max(a, b), 0.0) + std::hypot(std::max(a, 0.0), std::max(b, 0.0)) - round;
}

struct Finger {
    Vec3 joints[4];
    double radii[4];

    double Distance(const Vec3 &p) const {
        double d = Cone(p, joints[0], joints[1], radii[0], radii[1]);
        for (int i = 1; i < 3; i++) d = Smin(d, Cone(p, joints[i], joints[i + 1], radii[i], radii[i + 1]), kJointBlend);
        return d;
    }
};

class Glove {
public:
    Glove(bool right, HandPart part, bool closed) : part_(part) {
        using namespace hand;
        Vec3 open[kHandJoints], j[kHandJoints];
        HandShape(right, false, false, open);
        HandShape(right, part == HandPart::ThumbIndex && closed, part == HandPart::Hand && closed, j);
        origin_ = open[kWrist];
        along_ = Unit(open[kMiddleMcp] - open[kWrist]);
        normal_ = Unit(Cross(open[kIndexMcp] - open[kWrist], open[kPinkyMcp] - open[kWrist]));
        across_ = Unit(Cross(along_, normal_));
        const Vec3 side = across_ * (Dot(open[kIndexMcp] - open[kPinkyMcp], across_) > 0.0 ? 1.0 : -1.0);
        for (const Vec3 &q : {open[kWrist] + side * kWristSide, open[kThumbCmc], open[kIndexMcp], open[kMiddleMcp], open[kRingMcp],
                              open[kPinkyMcp], open[kWrist] - side * kWristSide})
            outline_.push_back({Dot(q - origin_, across_), Dot(q - origin_, along_)});
        wristA_ = open[kWrist] + across_ * kWristSpan;
        wristB_ = open[kWrist] - across_ * kWristSpan;
        const int first = part == HandPart::Hand ? 2 : 0, last = part == HandPart::Hand ? 4 : 1;
        for (int f = first; f <= last; f++) {
            Finger finger;
            const int root = f == 0 ? kThumbCmc : kIndexMcp + 4 * (f - 1);
            for (int i = 0; i < 4; i++) {
                finger.joints[i] = j[root + i];
                finger.radii[i] = kRadii[f][i];
            }
            finger.joints[3] = finger.joints[3] - Unit(finger.joints[3] - finger.joints[2]) * (finger.radii[3] * 0.7);
            fingers_.push_back(finger);
        }
        dome_ = open[kWrist] + (open[kMiddleMcp] - open[kWrist]) * kDomeAt;
        back_ = normal_ * (Dot(normal_, open[kThumbTip] - dome_) > 0.0 ? -1.0 : 1.0);
        fist_ = part == HandPart::Hand && closed;
        for (int f = 0; f < 3 && fist_; f++) {
            fistLine_[f] = Vec3{};
            for (int i = 0; i < 4; i++) fistLine_[f] = fistLine_[f] + j[kMiddleMcp + 4 * f + i] * 0.25;
        }
        lo_ = hi_ = origin_;
        const auto grow = [&](const Vec3 &q, double r) {
            lo_ = Vec3{std::min(lo_.x, q.x - r), std::min(lo_.y, q.y - r), std::min(lo_.z, q.z - r)};
            hi_ = Vec3{std::max(hi_.x, q.x + r), std::max(hi_.y, q.y + r), std::max(hi_.z, q.z + r)};
        };
        for (int i : {kWrist, kThumbCmc, kIndexMcp, kMiddleMcp, kRingMcp, kPinkyMcp}) grow(open[i], kPalmHalf + kPalmRound + kWristSide);
        grow(origin_ - along_ * kCuffLength, kCuffWidth[1] + kCuffRound);
        for (const Finger &f : fingers_)
            for (int i = 0; i < 4; i++) grow(f.joints[i], f.radii[i] + kThumbBlend);
    }

    double Distance(const Vec3 &p) const {
        double palm = Smin(Smin(Slab(p), Cone(p, wristA_, wristB_, kWristRadius, kWristRadius), kWristBlend), Cuff(p), kCuffBlend);
        if (part_ == HandPart::ThumbIndex) {
            double reach = 1e9;
            for (const Finger &f : fingers_) reach = std::min(reach, f.Distance(p));
            palm = -Smin(-(palm + kInset), kReach - reach, kCutBlend);
        }
        double fingers = fingers_[0].Distance(p);
        for (size_t i = 1; i < fingers_.size(); i++) fingers = Smin(fingers, fingers_[i].Distance(p), kTouchBlend);
        if (fist_)
            fingers = Smin(fingers, std::min(Cone(p, fistLine_[0], fistLine_[1], kFistRadius, kFistRadius),
                                             Cone(p, fistLine_[1], fistLine_[2], kFistRadius, kFistRadius)), kFistBlend);
        return Smin(palm, fingers, part_ == HandPart::ThumbIndex ? kThumbBlend : kFingerBlend);
    }

    double Tint(const Vec3 &p) const {
        double rest = Smin(Slab(p), Cone(p, wristA_, wristB_, kWristRadius, kWristRadius), kWristBlend);
        for (const Finger &f : fingers_) rest = std::min(rest, f.Distance(p));
        return std::min(1.0, std::max(0.0, 0.5 + (rest - Cuff(p)) / (2.0 * kTintEdge)));
    }

    double Shade(const Vec3 &p, const Vec3 &n) const {
        double hidden = 0.0, weight = 1.0;
        for (int i = 1; i <= 5; i++) {
            const double h = kOcclusionStep * i;
            hidden += (h - Distance(p + n * h)) * weight;
            weight *= 0.6;
        }
        const double open = std::min(1.0, std::max(0.0, 1.0 - kOcclusion * hidden / kOcclusionStep / 5.0));
        const double light = 0.88 + 0.12 * Dot(n, back_);
        return std::min(1.0, std::max(0.0, open * light));
    }

    Vec3 Low() const { return lo_ - Vec3{2.0, 2.0, 2.0} * kStep; }
    Vec3 High() const { return hi_ + Vec3{2.0, 2.0, 2.0} * kStep; }

private:
    double Slab(const Vec3 &p) const {
        const Vec3 q = p - origin_;
        const double slab = Rounded(Polygon(outline_, Dot(q, across_), Dot(q, along_)), std::fabs(Dot(q, normal_)) - (kPalmHalf - kPalmRound), kPalmRound);
        const Vec3 d = p - dome_;
        const double x = Dot(d, across_), y = Dot(d, along_), z = Dot(d, normal_);
        const double k0 = std::sqrt(x * x / (kDome[0] * kDome[0]) + y * y / (kDome[1] * kDome[1]) + z * z / (kDome[2] * kDome[2]));
        const double k1 = std::sqrt(x * x / std::pow(kDome[0], 4) + y * y / std::pow(kDome[1], 4) + z * z / std::pow(kDome[2], 4));
        return Smin(slab, k1 > 1e-12 ? k0 * (k0 - 1.0) / k1 : -kDome[2], kDomeBlend);
    }

    double Cuff(const Vec3 &p) const {
        const Vec3 q = p - origin_;
        const double t = -Dot(q, along_) + kCuffStart;
        const double s = std::min(1.0, std::max(0.0, t / kCuffLength));
        const double a = kCuffWidth[0] + (kCuffWidth[1] - kCuffWidth[0]) * s - kCuffRound;
        const double b = kCuffDepth[0] + (kCuffDepth[1] - kCuffDepth[0]) * s - kCuffRound;
        const double x = Dot(q, across_), y = Dot(q, normal_);
        const double k0 = std::hypot(x / a, y / b), k1 = std::hypot(x / (a * a), y / (b * b));
        const double radial = k1 > 1e-12 ? k0 * (k0 - 1.0) / k1 : -std::min(a, b);
        return Rounded(radial, std::fabs(t - kCuffLength * 0.5) - (kCuffLength * 0.5 - kCuffRound), kCuffRound);
    }

    HandPart part_;
    bool fist_ = false;
    Vec3 origin_, along_, across_, normal_, wristA_, wristB_, lo_, hi_, dome_, back_, fistLine_[3];
    std::vector<std::array<double, 2>> outline_;
    std::vector<Finger> fingers_;
};

template <typename Field> Vec3 Gradient(const Field &f, const Vec3 &p) {
    constexpr double e = 1e-5;
    return Vec3{f(p + Vec3{e, 0.0, 0.0}) - f(p - Vec3{e, 0.0, 0.0}), f(p + Vec3{0.0, e, 0.0}) - f(p - Vec3{0.0, e, 0.0}),
                f(p + Vec3{0.0, 0.0, e}) - f(p - Vec3{0.0, 0.0, e})} *
           (0.5 / e);
}

template <typename Field, typename Painter>
HandMesh SurfaceNet(const Field &f, const Painter &paint, const Vec3 &lo, const Vec3 &hi, double step) {
    const int nx = (int)std::ceil((hi.x - lo.x) / step) + 1, ny = (int)std::ceil((hi.y - lo.y) / step) + 1,
              nz = (int)std::ceil((hi.z - lo.z) / step) + 1;
    const auto at = [&](int i, int j, int k) { return (size_t(k) * ny + j) * nx + i; };
    const auto corner = [&](int i, int j, int k) { return Vec3{lo.x + i * step, lo.y + j * step, lo.z + k * step}; };
    std::vector<double> value(size_t(nx) * ny * nz);
    for (int k = 0; k < nz; k++)
        for (int j = 0; j < ny; j++)
            for (int i = 0; i < nx; i++) value[at(i, j, k)] = f(corner(i, j, k));
    std::vector<int> vertex(value.size(), -1);
    HandMesh m;
    for (int k = 0; k + 1 < nz; k++)
        for (int j = 0; j + 1 < ny; j++)
            for (int i = 0; i + 1 < nx; i++) {
                double c[8];
                int inside = 0;
                for (int n = 0; n < 8; n++) inside += (c[n] = value[at(i + (n & 1), j + ((n >> 1) & 1), k + (n >> 2))]) < 0.0;
                if (inside == 0 || inside == 8) continue;
                Vec3 sum;
                int count = 0;
                for (int a = 0; a < 8; a++)
                    for (int bit = 1; bit < 8; bit <<= 1) {
                        const int b = a | bit;
                        if (b == a || (c[a] < 0.0) == (c[b] < 0.0)) continue;
                        const Vec3 pa = corner(i + (a & 1), j + ((a >> 1) & 1), k + (a >> 2));
                        const Vec3 pb = corner(i + (b & 1), j + ((b >> 1) & 1), k + (b >> 2));
                        sum = sum + pa + (pb - pa) * (c[a] / (c[a] - c[b]));
                        count++;
                    }
                Vec3 p = sum * (1.0 / count);
                for (int it = 0; it < 3; it++) {
                    const Vec3 g = Gradient(f, p);
                    const double gg = Dot(g, g);
                    if (gg < 1e-12) break;
                    p = p - g * (f(p) / gg);
                }
                const Vec3 low = corner(i, j, k), high = corner(i + 1, j + 1, k + 1);
                p = Vec3{std::clamp(p.x, low.x, high.x), std::clamp(p.y, low.y, high.y), std::clamp(p.z, low.z, high.z)};
                const Vec3 n = Unit(Gradient(f, p));
                const std::array<double, 2> colour = paint(p, n);
                vertex[at(i, j, k)] = m.Add(p, n, colour[0], colour[1]);
            }
    for (int k = 1; k + 1 < nz; k++)
        for (int j = 1; j + 1 < ny; j++)
            for (int i = 1; i + 1 < nx; i++) {
                const bool in = value[at(i, j, k)] < 0.0;
                for (int axis = 0; axis < 3; axis++) {
                    const int di = axis == 0, dj = axis == 1, dk = axis == 2;
                    if ((value[at(i + di, j + dj, k + dk)] < 0.0) == in) continue;
                    int q[4];
                    if (axis == 0) q[0] = vertex[at(i, j - 1, k - 1)], q[1] = vertex[at(i, j, k - 1)], q[2] = vertex[at(i, j, k)], q[3] = vertex[at(i, j - 1, k)];
                    else if (axis == 1) q[0] = vertex[at(i - 1, j, k - 1)], q[1] = vertex[at(i, j, k - 1)], q[2] = vertex[at(i, j, k)], q[3] = vertex[at(i - 1, j, k)];
                    else q[0] = vertex[at(i - 1, j - 1, k)], q[1] = vertex[at(i, j - 1, k)], q[2] = vertex[at(i, j, k)], q[3] = vertex[at(i - 1, j, k)];
                    if (q[0] < 0 || q[1] < 0 || q[2] < 0 || q[3] < 0) continue;
                    if (in == (axis == 1)) std::swap(q[1], q[3]);
                    if (Length(m.points[q[0]] - m.points[q[2]]) < Length(m.points[q[1]] - m.points[q[3]])) {
                        m.faces.push_back({q[0], q[1], q[2]});
                        m.faces.push_back({q[0], q[2], q[3]});
                    } else {
                        m.faces.push_back({q[0], q[1], q[3]});
                        m.faces.push_back({q[1], q[2], q[3]});
                    }
                }
            }
    return m;
}

}

inline HandMesh BuildHandPart(bool right, HandPart part, bool closed) {
    const glove::Glove g(right, part, closed);
    return glove::SurfaceNet([&](const Vec3 &p) { return g.Distance(p); }, [&](const Vec3 &p, const Vec3 &n) { return std::array<double, 2>{g.Tint(p), g.Shade(p, n)}; }, g.Low(), g.High(),
                             glove::kStep);
}

inline std::string HandObj(const HandMesh &m, const char *material) {
    std::string out = "mtllib hand.mtl\n";
    char line[160];
    for (const Vec3 &p : m.points) {
        snprintf(line, sizeof(line), "v %.5f %.5f %.5f\n", p.x, p.y, p.z);
        out += line;
    }
    for (size_t i = 0; i < m.tints.size(); i++) {
        snprintf(line, sizeof(line), "vt %.3f %.3f\n", 0.02 + 0.96 * m.shades[i], 0.02 + 0.46 * m.tints[i]);
        out += line;
    }
    for (const Vec3 &n : m.normals) {
        snprintf(line, sizeof(line), "vn %.4f %.4f %.4f\n", n.x, n.y, n.z);
        out += line;
    }
    out += "usemtl ";
    out += material;
    out += "\n";
    for (const auto &f : m.faces) {
        snprintf(line, sizeof(line), "f %d/%d/%d %d/%d/%d %d/%d/%d\n", f[0] + 1, f[0] + 1, f[0] + 1, f[1] + 1, f[1] + 1, f[1] + 1, f[2] + 1,
                 f[2] + 1, f[2] + 1);
        out += line;
    }
    return out;
}

inline std::string HandMtl(const char *material, const char *texture) {
    std::string out = "newmtl ";
    out += material;
    out += "\nNs 12.0\nd 1.0\nillum 2\nKa 1.0 1.0 1.0\nKd 1.0 1.0 1.0\nKs 0.1 0.1 0.1\nmap_Kd ";
    out += texture;
    out += "\n";
    return out;
}

inline std::string HandModelJson(bool right) {
    const auto part = [&](const char *name, const char *path, int button, bool shown) {
        char text[512];
        snprintf(text, sizeof(text),
                 "        \"%s\": {\n"
                 "            \"filename\": \"%s.obj\",\n"
                 "            \"motion\": {\n"
                 "                \"type\": \"translate\",\n"
                 "                \"pressed_path\": \"%s\",\n"
                 "                \"controller_button\": %d,\n"
                 "                \"value_mapping\": [0.0, 0.0],\n"
                 "                \"axis\": [0.0, 0.0, 1.0]\n"
                 "            },\n"
                 "            \"visibility\": { \"default\": %s, \"press\": %s }\n"
                 "        },\n",
                 name, name, path, button, shown ? "false" : "true", shown ? "true" : "false");
        return std::string(text);
    };
    const auto pose = [](const char *name, const char *origin, const char *rotate) {
        char text[256];
        snprintf(text, sizeof(text), "        \"%s\": { \"component_local\": { \"origin\": [%s], \"rotate_xyz\": [%s] } }", name, origin, rotate);
        return std::string(text);
    };
    std::string out = "{\n    \"components\": {\n";
    out += part("hand_open", "/input/grip/touch", 2, false);
    out += part("hand_fist", "/input/grip/touch", 2, true);
    out += part("thumb_index_open", "/input/trigger/click", 33, false);
    out += part("thumb_index_pinch", "/input/trigger/click", 33, true);
    out += pose("tip", right ? "-0.006, -0.015, 0.02" : "0.006, -0.015, 0.02", right ? "-40.0, 5.0, 0.0" : "-40.0, -5.0, 0.0") + ",\n";
    out += pose("base", right ? "-0.004758, -0.037977, 0.200466" : "0.004758, -0.037977, 0.200466",
                right ? "-155.4, -0.427, -7.081" : "-155.4, -0.427, 7.081") + ",\n";
    out += pose("handgrip", right ? "0.003851, 0.003715, 0.075948" : "-0.003851, 0.003715, 0.075948",
                right ? "15.392, 2.071, -0.303" : "15.392, -2.071, 0.303") + ",\n";
    out += pose("grip", "0.0, -0.015, 0.13", right ? "15.392, 2.071, -0.303" : "15.392, -2.071, 0.303") + ",\n";
    out += pose("gdc2015", "0.0, 0.0, 0.0", "0, 0, 0") + "\n";
    out += "    }\n}\n";
    return out;
}

inline uint32_t Crc32(const uint8_t *data, size_t size, uint32_t crc = 0) {
    crc = ~crc;
    for (size_t i = 0; i < size; i++) {
        crc ^= data[i];
        for (int k = 0; k < 8; k++) crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
    }
    return ~crc;
}

inline std::vector<uint8_t> GlovePng(int size, const std::array<uint8_t, 3> &glove, const std::array<uint8_t, 3> &cuff, double darkest) {
    std::vector<uint8_t> raw;
    for (int y = 0; y < size; y++) {
        raw.push_back(0);
        const double t = 1.0 - std::fabs(2.0 * (y + 0.5) / size - 1.0);
        for (int x = 0; x < size; x++) {
            const double light = darkest + (1.0 - darkest) * x / double(size - 1);
            for (int c = 0; c < 3; c++) raw.push_back(uint8_t(std::lround((glove[c] + (cuff[c] - glove[c]) * t) * light)));
        }
    }
    std::vector<uint8_t> zlib = {0x78, 0x01, 0x01, uint8_t(raw.size() & 0xff), uint8_t(raw.size() >> 8),
                                 uint8_t(~raw.size() & 0xff), uint8_t((~raw.size() >> 8) & 0xff)};
    zlib.insert(zlib.end(), raw.begin(), raw.end());
    uint32_t a = 1, s = 0;
    for (uint8_t v : raw) {
        a = (a + v) % 65521;
        s = (s + a) % 65521;
    }
    const uint32_t adler = (s << 16) | a;
    for (int i = 3; i >= 0; i--) zlib.push_back(uint8_t(adler >> (8 * i)));
    std::vector<uint8_t> png = {0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
    const auto chunk = [&](const char *type, const std::vector<uint8_t> &data) {
        for (int i = 3; i >= 0; i--) png.push_back(uint8_t(data.size() >> (8 * i)));
        std::vector<uint8_t> body(type, type + 4);
        body.insert(body.end(), data.begin(), data.end());
        png.insert(png.end(), body.begin(), body.end());
        const uint32_t crc = Crc32(body.data(), body.size());
        for (int i = 3; i >= 0; i--) png.push_back(uint8_t(crc >> (8 * i)));
    };
    std::vector<uint8_t> header;
    for (int v : {size, size})
        for (int i = 3; i >= 0; i--) header.push_back(uint8_t(v >> (8 * i)));
    header.insert(header.end(), {8, 2, 0, 0, 0});
    chunk("IHDR", header);
    chunk("IDAT", zlib);
    chunk("IEND", {});
    return png;
}

}
