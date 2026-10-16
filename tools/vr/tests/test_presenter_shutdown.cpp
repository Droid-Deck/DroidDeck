#include "../src/presenter.h"
#include <cassert>
#include <chrono>
#include <cstdlib>
#include <cstdio>
#include <thread>
#include <vector>

int main() {
    setenv("DROIDDECK_VR_PRESENT", "questframes", 1);
    setenv("VK_ICD_FILENAMES", "/droiddeck-test-no-icd.json", 1);
    setenv("VK_DRIVER_FILES", "/droiddeck-test-no-icd.json", 1);
    auto started = std::chrono::steady_clock::now();
    std::vector<std::thread> callers;
    for (int index = 0; index < 8; index++) callers.emplace_back([] {
        droiddeck::EyeFrame frame{};
        for (int count = 0; count < 1000; count++) droiddeck::Presenter::Submit(frame);
    });
    for (auto &caller : callers) caller.join();
    droiddeck::Presenter::Shutdown();
    assert(std::chrono::steady_clock::now() - started < std::chrono::seconds(5));
    std::puts("Presenter validation passed: unavailable GPU releases concurrent blocking callers and shutdown");
}
