#version 450

layout(binding = 0) uniform sampler2D InputTexture;

layout(push_constant) uniform PC {
    vec4 ndc;
    vec2 resolution;
    float brightness;
    float contrast;
    float gamma;
    float saturation;
} pc;

layout(location = 0) in vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 uv = fragTexCoord;
    vec2 offset = vec2(1.0) / pc.resolution;

    vec3 center = texture(InputTexture, uv).rgb;
    vec3 left = texture(
        InputTexture, uv - vec2(offset.x, 0.0)).rgb;
    vec3 right = texture(
        InputTexture, uv + vec2(offset.x, 0.0)).rgb;
    vec3 up = texture(
        InputTexture, uv - vec2(0.0, offset.y)).rgb;
    vec3 down = texture(
        InputTexture, uv + vec2(0.0, offset.y)).rgb;

    float horizontal = length(right - left);
    float vertical = length(up - down);
    float edge = step(0.2, horizontal + vertical);

    vec3 color = mix(center, vec3(0.0), edge);

        color = roundEven(clamp(color, 0.0, 1.0) * 255.0) / 255.0;

        color = clamp(color + pc.brightness, 0.0, 1.0);
    color = (color - 0.5) *
            clamp(pc.contrast + 1.0, 0.5, 2.0) + 0.5;

    vec3 grey = vec3(
        dot(color, vec3(0.2126, 0.7152, 0.0722)));

    color = mix(grey, color, clamp(pc.saturation, 0.0, 2.0));
    color = max(color, vec3(0.0));
    color = pow(color, vec3(1.0 / pc.gamma));

    outColor = vec4(color, 1.0);
}
