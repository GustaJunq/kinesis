#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_angle;   // degrees at the centre
uniform float u_radius;  // px
uniform vec2 u_center;   // uv

void main() {
    vec2 px = toPx(v_uv);
    vec2 c = toPx(u_center);
    vec2 d = px - c;
    float r = length(d);
    if (r >= u_radius || u_radius < 0.5) { fragColor = texture(u_texture, v_uv); return; }
    float k = 1.0 - r / u_radius;
    fragColor = samplePx(c + rotate2(d, -u_angle * k * k));
}
