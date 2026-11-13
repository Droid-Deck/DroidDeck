#pragma once

#include "sdk_api.h"
#include <string>
#include <vector>

const VitureSdkApi* viture_loaded_api();

class VitureSdkLoader {
public:
    VitureSdkApi api;
    bool loaded() const { return api.GetVersionString != nullptr; }
    bool load(const std::string& directory, std::string& error, const std::string& expected_version = "");

private:
    std::vector<void*> handles;
};
