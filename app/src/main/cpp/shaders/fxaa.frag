#version 450

// =============================================================================
//  FXAA (Fast Approximate Anti-Aliasing) post effect.
//
//  Ported verbatim from the OpenGL FXAAEffect.java shader so the Vulkan
//  compositor's post-process pipeline applies the SAME anti-aliasing the GL
//  renderer's "FXAA" toggle does. GL sampled with gl_FragCoord.xy*invResolution;
//  here fragTexCoord already spans [0,1] across the output, so it IS that uv.
//
//  Conventions match upscale.vert: combined-image-sampler at binding 0, the
//  push-constant block leads with vec4 ndc (offset 0), fragTexCoord in [0,1].
//  Runs FIRST in the canonical post chain (AA on the clean composited image).
// =============================================================================

layout(binding = 0) uniform sampler2D InputTexture;

layout(push_constant) uniform PC {
    vec4 ndc;        // quad NDC rect, consumed by upscale.vert (offset 0)
    vec2 resolution; // input texture size in px
} pc;

layout(location = 0) in  vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

#define FXAA_MIN_REDUCE (1.0 / 128.0)
#define FXAA_MUL_REDUCE (1.0 / 8.0)
#define MAX_SPAN 8.0
const vec3 luma = vec3(0.299, 0.587, 0.114);

void main() {
    vec2 invResolution = 1.0 / pc.resolution;
    vec2 uv = fragTexCoord;

    vec3 rgbNW = texture(InputTexture, uv + vec2(-1.0, -1.0) * invResolution).rgb;
    vec3 rgbNE = texture(InputTexture, uv + vec2( 1.0, -1.0) * invResolution).rgb;
    vec3 rgbSW = texture(InputTexture, uv + vec2(-1.0,  1.0) * invResolution).rgb;
    vec3 rgbSE = texture(InputTexture, uv + vec2( 1.0,  1.0) * invResolution).rgb;
    vec3 rgbM  = texture(InputTexture, uv).rgb;

    float lumaNW = dot(rgbNW, luma);
    float lumaNE = dot(rgbNE, luma);
    float lumaSW = dot(rgbSW, luma);
    float lumaSE = dot(rgbSE, luma);
    float lumaM  = dot(rgbM,  luma);

    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));

    vec2 dir;
    dir.x = -((lumaNW + lumaNE) - (lumaSW + lumaSE));
    dir.y =  ((lumaNW + lumaSW) - (lumaNE + lumaSE));

    float dirReduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * 0.25 * FXAA_MUL_REDUCE, FXAA_MIN_REDUCE);
    float minDirFactor = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
    dir = clamp(dir * minDirFactor, vec2(-MAX_SPAN), vec2(MAX_SPAN)) * invResolution;

    vec4 rgbA = 0.5 * (
        texture(InputTexture, uv + dir * (1.0 / 3.0 - 0.5)) +
        texture(InputTexture, uv + dir * (2.0 / 3.0 - 0.5)));
    vec4 rgbB = rgbA * 0.5 + 0.25 * (
        texture(InputTexture, uv + dir * -0.5) +
        texture(InputTexture, uv + dir *  0.5));

    float lumaB = dot(rgbB, vec4(luma, 0.0));
    outColor = (lumaB < lumaMin || lumaB > lumaMax) ? rgbA : rgbB;
}
