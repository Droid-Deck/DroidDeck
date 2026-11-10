#pragma once
#include <GLES3/gl3.h>
#include <string>
#include "controller_reference_asset.h"

class XrControllerReferenceRenderer {
public:
    void update(int hand, const std::string &name, const std::vector<uint8_t> &bytes,
                const std::vector<XrModelNodeTransform> &states) {
        auto &model = models_[hand];
        if (name.empty() || bytes.empty()) { clear(model); return; }
        if (model.name != name) {
            clear(model);
            model.asset.load(bytes);
            initialize();
            for (const auto &primitive : model.asset.primitives) {
                Part part;
                part.node = primitive.node;
                part.triangles = primitive.indices.size();
                part.lines = primitive.edges.size();
                glGenVertexArrays(1, &part.vao);
                glBindVertexArray(part.vao);
                glGenBuffers(1, &part.vertices);
                glBindBuffer(GL_ARRAY_BUFFER, part.vertices);
                glBufferData(GL_ARRAY_BUFFER, primitive.positions.size() * sizeof(float),
                             primitive.positions.data(), GL_STATIC_DRAW);
                glEnableVertexAttribArray(0);
                glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 3 * sizeof(float), nullptr);
                glGenBuffers(1, &part.indices);
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, part.indices);
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, primitive.indices.size() * sizeof(uint32_t),
                             primitive.indices.data(), GL_STATIC_DRAW);
                glGenBuffers(1, &part.edges);
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, part.edges);
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, primitive.edges.size() * sizeof(uint32_t),
                             primitive.edges.data(), GL_STATIC_DRAW);
                model.parts.push_back(part);
            }
            glBindVertexArray(0);
            model.name = name;
        }
        model.asset.update(states);
    }
    size_t primitives(int hand) const { return models_[hand].parts.size(); }
    size_t triangles(int hand) const {
        size_t result = 0;
        for (const auto &part : models_[hand].parts) result += part.triangles / 3;
        return result;
    }
    void draw(const XrView &eye, const XrSpaceLocation (&models)[2], bool wireframe) {
        if (!program_) return;
        const auto camera = xr_reference::multiply(xr_reference::projection(eye.fov),
                                                   xr_reference::inversePose(eye.pose));
        glUseProgram(program_);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(GL_TRUE);
        glUniform1f(wire_, wireframe ? 1.f : 0.f);
        for (int hand = 0; hand < 2; ++hand) {
            if ((models[hand].locationFlags & 3) != 3) continue;
            const auto modelView = xr_reference::multiply(xr_reference::inversePose(eye.pose),
                                                         xr_reference::poseMatrix(models[hand].pose));
            const auto modelClip = xr_reference::multiply(camera, xr_reference::poseMatrix(models[hand].pose));
            glUniform3f(color_, wireframe ? 1.f : .15f, wireframe ? .55f : .8f, wireframe ? .05f : 1.f);
            for (const auto &part : models_[hand].parts) {
                if (!models_[hand].asset.visible(part.node)) continue;
                const auto node = models_[hand].asset.transform(part.node);
                const auto clip = xr_reference::multiply(modelClip, node);
                const auto view = xr_reference::multiply(modelView, node);
                glUniformMatrix4fv(clip_, 1, GL_FALSE, clip.data());
                glUniformMatrix4fv(view_, 1, GL_FALSE, view.data());
                glBindVertexArray(part.vao);
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, wireframe ? part.edges : part.indices);
                glDrawElements(wireframe ? GL_LINES : GL_TRIANGLES,
                               wireframe ? part.lines : part.triangles, GL_UNSIGNED_INT, nullptr);
            }
        }
        glBindVertexArray(0);
        glDisable(GL_DEPTH_TEST);
    }
    void cleanup() {
        for (auto &model : models_) clear(model);
        glDeleteProgram(program_); program_ = 0;
    }
private:
    struct Part {
        size_t node = 0;
        GLuint vao = 0, vertices = 0, indices = 0, edges = 0;
        GLsizei triangles = 0, lines = 0;
    };
    struct Model {
        std::string name;
        xr_reference::Asset asset;
        std::vector<Part> parts;
    } models_[2];
    GLuint program_ = 0;
    GLint clip_ = -1, view_ = -1, color_ = -1, wire_ = -1;
    static void clear(Model &model) {
        for (const auto &part : model.parts) {
            glDeleteVertexArrays(1, &part.vao);
            const GLuint buffers[]{part.vertices, part.indices, part.edges};
            glDeleteBuffers(3, buffers);
        }
        model.parts.clear();
        model.name.clear();
    }
    static GLuint shader(GLenum type, const char *source) {
        const auto handle = glCreateShader(type);
        glShaderSource(handle, 1, &source, nullptr); glCompileShader(handle);
        GLint ok = 0; glGetShaderiv(handle, GL_COMPILE_STATUS, &ok);
        if (!ok) { glDeleteShader(handle); throw std::runtime_error("Reference shader compilation failed"); }
        return handle;
    }
    void initialize() {
        if (program_) return;
        const auto vertex = shader(GL_VERTEX_SHADER,
            "#version 300 es\nlayout(location=0) in vec3 position; uniform mat4 clipMatrix; uniform mat4 viewMatrix;"
            "out vec3 point; void main(){point=(viewMatrix*vec4(position,1)).xyz;gl_Position=clipMatrix*vec4(position,1);}");
        const auto fragment = shader(GL_FRAGMENT_SHADER,
            "#version 300 es\nprecision highp float; in vec3 point; uniform vec3 color; uniform float wire;"
            "out vec4 outputColor; void main(){vec3 n=normalize(cross(dFdx(point),dFdy(point)));"
            "float light=wire>0.5?1.0:0.35+0.65*abs(dot(n,normalize(vec3(0.3,0.5,1))));"
            "outputColor=vec4(color*light,1);}");
        program_ = glCreateProgram();
        glAttachShader(program_, vertex); glAttachShader(program_, fragment); glLinkProgram(program_);
        glDeleteShader(vertex); glDeleteShader(fragment);
        GLint ok = 0; glGetProgramiv(program_, GL_LINK_STATUS, &ok);
        if (!ok) throw std::runtime_error("Reference shader linking failed");
        clip_ = glGetUniformLocation(program_, "clipMatrix");
        view_ = glGetUniformLocation(program_, "viewMatrix");
        color_ = glGetUniformLocation(program_, "color");
        wire_ = glGetUniformLocation(program_, "wire");
    }
};
