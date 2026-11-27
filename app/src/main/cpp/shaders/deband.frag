#version 450

// =============================================================================
//  Debanding / dither - terminal post pass.
//
//  Adds sub-LSB triangular-PDF (TPDF) noise just before the final 8-bit
//  swapchain quantization, so smooth gradients (dark skies, fog, bloom
//  falloff, vignettes) stop showing 8-bit "stair-step" banding. Especially
//  visible on AMOLED panels in dark scenes.
//
//  This is the LAST pass in the effect chain and always writes the swapchain,
//  so the dither matches the display quantizer. The whole compositor chain is
//  R8G8B8A8_UNORM and the swapchain has no _SRGB view, i.e. values are already
//  display(gamma)-encoded here - so we dither in display space, ~1 LSB = 1/255,
//  which is where AMOLED banding is worst (the darks).
//
//  Noise = interleaved gradient noise (Jimenez 2014, public technique), two
//  screen-locked samples summed for a triangular PDF (flat noise spectrum, no
//  DC bias). Screen-locked (gl_FragCoord) so the pattern is static = no shimmer.
//  Float-only hash (no floatBitsToInt / bvec / bitwise) = Adreno-compile-safe.
//
//  Conventions match upscale.vert / cas.frag: combined-image-sampler at binding
//  0, push-constant block leads with vec4 ndc (offset 0), fragTexCoord in [0,1].
//
//  CPU side (planUpscaleFrame) maps the UI strength slider to LSB units:
//      strength = slider / 100      (slider 100 -> 1.0 LSB, the default)
// =============================================================================

layout(binding = 0) uniform sampler2D InputTexture;

layout(push_constant) uniform PC {
    vec4  ndc;        // quad NDC rect, consumed by upscale.vert (offset 0)
    vec2  resolution; // input texture size in pixels (unused here, kept for layout parity)
    float strength;   // dither amplitude in LSBs (1.0 = +/-1/255 triangular)
} pc;

layout(location = 0) in  vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

// Interleaved gradient noise - returns [0,1), float-only, Adreno-safe.
float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

void main() {
    vec3 col = texture(InputTexture, fragTexCoord).rgb;

    // Two decorrelated samples -> triangular PDF in (-1, 1).
    float n1 = ign(gl_FragCoord.xy);
    float n2 = ign(gl_FragCoord.xy + vec2(11.0, 17.0));
    float tri = (n1 + n2) - 1.0;

    col += vec3(tri * (pc.strength / 255.0));

    outColor = vec4(col, 1.0);
}
