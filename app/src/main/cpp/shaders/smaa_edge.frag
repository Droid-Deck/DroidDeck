#version 450

layout(location = 0) in vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;
layout(set = 0, binding = 0) uniform sampler2D InputTexture;
layout(push_constant) uniform Constants {
    vec4 ndc;
    vec2 resolution;
    float extra;
    float pad;
} pc;

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec2 px = 1.0 / max(pc.resolution, vec2(1.0));
    vec2 uv = clamp(fragTexCoord, px * 0.5, vec2(1.0) - px * 0.5);
    float center = luma(texture(InputTexture, uv).rgb);
    float left = luma(texture(InputTexture, uv - vec2(px.x, 0.0)).rgb);
    float right = luma(texture(InputTexture, uv + vec2(px.x, 0.0)).rgb);
    float up = luma(texture(InputTexture, uv - vec2(0.0, px.y)).rgb);
    float down = luma(texture(InputTexture, uv + vec2(0.0, px.y)).rgb);

    // Store directional edge strengths for the weight pass.
    outColor = vec4(abs(center - left), abs(center - right),
                    abs(center - up), 0.0);
}
