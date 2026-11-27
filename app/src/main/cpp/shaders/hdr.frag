#version 450

// =============================================================================
//  Fake-HDR (dual-radius bloom + contrast lift) post effect.
//
//  Ported from the OpenGL HDREffect.java shader so the Vulkan compositor's
//  post-process pipeline applies the SAME fake-HDR the GL renderer's "HDR"
//  toggle does. Binary on/off; HDRPower fixed at 1.30. Composable: runs at any
//  resolution, after the optional scaling and CAS passes.
//
//  Conventions match upscale.vert: combined-image-sampler at binding 0, the
//  push-constant block leads with vec4 ndc (offset 0), fragTexCoord in [0,1].
// =============================================================================

layout(binding = 0) uniform sampler2D InputTexture;

layout(push_constant) uniform PC {
    vec4 ndc;        // quad NDC rect, consumed by upscale.vert (offset 0)
    vec2 resolution; // input texture size in pixels
} pc;

layout(location = 0) in  vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

const float HDRPower = 1.30;
const float radius1  = 0.793;
const float radius2  = 0.870;

void main() {
    vec2 texcoord = fragTexCoord;
    vec2 px = 1.0 / pc.resolution;

    vec3 color = texture(InputTexture, texcoord).rgb;

    // --- BLOOM PASS 1 (radius1) ---
    vec3 bloom_sum1 = texture(InputTexture, texcoord + vec2( 1.5, -1.5) * radius1 * px).rgb;
    bloom_sum1 += texture(InputTexture, texcoord + vec2(-1.5, -1.5) * radius1 * px).rgb;
    bloom_sum1 += texture(InputTexture, texcoord + vec2( 1.5,  1.5) * radius1 * px).rgb;
    bloom_sum1 += texture(InputTexture, texcoord + vec2(-1.5,  1.5) * radius1 * px).rgb;
    bloom_sum1 += texture(InputTexture, texcoord + vec2( 0.0, -2.5) * radius1 * px).rgb;
    bloom_sum1 += texture(InputTexture, texcoord + vec2( 0.0,  2.5) * radius1 * px).rgb;
    bloom_sum1 += texture(InputTexture, texcoord + vec2(-2.5,  0.0) * radius1 * px).rgb;
    bloom_sum1 += texture(InputTexture, texcoord + vec2( 2.5,  0.0) * radius1 * px).rgb;
    bloom_sum1 *= 0.005;

    // --- BLOOM PASS 2 (radius2) ---
    vec3 bloom_sum2 = texture(InputTexture, texcoord + vec2( 1.5, -1.5) * radius2 * px).rgb;
    bloom_sum2 += texture(InputTexture, texcoord + vec2(-1.5, -1.5) * radius2 * px).rgb;
    bloom_sum2 += texture(InputTexture, texcoord + vec2( 1.5,  1.5) * radius2 * px).rgb;
    bloom_sum2 += texture(InputTexture, texcoord + vec2(-1.5,  1.5) * radius2 * px).rgb;
    bloom_sum2 += texture(InputTexture, texcoord + vec2( 0.0, -2.5) * radius2 * px).rgb;
    bloom_sum2 += texture(InputTexture, texcoord + vec2( 0.0,  2.5) * radius2 * px).rgb;
    bloom_sum2 += texture(InputTexture, texcoord + vec2(-2.5,  0.0) * radius2 * px).rgb;
    bloom_sum2 += texture(InputTexture, texcoord + vec2( 2.5,  0.0) * radius2 * px).rgb;
    bloom_sum2 *= 0.010;

    // --- FAKE HDR ---
    float dist = radius2 - radius1;
    vec3 HDR = (color + (bloom_sum2 - bloom_sum1)) * dist;
    vec3 blend = HDR + color;
    color = pow(abs(blend), vec3(abs(HDRPower))) + HDR;

    outColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
