#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform int u_shape;   // 0 linear, 1 radial
uniform vec2 u_start;  // uv
uniform vec2 u_end;    // uv
uniform vec4 u_startColor;
uniform vec4 u_endColor;

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec2 px = toPx(v_uv), a = toPx(u_start), b = toPx(u_end);
    vec2 ab = b - a;
    float t = u_shape == 0 ? dot(px - a, ab) / max(dot(ab, ab), 0.0001) : length(px - a) / max(length(ab), 0.0001);
    fragColor = combine(p, mix(u_startColor, u_endColor, clamp(t, 0.0, 1.0)));
}
