#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_amount;
uniform float u_size;  // px
uniform float u_rate;
uniform float u_seed;

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec2 g = floor(toPx(v_uv) / max(u_size, 0.5));
    float n = hash3(g.x, g.y, u_seed * 4099.0 + floor(u_time * u_rate));
    fragColor = premul(mix(unpremul(p), vec3(n), u_amount), p.a);
}
