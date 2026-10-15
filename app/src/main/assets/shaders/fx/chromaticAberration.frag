#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_amount;  // px
uniform float u_angle;

void main() {
    vec2 o = toUv(dirOf(u_angle) * u_amount);
    vec4 r = texture(u_texture, v_uv + o);
    vec4 g = texture(u_texture, v_uv);
    vec4 b = texture(u_texture, v_uv - o);
    fragColor = vec4(r.r, g.g, b.b, max(g.a, max(r.a, b.a)));
}
