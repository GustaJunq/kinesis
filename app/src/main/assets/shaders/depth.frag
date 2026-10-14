#version 300 es
precision highp float;
// Depth Map effect: distance to the camera, white at u_near and black at u_far.
in vec3 v_worldPos;
in vec3 v_normal;
in vec2 v_uv;
uniform vec3 u_cameraPos;
uniform float u_near;
uniform float u_far;
uniform float u_gamma;
uniform float u_invert;
uniform float u_opacity;
out vec4 fragColor;

void main() {
    float d = distance(v_worldPos, u_cameraPos);
    float t = clamp((d - u_near) / max(u_far - u_near, 0.001), 0.0, 1.0);
    float v = pow(1.0 - t, max(u_gamma, 0.01));
    if (u_invert > 0.5) v = 1.0 - v;
    fragColor = vec4(vec3(v) * u_opacity, u_opacity);
}
