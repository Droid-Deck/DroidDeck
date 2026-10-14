#pragma once
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <fstream>
#include <sstream>

namespace winfg {

struct Config {
    bool     enabled     = false;







    bool     debug       = false;
    bool     pacing      = false;
    int      model       = 4;
    int      multiplier  = 2;













    int      extraImages = 2;











    int      perfPreset  = 1;
    float    flowScale   = 1.0f;






    int      gmMode      = 0;









    int      frMode      = 0;
    int      frIters     = 4;
    float    frLambda    = 2.0f;
    float    frDt        = 0.25f;
    float    frEdge      = 8.0f;
    float    frEps       = 0.05f;

    float    beta        = 8.0f;
    float    lambda      = 0.6f;










    float    epsilon     = 0.07f;
    float    photoScale  = 7.5f;






    float    hudX0       = 0.0f;
    float    hudY0       = 0.0f;
    float    hudX1       = 0.0f;
    float    hudY1       = 0.0f;

    bool     capture       = false;
    int      captureW      = 1280;
    int      captureH      = 720;
    int      capMode       = 0;
    int      capPatches    = 3;
    int      capPatchSize  = 256;


    float    capMotion     = 2.0f;
    std::string capDir;
    int      capShardMB    = 1024;

    void sanitize() {
        if (model < 3) model = 3; if (model > 4) model = 4;
        if (multiplier < 2) multiplier = 2; if (multiplier > 4) multiplier = 4;
        if (extraImages < 0) extraImages = 0; if (extraImages > 8) extraImages = 8;
        if (perfPreset < 0 || perfPreset > 2) perfPreset = 1;
        if (gmMode < 0) gmMode = 0; if (gmMode > 2) gmMode = 2;
        if (frMode < 0) frMode = 0; if (frMode > 2) frMode = 2;
        if (frIters < 0) frIters = 0; if (frIters > 16) frIters = 16;
        if (frLambda < 0.0f) frLambda = 0.0f;
        if (frDt < 0.01f) frDt = 0.01f; if (frDt > 4.0f) frDt = 4.0f;
        if (frEdge < 0.0f) frEdge = 0.0f;
        if (frEps < 1e-3f) frEps = 1e-3f;
        if (flowScale < 0.05f) flowScale = 0.05f; if (flowScale > 4.0f) flowScale = 4.0f;
        if (beta < 0.0f) beta = 0.0f; if (lambda < 0.0f) lambda = 0.0f;
        if (captureW < 16) captureW = 16; if (captureW > 7680) captureW = 7680;
        if (captureH < 16) captureH = 16; if (captureH > 4320) captureH = 4320;
        if (capMode < 0) capMode = 0; if (capMode > 1) capMode = 1;
        if (capPatches < 1) capPatches = 1; if (capPatches > 16) capPatches = 16;
        if (capPatchSize < 16) capPatchSize = 16; if (capPatchSize > 1024) capPatchSize = 1024;
        if (capMotion < 0.0f) capMotion = 0.0f;
        if (capShardMB < 16) capShardMB = 16; if (capShardMB > 65536) capShardMB = 65536;
    }
};

static inline float envf(const char* k, float d) {
    const char* v = std::getenv(k); return v ? std::strtof(v, nullptr) : d;
}
static inline int envi(const char* k, int d) {
    const char* v = std::getenv(k); return v ? std::atoi(v) : d;
}

static inline int parse_tristate(const std::string& v, int dflt) {
    if (v.empty()) return dflt;
    if (v == "auto") return 0;
    if (v == "on"  || v == "1" || v == "force" || v == "true"  || v == "yes") return 1;
    if (v == "off" || v == "0" || v == "false" || v == "no")                  return 2;
    return dflt;
}

static inline bool parse_bool(const std::string& v, bool dflt) {
    int t = parse_tristate(v, dflt ? 1 : 2);
    return t == 1;
}
static inline int parse_capmode(const std::string& v, int dflt) {
    if (v == "patch" || v == "patches" || v == "0") return 0;
    if (v == "frame" || v == "frames"  || v == "1") return 1;
    return dflt;
}

static inline void apply_toml(Config& c, const std::string& path) {
    std::ifstream f(path);
    if (!f) return;
    std::string line;
    while (std::getline(f, line)) {
        auto h = line.find('#'); if (h != std::string::npos) line = line.substr(0, h);
        auto eq = line.find('='); if (eq == std::string::npos) continue;
        auto trim = [](std::string s){
            size_t a = s.find_first_not_of(" \t\r\n"); size_t b = s.find_last_not_of(" \t\r\n");
            return (a == std::string::npos) ? std::string() : s.substr(a, b - a + 1); };
        std::string k = trim(line.substr(0, eq)), v = trim(line.substr(eq + 1));
        if (k.empty() || v.empty()) continue;
        if      (k == "enabled")    c.enabled = (v == "1" || v == "true");
        else if (k == "debug")      c.debug = parse_bool(v, c.debug);
        else if (k == "pacing")     c.pacing = parse_bool(v, c.pacing);
        else if (k == "model")      c.model = std::atoi(v.c_str());
        else if (k == "multiplier") c.multiplier = std::atoi(v.c_str());
        else if (k == "extra_images") c.extraImages = std::atoi(v.c_str());
        else if (k == "perf_preset") c.perfPreset = std::atoi(v.c_str());
        else if (k == "global_motion") c.gmMode = parse_tristate(v, c.gmMode);
        else if (k == "flow_reg")   c.frMode = parse_tristate(v, c.frMode);
        else if (k == "fr_iters")   c.frIters = std::atoi(v.c_str());
        else if (k == "fr_lambda")  c.frLambda = std::strtof(v.c_str(), nullptr);
        else if (k == "fr_dt")      c.frDt = std::strtof(v.c_str(), nullptr);
        else if (k == "fr_edge")    c.frEdge = std::strtof(v.c_str(), nullptr);
        else if (k == "fr_eps")     c.frEps = std::strtof(v.c_str(), nullptr);
        else if (k == "flowScale")  c.flowScale = std::strtof(v.c_str(), nullptr);
        else if (k == "beta")       c.beta = std::strtof(v.c_str(), nullptr);
        else if (k == "lambda")     c.lambda = std::strtof(v.c_str(), nullptr);
        else if (k == "epsilon")    c.epsilon = std::strtof(v.c_str(), nullptr);
        else if (k == "photoScale") c.photoScale = std::strtof(v.c_str(), nullptr);
        else if (k == "capture")        c.capture = parse_bool(v, c.capture);
        else if (k == "capture_dir")    c.capDir = v;
        else if (k == "capture_width")  c.captureW = std::atoi(v.c_str());
        else if (k == "capture_height") c.captureH = std::atoi(v.c_str());
        else if (k == "capture_mode")   c.capMode = parse_capmode(v, c.capMode);
        else if (k == "capture_patches")    c.capPatches = std::atoi(v.c_str());
        else if (k == "capture_patch_size") c.capPatchSize = std::atoi(v.c_str());
        else if (k == "capture_motion")     c.capMotion = std::strtof(v.c_str(), nullptr);
        else if (k == "capture_shard_mb")   c.capShardMB = std::atoi(v.c_str());
        else if (k == "hudRect") {
            float r[4] = {0,0,0,0}; int n = 0;
            size_t start = 0; std::string s = v;
            for (; n < 4; ++n) {
                size_t comma = s.find(',', start);
                std::string tok = s.substr(start, comma == std::string::npos ? std::string::npos : comma - start);
                r[n] = std::strtof(tok.c_str(), nullptr);
                if (comma == std::string::npos) break;
                start = comma + 1;
            }
            c.hudX0 = r[0]; c.hudY0 = r[1]; c.hudX1 = r[2]; c.hudY1 = r[3];
        }
    }
}

static inline std::string conf_path() {
    if (const char* p = std::getenv("WIN_FG_CONF")) return p;
    const char* home = std::getenv("HOME");
    return home ? std::string(home) + "/.config/win-fg/conf.toml" : std::string();
}

static inline std::string capture_root(const Config& c) {
    if (!c.capDir.empty()) return c.capDir;
    if (const char* home = std::getenv("HOME")) return std::string(home) + "/.cache/winfg-capture";
    return std::string("/tmp/winfg-capture");
}

static inline Config load_config() {
    Config c;
    c.enabled    = envi("WIN_FG_ENABLE", 0) != 0;
    if (const char* d = std::getenv("WIN_FG_DEBUG")) c.debug = parse_bool(d, c.debug);
    if (const char* pc = std::getenv("WIN_FG_PACING")) c.pacing = parse_bool(pc, c.pacing);
    c.model      = envi("WIN_FG_MODEL", c.model);
    c.multiplier = envi("WIN_FG_MULT", c.multiplier);
    c.extraImages = envi("WIN_FG_EXTRA_IMAGES", c.extraImages);
    c.perfPreset  = envi("WIN_FG_PERF_PRESET", c.perfPreset);
    if (const char* g = std::getenv("WIN_FG_GM")) c.gmMode = parse_tristate(g, c.gmMode);
    if (const char* r = std::getenv("WIN_FG_FLOWREG")) c.frMode = parse_tristate(r, c.frMode);
    c.frIters    = envi("WIN_FG_FR_ITERS", c.frIters);
    c.frLambda   = envf("WIN_FG_FR_LAMBDA", c.frLambda);
    c.frDt       = envf("WIN_FG_FR_DT", c.frDt);
    c.frEdge     = envf("WIN_FG_FR_EDGE", c.frEdge);
    c.frEps      = envf("WIN_FG_FR_EPS", c.frEps);
    c.flowScale  = envf("WIN_FG_FLOWSCALE", c.flowScale);
    c.beta       = envf("WIN_FG_BETA", c.beta);
    c.lambda     = envf("WIN_FG_LAMBDA", c.lambda);
    c.epsilon    = envf("WIN_FG_EPSILON", c.epsilon);
    c.photoScale = envf("WIN_FG_PHOTOSCALE", c.photoScale);
    if (const char* cc = std::getenv("WIN_FG_CAPTURE")) c.capture = parse_bool(cc, c.capture);
    c.captureW     = envi("WIN_FG_CAPTURE_W", c.captureW);
    c.captureH     = envi("WIN_FG_CAPTURE_H", c.captureH);
    if (const char* cm = std::getenv("WIN_FG_CAPTURE_MODE")) c.capMode = parse_capmode(cm, c.capMode);
    c.capPatches   = envi("WIN_FG_CAPTURE_PATCHES", c.capPatches);
    c.capPatchSize = envi("WIN_FG_CAPTURE_PATCH", c.capPatchSize);
    c.capMotion    = envf("WIN_FG_CAPTURE_MOTION", c.capMotion);
    c.capShardMB   = envi("WIN_FG_CAPTURE_SHARD_MB", c.capShardMB);
    if (const char* cd = std::getenv("WIN_FG_CAPTURE_DIR")) c.capDir = cd;
    if (const char* h = std::getenv("WIN_FG_HUD_RECT")) {
        float r[4] = {0,0,0,0}; int n = 0;
        std::string s = h; size_t start = 0;
        for (; n < 4; ++n) {
            size_t comma = s.find(',', start);
            r[n] = std::strtof(s.substr(start, comma == std::string::npos ? std::string::npos : comma - start).c_str(), nullptr);
            if (comma == std::string::npos) break;
            start = comma + 1;
        }
        c.hudX0 = r[0]; c.hudY0 = r[1]; c.hudX1 = r[2]; c.hudY1 = r[3];
    }
    const char* home = std::getenv("HOME");
    if (home) apply_toml(c, std::string(home) + "/.config/win-fg/conf.toml");
    if (const char* p = std::getenv("WIN_FG_CONF")) apply_toml(c, p);
    c.sanitize();
    return c;
}

struct SynthUBO {
    float flowScale, alpha, beta, lambda, epsilon, photoScale, pad0, pad1;
    float hudX0, hudY0, hudX1, hudY1;
};
struct FlowUBO { float flowScale; uint32_t level; float occlLo; float occlHi; };

struct GMUBO {
    float lin[4];
    float linT[4];
    float app[4];
    float appT[4];
};

struct FlowRegUBO {
    float dt;
    float lambda;
    float edgeAlpha;
    float epsTV;
};

}
