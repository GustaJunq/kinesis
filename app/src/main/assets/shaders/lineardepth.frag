#version 300 es
precision highp float;
// Distance to the camera for depth of field, as 16-bit fixed point in red (high) and green (low).
in vec3 v_worldPos;
in vec3 v_normal;
in vec2 v_uv;
in vec3 v_bary;
uniform vec3 u_cameraPos;
uniform float u_far;
out vec4 fragColor;

void main() {
    float d = clamp(distance(v_worldPos, u_cameraPos) / u_far, 0.0, 1.0) * 255.0;
    float hi = floor(d);
    fragColor = vec4(hi / 255.0, fract(d), 0.0, 1.0);
}
