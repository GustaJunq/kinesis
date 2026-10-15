#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform vec2 u_center;  // uv
uniform float u_angle;
uniform int u_axis;     // 0 horizontal, 1 vertical, 2 both

void main() {
    vec2 c = toPx(u_center);
    vec2 q = rotate2(toPx(v_uv) - c, -u_angle);
    if (u_axis != 1 && q.x > 0.0) q.x = -q.x;   // right half shows the left half
    if (u_axis != 0 && q.y < 0.0) q.y = -q.y;   // bottom half shows the top half
    fragColor = samplePx(c + rotate2(q, u_angle));
}
