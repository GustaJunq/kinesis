#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_size;   // px
uniform float u_angle;
uniform vec4 u_colorA;
uniform vec4 u_colorB;
uniform vec2 u_offset;  // uv of the offset point

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec2 q = rotate2(toPx(v_uv) - toPx(u_offset), -u_angle) / max(u_size, 1.0);
    float parity = mod(floor(q.x) + floor(q.y), 2.0);
    fragColor = combine(p, parity < 0.5 ? u_colorA : u_colorB);
}
