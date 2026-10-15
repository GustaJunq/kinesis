#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_amount;
uniform float u_size;  // px
uniform float u_seed;

void main() {
    vec4 p = texture(u_texture, v_uv);
    float frame = floor(u_time * 60.0);
    vec2 g = floor(toPx(v_uv) / max(u_size, 0.5));
    float n = hash3(g.x, g.y, u_seed * 7919.0 + frame) + hash3(g.x + 3.0, g.y - 5.0, u_seed * 7919.0 + frame + 0.5) - 1.0;
    fragColor = premul(unpremul(p) + n * u_amount, p.a);
}
