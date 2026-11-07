#pragma once
#include "HandSkinning.h"
#include <GLES3/gl3.h>
#include <cstring>
#include <vector>
#include <stdexcept>

namespace droiddeck::xr {
class HandGloveRenderer {
public:
    bool load(int hand, const std::vector<uint8_t> &data) {
        if (hand < 0 || hand > 1 || data.size() < 16 || data.size() > 8*1024*1024) return false;
        uint32_t header[4]; std::memcpy(header, data.data(), sizeof(header));
        const auto vertices = header[1], indices = header[2];
        if (header[0] != 0x31484d44 || header[3] != 31 || !vertices || vertices > 100000 ||
            !indices || indices > 100000 || indices % 3 || data.size() != 16ULL + vertices*56ULL + indices*4ULL + 31*64) return false;
        std::vector<float> points(vertices*14);
        std::memcpy(points.data(), data.data()+16, points.size()*sizeof(float));
        for (float value : points) if (!std::isfinite(value)) return false;
        for (uint32_t v = 0; v < vertices; ++v) {
            float sum = 0;
            for (int j = 0; j < 4; ++j) {
                const float weight = points[v*14+6+j], bone = points[v*14+10+j];
                if (weight < 0 || bone < 0 || bone > 30 || bone != std::floor(bone)) return false;
                sum += weight;
            }
            if (std::abs(sum-1.f) > .001f) return false;
        }
        std::vector<uint32_t> triangles(indices);
        std::memcpy(triangles.data(), data.data()+16+points.size()*sizeof(float), indices*sizeof(uint32_t));
        for (auto index : triangles) if (index >= vertices) return false;
        SkinMatrix inverse[31]; std::memcpy(inverse, data.data()+data.size()-sizeof(inverse), sizeof(inverse));
        for (const auto &matrix : inverse) for (float value : matrix) if (!std::isfinite(value)) return false;
        if (!program_) initialize();
        auto &mesh = meshes_[hand];
        glGenVertexArrays(1, &mesh.vao); glBindVertexArray(mesh.vao);
        glGenBuffers(1, &mesh.vertices); glBindBuffer(GL_ARRAY_BUFFER, mesh.vertices);
        glBufferData(GL_ARRAY_BUFFER, points.size()*sizeof(float), points.data(), GL_STATIC_DRAW);
        const int sizes[] = {3,3,4,4}, offsets[] = {0,3,6,10};
        for (GLuint attribute = 0; attribute < 4; ++attribute) {
            glEnableVertexAttribArray(attribute);
            glVertexAttribPointer(attribute, sizes[attribute], GL_FLOAT, GL_FALSE, 56,
                reinterpret_cast<void *>(uintptr_t(offsets[attribute]*sizeof(float))));
        }
        glGenBuffers(1, &mesh.indices); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mesh.indices);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices*sizeof(uint32_t), triangles.data(), GL_STATIC_DRAW);
        glBindVertexArray(0); mesh.count = indices;
        std::copy(std::begin(inverse), std::end(inverse), mesh.inverseBind);
        if (glGetError() != GL_NO_ERROR) { clear(); return false; }
        return true;
    }
    bool ready() const { return meshes_[0].count && meshes_[1].count; }
    bool loaded(int hand) const { return meshes_[hand].count != 0; }
    unsigned draw(int width, int height, const SkinMatrix &viewProjection, const DroidDeckXrSkeleton &skeleton) {
        if (!ready()) return 0;
        if (!depth_) glGenRenderbuffers(1, &depth_);
        if (width != depthWidth_ || height != depthHeight_) {
            glBindRenderbuffer(GL_RENDERBUFFER, depth_);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width, height);
            depthWidth_ = width; depthHeight_ = height;
        }
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth_);
        glDepthMask(GL_TRUE); glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LESS);
        glClearDepthf(1.f); glClear(GL_DEPTH_BUFFER_BIT);
        glDisable(GL_BLEND); glDisable(GL_CULL_FACE);
        glUseProgram(program_);
        glUniformMatrix4fv(viewProjection_, 1, GL_FALSE, viewProjection.data());
        unsigned drawn = 0;
        for (int hand = 0; hand < 2; ++hand) {
            if (!skeleton.active[hand]) continue;
            bool valid = true;
            for (const auto &joint : skeleton.joints[hand]) valid &= poseValid(joint);
            if (!valid) continue;
            SkinMatrix palette[31]; handSkinPalette(skeleton.joints[hand], hand != 0, meshes_[hand].inverseBind, palette);
            glUniformMatrix4fv(bones_, 31, GL_FALSE, palette[0].data());
            glBindVertexArray(meshes_[hand].vao);
            glDrawElements(GL_TRIANGLES, meshes_[hand].count, GL_UNSIGNED_INT, nullptr);
            ++drawn;
        }
        glBindVertexArray(0); glDisable(GL_DEPTH_TEST);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, 0);
        return drawn;
    }
    void clear() {
        for (auto &mesh : meshes_) {
            glDeleteVertexArrays(1, &mesh.vao); glDeleteBuffers(1, &mesh.vertices); glDeleteBuffers(1, &mesh.indices); mesh = {};
        }
        glDeleteRenderbuffers(1, &depth_); depth_ = 0;
        glDeleteProgram(program_); program_ = 0;
    }
private:
    struct Mesh { GLuint vao = 0, vertices = 0, indices = 0; GLsizei count = 0; SkinMatrix inverseBind[31]; } meshes_[2];
    GLuint program_ = 0, depth_ = 0;
    GLint viewProjection_ = -1, bones_ = -1;
    int depthWidth_ = 0, depthHeight_ = 0;
    void initialize() {
        const char *vertex = R"(#version 300 es
layout(location=0) in vec3 position; layout(location=1) in vec3 normal;
layout(location=2) in vec4 weights; layout(location=3) in vec4 joints;
uniform mat4 viewProjection; uniform mat4 bones[31]; out vec3 worldNormal;
void main() {
    mat4 skin = weights.x*bones[int(joints.x)] + weights.y*bones[int(joints.y)]
              + weights.z*bones[int(joints.z)] + weights.w*bones[int(joints.w)];
    gl_Position = viewProjection*skin*vec4(position,1.0);
    worldNormal = mat3(skin)*normal;
})";
        const char *fragment = R"(#version 300 es
precision highp float; in vec3 worldNormal; out vec4 color;
void main() {
    float light = 0.48 + 0.52*abs(dot(normalize(worldNormal),normalize(vec3(-0.3,0.8,0.5))));
    color = vec4(vec3(0.88,0.91,0.95)*light,1.0);
})";
        const auto compile = [](GLenum type, const char *source) {
            GLuint shader = glCreateShader(type); glShaderSource(shader, 1, &source, nullptr); glCompileShader(shader);
            GLint ok = 0; glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
            if (!ok) { glDeleteShader(shader); throw std::runtime_error("Hand skinning shader failed"); }
            return shader;
        };
        GLuint vs = compile(GL_VERTEX_SHADER, vertex), fs = 0;
        try { fs = compile(GL_FRAGMENT_SHADER, fragment); }
        catch (...) { glDeleteShader(vs); throw; }
        program_ = glCreateProgram(); glAttachShader(program_, vs); glAttachShader(program_, fs); glLinkProgram(program_);
        glDeleteShader(vs); glDeleteShader(fs);
        GLint ok = 0; glGetProgramiv(program_, GL_LINK_STATUS, &ok);
        if (!ok) throw std::runtime_error("Hand skinning program failed");
        viewProjection_ = glGetUniformLocation(program_, "viewProjection"); bones_ = glGetUniformLocation(program_, "bones");
    }
};
}
