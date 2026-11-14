#pragma once
#include <openxr/openxr.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <memory>
#include <stdexcept>
#include <vector>
#include "xr_model_asset.h"
#include "../../../../../tools/vr/third_party/cgltf/cgltf.h"

namespace xr_reference {
using Matrix = std::array<float, 16>;

inline Matrix multiply(const Matrix &a, const Matrix &b) {
    Matrix result{};
    for (int column = 0; column < 4; ++column)
        for (int row = 0; row < 4; ++row)
            for (int k = 0; k < 4; ++k) result[column * 4 + row] += a[k * 4 + row] * b[column * 4 + k];
    return result;
}

inline Matrix poseMatrix(const XrPosef &pose) {
    const auto &q = pose.orientation;
    return {1 - 2 * (q.y * q.y + q.z * q.z), 2 * (q.x * q.y + q.z * q.w), 2 * (q.x * q.z - q.y * q.w), 0,
            2 * (q.x * q.y - q.z * q.w), 1 - 2 * (q.x * q.x + q.z * q.z), 2 * (q.y * q.z + q.x * q.w), 0,
            2 * (q.x * q.z + q.y * q.w), 2 * (q.y * q.z - q.x * q.w), 1 - 2 * (q.x * q.x + q.y * q.y), 0,
            pose.position.x, pose.position.y, pose.position.z, 1};
}

inline Matrix inversePose(const XrPosef &pose) {
    XrPosef inverse{{-pose.orientation.x, -pose.orientation.y, -pose.orientation.z, pose.orientation.w}, {}};
    auto matrix = poseMatrix(inverse);
    for (int row = 0; row < 3; ++row)
        matrix[12 + row] = -(matrix[row] * pose.position.x + matrix[4 + row] * pose.position.y +
                            matrix[8 + row] * pose.position.z);
    return matrix;
}

inline Matrix projection(const XrFovf &fov, float nearPlane = .02f, float farPlane = 20.f) {
    const float left = std::tan(fov.angleLeft), right = std::tan(fov.angleRight);
    const float down = std::tan(fov.angleDown), up = std::tan(fov.angleUp);
    Matrix matrix{};
    matrix[0] = 2 / (right - left);
    matrix[5] = 2 / (up - down);
    matrix[8] = (right + left) / (right - left);
    matrix[9] = (up + down) / (up - down);
    matrix[10] = -(farPlane + nearPlane) / (farPlane - nearPlane);
    matrix[11] = -1;
    matrix[14] = -2 * farPlane * nearPlane / (farPlane - nearPlane);
    return matrix;
}

class Asset {
public:
    struct Primitive {
        size_t node = 0;
        std::vector<float> positions;
        std::vector<uint32_t> indices, edges;
    };
    void load(const std::vector<uint8_t> &bytes) {
        bytes_ = bytes;
        cgltf_options options{};
        cgltf_data *parsed = nullptr;
        require(cgltf_parse(&options, bytes_.data(), bytes_.size(), &parsed) == cgltf_result_success,
                "Invalid reference GLB");
        data_.reset(parsed);
        require(parsed->file_type == cgltf_file_type_glb && parsed->buffers_count == 1 &&
                !parsed->buffers[0].uri && !parsed->extensions_required_count,
                "Unsupported reference GLB storage");
        require(cgltf_load_buffers(&options, parsed, nullptr) == cgltf_result_success &&
                cgltf_validate(parsed) == cgltf_result_success, "Invalid reference GLB buffers");
        require(parsed->nodes_count <= DROIDDECK_XR_MODEL_NODES, "Too many reference nodes");
        visible_.assign(parsed->nodes_count, true);
        primitives.clear();
        const auto *scene = parsed->scene ? parsed->scene : (parsed->scenes_count ? &parsed->scenes[0] : nullptr);
        require(scene != nullptr, "Missing reference scene");
        for (cgltf_size i = 0; i < scene->nodes_count; ++i) visit(scene->nodes[i]);
        require(!primitives.empty(), "Empty reference GLB");
    }
    void update(const std::vector<XrModelNodeTransform> &states) {
        for (const auto &state : states) {
            size_t matches = 0;
            for (cgltf_size i = 0; i < data_->nodes_count; ++i) {
                auto &node = data_->nodes[i];
                if (!node.name || state.name != node.name) continue;
                ++matches;
                visible_[i] = state.visible;
                node.has_matrix = false;
                node.has_translation = node.has_rotation = node.has_scale = true;
                node.scale[0] = node.scale[1] = node.scale[2] = 1;
                float length = 0;
                for (int axis = 0; axis < 3; ++axis) {
                    require(std::isfinite(state.position[axis]), "Invalid reference node translation");
                    node.translation[axis] = state.position[axis];
                }
                for (int axis = 0; axis < 4; ++axis) {
                    require(std::isfinite(state.orientation[axis]), "Invalid reference node rotation");
                    node.rotation[axis] = state.orientation[axis];
                    length += node.rotation[axis] * node.rotation[axis];
                }
                require(std::abs(length - 1) < .01f, "Invalid reference quaternion");
            }
            require(matches == 1, "Ambiguous reference node");
        }
        for (cgltf_size i = 0; i < data_->nodes_count; ++i)
            require(!data_->nodes[i].skin || !visible(i), "Visible skinned reference nodes are unsupported");
    }
    Matrix transform(size_t index) const {
        Matrix matrix{};
        cgltf_node_transform_world(&data_->nodes[index], matrix.data());
        return matrix;
    }
    bool visible(size_t index) const {
        for (auto *node = &data_->nodes[index]; node; node = node->parent)
            if (!visible_[node - data_->nodes]) return false;
        return true;
    }
    std::vector<Primitive> primitives;
private:
    static void require(bool condition, const char *text) {
        if (!condition) throw std::runtime_error(text);
    }
    void visit(const cgltf_node *node) {
        if (node->mesh && !node->skin) for (cgltf_size i = 0; i < node->mesh->primitives_count; ++i) {
            const auto &source = node->mesh->primitives[i];
            require(source.type == cgltf_primitive_type_triangles && !source.targets_count,
                    "Unsupported reference primitive");
            const cgltf_accessor *positions = nullptr;
            for (cgltf_size a = 0; a < source.attributes_count; ++a)
                if (source.attributes[a].type == cgltf_attribute_type_position)
                    positions = source.attributes[a].data;
            require(positions && positions->count <= 2000000, "Invalid reference vertices");
            Primitive primitive;
            primitive.node = node - data_->nodes;
            primitive.positions.resize(positions->count * 3);
            for (size_t v = 0; v < positions->count; ++v) {
                auto *position = primitive.positions.data() + v * 3;
                require(cgltf_accessor_read_float(positions, v, position, 3), "Invalid reference position");
                for (int axis = 0; axis < 3; ++axis)
                    require(std::isfinite(position[axis]), "Nonfinite reference vertex");
            }
            const size_t count = source.indices ? source.indices->count : positions->count;
            require(count % 3 == 0 && count <= 6000000, "Invalid reference indices");
            primitive.indices.resize(count);
            for (size_t i = 0; i < count; ++i) {
                const auto index = source.indices ? cgltf_accessor_read_index(source.indices, i) : i;
                require(index < positions->count, "Reference index out of bounds");
                primitive.indices[i] = static_cast<uint32_t>(index);
            }
            primitive.edges.reserve(count * 2);
            for (size_t i = 0; i < count; i += 3)
                for (int corner = 0; corner < 3; ++corner) {
                    primitive.edges.push_back(primitive.indices[i + corner]);
                    primitive.edges.push_back(primitive.indices[i + (corner + 1) % 3]);
                }
            primitives.push_back(std::move(primitive));
        }
        for (cgltf_size i = 0; i < node->children_count; ++i) visit(node->children[i]);
    }
    std::vector<uint8_t> bytes_;
    std::unique_ptr<cgltf_data, decltype(&cgltf_free)> data_{nullptr, cgltf_free};
    std::vector<bool> visible_;
};
}
