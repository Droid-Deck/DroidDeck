#include "tests.h"
#include <iostream>
int main(int argc, char **argv) {
    try {
        const int count = droiddeck::xr::runTests(argc > 1 ? argv[1] : "/tmp");
        std::cout << "{\"source\":\"synthetic\",\"checks\":" << count << ",\"passed\":true}\n";
    } catch (const std::exception &e) { std::cerr << e.what() << '\n'; return 1; }
}
