#version 450

layout(location = 0) in vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;
layout(set = 0, binding = 0) uniform sampler2D InputTexture;
layout(set = 1, binding = 0) uniform sampler2D WeightTexture;
layout(push_constant) uniform Constants {
    vec4 ndc;
    vec2 resolution;
    float extra;
    float pad;
} pc;

void main() {
    vec2 px = 1.0 / max(pc.resolution, vec2(1.0));
    vec2 uv = clamp(fragTexCoord, px * 0.5, vec2(1.0) - px * 0.5);
    vec4 center = texture(InputTexture, uv);
    vec4 weights = texture(WeightTexture, uv);
    float weight = clamp(weights.r, 0.0, 0.42);

    if (weight <= 0.001) {
        outColor = center;
        return;
    }

    vec2 normal = weights.gb * 2.0 - 1.0;
    vec2 acrossUv = clamp(uv + normal * px, px * 0.5, vec2(1.0) - px * 0.5);
    vec3 across = texture(InputTexture, acrossUv).rgb;
    outColor = vec4(mix(center.rgb, across, weight), center.a);
}
