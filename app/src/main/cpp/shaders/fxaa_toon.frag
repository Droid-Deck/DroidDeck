#version 450

layout(binding = 0) uniform sampler2D InputTexture;

layout(push_constant) uniform PC {
    vec4 ndc;
    vec2 resolution;
} pc;

layout(location = 0) in vec2 fragTexCoord;
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
    float lumaM  = dot(rgbM, luma);

    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));

    vec2 dir;
    dir.x = -((lumaNW + lumaNE) - (lumaSW + lumaSE));
    dir.y =  ((lumaNW + lumaSW) - (lumaNE + lumaSE));

    float dirReduce = max(
        (lumaNW + lumaNE + lumaSW + lumaSE) * 0.25 * FXAA_MUL_REDUCE,
        FXAA_MIN_REDUCE);
    float minDirFactor = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
    dir = clamp(dir * minDirFactor, vec2(-MAX_SPAN), vec2(MAX_SPAN)) * invResolution;

    vec4 rgbA = 0.5 * (
        texture(InputTexture, uv + dir * (1.0 / 3.0 - 0.5)) +
        texture(InputTexture, uv + dir * (2.0 / 3.0 - 0.5)));
    vec4 rgbB = rgbA * 0.5 + 0.25 * (
        texture(InputTexture, uv + dir * -0.5) +
        texture(InputTexture, uv + dir *  0.5));

    vec4 fxaaColor = dot(rgbB, vec4(luma, 0.0)) < lumaMin ||
                     dot(rgbB, vec4(luma, 0.0)) > lumaMax ? rgbA : rgbB;

    // Approximate Toon edges using only FXAA's existing five samples.
    vec3 colorLeft  = 0.5 * (rgbNW + rgbSW);
    vec3 colorRight = 0.5 * (rgbNE + rgbSE);
    vec3 colorUp    = 0.5 * (rgbNW + rgbNE);
    vec3 colorDown  = 0.5 * (rgbSW + rgbSE);

    float diffHorizontal = length(colorRight - colorLeft);
    float diffVertical = length(colorUp - colorDown);
    float edgeFactor = step(0.2, diffHorizontal + diffVertical);

    outColor = vec4(mix(fxaaColor.rgb, vec3(0.0), edgeFactor), 1.0);
}
