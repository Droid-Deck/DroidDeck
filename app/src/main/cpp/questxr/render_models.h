#pragma once
#include <openxr/openxr.h>
#include <array>
#include <chrono>
#include <cstring>
#include <functional>
#include <future>
#include <filesystem>
#include <string>
#include <vector>
#include "xr_controller_pose.h"
#include "xr_model_asset.h"
#include "controller_location.h"

class XrRenderModels {
public:
    static constexpr std::array<const char *, 3> extensions{
        XR_EXT_UUID_EXTENSION_NAME, XR_EXT_RENDER_MODEL_EXTENSION_NAME, XR_EXT_INTERACTION_RENDER_MODEL_EXTENSION_NAME};

    static bool available(const std::vector<XrExtensionProperties> &properties) {
        for (const char *name : extensions) {
            bool found = false;
            for (const auto &property : properties) found |= !std::strcmp(name, property.extensionName);
            if (!found) return false;
        }
        return true;
    }

    bool initialize(XrInstance instance, XrSession session, const XrPath hands[2],
                    PFN_xrGetInstanceProcAddr get, PFN_xrLocateSpace locate, PFN_xrDestroySpace destroy,
                    const std::string &directory, std::function<void(const std::string &)> log) {
        session_ = session; locate_ = locate; destroySpace_ = destroy;
        hands_[0] = hands[0]; hands_[1] = hands[1]; directory_ = directory; log_ = std::move(log);
        std::filesystem::create_directories(directory_);
        if (!stateFile_.open((std::filesystem::path(directory_).parent_path() / DROIDDECK_XR_MODEL_FILE).string(), true))
            return false;
#define LOAD(name) if (XR_FAILED(get(instance, #name, reinterpret_cast<PFN_xrVoidFunction *>(&name))) || !name) return false
        LOAD(xrEnumerateInteractionRenderModelIdsEXT);
        LOAD(xrCreateRenderModelEXT); LOAD(xrDestroyRenderModelEXT);
        LOAD(xrGetRenderModelPoseTopLevelUserPathEXT);
        LOAD(xrGetRenderModelPropertiesEXT); LOAD(xrCreateRenderModelSpaceEXT);
        LOAD(xrCreateRenderModelAssetEXT); LOAD(xrDestroyRenderModelAssetEXT); LOAD(xrGetRenderModelAssetDataEXT);
        LOAD(xrGetRenderModelAssetPropertiesEXT); LOAD(xrGetRenderModelStateEXT);
#undef LOAD
        enabled_ = true;
        return true;
    }

    void changed() { dirty_ = true; nextDiscover_ = {}; }
    bool enabled() const { return enabled_; }
    bool ready(int hand) const { return models_[hand].ready; }
    const XrSpaceLocation &location(int hand) const { return models_[hand].location; }
    const XrControllerLocation &tracking(int hand) const { return models_[hand].tracking; }
    const std::string &name(int hand) const { return models_[hand].name; }
    const std::vector<uint8_t> &asset(int hand) const { return models_[hand].bytes; }
    const std::vector<XrModelNodeTransform> &nodes(int hand) const { return models_[hand].transforms; }
    XrSpaceLocation locate(int hand, XrSpace base, XrTime time) const {
        XrSpaceLocation result{XR_TYPE_SPACE_LOCATION};
        if (!models_[hand].space || XR_FAILED(locate_(models_[hand].space, base, time, &result)))
            result.locationFlags = 0;
        return result;
    }

    void update(XrSpace base, XrTime time, const XrSpaceLocation *baseLocation = nullptr,
                XrSpace fallback = XR_NULL_HANDLE) {
        if (!enabled_) return;
        const auto now = std::chrono::steady_clock::now();
        if (dirty_ && now >= nextDiscover_) {
            dirty_ = false;
            nextDiscover_ = now + std::chrono::seconds(1);
            try { discover(base, time, baseLocation, fallback); }
            catch (const std::exception &error) { log_(error.what()); dirty_ = true; }
        }
        for (auto &model : models_) {
            model.location = {XR_TYPE_SPACE_LOCATION};
            model.tracking = {};
            if (model.exporting.valid() && model.exporting.wait_for(std::chrono::seconds(0)) == std::future_status::ready) {
                const auto error = model.exporting.get();
                model.ready = error.empty();
                log_(model.ready ? "native model ready: " + model.name : "native model export failed: " + error);
            }
            if (model.space) {
                if (baseLocation && fallback) {
                    model.tracking = xrLocateController(locate_, model.space, base, fallback, time, *baseLocation);
                    model.location = model.tracking.location;
                } else {
                    const auto result = locate_(model.space, base, time, &model.location);
                    if (XR_FAILED(result)) model.location.locationFlags = 0;
                    if (baseLocation) model.location = xrComposeLocation(*baseLocation, model.location);
                }
            }
        }
        statePacket_.sampled_ns = XrTrackingFile::Now();
        for (int hand = 0; hand < 2; ++hand) {
            auto &model = models_[hand];
            statePacket_.count[hand] = 0;
            std::memcpy(statePacket_.key[hand], model.key.data(), model.key.size());
            if (!model.ready || !model.animation) continue;
            XrRenderModelStateGetInfoEXT info{XR_TYPE_RENDER_MODEL_STATE_GET_INFO_EXT};
            info.displayTime = time;
            XrRenderModelStateEXT state{XR_TYPE_RENDER_MODEL_STATE_EXT};
            state.nodeStateCount = model.states.size(); state.nodeStates = model.states.data();
            if (XR_FAILED(xrGetRenderModelStateEXT(model.handle, &info, &state))) continue;
            for (size_t node = 0; node < model.states.size(); ++node) {
                const auto &pose = model.states[node].nodePose;
                auto &transform = model.transforms[node];
                transform.orientation = {pose.orientation.x, pose.orientation.y, pose.orientation.z, pose.orientation.w};
                transform.position = {pose.position.x, pose.position.y, pose.position.z};
                transform.visible = model.states[node].isVisible == XR_TRUE;
            }
            try {
                statePacket_.count[hand] = model.animation->update(model.transforms, statePacket_.nodes[hand]);
            } catch (const std::exception &error) {
                log_("native model animation failed: " + std::string(error.what()));
            }
        }
        stateFile_.write(statePacket_);
    }

    void publish(int hand, DroidDeckXrHand &packet) const {
        const auto &model = models_[hand];
        if (!model.ready) return;
        const auto &pose = model.location.pose;
        packet.model = {{pose.orientation.x, pose.orientation.y, pose.orientation.z, pose.orientation.w},
                        {pose.position.x, pose.position.y, pose.position.z}, uint32_t(model.location.locationFlags)};
        std::memcpy(packet.model_key, model.key.data(), model.key.size());
    }

    void cleanup() {
        for (auto &model : models_) reset(model);
        stateFile_.write(DroidDeckXrModelState{});
        enabled_ = false;
    }

private:
    struct Model {
        XrRenderModelIdEXT id = XR_NULL_RENDER_MODEL_ID_EXT;
        XrRenderModelEXT handle = XR_NULL_HANDLE;
        XrSpace space = XR_NULL_HANDLE;
        std::array<uint8_t, 16> key{};
        std::string name;
        std::future<std::string> exporting;
        bool ready = false;
        XrSpaceLocation location{XR_TYPE_SPACE_LOCATION};
        XrControllerLocation tracking;
        std::vector<XrRenderModelNodeStateEXT> states;
        std::vector<XrModelNodeTransform> transforms;
        std::vector<uint8_t> bytes;
        std::unique_ptr<XrModelAnimation> animation;
    };
    Model models_[2];
    XrSession session_ = XR_NULL_HANDLE;
    XrPath hands_[2]{};
    PFN_xrLocateSpace locate_ = nullptr;
    PFN_xrDestroySpace destroySpace_ = nullptr;
    bool enabled_ = false, dirty_ = true;
    std::chrono::steady_clock::time_point nextDiscover_{};
    std::string directory_;
    std::function<void(const std::string &)> log_;
    XrModelStateFile stateFile_;
    DroidDeckXrModelState statePacket_{};
    PFN_xrEnumerateInteractionRenderModelIdsEXT xrEnumerateInteractionRenderModelIdsEXT = nullptr;
    PFN_xrCreateRenderModelEXT xrCreateRenderModelEXT = nullptr;
    PFN_xrDestroyRenderModelEXT xrDestroyRenderModelEXT = nullptr;
    PFN_xrGetRenderModelPoseTopLevelUserPathEXT xrGetRenderModelPoseTopLevelUserPathEXT = nullptr;
    PFN_xrGetRenderModelPropertiesEXT xrGetRenderModelPropertiesEXT = nullptr;
    PFN_xrCreateRenderModelSpaceEXT xrCreateRenderModelSpaceEXT = nullptr;
    PFN_xrCreateRenderModelAssetEXT xrCreateRenderModelAssetEXT = nullptr;
    PFN_xrDestroyRenderModelAssetEXT xrDestroyRenderModelAssetEXT = nullptr;
    PFN_xrGetRenderModelAssetDataEXT xrGetRenderModelAssetDataEXT = nullptr;
    PFN_xrGetRenderModelAssetPropertiesEXT xrGetRenderModelAssetPropertiesEXT = nullptr;
    PFN_xrGetRenderModelStateEXT xrGetRenderModelStateEXT = nullptr;

    static void check(XrResult result, const char *name) {
        if (XR_FAILED(result)) throw std::runtime_error(std::string(name) + ": " + std::to_string(result));
    }

    void reset(Model &model) {
        if (model.exporting.valid()) model.exporting.get();
        if (model.space && destroySpace_) destroySpace_(model.space);
        if (model.handle && xrDestroyRenderModelEXT) xrDestroyRenderModelEXT(model.handle);
        model = Model{};
    }

    void load(Model &model, int hand, XrTime time) {
        XrRenderModelPropertiesGetInfoEXT info{XR_TYPE_RENDER_MODEL_PROPERTIES_GET_INFO_EXT};
        XrRenderModelPropertiesEXT properties{XR_TYPE_RENDER_MODEL_PROPERTIES_EXT};
        check(xrGetRenderModelPropertiesEXT(model.handle, &info, &properties), "render model properties");
        std::memcpy(model.key.data(), properties.cacheId.data, model.key.size());
        model.name = droiddeck::XrRuntimeModelName(model.key.data(), hand == 1);
        if (model.name.empty()) throw std::runtime_error("Runtime model has an invalid cache UUID");
        XrRenderModelAssetCreateInfoEXT create{XR_TYPE_RENDER_MODEL_ASSET_CREATE_INFO_EXT};
        create.cacheId = properties.cacheId;
        XrRenderModelAssetEXT asset = XR_NULL_HANDLE;
        check(xrCreateRenderModelAssetEXT(session_, &create, &asset), "render model asset");
        std::vector<uint8_t> bytes;
        std::vector<XrModelNodeTransform> nodes;
        try {
            XrRenderModelAssetDataGetInfoEXT get{XR_TYPE_RENDER_MODEL_ASSET_DATA_GET_INFO_EXT};
            XrRenderModelAssetDataEXT data{XR_TYPE_RENDER_MODEL_ASSET_DATA_EXT};
            check(xrGetRenderModelAssetDataEXT(asset, &get, &data), "render model asset size");
            if (data.bufferCountOutput < 20 || data.bufferCountOutput > 64 * 1024 * 1024)
                throw std::runtime_error("Runtime model asset exceeds size limit");
            bytes.resize(data.bufferCountOutput);
            data.bufferCapacityInput = bytes.size(); data.buffer = bytes.data();
            check(xrGetRenderModelAssetDataEXT(asset, &get, &data), "render model asset bytes");
            bytes.resize(data.bufferCountOutput);
            if (properties.animatableNodeCount > 8192) throw std::runtime_error("Too many runtime model nodes");
            std::vector<XrRenderModelAssetNodePropertiesEXT> names(properties.animatableNodeCount);
            XrRenderModelAssetPropertiesGetInfoEXT nodeInfo{XR_TYPE_RENDER_MODEL_ASSET_PROPERTIES_GET_INFO_EXT};
            XrRenderModelAssetPropertiesEXT nodeProperties{XR_TYPE_RENDER_MODEL_ASSET_PROPERTIES_EXT};
            nodeProperties.nodePropertyCount = names.size(); nodeProperties.nodeProperties = names.data();
            check(xrGetRenderModelAssetPropertiesEXT(asset, &nodeInfo, &nodeProperties), "render model node properties");
            std::vector<XrRenderModelNodeStateEXT> states(names.size());
            XrRenderModelStateGetInfoEXT stateInfo{XR_TYPE_RENDER_MODEL_STATE_GET_INFO_EXT};
            stateInfo.displayTime = time;
            XrRenderModelStateEXT state{XR_TYPE_RENDER_MODEL_STATE_EXT};
            state.nodeStateCount = states.size(); state.nodeStates = states.data();
            check(xrGetRenderModelStateEXT(model.handle, &stateInfo, &state), "render model node state");
            for (size_t i = 0; i < names.size(); ++i) {
                const auto &pose = states[i].nodePose;
                nodes.push_back({names[i].uniqueName,
                    {pose.orientation.x, pose.orientation.y, pose.orientation.z, pose.orientation.w},
                    {pose.position.x, pose.position.y, pose.position.z}, states[i].isVisible == XR_TRUE});
                log_("native model node: hand=" + std::to_string(hand) + " name=" + nodes.back().name +
                     " visible=" + std::to_string(nodes.back().visible));
            }
        } catch (...) {
            xrDestroyRenderModelAssetEXT(asset);
            throw;
        }
        xrDestroyRenderModelAssetEXT(asset);
        model.states.resize(nodes.size());
        model.transforms = nodes;
        model.animation = std::make_unique<XrModelAnimation>();
        std::string animationError;
        if (!model.animation->initialize(bytes, nodes, animationError))
            throw std::runtime_error(animationError);
        model.bytes = bytes;
        const auto directory = directory_, name = model.name;
        model.exporting = std::async(std::launch::async, [directory, name, bytes = std::move(bytes), nodes = std::move(nodes)] {
            std::string error;
            xr_export_model_asset(bytes, directory, name, error, nodes);
            return error;
        });
        log_("native model asset: " + model.name + " animated nodes=" + std::to_string(properties.animatableNodeCount));
    }

    void discover(XrSpace base, XrTime time, const XrSpaceLocation *baseLocation, XrSpace fallback) {
        XrInteractionRenderModelIdsEnumerateInfoEXT info{XR_TYPE_INTERACTION_RENDER_MODEL_IDS_ENUMERATE_INFO_EXT};
        uint32_t count = 0;
        check(xrEnumerateInteractionRenderModelIdsEXT(session_, &info, 0, &count, nullptr), "render model count");
        if (count > 64) throw std::runtime_error("Too many runtime render models");
        std::vector<XrRenderModelIdEXT> ids(count);
        check(xrEnumerateInteractionRenderModelIdsEXT(session_, &info, count, &count, ids.data()), "render model IDs");
        ids.resize(count);
        bool found[2]{};
        for (const auto id : ids) {
            bool existing = false;
            for (int hand = 0; hand < 2; ++hand) if (models_[hand].id == id) {
                found[hand] = true; existing = true;
            }
            if (existing || !id) continue;
            Model candidate;
            candidate.id = id;
            XrRenderModelCreateInfoEXT create{XR_TYPE_RENDER_MODEL_CREATE_INFO_EXT};
            create.renderModelId = id;
            if (XR_FAILED(xrCreateRenderModelEXT(session_, &create, &candidate.handle))) continue;
            XrInteractionRenderModelTopLevelUserPathGetInfoEXT pathInfo{XR_TYPE_INTERACTION_RENDER_MODEL_TOP_LEVEL_USER_PATH_GET_INFO_EXT};
            pathInfo.topLevelUserPathCount = 2; pathInfo.topLevelUserPaths = hands_;
            XrPath path = XR_NULL_PATH;
            const auto result = xrGetRenderModelPoseTopLevelUserPathEXT(candidate.handle, &pathInfo, &path);
            const int hand = path == hands_[0] ? 0 : path == hands_[1] ? 1 : -1;
            if (XR_FAILED(result) || hand < 0 || found[hand]) { reset(candidate); continue; }
            try {
                XrRenderModelSpaceCreateInfoEXT space{XR_TYPE_RENDER_MODEL_SPACE_CREATE_INFO_EXT};
                space.renderModel = candidate.handle;
                check(xrCreateRenderModelSpaceEXT(session_, &space, &candidate.space), "render model space");
                if (baseLocation && fallback) {
                    candidate.tracking = xrLocateController(locate_, candidate.space, base, fallback, time, *baseLocation);
                    candidate.location = candidate.tracking.location;
                } else check(locate_(candidate.space, base, time, &candidate.location), "render model locate");
                log_("native model candidate: id=" + std::to_string(id) + " hand=" + std::to_string(hand) +
                     " flags=" + std::to_string(candidate.location.locationFlags));
                if ((candidate.location.locationFlags & 3) != 3) { reset(candidate); continue; }
                load(candidate, hand, time);
                reset(models_[hand]);
                models_[hand] = std::move(candidate);
                found[hand] = true;
            } catch (const std::exception &error) { log_(error.what()); reset(candidate); }
        }
        for (int hand = 0; hand < 2; ++hand) if (!found[hand]) reset(models_[hand]);
        dirty_ = !found[0] || !found[1];
    }
};
