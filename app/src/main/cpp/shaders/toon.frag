#version 450

// =============================================================================
//  Toon / cel edge-outline post effect.
//
//  Ported verbatim from the OpenGL ToonEffect.java shader. GL derived uv from
//  gl_FragCoord.xy / resolution; here fragTexCoord already spans [0,1] across
//  the output, so it IS that uv. edgeThreshold/offset constants unchanged.
//
//  Conventions match upscale.vert: combined-image-sampler at binding 0, the
//  push-constant block leads with vec4 ndc (offset 0), fragTexCoord in [0,1].
//  Runs after FXAA in the canonical chain (stylize the clean image).
// =============================================================================

layout(binding = 0) uniform sampler2D InputTexture;

layout(push_constant) uniform PC {
    vec4 ndc;        // quad NDC rect, consumed by upscale.vert (offset 0)
    vec2 resolution; // input texture size in px
} pc;

layout(location = 0) in  vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 uv = fragTexCoord;

    float edgeThreshold = 0.2;                 // edge-detect threshold
    vec2 offset = vec2(1.0) / pc.resolution;   // 1-texel neighbour offset

    vec3 colorCenter = texture(InputTexture, uv).rgb;
    vec3 colorLeft   = texture(InputTexture, uv - vec2(offset.x, 0.0)).rgb;
    vec3 colorRight  = texture(InputTexture, uv + vec2(offset.x, 0.0)).rgb;
    vec3 colorUp     = texture(InputTexture, uv - vec2(0.0, offset.y)).rgb;
    vec3 colorDown   = texture(InputTexture, uv + vec2(0.0, offset.y)).rgb;

    float diffHorizontal = length(colorRight - colorLeft);
    float diffVertical   = length(colorUp - colorDown);

    float edgeFactor = step(edgeThreshold, diffHorizontal + diffVertical);
    vec3 outlineColor = mix(colorCenter, vec3(0.0), edgeFactor);

    outColor = vec4(outlineColor, 1.0);
}
