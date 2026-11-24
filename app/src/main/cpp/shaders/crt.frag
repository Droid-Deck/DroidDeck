#version 450

// =============================================================================
//  CRT (chromatic aberration + scanlines) post effect.
//
//  Ported verbatim from the OpenGL CRTEffect.java shader. GL sampled with the
//  varying vUV; here fragTexCoord IS that uv. All constants unchanged. Runs LAST
//  in the canonical chain (the physical tube emulation, after NTSC's analog
//  signal). Does not need the resolution, but the PC carries it for a uniform
//  effect-PC layout.
//
//  Conventions match upscale.vert: combined-image-sampler at binding 0, the
//  push-constant block leads with vec4 ndc (offset 0), fragTexCoord in [0,1].
// =============================================================================

layout(binding = 0) uniform sampler2D InputTexture;

layout(push_constant) uniform PC {
    vec4 ndc;        // quad NDC rect, consumed by upscale.vert (offset 0)
    vec2 resolution; // input texture size in px (unused; layout uniformity)
} pc;

layout(location = 0) in  vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

#define CA_AMOUNT 1.0025
#define SCANLINE_INTENSITY_X 0.125
#define SCANLINE_INTENSITY_Y 0.375
#define SCANLINE_SIZE 1024.0

void main() {
    vec2 vUV = fragTexCoord;
    vec4 finalColor = texture(InputTexture, vUV);
    finalColor.rgb = vec3(
        texture(InputTexture, (vUV - 0.5) * CA_AMOUNT + 0.5).r,
        finalColor.g,
        texture(InputTexture, (vUV - 0.5) / CA_AMOUNT + 0.5).b
    );
    float scanlineX = abs(sin(vUV.x * SCANLINE_SIZE) * 0.5 * SCANLINE_INTENSITY_X);
    float scanlineY = abs(sin(vUV.y * SCANLINE_SIZE) * 0.5 * SCANLINE_INTENSITY_Y);
    outColor = vec4(mix(finalColor.rgb, vec3(0.0), scanlineX + scanlineY), finalColor.a);
}
