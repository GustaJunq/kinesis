#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform int u_levels;

void main() {
    vec4 p = texture(u_texture, v_uv);
    float n = float(max(u_levels, 2));
    vec3 c = floor(unpremul(p) * n * 0.9999) / (n - 1.0);
    fragColor = premul(c, p.a);
}
