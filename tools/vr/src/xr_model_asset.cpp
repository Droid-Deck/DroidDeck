#include "xr_model_asset.h"
#define CGLTF_IMPLEMENTATION
#include "../third_party/cgltf/cgltf.h"
#include <array>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <functional>
#include <map>
#include <memory>
#include <stdexcept>

namespace {
using Vector = std::array<float, 3>;

void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

Vector cross(const Vector &a, const Vector &b) {
    return {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
}

Vector normal(const Vector &v) {
    const float length = std::sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    require(std::isfinite(length) && length > 1e-12f, "Invalid model normal");
    return {v[0] / length, v[1] / length, v[2] / length};
}

std::ofstream output(const std::filesystem::path &path) {
    std::ofstream file(path, std::ios::binary);
    file.exceptions(std::ios::failbit | std::ios::badbit);
    file.precision(9);
    return file;
}
}

struct XrModelAnimation::Impl {
    std::vector<uint8_t> bytes;
    std::unique_ptr<cgltf_data, decltype(&cgltf_free)> data{nullptr, cgltf_free};
    std::vector<bool> visible;

    void apply(const std::vector<XrModelNodeTransform> &states) {
        for (const auto &state : states) {
            size_t matches = 0;
            for (cgltf_size i = 0; i < data->nodes_count; ++i) {
                auto &node = data->nodes[i];
                if (!node.name || state.name != node.name) continue;
                ++matches;
                visible[i] = state.visible;
                node.has_matrix = false;
                node.has_translation = node.has_rotation = node.has_scale = true;
                node.scale[0] = node.scale[1] = node.scale[2] = 1;
                for (int axis = 0; axis < 3; ++axis) {
                    require(std::isfinite(state.position[axis]), "Invalid node position");
                    node.translation[axis] = state.position[axis];
                }
                float length = 0;
                for (int axis = 0; axis < 4; ++axis) {
                    require(std::isfinite(state.orientation[axis]), "Invalid node orientation");
                    node.rotation[axis] = state.orientation[axis];
                    length += node.rotation[axis] * node.rotation[axis];
                }
                require(std::abs(length - 1) < .01f, "Invalid node orientation length");
            }
            require(matches == 1, "Runtime node name is missing or ambiguous");
        }
    }
};

XrModelAnimation::XrModelAnimation() = default;
XrModelAnimation::~XrModelAnimation() = default;

bool XrModelAnimation::initialize(const std::vector<uint8_t> &glb,
                                const std::vector<XrModelNodeTransform> &states, std::string &error) {
    try {
        auto model = std::make_unique<Impl>();
        model->bytes = glb;
        cgltf_options options{};
        cgltf_data *parsed = nullptr;
        require(cgltf_parse(&options, model->bytes.data(), model->bytes.size(), &parsed) == cgltf_result_success,
                "Invalid animation asset");
        model->data.reset(parsed);
        require(parsed->nodes_count <= DROIDDECK_XR_MODEL_NODES, "Too many model scene nodes");
        require(cgltf_load_buffers(&options, parsed, nullptr) == cgltf_result_success &&
                cgltf_validate(parsed) == cgltf_result_success, "Invalid animation buffers");
        model->visible.resize(parsed->nodes_count, true);
        model->apply(states);
        impl_ = std::move(model);
        return true;
    } catch (const std::exception &exception) {
        error = exception.what();
        return false;
    }
}

uint32_t XrModelAnimation::update(const std::vector<XrModelNodeTransform> &states, DroidDeckXrModelNode *output) {
    if (!impl_) return 0;
    impl_->apply(states);
    auto *data = impl_->data.get();
    std::fill(output, output + data->nodes_count, DroidDeckXrModelNode{});
    std::function<void(const cgltf_node *, bool)> visit = [&](const cgltf_node *node, bool visible) {
        const auto index = node - data->nodes;
        visible = visible && impl_->visible[index];
        if (node->mesh && !node->skin) {
            float world[16]{};
            cgltf_node_transform_world(node, world);
            for (int row = 0; row < 3; ++row) for (int column = 0; column < 4; ++column) {
                require(std::isfinite(world[column * 4 + row]), "Invalid model world transform");
                output[index].matrix[row * 4 + column] = world[column * 4 + row];
            }
            output[index].visible = visible;
        }
        for (cgltf_size child = 0; child < node->children_count; ++child) visit(node->children[child], visible);
    };
    const auto *scene = data->scene ? data->scene : (data->scenes_count ? &data->scenes[0] : nullptr);
    require(scene != nullptr, "Animation scene is unavailable");
    for (cgltf_size i = 0; i < scene->nodes_count; ++i) visit(scene->nodes[i], true);
    return data->nodes_count;
}

bool xr_export_model_asset(const std::vector<uint8_t> &glb, const std::string &directory,
                           const std::string &name, std::string &error,
                           const std::vector<XrModelNodeTransform> &nodes) {
    if (name.empty() || name.find_first_not_of("abcdefghijklmnopqrstuvwxyz0123456789_") != std::string::npos) {
        error = "Invalid model cache name";
        return false;
    }
    const std::filesystem::path target = std::filesystem::path(directory) / name;
    const auto pending = target.string() + ".pending";
    try {
        require(glb.size() >= 20 && glb.size() <= 64 * 1024 * 1024, "Invalid model asset size");
        const auto sources = std::filesystem::path(directory) / "source_assets";
        std::filesystem::create_directories(sources);
        {
            auto source = output(sources / (name + ".glb"));
            source.write(reinterpret_cast<const char *>(glb.data()), glb.size());
        }
        cgltf_options options{};
        cgltf_data *parsed = nullptr;
        require(cgltf_parse(&options, glb.data(), glb.size(), &parsed) == cgltf_result_success, "Invalid glTF asset");
        std::unique_ptr<cgltf_data, decltype(&cgltf_free)> data(parsed, cgltf_free);
        require(data->file_type == cgltf_file_type_glb && data->buffers_count == 1 &&
                !data->buffers[0].uri && !data->extensions_required_count, "Unsupported glTF asset storage or extensions");
        require(cgltf_load_buffers(&options, data.get(), nullptr) == cgltf_result_success &&
                cgltf_validate(data.get()) == cgltf_result_success, "Invalid glTF buffers");
        std::vector<bool> visible(data->nodes_count, true);
        for (const auto &state : nodes) {
            size_t matches = 0;
            for (cgltf_size i = 0; i < data->nodes_count; ++i) {
                auto &node = data->nodes[i];
                if (!node.name || state.name != node.name) continue;
                ++matches;
                visible[i] = state.visible;
                node.has_matrix = false;
                node.has_translation = node.has_rotation = node.has_scale = true;
                node.scale[0] = node.scale[1] = node.scale[2] = 1;
                for (int axis = 0; axis < 3; ++axis) {
                    require(std::isfinite(state.position[axis]), "Invalid node position");
                    node.translation[axis] = state.position[axis];
                }
                float length = 0;
                for (int axis = 0; axis < 4; ++axis) {
                    require(std::isfinite(state.orientation[axis]), "Invalid node orientation");
                    node.rotation[axis] = state.orientation[axis];
                    length += node.rotation[axis] * node.rotation[axis];
                }
                require(std::abs(length - 1) < .01f, "Invalid node orientation length");
            }
            require(matches == 1, "Runtime node name is missing or ambiguous");
        }
        std::filesystem::create_directories(directory);
        if (std::filesystem::is_regular_file(target / (name + ".json"))) {
            std::ifstream existing(target / (name + ".json"));
            const std::string descriptor{std::istreambuf_iterator<char>(existing), std::istreambuf_iterator<char>()};
            if (descriptor.find("\"model_state_version\":1") != std::string::npos) return true;
        }
        std::filesystem::remove_all(pending);
        std::filesystem::create_directory(pending);
        struct Part {
            std::string filename;
            std::ofstream mesh;
            size_t vertices = 0;
            size_t node = 0;
        };
        std::vector<Part> parts;
        std::map<std::pair<const cgltf_material *, const cgltf_node *>, size_t> materialParts;
        const bool animated = !nodes.empty();
        const auto partFor = [&](const cgltf_material *source, size_t count, const cgltf_node *node) -> Part & {
            require(count <= 65535, "Model primitive exceeds OpenVR vertex limit");
            const auto key = std::make_pair(source, animated ? node : nullptr);
            const auto found = materialParts.find(key);
            if (found != materialParts.end() && parts[found->second].vertices + count <= 65535)
                return parts[found->second];
            const auto index = parts.size();
            const std::string stem = index ? "model_" + std::to_string(index) : "model";
            auto material = output(std::filesystem::path(pending) / (stem + ".mtl"));
            material << "newmtl material\nKd ";
            if (source) {
                const auto &pbr = source->pbr_metallic_roughness;
                material << pbr.base_color_factor[0] << ' ' << pbr.base_color_factor[1] << ' '
                         << pbr.base_color_factor[2] << "\nd " << pbr.base_color_factor[3] << '\n';
                if (pbr.base_color_texture.texture) {
                    require(pbr.base_color_texture.texcoord == 0 && !pbr.base_color_texture.has_transform,
                            "Unsupported model texture coordinates");
                    const auto *image = pbr.base_color_texture.texture->image;
                    require(image && image->buffer_view && image->mime_type, "Model texture must be embedded");
                    const std::string mime = image->mime_type;
                    require(mime == "image/png" || mime == "image/jpeg", "Unsupported model texture format");
                    const std::string filename = "texture_" + std::to_string(index) +
                        (mime == "image/png" ? ".png" : ".jpg");
                    const auto *bytes = cgltf_buffer_view_data(image->buffer_view);
                    require(bytes && image->buffer_view->size, "Empty model texture");
                    auto texture = output(std::filesystem::path(pending) / filename);
                    texture.write(reinterpret_cast<const char *>(bytes), image->buffer_view->size);
                    material << "map_Kd " << filename << '\n';
                }
            } else material << "1 1 1\n";
            material.close();
            parts.push_back({stem + ".obj", output(std::filesystem::path(pending) / (stem + ".obj")),
                             0, size_t(node - data->nodes)});
            parts.back().mesh << "mtllib " << stem << ".mtl\nusemtl material\n";
            materialParts[key] = index;
            return parts.back();
        };
        size_t triangles = 0;
        std::function<void(const cgltf_node *)> visit = [&](const cgltf_node *node) {
            bool shown = true;
            for (auto *ancestor = node; ancestor; ancestor = ancestor->parent)
                shown = shown && visible[ancestor - data->nodes];
            if (!shown && !animated) return;
            if (!shown && node->skin) return;
            require(!node->skin, "Skinned model assets are unsupported");
            float transform[16]{};
            if (animated) transform[0] = transform[5] = transform[10] = transform[15] = 1;
            else cgltf_node_transform_world(node, transform);
            Vector x{transform[0], transform[1], transform[2]}, y{transform[4], transform[5], transform[6]},
                   z{transform[8], transform[9], transform[10]};
            const Vector nx = cross(y, z), ny = cross(z, x), nz = cross(x, y);
            const float determinant = x[0] * nx[0] + x[1] * nx[1] + x[2] * nx[2];
            require(std::isfinite(determinant) && std::abs(determinant) > 1e-12f, "Singular model transform");
            if (node->mesh) for (cgltf_size primitiveIndex = 0; primitiveIndex < node->mesh->primitives_count; ++primitiveIndex) {
                const auto &primitive = node->mesh->primitives[primitiveIndex];
                require(primitive.type == cgltf_primitive_type_triangles && !primitive.targets_count,
                        "Unsupported model primitive");
                const cgltf_accessor *positions = nullptr, *normals = nullptr, *uvs = nullptr;
                for (cgltf_size a = 0; a < primitive.attributes_count; ++a) {
                    const auto &attribute = primitive.attributes[a];
                    if (attribute.type == cgltf_attribute_type_position) positions = attribute.data;
                    if (attribute.type == cgltf_attribute_type_normal) normals = attribute.data;
                    if (attribute.type == cgltf_attribute_type_texcoord && attribute.index == 0) uvs = attribute.data;
                }
                require(positions && positions->count <= 2000000, "Invalid model vertices");
                const size_t count = positions->count;
                auto &part = partFor(primitive.material, count, node);
                auto &mesh = part.mesh;
                const size_t base = part.vertices + 1;
                const size_t indices = primitive.indices ? primitive.indices->count : count;
                require(indices % 3 == 0 && indices <= 6000000, "Invalid model triangles");
                std::vector<Vector> vertices(count), directions(count, Vector{});
                std::vector<std::array<float, 2>> texcoords(count);
                for (size_t v = 0; v < count; ++v) {
                    float p[3]{};
                    require(cgltf_accessor_read_float(positions, v, p, 3), "Invalid model position");
                    for (int axis = 0; axis < 3; ++axis) {
                        vertices[v][axis] = x[axis] * p[0] + y[axis] * p[1] + z[axis] * p[2] + transform[12 + axis];
                        require(std::isfinite(vertices[v][axis]), "Nonfinite model position");
                    }
                    if (normals) {
                        require(cgltf_accessor_read_float(normals, v, p, 3), "Invalid model normal");
                        for (int axis = 0; axis < 3; ++axis)
                            directions[v][axis] = (nx[axis] * p[0] + ny[axis] * p[1] + nz[axis] * p[2]) / determinant;
                    }
                    if (uvs) require(cgltf_accessor_read_float(uvs, v, texcoords[v].data(), 2), "Invalid model UV");
                }
                std::vector<std::array<size_t, 3>> faces;
                for (size_t i = 0; i < indices; i += 3) {
                    std::array<size_t, 3> face{};
                    for (int corner = 0; corner < 3; ++corner) {
                        face[corner] = primitive.indices ? cgltf_accessor_read_index(primitive.indices, i + corner) : i + corner;
                        require(face[corner] < count, "Model index out of bounds");
                    }
                    if (determinant < 0) std::swap(face[1], face[2]);
                    if (!normals) {
                        Vector a{}, b{};
                        for (int axis = 0; axis < 3; ++axis) {
                            a[axis] = vertices[face[1]][axis] - vertices[face[0]][axis];
                            b[axis] = vertices[face[2]][axis] - vertices[face[0]][axis];
                        }
                        const auto direction = cross(a, b);
                        for (auto vertex : face) for (int axis = 0; axis < 3; ++axis) directions[vertex][axis] += direction[axis];
                    }
                    faces.push_back(face);
                }
                for (size_t v = 0; v < count; ++v) {
                    const auto n = normal(directions[v]);
                    mesh << "v " << vertices[v][0] << ' ' << vertices[v][1] << ' ' << vertices[v][2]
                         << "\nvt " << texcoords[v][0] << ' ' << 1 - texcoords[v][1]
                         << "\nvn " << n[0] << ' ' << n[1] << ' ' << n[2] << '\n';
                }
                for (const auto &face : faces) {
                    mesh << 'f';
                    for (auto vertex : face) {
                        const auto index = base + vertex;
                        mesh << ' ' << index << '/' << index << '/' << index;
                    }
                    mesh << '\n';
                }
                triangles += faces.size();
                part.vertices += count;
            }
            for (cgltf_size child = 0; child < node->children_count; ++child) visit(node->children[child]);
        };
        const auto *scene = data->scene ? data->scene : (data->scenes_count ? &data->scenes[0] : nullptr);
        require(scene != nullptr, "Model scene is unavailable");
        for (cgltf_size node = 0; node < scene->nodes_count; ++node) visit(scene->nodes[node]);
        require(triangles > 0, "Empty model scene");
        for (auto &part : parts) part.mesh.close();
        auto descriptor = output(std::filesystem::path(pending) / (name + ".json"));
        descriptor << "{\"model_state_version\":1,\"components\":{";
        for (size_t index = 0; index < parts.size(); ++index) {
            if (index) descriptor << ',';
            descriptor << '"' << (index ? "body_" + std::to_string(index) : "body")
                       << "\":{\"filename\":\"" << parts[index].filename << '"';
            if (animated)
                descriptor << ",\"motion\":{\"type\":\"pose_component\",\"pose_path\":\"/pose/model_"
                           << parts[index].node << "\"}";
            descriptor << '}';
        }
        for (const auto *component : {"grip", "handgrip", "tip", "openxr_grip", "openxr_aim"})
            descriptor << ",\"" << component << "\":{\"motion\":{\"type\":\"pose_component\",\"pose_path\":\"/pose/"
                       << component << "\"},\"component_local\":{\"origin\":[0,0,0],\"rotate_xyz\":[0,0,0]}}";
        descriptor << "}}\n";
        descriptor.close();
        if (std::filesystem::exists(target)) {
            const auto previous = target.string() + ".previous";
            std::filesystem::remove_all(previous);
            std::filesystem::rename(target, previous);
        }
        std::filesystem::rename(pending, target);
        return true;
    } catch (const std::exception &exception) {
        error = exception.what();
        std::error_code ignored;
        std::filesystem::remove_all(pending, ignored);
        return false;
    }
}
