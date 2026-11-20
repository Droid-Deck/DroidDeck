#version 450

// =============================================================================
//  NTSC composite-signal artifacts (YIQ chroma modulation + chromatic
//  aberration + scanlines + subtle barrel warp) post effect.
//
//  Ported verbatim from the OpenGL NTSCCombinedEffect.java shader. GL sampled
//  with the varying vUV; here fragTexCoord IS that uv. GL's TextureSize and
//  resolution uniforms were both the screen size -> we feed both from pc.resolution.
//  FrameCount animates the chroma phase; planUpscaleFrame feeds a per-frame
//  counter (mod 4, the visible cos/sin period) so the analog shimmer animates.
//
//  Conventions match upscale.vert: combined-image-sampler at binding 0, the
//  push-constant block leads with vec4 ndc (offset 0), fragTexCoord in [0,1].
//  Runs after HDR, before CRT in the canonical chain (analog signal, then tube).
// =============================================================================

layout(binding = 0) uniform sampler2D InputTexture;

layout(push_constant) uniform PC {
    vec4  ndc;        // quad NDC rect, consumed by upscale.vert (offset 0)
    vec2  resolution; // screen size in px (= TextureSize = resolution in GL)
    float frameCount; // animated chroma-phase counter (cos/sin period 4)
} pc;

layout(location = 0) in  vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

#define PI 3.14159265
#define SCANLINE_INTENSITY 0.35
#define CHROMA_OFFSET 0.005
#define BLUR_RADIUS 0.002
#define WARP_AMOUNT 0.01
#define SCANLINE_DARKEN 0.5

const mat3 yiq_mat = mat3(
    0.299, 0.587, 0.114,
    0.596, -0.275, -0.321,
    0.212, -0.523, 0.311
);
const mat3 yiq2rgb_mat = mat3(
    1.0, 0.956, 0.621,
    1.0, -0.272, -0.647,
    1.0, -1.106, 1.705
);

vec3 applyNTSC(vec2 uv) {
    vec3 col = texture(InputTexture, uv).rgb;
    vec3 yiq = col * yiq_mat;

    float chromaPhase = PI * (mod(uv.y * pc.resolution.y, 2.0) + pc.frameCount);
    yiq.y *= cos(chromaPhase * 0.5);
    yiq.z *= sin(chromaPhase * 0.5);

    vec3 rgb = yiq * yiq2rgb_mat;

    vec3 finalColor;
    finalColor.r = texture(InputTexture, uv + vec2(CHROMA_OFFSET, 0.0)).r;
    finalColor.g = texture(InputTexture, uv + vec2(0.0, BLUR_RADIUS)).g;
    finalColor.b = texture(InputTexture, uv - vec2(CHROMA_OFFSET, 0.0)).b;
    return finalColor;
}

vec3 applyScanlines(vec2 uv) {
    vec3 col = texture(InputTexture, uv).rgb;
    float scanline = abs(sin(uv.y * pc.resolution.y * 2.0)) * SCANLINE_INTENSITY;
    col *= 1.0 - (scanline * SCANLINE_DARKEN);
    return col;
}

vec2 applyWarp(vec2 uv) {
    uv = uv * 2.0 - 1.0;
    float r = sqrt(uv.x * uv.x + uv.y * uv.y);
    uv += uv * (r * r) * WARP_AMOUNT;
    return uv * 0.5 + 0.5;
}

void main() {
    vec2 warpedUV = applyWarp(fragTexCoord);
    vec3 ntscColor = applyNTSC(warpedUV);
    vec3 scanlineColor = applyScanlines(warpedUV);
    vec3 finalColor = mix(ntscColor, scanlineColor, 0.7);
    outColor = vec4(finalColor, 1.0);
}
