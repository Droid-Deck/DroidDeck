#include <cstdio>
#include <string>
#include <sys/stat.h>

#include "hand_mesh.h"

using namespace droiddeck;

static bool Write(const std::string &path, const void *data, size_t size) {
    FILE *f = fopen(path.c_str(), "wb");
    if (!f) return false;
    const bool ok = fwrite(data, 1, size, f) == size;
    return fclose(f) == 0 && ok;
}

static bool Write(const std::string &path, const std::string &text) { return Write(path, text.data(), text.size()); }

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: make_hand_models <rendermodels dir>\n");
        return 2;
    }
    const std::string root = argv[1];
    mkdir(root.c_str(), 0755);
    const std::vector<uint8_t> skin = GlovePng(64, {246, 246, 249}, {224, 227, 233}, glove::kDarkest);
    for (int right = 0; right < 2; right++) {
        const std::string name = right ? "droiddeck_hand_right" : "droiddeck_hand_left";
        const std::string dir = root + "/" + name;
        mkdir(dir.c_str(), 0755);
        bool ok = Write(dir + "/" + name + ".json", HandModelJson(right)) && Write(dir + "/hand.mtl", HandMtl("hand", "hand.png")) &&
                  Write(dir + "/hand.png", skin.data(), skin.size()) &&
                  Write(dir + "/hand_open.obj", HandObj(BuildHandPart(right, HandPart::Hand, false), "hand")) &&
                  Write(dir + "/hand_fist.obj", HandObj(BuildHandPart(right, HandPart::Hand, true), "hand")) &&
                  Write(dir + "/thumb_index_open.obj", HandObj(BuildHandPart(right, HandPart::ThumbIndex, false), "hand")) &&
                  Write(dir + "/thumb_index_pinch.obj", HandObj(BuildHandPart(right, HandPart::ThumbIndex, true), "hand"));
        if (!ok) {
            fprintf(stderr, "make_hand_models: could not write %s\n", dir.c_str());
            return 1;
        }
    }
    return 0;
}
