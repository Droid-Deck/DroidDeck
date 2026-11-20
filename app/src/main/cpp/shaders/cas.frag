#version 450

// =============================================================================
//  AMD FidelityFX CAS (Contrast-Adaptive Sharpening) - pure-CAS path.
//
//  Ported from the OpenGL FSREffect.java MODE_SUPER_RESOLUTION shader so the
//  Vulkan compositor's post-process pipeline applies the SAME sharpen the GL
//  renderer's "Sharpen (CAS)" toggle does. Composable: layers on top of any
//  scaling mode and runs even at native resolution (no upscale).
//
//  Conventions match upscale.vert: combined-image-sampler at binding 0, the
//  push-constant block leads with vec4 ndc (offset 0), fragTexCoord in [0,1].
//
//  Slider -> SHARPNESS mapping is done CPU-side (planUpscaleFrame):
//      SHARPNESS = slider / 100   (clamped 0..1, default slider 60 -> 0.60)
//  Higher slider = stronger sharpen. This is the genuine AMD CAS sharpness term
//  (peak = lerp(8,5,SHARPNESS)); note the GL source's level->value comments were
//  inverted, so we map directly to the AMD term instead of copying that table.
// =============================================================================

layout(binding = 0) uniform sampler2D InputTexture;

layout(push_constant) uniform PC {
    vec4  ndc;        // quad NDC rect, consumed by upscale.vert (offset 0)
    vec2  resolution; // input texture size in pixels
    float sharpness;  // CAS SHARPNESS term [0..1]
} pc;

layout(location = 0) in  vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 uv = fragTexCoord;
    vec2 px = 1.0 / pc.resolution;
    float SHARPNESS = pc.sharpness;

    // 3x3 neighbourhood
    vec3 a = texture(InputTexture, uv + vec2(-px.x, -px.y)).rgb;
    vec3 b = texture(InputTexture, uv + vec2( 0.0,  -px.y)).rgb;
    vec3 c = texture(InputTexture, uv + vec2( px.x, -px.y)).rgb;
    vec3 d = texture(InputTexture, uv + vec2(-px.x,  0.0)).rgb;
    vec3 e = texture(InputTexture, uv).rgb;
    vec3 f = texture(InputTexture, uv + vec2( px.x,  0.0)).rgb;
    vec3 g = texture(InputTexture, uv + vec2(-px.x,  px.y)).rgb;
    vec3 h = texture(InputTexture, uv + vec2( 0.0,   px.y)).rgb;
    vec3 i = texture(InputTexture, uv + vec2( px.x,  px.y)).rgb;

    // CAS
    vec3 mnRGB  = min(min(min(d, e), min(f, b)), h);
    vec3 mnRGB2 = min(min(min(mnRGB, a), min(g, c)), i);
    mnRGB += mnRGB2;

    vec3 mxRGB  = max(max(max(d, e), max(f, b)), h);
    vec3 mxRGB2 = max(max(max(mxRGB, a), max(g, c)), i);
    mxRGB += mxRGB2;

    vec3 rcpMxRGB = vec3(1.0) / mxRGB;
    vec3 ampRGB = clamp((min(mnRGB, 2.0 - mxRGB) * rcpMxRGB), 0.0, 1.0);

    ampRGB = inversesqrt(ampRGB);
    float peak = 8.0 - 3.0 * SHARPNESS;
    vec3 wRGB = -vec3(1.0) / (ampRGB * peak);
    vec3 rcpWeightRGB = vec3(1.0) / (1.0 + 4.0 * wRGB);

    vec3 window = (b + d) + (f + h);
    vec3 col = clamp((window * wRGB + e) * rcpWeightRGB, 0.0, 1.0);

    outColor = vec4(col, 1.0);
}
